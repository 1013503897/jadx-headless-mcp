package com.atxx.jhmcp

import jadx.api.JavaClass
import jadx.core.xmlgen.ResContainer
import kotlinx.coroutines.runBlocking

/**
 * Temporary measurement harness — not part of the MCP server.
 * Run with: ./gradlew bench --args="/path/to/app.apk"
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: bench <apk-path>" }
    benchMain(args[0])
}

fun benchMain(apkPath: String) = runBlocking {
    val holder = SessionHolder(maxSourceBytes = 200_000)
    holder.load(apkPath)
    val s = holder.current()!!

    println("=== overview ===")
    println("apk: $apkPath")
    println("classes: ${s.classes.size}")
    println("resources: ${s.resources.size}")

    println("\n=== list_classes (limit 200) ===")
    val firstPage = s.classes.asSequence().take(200).map { it.fullName }.toList()
    val firstPageJson = firstPage.joinToString("\n")
    println("response size: ${firstPageJson.length} chars, ${firstPage.size} items")

    println("\n=== AndroidManifest ===")
    val manifest = s.manifestText.orEmpty()
    println("manifest full: ${manifest.length} chars")
    // Approximate section slicing the same way the tool does it.
    val permRe = Regex("""<uses-permission\b[^/>]*/>|<uses-permission\b[\s\S]*?</uses-permission>""")
    val perms = permRe.findAll(manifest).joinToString("\n") { it.value }
    println("manifest section=permissions: ${perms.length} chars")
    val actRe = Regex("""<activity\b[\s\S]*?</activity>""")
    val acts = actRe.findAll(manifest).joinToString("\n\n") { it.value }
    println("manifest section=activities: ${acts.length} chars")

    // Pick a couple of representative classes
    val mainActivityFqn = "com.braingames.tangramninja.MainActivity"
    val candidates = listOf(
        mainActivityFqn,
        // fall back to first class with "Activity" in name, and first "Application"
    ) + s.classes.asSequence().map { it.fullName }
        .filter { "Activity" in it || "Application" in it || "MainActivity" in it }
        .take(5).toList()

    for (fqn in candidates.distinct().take(6)) {
        val cls = s.findClass(fqn) ?: continue
        benchClass(s, cls)
    }

    // Pick a fat-looking class by source size to demonstrate worst case
    println("\n=== probing for fattest class (sampling first 2000) ===")
    val fattest = s.classes.asSequence().take(2000)
        .map { it to runCatching { it.code?.length ?: 0 }.getOrDefault(0) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .first()
    println("fattest of sampled: ${fattest.first.fullName} (source ${fattest.second} chars)")
    benchClass(s, fattest.first)

    // get_strings & list_resource_files measurements
    println("\n=== get_strings (no filter, limit 500) ===")
    val started = System.currentTimeMillis()
    val items = mutableListOf<Pair<String, String>>()
    val stringRe = Regex("""<string\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</string>""", RegexOption.IGNORE_CASE)
    outer@ for (res in s.resources) {
        val deobf = res.deobfName.lowercase()
        val looksLikeStrings = "/values" in deobf && "strings" in deobf && deobf.endsWith(".xml")
        val isResTable = deobf.endsWith("resources.arsc") || deobf == "resources"
        if (!looksLikeStrings && !isResTable) continue
        val container = runCatching { res.loadContent() }.getOrNull() ?: continue
        val candidates2 = mutableListOf(container).also { it += container.subFiles }
        for (c in candidates2) {
            if (c.dataType != ResContainer.DataType.TEXT) continue
            val name = c.name.lowercase()
            if (!("/values" in name && "strings" in name && name.endsWith(".xml"))) continue
            val xml = c.text.codeStr ?: continue
            for (m in stringRe.findAll(xml)) {
                items += m.groupValues[1] to m.groupValues[2]
                if (items.size >= 500) break@outer
            }
        }
    }
    val stringsBytes = items.sumOf { it.first.length + it.second.length + 20 }
    println("collected ${items.size} strings in ${System.currentTimeMillis() - started}ms, ~${stringsBytes} chars")

    println("\n=== search_classes_by_keyword: scope=class, term=Activity ===")
    var t0 = System.currentTimeMillis()
    var r = s.searchClassesAdvanced("Activity", setOf(JadxSession.SearchScope.CLASS), count = 20)
    println("hits=${r.hits.size} scanned=${r.scanned} stopped=${r.maxScanReached} elapsed=${System.currentTimeMillis() - t0}ms")
    r.hits.take(3).forEach { println("  ${it.fqn} matched=${it.matchedIn}") }

    println("\n=== search_classes_by_keyword: scope=method, term=onCreate ===")
    t0 = System.currentTimeMillis()
    r = s.searchClassesAdvanced("onCreate", setOf(JadxSession.SearchScope.METHOD), count = 20)
    println("hits=${r.hits.size} scanned=${r.scanned} stopped=${r.maxScanReached} elapsed=${System.currentTimeMillis() - t0}ms")
    r.hits.take(3).forEach { println("  ${it.fqn} matched=${it.matchedIn}") }

    println("\n=== search_classes_by_keyword: scope=code, term=ironsource, package=com.json (rename test) ===")
    t0 = System.currentTimeMillis()
    r = s.searchClassesAdvanced(
        "ironsource",
        setOf(JadxSession.SearchScope.CODE),
        packagePrefix = "com.json",
        count = 5
    )
    println("hits=${r.hits.size} scanned=${r.scanned} stopped=${r.maxScanReached} elapsed=${System.currentTimeMillis() - t0}ms")
    r.hits.take(3).forEach {
        println("  ${it.fqn} matched=${it.matchedIn}")
        println("    snippet: ${it.snippet?.take(120)}")
    }

    println("\n=== search_classes_by_keyword: scope=code, term=applovin, NO package (worst case) ===")
    t0 = System.currentTimeMillis()
    r = s.searchClassesAdvanced(
        "applovin",
        setOf(JadxSession.SearchScope.CODE),
        count = 5
    )
    println("hits=${r.hits.size} scanned=${r.scanned} stopped=${r.maxScanReached} elapsed=${System.currentTimeMillis() - t0}ms")
    r.hits.take(3).forEach {
        println("  ${it.fqn} matched=${it.matchedIn}")
        println("    snippet: ${it.snippet?.take(120)}")
    }

    holder.unload()
}

private fun benchClass(s: JadxSession, cls: JavaClass) {
    println("\n--- class: ${cls.fullName} ---")
    val src60k = runCatching { s.getClassSource(cls, 60_000) }.getOrDefault("")
    println("get_class_source (60k cap): ${src60k.length} chars")
    val smali60k = runCatching { s.getClassSmali(cls, 60_000) }.getOrDefault("")
    println("get_smali_of_class (60k cap): ${smali60k.length} chars")

    // summary is the new lightweight path
    val summary = runCatching { s.summarizeClass(cls) }.getOrDefault(emptyMap())
    val summarySize = estimateMapSize(summary)
    println("get_class_summary: ~$summarySize chars (methods=${cls.methods.size}, fields=${cls.fields.size}, inners=${cls.innerClasses.size})")

    val methods = cls.methods
    val methodsFirstPage = methods.take(100)
    val methodsFirstPageSize = methodsFirstPage.sumOf { m ->
        m.name.length + m.fullName.length + m.returnType.toString().length +
            m.arguments.sumOf { it.toString().length + 4 } + 60
    }
    println("get_methods_of_class (page=100): ${methodsFirstPage.size}/${methods.size}, ~$methodsFirstPageSize chars")
    val fields = cls.fields
    val fieldsFirstPage = fields.take(200)
    val fieldsFirstPageSize = fieldsFirstPage.sumOf { it.name.length + it.fullName.length + it.type.toString().length + 50 }
    println("get_fields_of_class (page=200): ${fieldsFirstPage.size}/${fields.size}, ~$fieldsFirstPageSize chars")

    // xrefs: pick first method/field with usage
    val methodWithUse = methods.firstOrNull { it.useIn.isNotEmpty() }
    if (methodWithUse != null) {
        val uses = methodWithUse.useIn
        val sample = uses.take(200)
        val perItemAvg = sample.firstOrNull()?.let { node ->
            val desc = s.describeUsage(node)
            desc.values.sumOf { it.toString().length } + 80
        } ?: 0
        println("get_xrefs_to_method(${methodWithUse.name}): total ${uses.size}, sample 200 ≈ ${sample.size * perItemAvg} chars")
    }
    val xrefsClass = cls.useIn
    println("get_xrefs_to_class: ${xrefsClass.size} refs")
}

private fun estimateMapSize(m: Map<String, Any>): Int {
    var n = 0
    for ((k, v) in m) {
        n += k.length + 6
        n += when (v) {
            is String -> v.length + 2
            is Int, is Long, is Boolean -> 8
            is List<*> -> v.sumOf { e ->
                when (e) {
                    is String -> e.length + 4
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        estimateMapSize(e as Map<String, Any>)
                    }
                    else -> 8
                }
            }
            else -> 16
        }
    }
    return n
}
