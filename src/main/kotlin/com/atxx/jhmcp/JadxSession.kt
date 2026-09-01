package com.atxx.jhmcp

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.ResourceFile
import jadx.core.xmlgen.ResContainer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile

class JadxSession private constructor(
    private val decompiler: JadxDecompiler,
    val apkPath: String,
    val maxSourceBytes: Int,
    /** Per-process override for the default `code`-scope scan cap. 0 = use built-in tiered defaults. */
    val codeScanCap: Int = 0,
    /**
     * Hard wall-clock budget for a single jadx decompile/smali materialization.
     * Truncation (max_bytes) only runs *after* jadx finishes — without this timeout a fat
     * obfuscated class can block the whole MCP process for tens of minutes.
     */
    val decompileTimeoutMs: Long = DEFAULT_DECOMPILE_TIMEOUT_MS,
) : Closeable {

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

    val classes: List<JavaClass> by lazy { decompiler.classes }
    val resources: List<ResourceFile> by lazy { decompiler.resources }

    /** Cached top-level class FQNs so list_classes / main-app filtering doesn't re-map every call. */
    val classFqns: List<String> by lazy { classes.map { it.fullName } }

    /**
     * Every class INCLUDING nested/inner ones. jadx's `decompiler.classes` returns only top-level
     * classes; inner classes (and Kotlin `$Companion`) are reachable solely via `getInnerClasses()`.
     * Flattening them here is what makes `Outer$Companion` / `Outer.Inner` addressable by name.
     */
    val allClasses: List<JavaClass> by lazy {
        val out = ArrayList<JavaClass>(classes.size + classes.size / 4)
        val stack = ArrayDeque(classes)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            out += c
            val inners = runCatching { c.innerClasses }.getOrNull()
            if (!inners.isNullOrEmpty()) stack.addAll(inners)
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

    private val methodsByName: Map<String, List<JavaMethod>> by lazy {
        val map = HashMap<String, MutableList<JavaMethod>>(allClasses.size * 4)
        for (cls in allClasses) {
            val methods = runCatching { cls.methods }.getOrNull() ?: continue
            for (m in methods) {
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
        // Tier 2: bare simple-name match (broad) — only auto-pick when unique.
        val lastSeg = cq.substringAfterLast('.')
        val tier2 = allClasses.asSequence()
            .filter { canonicalizeClassName(it.fullName).substringAfterLast('.') == lastSeg }
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
        cls.innerClasses.map {
            mapOf(
                "full_name" to it.fullName,
                "raw_name" to it.rawName,
                "name" to it.name,
            )
        }

    fun searchClasses(keyword: String, limit: Int): List<JavaClass> {
        val kw = keyword.lowercase()
        return classes.asSequence()
            .filter { it.fullName.lowercase().contains(kw) }
            .take(limit)
            .toList()
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
            if (SearchScope.METHOD in scopes && cls.methods.any { it.name.lowercase().contains(kw) }) {
                matched += SearchScope.METHOD
            }
            if (SearchScope.FIELD in scopes && cls.fields.any { it.name.lowercase().contains(kw) }) {
                matched += SearchScope.FIELD
            }
            // CODE only on top-level classes: jadx inlines inner-class source into the outer class's
            // `code`, so decompiling inners too would re-scan the same text and double-count.
            if (codeScope && runCatching { cls.declaringClass == null }.getOrDefault(true)) {
                // Per-class budget: never let one class hang the whole scan.
                val code = decompileTimed(cls.fullName, "code-search", decompileTimeoutMs.coerceAtMost(20_000L)) {
                    cls.code.orEmpty()
                }.getOrElse { "" }
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
        return cls.methods.firstOrNull { it.name == methodName }
    }

    /** All methods matching [methodName] in the class (handles overloads). */
    fun findMethods(classFqn: String, methodName: String): List<JavaMethod> {
        val cls = resolveClass(classFqn) ?: return emptyList()
        return cls.methods.filter { it.name == methodName }
    }

    fun findField(classFqn: String, fieldName: String): JavaField? {
        val cls = resolveClass(classFqn) ?: return null
        return cls.fields.firstOrNull { it.name == fieldName }
    }

    fun getClassSource(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "source", decompileTimeoutMs) {
            cls.code.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "source", err) }
        return truncateToBytes(text, maxBytes)
    }

    fun getClassSmali(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "smali", err) }
        return truncateToBytes(text, maxBytes)
    }

    /** Decompile a single method body with the same hard timeout. */
    fun getMethodSource(method: JavaMethod): String {
        val owner = method.declaringClass?.fullName ?: method.fullName
        return decompileTimed(owner, "method:${method.name}", decompileTimeoutMs) {
            method.codeStr.orEmpty().ifEmpty {
                "// method exists but has no decompiled body (native/abstract)"
            }
        }.getOrElse { err ->
            "// ERROR: decompile timed out or failed for ${method.fullName}: ${err.message}"
        }
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
     * Fetch decompiled Java for a class; if jadx emitted an anti-decompile / decompile-failure
     * banner (goto stubs, "Code decompiled incorrectly", "Method not decompiled", …), transparently
     * return the class smali instead — prefixed with a `// [jadx java-decompile failed → smali]`
     * marker so the caller knows. Set [smaliFallback] = false to force the (partial) Java.
     */
    fun getClassSourceSmart(cls: JavaClass, maxBytes: Int = maxSourceBytes, smaliFallback: Boolean = true): SmartCode {
        val java = decompileTimed(cls.fullName, "source", decompileTimeoutMs) {
            cls.code.orEmpty()
        }.getOrElse { err -> return SmartCode(decompileErrorBanner(cls, "source", err), "java", false, emptyList()) }
        val markers = detectDecompileFailure(java)
        if (smaliFallback && markers.any { it in STRONG_FAILURE_MARKERS }) {
            val smali = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
                cls.smali.orEmpty()
            }.getOrElse { err -> return SmartCode(decompileErrorBanner(cls, "smali", err), "smali", true, markers) }
            return SmartCode(truncateToBytes(smaliFallbackHeader(cls.fullName, markers) + smali, maxBytes), "smali", true, markers)
        }
        return SmartCode(truncateToBytes(java, maxBytes), "java", false, markers)
    }

    /**
     * Java body of a single method, or its smali when the Java body is an anti-decompile stub.
     * The pinpoint version of [getClassSourceSmart] — only the requested method's smali is returned.
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
        if (smali.isEmpty()) return MethodSmali(methodName, emptyList(), false, "class has no smali (no code)")
        val blocks = extractMethodBlocks(smali, methodName)
        return MethodSmali(methodName, blocks, blocks.isNotEmpty())
    }

    /** Slice a byte window out of the class smali (for paging huge classes). */
    fun getClassSmaliWindow(cls: JavaClass, offset: Int, limit: Int): Triple<String, Int, Int> {
        val smali = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err -> return Triple(decompileErrorBanner(cls, "smali", err), 0, 0) }
        val total = smali.length
        if (offset >= total) return Triple("", total, total)
        val from = offset.coerceIn(0, total)
        val to = (from + limit).coerceAtMost(total)
        return Triple(smali.substring(from, to), total, to)
    }

    // Smali block slicing (extractMethodBlocks / parseSmaliMethodName) now lives in SmaliSlicing.kt
    // so it is unit-testable without an APK.

    /**
     * Run [block] on the decompile worker with a wall-clock [timeoutMs] budget.
     * On timeout the worker thread is interrupted; jadx may still be computing until
     * it honors interrupt, but the MCP call returns immediately so the server unblocks.
     */
    private fun <T> decompileTimed(
        classFqn: String,
        kind: String,
        timeoutMs: Long,
        block: () -> T,
    ): Result<T> {
        val started = System.currentTimeMillis()
        val pool = decompilePool
        val future = try {
            pool.submit(Callable { block() })
        } catch (re: RejectedExecutionException) {
            // Pool was retired concurrently (a sibling call timed out and rebuilt it). Transient.
            return Result.failure(re)
        }
        return try {
            val value = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > 2_000) {
                System.err.println("[jhmcp] $kind $classFqn took ${elapsed}ms")
            }
            Result.success(value)
        } catch (te: TimeoutException) {
            future.cancel(true)
            // The worker is still running the (likely uninterruptible) decompile. Retire this pool so
            // its abandoned daemon worker stops blocking the queue, and hand later calls a fresh one.
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
            Result.failure(TimeoutException(msg))
        } catch (ee: ExecutionException) {
            val cause = ee.cause ?: ee
            System.err.println("[jhmcp] decompile $kind failed for $classFqn: ${cause.message}")
            Result.failure(cause)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            Result.failure(ie)
        }
    }

    private fun decompileErrorBanner(cls: JavaClass, kind: String, err: Throwable): String {
        val methods = runCatching { cls.methods.size }.getOrDefault(-1)
        val fields = runCatching { cls.fields.size }.getOrDefault(-1)
        return buildString {
            appendLine("// ERROR: jadx $kind failed for ${cls.fullName}")
            appendLine("// reason: ${err.message ?: err.javaClass.simpleName}")
            appendLine("// method_count=$methods field_count=$fields")
            appendLine("// tip: use get_class_summary, get_methods_of_class, get_method_by_name instead of full-class $kind")
            appendLine("// decompile_timeout_ms=$decompileTimeoutMs (raise via --decompile-timeout-ms only if needed)")
        }
    }

    /**
     * Class skeleton without method/field bodies.
     * Note: jadx's `JavaClass.getMethods()`/`getFields()` can force full decompile on some
     * builds (especially control-flow-obfuscated classes). We still wrap with the hard timeout
     * so summary cannot hang MCP for hours.
     */
    fun summarizeClass(cls: JavaClass): Map<String, Any> {
        val skeleton = decompileTimed(cls.fullName, "summary", decompileTimeoutMs) {
            val methods = cls.methods.map { m ->
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
            val fields = cls.fields.map { f ->
                mapOf(
                    "name" to f.name,
                    "type" to shortType(f.type.toString()),
                    "def_pos" to f.defPos,
                )
            }
            mapOf(
                "full_name" to cls.fullName,
                "name" to cls.name,
                "method_count" to methods.size,
                "field_count" to fields.size,
                "inner_class_count" to cls.innerClasses.size,
                "methods" to methods,
                "fields" to fields,
                "inner_classes" to cls.innerClasses.map { it.fullName },
            )
        }
        return skeleton.getOrElse { err ->
            mapOf(
                "full_name" to cls.fullName,
                "name" to cls.name,
                "error" to (err.message ?: err.javaClass.simpleName),
                "method_count" to -1,
                "field_count" to -1,
                "methods" to emptyList<Any>(),
                "fields" to emptyList<Any>(),
                "inner_classes" to emptyList<Any>(),
                "hint" to "summary timed out — class likely control-flow obfuscated; try search_method_by_name / DEX strings",
            )
        }
    }

    /**
     * Describe a single xref entry. With [resolveLine] = true, includes the line
     * in the top-level containing class's decompiled source — at the cost of
     * forcing that class to be decompiled (cached by jadx after first call).
     */
    fun describeUsage(node: JavaNode, resolveLine: Boolean = true): Map<String, Any> {
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
            runCatching { topCls.getSourceLine(defPos) }.getOrNull() ?: 0
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
            maxSourceBytes: Int = 200_000,
            codeScanCap: Int = 0,
            decompileTimeoutMs: Long = DEFAULT_DECOMPILE_TIMEOUT_MS,
        ): JadxSession {
            val log = LoggerFactory.getLogger(JadxSession::class.java)
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

            val args = JadxArgs().apply {
                inputFiles.add(effectiveFile)
                setOutDir(outDir)
                isShowInconsistentCode = true
                threadsCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                // Suppress JADX's "case insensitive filesystem" rename pass so class names stay
                // identical to the runtime FQN — Frida users rely on this. Safe because we only
                // call decompiler.load() and read sources in-memory, never decompiler.save().
                isFsCaseSensitive = true
            }

            val started = System.currentTimeMillis()
            System.err.println("[jhmcp] loading APK: $apkPath")
            if (effectiveFile !== file) {
                System.err.println("[jhmcp] XAPK has no manifest.json; extracted base APK: ${effectiveFile.name}")
            }
            val decompiler = JadxDecompiler(args)
            decompiler.load()
            val elapsed = System.currentTimeMillis() - started
            System.err.println("[jhmcp] loaded in ${elapsed}ms, classes=${decompiler.classes.size}")

            return JadxSession(decompiler, apkPath, maxSourceBytes, codeScanCap, decompileTimeoutMs)
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
