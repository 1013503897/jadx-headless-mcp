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

class JadxSession private constructor(
    private val decompiler: JadxDecompiler,
    val apkPath: String,
    val maxSourceBytes: Int,
) : Closeable {

    private val log = LoggerFactory.getLogger(JadxSession::class.java)

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
            pkg != null -> 5_000
            else -> 1_000
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
                val code = runCatching { cls.code }.getOrNull().orEmpty()
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

    fun getClassSource(cls: JavaClass, maxBytes: Int = maxSourceBytes): String =
        truncate(cls.code, maxBytes)

    fun getClassSmali(cls: JavaClass, maxBytes: Int = maxSourceBytes): String =
        truncate(cls.smali, maxBytes)

    /** Class skeleton without method/field bodies. Cheap to assemble, useful for navigation. */
    fun summarizeClass(cls: JavaClass): Map<String, Any> {
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
        return mapOf(
            "full_name" to cls.fullName,
            "name" to cls.name,
            "method_count" to cls.methods.size,
            "field_count" to cls.fields.size,
            "inner_class_count" to cls.innerClasses.size,
            "methods" to methods,
            "fields" to fields,
            "inner_classes" to cls.innerClasses.map { it.fullName },
        )
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
        runCatching { decompiler.close() }
    }

    companion object {
        fun open(apkPath: String, maxSourceBytes: Int = 200_000): JadxSession {
            val log = LoggerFactory.getLogger(JadxSession::class.java)
            val file = File(apkPath)
            require(file.exists()) { "APK not found: $apkPath" }
            require(file.isFile) { "Not a regular file: $apkPath" }

            val outDir = File(System.getProperty("java.io.tmpdir"), "jhmcp-${System.nanoTime()}")
            outDir.mkdirs()
            outDir.deleteOnExit()

            val args = JadxArgs().apply {
                inputFiles.add(file)
                setOutDir(outDir)
                isShowInconsistentCode = true
                threadsCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            }

            val started = System.currentTimeMillis()
            System.err.println("[jhmcp] loading APK: $apkPath")
            val decompiler = JadxDecompiler(args)
            decompiler.load()
            val elapsed = System.currentTimeMillis() - started
            System.err.println("[jhmcp] loaded in ${elapsed}ms, classes=${decompiler.classes.size}")

            return JadxSession(decompiler, apkPath, maxSourceBytes)
        }
    }
}
