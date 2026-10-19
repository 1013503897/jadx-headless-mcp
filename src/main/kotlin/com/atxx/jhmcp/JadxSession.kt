package com.atxx.jhmcp

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.JhmcpJadxAccess
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.xmlgen.ResContainer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

class JadxSession private constructor(
    private val decompiler: JadxDecompiler,
    val apkPath: String,
    val options: SessionConfig,
    /** Top-level class count as jadx loaded them, before [PackageFilter]. */
    val rawClassCount: Int,
) : Closeable {
    val maxSourceBytes: Int get() = options.maxSourceBytes
    val codeScanCap: Int get() = options.codeScanCap
    val decompileTimeoutMs: Long get() = options.decompileTimeoutMs
    val resourceMode: ResourceMode get() = options.resourceMode
    val threads: Int get() = options.resolvedThreads()
    val codeCacheSize: Int get() = options.codeCacheSize
    val packageFilter: PackageFilter get() = options.packageFilter

    private val log = LoggerFactory.getLogger(JadxSession::class.java)

    // One worker: jadx class decompilation is not assumed re-entrant across threads on a single
    // JadxDecompiler instance, so all materialization is serialized through one thread. The pool is
    // REBUILDABLE: jadx's control-flow analysis is a tight CPU loop that may not honor interruption,
    // so on a decompile timeout we retire the stuck pool (its daemon worker is abandoned) and spin up
    // a fresh one — otherwise one pathological class would wedge every later request behind a dead
    // worker, turning the whole session into a string of timeouts.
    private val poolLock = Any()

    @Volatile
    private var decompilePool: ExecutorService = newDecompilePool()

    private fun newDecompilePool(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "jhmcp-decompile").apply { isDaemon = true }
        }

    private val decompileLru = DecompileLru(options.codeCacheSize)

    /**
     * Top-level classes visible to tools. When a package filter is active, excluded classes
     * are `unload()`'d (method IR eligible for GC) and omitted here. jadx still parsed them
     * during `load()` — see [rawClassCount].
     */
    val classes: List<JavaClass> by lazy {
        val raw = decompiler.classes
        if (!packageFilter.active) return@lazy raw
        val kept = ArrayList<JavaClass>(raw.size)
        for (c in raw) {
            if (packageFilter.keep(c.fullName, c.rawName)) kept += c
            else runCatching { c.unload() }
        }
        kept
    }

    val resources: List<ResourceFile> by lazy {
        val all = decompiler.resources
        when (resourceMode) {
            ResourceMode.FULL -> all
            ResourceMode.LITE -> all.filter { isLiteResource(it) }
            ResourceMode.NONE -> all.filter { isManifestResource(it) }
        }
    }

    /** Cached top-level class FQNs so list_classes / main-app filtering doesn't re-map every call. */
    val classFqns: List<String> by lazy { classes.map { it.fullName } }

    /**
     * Every class INCLUDING nested/inner ones, built without materializing `JavaClass.innerClasses`
     * (which calls `load()` / decompile in jadx 1.5.x). Uses [JadxDecompiler.getClassesWithInners]
     * so exact/`$Companion` lookup stays addressable without whole-APK code generation.
     */
    val allClasses: List<JavaClass> by lazy {
        val raw = decompiler.classesWithInners
        val out = ArrayList<JavaClass>(raw.size)
        for (c in raw) {
            // Match getClasses() filtering: skip DONT_GENERATE / no-code synthetic shells.
            if (c.isNoCode) continue
            if (packageFilter.active && !packageFilter.keep(c.fullName, c.rawName)) continue
            out += c
        }
        out
    }

    private val classByFqn: Map<String, JavaClass> by lazy {
        allClasses.associateBy { it.fullName }
    }

    /** Keyed on the runtime/raw name (nested classes joined with `$`, e.g. `Outer$Companion`). */
    private val classByRawName: Map<String, JavaClass> by lazy {
        allClasses.associateBy { it.rawName }
    }

    /**
     * Canonical lookup: `$` and `.` nesting separators unified so a caller can address a class
     * with either `Outer$Inner`, `Outer.Inner`, `Outer$Companion`, or `Outer.Companion`.
     * Both `fullName` (dotted) and `rawName` (`$`) are indexed; exact indexes are tried first.
     */
    private val classByCanon: Map<String, JavaClass> by lazy {
        val map = HashMap<String, JavaClass>(allClasses.size * 2)
        for (cls in allClasses) {
            map[canonicalizeClassName(cls.fullName)] = cls
            map.putIfAbsent(canonicalizeClassName(cls.rawName), cls)
        }
        map
    }

    /**
     * canonical simple-name → classes, so [resolveClassDetailed]'s fuzzy tier-2 (bare simple name)
     * is a map lookup instead of a full `allClasses` canonicalize-and-scan on every miss. Built once
     * (cost ≈ one old fuzzy query), amortized across all later lookups.
     */
    private val classesBySimpleName: Map<String, List<JavaClass>> by lazy {
        val map = HashMap<String, MutableList<JavaClass>>(allClasses.size)
        for (cls in allClasses) {
            val simple = canonicalizeClassName(cls.fullName).substringAfterLast('.')
            map.getOrPut(simple) { mutableListOf() }.add(cls)
        }
        map
    }

    private val methodsByName: Map<String, List<JavaMethod>> by lazy {
        val map = HashMap<String, MutableList<JavaMethod>>(allClasses.size * 4)
        for (cls in allClasses) {
            for (m in metadataMethods(cls)) {
                map.getOrPut(m.name) { mutableListOf() }.add(m)
            }
        }
        map
    }

    /** Exact match on the dotted FQN only (legacy behaviour). Prefer [resolveClass]. */
    fun findClass(fqn: String): JavaClass? = classByFqn[fqn]

    data class ClassResolution(val cls: JavaClass?, val candidates: List<String>)

    /**
     * Robust class resolution. In precedence order:
     *  1. exact dotted FQN (`com.foo.Outer.Inner`)
     *  2. exact raw name (`com.foo.Outer$Inner`, `...$Companion`)
     *  3. canonical match (`$`/`.` nesting unified) — fixes `Outer$Companion` vs `Outer.Companion`
     *  4. fuzzy suffix: a class whose canonical FQN ends with the (canonical) query — auto-picked only if unique
     *  5. simple-name match — auto-picked only if unique, otherwise returned as candidates
     */
    fun resolveClassDetailed(query: String): ClassResolution {
        val q = query.trim()
        classByFqn[q]?.let { return ClassResolution(it, emptyList()) }
        classByRawName[q]?.let { return ClassResolution(it, emptyList()) }
        val cq = canonicalizeClassName(q).trim('.')
        classByCanon[cq]?.let { return ClassResolution(it, emptyList()) }
        // Tier 1: precise suffix on the whole (canonical) query.
        val tier1 = allClasses.asSequence()
            .filter { val cf = canonicalizeClassName(it.fullName); cf == cq || cf.endsWith(".$cq") }
            .distinctBy { it.fullName }
            .take(50)
            .toList()
        if (tier1.size == 1) return ClassResolution(tier1[0], emptyList())
        if (tier1.size > 1) return ClassResolution(null, tier1.map { it.fullName })
        // Tier 2: bare simple-name match (broad) — only auto-pick when unique. Indexed lookup.
        val lastSeg = cq.substringAfterLast('.')
        val tier2 = (classesBySimpleName[lastSeg] ?: emptyList()).asSequence()
            .distinctBy { it.fullName }
            .take(50)
            .toList()
        if (tier2.size == 1) return ClassResolution(tier2[0], emptyList())
        return ClassResolution(null, tier2.map { it.fullName })
    }

    /** Convenience: the resolved class (exact / canonical / unique-fuzzy) or null. */
    fun resolveClass(query: String): JavaClass? = resolveClassDetailed(query).cls

    /** Inner classes of [cls] with both dotted and raw (`$`) forms so callers can copy an addressable name. */
    fun innerClasses(cls: JavaClass): List<Map<String, Any>> =
        metadataInnerClasses(cls).map {
            mapOf(
                "full_name" to it.fullName,
                "raw_name" to it.rawName,
                "name" to it.name,
            )
        }

    enum class SearchScope { CLASS, METHOD, FIELD, CODE }

    data class ClassHit(
        val fqn: String,
        val matchedIn: Set<SearchScope>,
        val snippet: String? = null,
    )

    /**
     * Multi-scope class search. CLASS/METHOD/FIELD are metadata-only and cheap.
     * CODE forces decompilation of each scanned class — restrict scan size with
     * [packagePrefix] or [maxScan], and rely on early-exit once enough hits are found.
     */
    fun searchClassesAdvanced(
        term: String,
        scopes: Set<SearchScope>,
        packagePrefix: String? = null,
        offset: Int = 0,
        count: Int = 20,
        maxScan: Int = 0,
    ): SearchResult {
        require(term.isNotEmpty()) { "term must not be empty" }
        require(count >= 0 && offset >= 0)
        val kw = term.lowercase()
        val pkg = packagePrefix?.takeIf { it.isNotBlank() }
        val codeScope = SearchScope.CODE in scopes
        // Default cap: high when only metadata scopes (cheap); much lower for CODE without package.
        val effectiveMaxScan = when {
            maxScan > 0 -> maxScan
            !codeScope -> allClasses.size
            codeScanCap > 0 -> codeScanCap
            pkg != null -> 20_000
            else -> 5_000
        }
        val needed = offset + count
        val out = ArrayList<ClassHit>(needed.coerceAtLeast(16))
        var scanned = 0
        var stoppedEarly = false
        // Iterate allClasses (incl. inner/$Companion) so METHOD/FIELD/CLASS scopes match nested
        // members — consistent with search_method_by_name. CODE stays top-level-only (below).
        for (cls in allClasses) {
            if (pkg != null && !(cls.fullName == pkg || cls.fullName.startsWith("$pkg."))) continue
            if (scanned >= effectiveMaxScan) { stoppedEarly = true; break }
            scanned++
            val matched = HashSet<SearchScope>(4)
            var snippet: String? = null
            if (SearchScope.CLASS in scopes && cls.fullName.lowercase().contains(kw)) {
                matched += SearchScope.CLASS
            }
            if (SearchScope.METHOD in scopes && classNodeHasMethodName(cls, kw)) {
                matched += SearchScope.METHOD
            }
            if (SearchScope.FIELD in scopes && classNodeHasFieldName(cls, kw)) {
                matched += SearchScope.FIELD
            }
            // CODE only on top-level classes: jadx inlines inner-class source into the outer class's
            // `code`, so decompiling inners too would re-scan the same text and double-count.
            if (codeScope && runCatching { cls.declaringClass == null }.getOrDefault(true)) {
                // Per-class budget: never let one class hang the whole scan.
                val code = decompileTimed(cls.fullName, "code-search", decompileTimeoutMs.coerceAtMost(20_000L)) {
                    cls.code.orEmpty()
                }.getOrElse { "" }
                rememberDecompiled(cls)
                if (code.isNotEmpty()) {
                    val idx = code.indexOf(term, ignoreCase = true)
                    if (idx >= 0) {
                        matched += SearchScope.CODE
                        val from = (idx - 60).coerceAtLeast(0)
                        val to = (idx + term.length + 60).coerceAtMost(code.length)
                        snippet = code.substring(from, to)
                            .replace('\r', ' ').replace('\n', ' ').replace(Regex("""\s{2,}"""), " ")
                            .trim()
                    }
                }
            }
            if (matched.isNotEmpty()) {
                out += ClassHit(cls.fullName, matched, snippet)
                if (out.size >= needed) break
            }
        }
        val page = out.asSequence().drop(offset).take(count).toList()
        return SearchResult(page, scanned, stoppedEarly)
    }

    data class SearchResult(
        val hits: List<ClassHit>,
        val scanned: Int,
        val maxScanReached: Boolean,
    )

    fun searchMethods(name: String, limit: Int): List<JavaMethod> {
        val exact = methodsByName[name]
        if (!exact.isNullOrEmpty()) return exact.take(limit)
        val kw = name.lowercase()
        return methodsByName.entries.asSequence()
            .filter { it.key.lowercase().contains(kw) }
            .flatMap { it.value.asSequence() }
            .take(limit)
            .toList()
    }

    fun findMethod(classFqn: String, methodName: String): JavaMethod? {
        val cls = resolveClass(classFqn) ?: return null
        return metadataMethods(cls).firstOrNull { it.name == methodName }
    }

    /** All methods matching [methodName] in the class (handles overloads). */
    fun findMethods(classFqn: String, methodName: String): List<JavaMethod> {
        val cls = resolveClass(classFqn) ?: return emptyList()
        return metadataMethods(cls).filter { it.name == methodName }
    }

    fun findField(classFqn: String, fieldName: String): JavaField? {
        val cls = resolveClass(classFqn) ?: return null
        return metadataFields(cls).firstOrNull { it.name == fieldName }
    }

    fun getClassSource(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "source", decompileTimeoutMs) {
            cls.code.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "source", err) }
        rememberDecompiled(cls)
        return truncateToBytes(text, maxBytes)
    }

    fun getClassSmali(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "smali", err) }
        rememberDecompiled(cls)
        return truncateToBytes(text, maxBytes)
    }

    /** Decompile a single method body with the same hard timeout. */
    fun getMethodSource(method: JavaMethod): String {
        val owner = method.declaringClass?.fullName ?: method.fullName
        val text = decompileTimed(owner, "method:${method.name}", decompileTimeoutMs) {
            method.codeStr.orEmpty().ifEmpty {
                "// method exists but has no decompiled body (native/abstract)"
            }
        }.getOrElse { err ->
            return "// ERROR: decompile timed out or failed for ${method.fullName}: ${err.message}"
        }
        method.declaringClass?.let { rememberDecompiled(it) }
        return text
    }

    // ── Decompile-failure detection & seamless smali fallback ────────────────────

    /** Result of a source/body fetch that may have transparently fallen back to smali. */
    data class SmartCode(
        val text: String,
        val kind: String,          // "java" | "smali"
        val fellBack: Boolean,
        val markers: List<String>, // failure markers that triggered the fallback
    )

    /**
     * Decompile Java for a class; if jadx emitted an anti-decompile / decompile-failure banner (goto
     * stubs, "Code decompiled incorrectly", "Method not decompiled", …), transparently swap in the
     * class smali instead — prefixed with a `// [jadx java-decompile failed → smali]` marker so the
     * caller knows. Set [smaliFallback] = false to force the (partial) Java. No byte cap applied;
     * [getClassSourceWindow] pages the result.
     */
    private fun decompileClassSmartFull(cls: JavaClass, smaliFallback: Boolean): SmartCode {
        val java = decompileTimed(cls.fullName, "source", decompileTimeoutMs) {
            cls.code.orEmpty()
        }.getOrElse { err -> return SmartCode(decompileErrorBanner(cls, "source", err), "java", false, emptyList()) }
        rememberDecompiled(cls)
        val markers = detectDecompileFailure(java)
        if (smaliFallback && markers.any { it in STRONG_FAILURE_MARKERS }) {
            val smali = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
                cls.smali.orEmpty()
            }.getOrElse { err -> return SmartCode(decompileErrorBanner(cls, "smali", err), "smali", true, markers) }
            rememberDecompiled(cls)
            return SmartCode(smaliFallbackHeader(cls.fullName, markers) + smali, "smali", true, markers)
        }
        return SmartCode(java, "java", false, markers)
    }

    /** A UTF-8 byte window of the smart (Java-or-smali) source for paging a huge class. */
    data class SourceWindow(
        val text: String,
        val totalBytes: Int,
        val nextOffset: Int,
        val kind: String,
        val fellBack: Boolean,
    )

    /**
     * Slice a window out of the smart (Java-or-smali) source for paging huge classes, mirroring
     * [getClassSmaliWindow]. [offset]/[limit] are UTF-8 byte offsets (same unit as `max_bytes`).
     */
    fun getClassSourceWindow(cls: JavaClass, offset: Int, limit: Int, smaliFallback: Boolean = true): SourceWindow {
        val full = decompileClassSmartFull(cls, smaliFallback)
        val w = byteWindow(full.text, offset, limit)
        return SourceWindow(w.text, w.totalBytes, w.nextOffset, full.kind, full.fellBack)
    }

    /**
     * Java body of a single method, or its smali when the Java body is an anti-decompile stub.
     * The pinpoint counterpart of [decompileClassSmartFull] — only the requested method's smali is returned.
     */
    fun getMethodBodySmart(method: JavaMethod, maxBytes: Int = maxSourceBytes, smaliFallback: Boolean = true): SmartCode {
        val java = getMethodSource(method)
        val markers = detectDecompileFailure(java)
        if (smaliFallback && markers.any { it in STRONG_FAILURE_MARKERS }) {
            val owner = method.declaringClass
            if (owner != null) {
                val ms = getMethodSmali(owner, method.name)
                if (ms.found) {
                    val body = ms.blocks.joinToString("\n\n")
                    return SmartCode(truncateToBytes(smaliFallbackHeader(method.fullName, markers) + body, maxBytes), "smali", true, markers)
                }
            }
        }
        return SmartCode(truncateToBytes(java, maxBytes), "java", false, markers)
    }

    private fun smaliFallbackHeader(name: String, markers: List<String>): String = buildString {
        appendLine("// [jadx java-decompile failed → smali]")
        appendLine("// target: $name")
        appendLine("// markers: ${markers.joinToString("; ")}")
        appendLine("// (Java body was an anti-decompile stub; showing smali. Pass smali_fallback=false for the partial Java.)")
        appendLine()
    }

    // Failure-marker detection now lives in DecompileFailure.kt (STRONG_FAILURE_MARKERS /
    // detectDecompileFailure) so it is unit-testable without an APK.

    // ── Method-level smali extraction ───────────────────────────────────────────

    data class MethodSmali(val name: String, val blocks: List<String>, val found: Boolean, val note: String? = null)

    /**
     * Extract the `.method … .end method` block(s) for [methodName] out of the class smali.
     * jadx has no per-method smali API, so we materialize the class disassembly once and slice it.
     * Returns every overload; an empty [blocks] with found=false if no such method name exists.
     */
    fun getMethodSmali(cls: JavaClass, methodName: String): MethodSmali {
        val smali = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err ->
            return MethodSmali(methodName, emptyList(), false, "smali generation failed: ${err.message}")
        }
        rememberDecompiled(cls)
        if (smali.isEmpty()) return MethodSmali(methodName, emptyList(), false, "class has no smali (no code)")
        val blocks = extractMethodBlocks(smali, methodName)
        return MethodSmali(methodName, blocks, blocks.isNotEmpty())
    }

    /** Slice a UTF-8 byte window out of the class smali (for paging huge classes). */
    fun getClassSmaliWindow(cls: JavaClass, offset: Int, limit: Int): Triple<String, Int, Int> {
        val smali = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err -> return Triple(decompileErrorBanner(cls, "smali", err), 0, 0) }
        rememberDecompiled(cls)
        val w = byteWindow(smali, offset, limit)
        return Triple(w.text, w.totalBytes, w.nextOffset)
    }

    // Smali block slicing (extractMethodBlocks / parseSmaliMethodName) now lives in SmaliSlicing.kt
    // so it is unit-testable without an APK.

    /**
     * Run [block] on the decompile worker with a wall-clock [timeoutMs] budget that counts only the
     * task's own EXECUTION time, never the time it spent queued behind another request.
     *
     * Why this matters under the HTTP transport (concurrent clients, one shared worker): the naive
     * `future.get(timeoutMs)` charges queue wait to the timeout, so a request that merely waited its
     * turn would "time out" and then `shutdownNow()` the pool — interrupting a *sibling's* perfectly
     * healthy, nearly-finished decompile. Here a queued task is given a bounded queue-wait allowance
     * and the pool is retired only when a task overruns its own execution budget. Each request already
     * polices itself, so no request needs to kill another's work on its behalf.
     */
    private fun <T> decompileTimed(
        classFqn: String,
        kind: String,
        timeoutMs: Long,
        block: () -> T,
    ): Result<T> {
        val submittedAt = System.currentTimeMillis()
        val execStart = AtomicLong(0L) // set by the worker the moment it actually starts running block()
        val pool = decompilePool
        val future = try {
            pool.submit(Callable { execStart.set(System.currentTimeMillis()); block() })
        } catch (re: RejectedExecutionException) {
            // Pool was retired concurrently (a sibling call timed out and rebuilt it). Transient.
            return Result.failure(re)
        }
        // Bound the pure-queue wait so a genuinely wedged pool still fails loudly, but keep it well
        // under the execution budget so the worst-case latency is queue-wait + execution rather than
        // 2×timeout. Predecessors retire the pool within their own budget, so the queue normally
        // clears far sooner than this cap.
        val maxQueueWaitMs = timeoutMs.coerceAtMost(30_000L)
        while (true) {
            val decision = decompileWaitDecision(
                nowMs = System.currentTimeMillis(),
                submittedAtMs = submittedAt,
                execStartMs = execStart.get(),
                timeoutMs = timeoutMs,
                maxQueueWaitMs = maxQueueWaitMs,
            )
            if (decision is WaitDecision.Retire) {
                // Either never picked up within the queue allowance (pool wedged) or the task overran
                // its own execution budget → retire the stuck pool and hand later calls a fresh one.
                future.cancel(true)
                synchronized(poolLock) {
                    if (decompilePool === pool) {
                        pool.shutdownNow()
                        decompilePool = newDecompilePool()
                    }
                }
                val msg = "decompile $kind timed out after ${timeoutMs}ms for $classFqn " +
                    "(max_bytes only truncates AFTER jadx finishes — use get_class_summary / get_method_by_name)"
                System.err.println("[jhmcp] $msg")
                log.warn(msg)
                return Result.failure(TimeoutException(msg))
            }
            try {
                val value = future.get((decision as WaitDecision.Wait).budgetMs, TimeUnit.MILLISECONDS)
                val elapsed = System.currentTimeMillis() - (execStart.get().takeIf { it > 0L } ?: submittedAt)
                if (elapsed > 2_000) System.err.println("[jhmcp] $kind $classFqn took ${elapsed}ms")
                return Result.success(value)
            } catch (te: TimeoutException) {
                // Budget slice elapsed without completion → loop and re-evaluate the policy. A task
                // still inside its execution budget keeps waiting; a wedged/overrun one retires above.
                continue
            } catch (ce: CancellationException) {
                // Our queued task was drained when a sibling call retired the pool. Transient.
                return Result.failure(ce)
            } catch (ee: ExecutionException) {
                val cause = ee.cause ?: ee
                System.err.println("[jhmcp] decompile $kind failed for $classFqn: ${cause.message}")
                return Result.failure(cause)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                return Result.failure(ie)
            }
        }
    }

    private fun decompileErrorBanner(cls: JavaClass, kind: String, err: Throwable): String {
        val methods = runCatching { metadataMethodNodes(cls.classNode).size }.getOrDefault(-1)
        val fields = runCatching { metadataFieldNodes(cls.classNode).size }.getOrDefault(-1)
        return buildString {
            appendLine("// ERROR: jadx $kind failed for ${cls.fullName}")
            appendLine("// reason: ${err.message ?: err.javaClass.simpleName}")
            appendLine("// method_count=$methods field_count=$fields")
            appendLine("// tip: use get_class_summary, get_methods_of_class, get_method_by_name instead of full-class $kind")
            appendLine("// decompile_timeout_ms=$decompileTimeoutMs (raise via --decompile-timeout-ms only if needed)")
        }
    }

    /**
     * Class skeleton from [ClassNode] metadata only — no `JavaClass.load()` / code generation.
     * Signatures come from parsed method/field nodes (may include synthetics; `def_pos` stays 0
     * until a later source request decompiles the class). Overload identity (name + arg types)
     * matches the [JavaMethod] wrappers used by get_method_by_name / get_method_body.
     */
    fun summarizeClass(cls: JavaClass): Map<String, Any> {
        return try {
            val methods = metadataMethods(cls).map { m ->
                mapOf(
                    "name" to m.name,
                    "signature" to buildString {
                        append(m.name)
                        append('(')
                        append(m.arguments.joinToString(", ") { shortType(it.toString()) })
                        append(')')
                        append(": ")
                        append(shortType(m.returnType.toString()))
                    },
                    "is_constructor" to m.isConstructor,
                    "def_pos" to m.defPos,
                )
            }
            val fields = metadataFields(cls).map { f ->
                mapOf(
                    "name" to f.name,
                    "type" to shortType(f.type.toString()),
                    "def_pos" to f.defPos,
                )
            }
            val inners = metadataInnerClasses(cls)
            mapOf(
                "full_name" to cls.fullName,
                "name" to cls.name,
                "method_count" to methods.size,
                "field_count" to fields.size,
                "inner_class_count" to inners.size,
                "methods" to methods,
                "fields" to fields,
                "inner_classes" to inners.map { it.fullName },
                "inventory" to "class_node_metadata",
            )
        } catch (err: Exception) {
            mapOf(
                "full_name" to cls.fullName,
                "name" to cls.name,
                "error" to (err.message ?: err.javaClass.simpleName),
                "method_count" to -1,
                "field_count" to -1,
                "methods" to emptyList<Any>(),
                "fields" to emptyList<Any>(),
                "inner_classes" to emptyList<Any>(),
                "hint" to "metadata summary failed — try search_method_by_name / DEX strings",
            )
        }
    }

    /**
     * Describe a single xref entry. With [resolveLine] = true, includes the line
     * in the top-level containing class's decompiled source — at the cost of
     * forcing that class to be decompiled (then subject to the decompile LRU).
     */
    fun describeUsage(node: JavaNode, resolveLine: Boolean = false): Map<String, Any> {
        val declaring = runCatching { node.declaringClass?.fullName }.getOrNull().orEmpty()
        val kind = when (node) {
            is JavaClass -> "class"
            is JavaMethod -> "method"
            is JavaField -> "field"
            else -> node.javaClass.simpleName
        }
        val topCls = runCatching { node.topParentClass }.getOrNull()
        val defPos = runCatching { node.defPos }.getOrNull() ?: 0
        val line = if (resolveLine && topCls != null && defPos > 0) {
            val n = runCatching { topCls.getSourceLine(defPos) }.getOrNull() ?: 0
            rememberDecompiled(topCls)
            n
        } else 0
        val out = mutableMapOf<String, Any>(
            "kind" to kind,
            "name" to node.name.orEmpty(),
            "full_name" to node.fullName.orEmpty(),
            "containing_class" to declaring,
            "top_class" to (topCls?.fullName ?: declaring),
            "def_pos" to defPos,
        )
        if (resolveLine) out["line"] = line
        return out
    }

    /** Lazy-cached AndroidManifest text. Null if not present or not decodable as text. */
    val manifestText: String? by lazy { loadManifestText() }

    private fun loadManifestText(): String? {
        val res = resources.firstOrNull {
            val n = it.deobfName.lowercase()
            n.endsWith("androidmanifest.xml") || n == "androidmanifest.xml"
        } ?: return null
        val container = runCatching { res.loadContent() }.getOrNull() ?: return null
        return if (container.dataType == ResContainer.DataType.TEXT) container.text.codeStr else null
    }

    private fun rememberDecompiled(cls: JavaClass) {
        decompileLru.remember(cls)
    }

    // ── ClassNode metadata (no JavaClass.load / decompile) ───────────────────────

    /**
     * Methods as [JavaMethod] wrappers built from [ClassNode.getMethods] via
     * [JadxDecompiler.convertMethodNode] — does not call [JavaClass.getMethods].
     */
    fun metadataMethods(cls: JavaClass): List<JavaMethod> {
        val nodes = metadataMethodNodes(cls.classNode)
        return nodes.map { JhmcpJadxAccess.wrapMethod(decompiler, it) }
            .sortedBy { it.name }
    }

    /** Fields as [JavaField] wrappers from [ClassNode.getFields] without [JavaClass.getFields]. */
    fun metadataFields(cls: JavaClass): List<JavaField> {
        val nodes = metadataFieldNodes(cls.classNode)
        return nodes.map { JhmcpJadxAccess.wrapField(decompiler, it) }
    }

    /** Inner classes from [ClassNode.getInnerClasses] without [JavaClass.getInnerClasses]. */
    fun metadataInnerClasses(cls: JavaClass): List<JavaClass> {
        return cls.classNode.innerClasses
            .asSequence()
            .filter { !it.contains(AFlag.DONT_GENERATE) }
            .map { JhmcpJadxAccess.wrapClass(decompiler, it) }
            .toList()
    }

    private fun metadataMethodNodes(node: ClassNode): List<MethodNode> =
        node.methods.filter { !it.contains(AFlag.DONT_GENERATE) }

    private fun metadataFieldNodes(node: ClassNode): List<FieldNode> =
        node.fields.filter { !it.contains(AFlag.DONT_GENERATE) }

    private fun classNodeHasMethodName(cls: JavaClass, kwLower: String): Boolean =
        metadataMethodNodes(cls.classNode).any { it.alias.lowercase().contains(kwLower) }

    private fun classNodeHasFieldName(cls: JavaClass, kwLower: String): Boolean =
        metadataFieldNodes(cls.classNode).any { it.alias.lowercase().contains(kwLower) }

    private fun shortType(s: String): String {
        // Strip leading package on common types to keep summary readable:
        // "java.lang.String" -> "String", "com.foo.Bar" -> "Bar". Keep arrays/generics intact.
        if (s.isEmpty()) return s
        val lastDot = s.lastIndexOf('.')
        if (lastDot < 0 || lastDot == s.length - 1) return s
        val tail = s.substring(lastDot + 1)
        // Don't truncate inner-class anchors written with $; keep them.
        return tail
    }

    override fun close() {
        runCatching { decompilePool.shutdownNow() }
        runCatching { decompiler.close() }
    }

    companion object {
        // Keep well under typical MCP client tool ceilings (e.g. Grok tool_timeout_sec≈150)
        // while still aborting hour-long hangs on control-flow-obfuscated classes.
        const val DEFAULT_DECOMPILE_TIMEOUT_MS: Long = 90_000L

        fun open(
            apkPath: String,
            options: SessionConfig = SessionConfig(),
        ): JadxSession {
            val file = File(apkPath)
            require(file.exists()) { "APK not found: $apkPath" }
            require(file.isFile) { "Not a regular file: $apkPath" }

            // jadx-xapk-input only recognizes XAPKs with manifest.json (APKPure layout).
            // Split-APK bundles without manifest.json fall through to the default zip
            // handler and yield ~0 classes — pre-extract the base APK so JADX sees a real APK.
            val effectiveFile = if (file.name.endsWith(".xapk", ignoreCase = true)) {
                extractXapkBaseIfNoManifest(file) ?: file
            } else file

            val outDir = File(System.getProperty("java.io.tmpdir"), "jhmcp-${System.nanoTime()}")
            outDir.mkdirs()
            outDir.deleteOnExit()

            val threads = options.resolvedThreads()
            val args = JadxArgs().apply {
                inputFiles.add(effectiveFile)
                setOutDir(outDir)
                isShowInconsistentCode = true
                threadsCount = threads
                // Suppress JADX's "case insensitive filesystem" rename pass so class names stay
                // identical to the runtime FQN — Frida users rely on this. Safe because we only
                // call decompiler.load() and read sources in-memory, never decompiler.save().
                isFsCaseSensitive = true
                isSkipFilesSave = true
                if (options.resourceMode != ResourceMode.FULL) {
                    isSkipXmlPrettyPrint = true
                }
            }

            val started = System.currentTimeMillis()
            System.err.println("[jhmcp] loading APK: $apkPath (threads=$threads resources=${options.resourceMode.name.lowercase()})")
            if (effectiveFile !== file) {
                System.err.println("[jhmcp] XAPK has no manifest.json; extracted base APK: ${effectiveFile.name}")
            }
            val decompiler = JadxDecompiler(args)
            decompiler.load()
            val elapsed = System.currentTimeMillis() - started
            val rawCount = decompiler.classes.size
            System.err.println("[jhmcp] loaded in ${elapsed}ms, classes=$rawCount")
            val filter = options.packageFilter
            if (filter.active) {
                System.err.println(
                    "[jhmcp] package filter include=${filter.include} exclude=${filter.exclude} " +
                        "(jadx still parsed all $rawCount top-level classes; dropped ones are unloaded)"
                )
            }

            return JadxSession(decompiler, apkPath, options, rawCount)
        }

        internal fun isManifestResource(res: ResourceFile): Boolean {
            val n = res.deobfName.lowercase()
            if (n.endsWith("androidmanifest.xml") || n == "androidmanifest.xml") return true
            return runCatching { res.type == ResourceType.MANIFEST }.getOrDefault(false)
        }

        internal fun isLiteResource(res: ResourceFile): Boolean {
            if (isManifestResource(res)) return true
            val n = res.deobfName.lowercase()
            if (n.endsWith("resources.arsc") || n == "resources") return true
            if ("/values" in n && "strings" in n && n.endsWith(".xml")) return true
            return runCatching { res.type == ResourceType.ARSC }.getOrDefault(false)
        }

        private fun extractXapkBaseIfNoManifest(xapk: File): File? {
            ZipFile(xapk).use { zip ->
                val entries = Collections.list(zip.entries())
                if (entries.any { it.name == "manifest.json" }) return null

                val baseEntry = entries
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .maxByOrNull { it.size }
                    ?: return null

                val outDir = File(System.getProperty("java.io.tmpdir"), "jhmcp-xapk-${System.nanoTime()}")
                outDir.mkdirs()
                outDir.deleteOnExit()
                val outFile = File(outDir, File(baseEntry.name).name)
                zip.getInputStream(baseEntry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.deleteOnExit()
                return outFile
            }
        }
    }
}
