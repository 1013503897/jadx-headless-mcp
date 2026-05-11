package com.atxx.jhmcp

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.ResourceFile
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

    fun getClassSource(cls: JavaClass): String = truncate(cls.code)

    fun getClassSmali(cls: JavaClass): String = truncate(cls.smali)

    fun describeUsage(node: JavaNode): Map<String, String> {
        val declaring = runCatching { node.declaringClass?.fullName }.getOrNull().orEmpty()
        val kind = when (node) {
            is JavaClass -> "class"
            is JavaMethod -> "method"
            is JavaField -> "field"
            else -> node.javaClass.simpleName
        }
        return mapOf(
            "kind" to kind,
            "name" to node.name.orEmpty(),
            "full_name" to node.fullName.orEmpty(),
            "containing_class" to declaring,
        )
    }

    private fun truncate(s: String?): String {
        val text = s ?: return ""
        if (text.length <= maxSourceBytes) return text
        return text.substring(0, maxSourceBytes) +
            "\n\n... [truncated: source exceeds $maxSourceBytes bytes, total ${text.length} bytes]"
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
