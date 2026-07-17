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
import java.util.concurrent.Executors
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
    // One worker: jadx class decompilation is not assumed re-entrant across threads on one instance.
    private val decompilePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jhmcp-decompile").apply { isDaemon = true }
    }

    val classes: List<JavaClass> by lazy { decompiler.classes }
    val resources: List<ResourceFile> by lazy { decompiler.resources }

    private val classByFqn: Map<String, JavaClass> by lazy {
        classes.associateBy { it.fullName }
    }

    private val methodsByName: Map<String, List<JavaMethod>> by lazy {
        val map = HashMap<String, MutableList<JavaMethod>>(classes.size * 4)
        for (cls in classes) {
            for (m in cls.methods) {
                map.getOrPut(m.name) { mutableListOf() }.add(m)
            }
        }
        map
    }

    fun findClass(fqn: String): JavaClass? = classByFqn[fqn]

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
            !codeScope -> classes.size
            codeScanCap > 0 -> codeScanCap
            pkg != null -> 20_000
            else -> 5_000
        }
        val needed = offset + count
        val out = ArrayList<ClassHit>(needed.coerceAtLeast(16))
        var scanned = 0
        var stoppedEarly = false
        for (cls in classes) {
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
            if (codeScope) {
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
        val cls = findClass(classFqn) ?: return null
        return cls.methods.firstOrNull { it.name == methodName }
    }

    fun findField(classFqn: String, fieldName: String): JavaField? {
        val cls = findClass(classFqn) ?: return null
        return cls.fields.firstOrNull { it.name == fieldName }
    }

    fun getClassSource(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "source", decompileTimeoutMs) {
            cls.code.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "source", err) }
        return truncate(text, maxBytes)
    }

    fun getClassSmali(cls: JavaClass, maxBytes: Int = maxSourceBytes): String {
        val text = decompileTimed(cls.fullName, "smali", decompileTimeoutMs) {
            cls.smali.orEmpty()
        }.getOrElse { err -> return decompileErrorBanner(cls, "smali", err) }
        return truncate(text, maxBytes)
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
        val future = decompilePool.submit(Callable {
            block()
        })
        return try {
            val value = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > 2_000) {
                System.err.println("[jhmcp] $kind $classFqn took ${elapsed}ms")
            }
            Result.success(value)
        } catch (te: TimeoutException) {
            future.cancel(true)
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

    private fun truncate(s: String?, maxBytes: Int): String {
        val text = s ?: return ""
        if (text.length <= maxBytes) return text
        return text.substring(0, maxBytes) +
            "\n\n... [truncated: source exceeds $maxBytes bytes, total ${text.length} bytes]"
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
