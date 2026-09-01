package com.atxx.jhmcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Regex-based AndroidManifest parsing/slicing. Intentionally NOT a full XML parser — the decoded
 * manifest jadx produces is well-formed enough for these targeted extractions, and this keeps the
 * server dependency-free. Pure functions, extracted from Main.kt so they are unit-testable.
 */

internal val MANIFEST_PKG_RE = Regex("""<manifest\b[^>]*\bpackage="([^"]+)"""")
private val VERSION_NAME_RE = Regex("""android:versionName="([^"]+)"""")
private val VERSION_CODE_RE = Regex("""android:versionCode="([^"]+)"""")
private val MIN_SDK_RE = Regex("""android:minSdkVersion="([^"]+)"""")
private val TARGET_SDK_RE = Regex("""android:targetSdkVersion="([^"]+)"""")
private val PERMISSION_RE = Regex("""<uses-permission\b[^>]*android:name="([^"]+)"""")

internal fun parseManifestSummary(xml: String): JsonObject = buildJsonObject {
    put("package", MANIFEST_PKG_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("version_name", VERSION_NAME_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("version_code", VERSION_CODE_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("min_sdk", MIN_SDK_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("target_sdk", TARGET_SDK_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("permissions", buildJsonArray {
        PERMISSION_RE.findAll(xml).forEach { add(it.groupValues[1]) }
    })
}

/**
 * Slice AndroidManifest XML by section name. Returns null if [section] is not recognized.
 * For multi-instance sections (activities/services/etc.) joins all matches with blank lines.
 */
internal fun sliceManifest(xml: String, section: String): String? {
    return when (section) {
        "permissions" -> {
            val re = Regex("""<uses-permission\b[^/>]*/>|<uses-permission\b[\s\S]*?</uses-permission>""")
            re.findAll(xml).joinToString("\n") { it.value }.ifBlank { "<!-- no <uses-permission> entries -->" }
        }
        "activities" -> sliceTopLevel(xml, "activity") ?: "<!-- no <activity> entries -->"
        "services" -> sliceTopLevel(xml, "service") ?: "<!-- no <service> entries -->"
        "providers" -> sliceTopLevel(xml, "provider") ?: "<!-- no <provider> entries -->"
        "receivers" -> sliceTopLevel(xml, "receiver") ?: "<!-- no <receiver> entries -->"
        "application" -> {
            // Application open-tag with its attrs (no children — children go to other sections).
            Regex("""<application\b[^>]*>""").find(xml)?.value ?: "<!-- <application> tag not found -->"
        }
        else -> null
    }
}

private fun sliceTopLevel(xml: String, tag: String): String? {
    val open = Regex("""<$tag\b""")
    val results = mutableListOf<String>()
    for (m in open.findAll(xml)) {
        val start = m.range.first
        // Find matching end: either self-closing /> or </tag>
        val rest = xml.substring(start)
        val selfClose = Regex("""<$tag\b[^>]*/>""").find(rest, 0)
        val pairClose = Regex("""<$tag\b[\s\S]*?</$tag>""").find(rest, 0)
        val chosen = when {
            selfClose != null && pairClose != null ->
                if (selfClose.range.last <= pairClose.range.last) selfClose else pairClose
            selfClose != null -> selfClose
            pairClose != null -> pairClose
            else -> null
        } ?: continue
        if (chosen.range.first != 0) continue // must start at our anchor
        results += chosen.value
    }
    return if (results.isEmpty()) null else results.joinToString("\n\n")
}

internal fun findLauncherActivity(xml: String): String? {
    val activityBlockRe = Regex(
        """<activity\b[^>]*android:name="([^"]+)"[^>]*>([\s\S]*?)</activity>""",
        RegexOption.IGNORE_CASE
    )
    val mainAction = "android.intent.action.MAIN"
    val launcherCategory = "android.intent.category.LAUNCHER"
    for (m in activityBlockRe.findAll(xml)) {
        val body = m.groupValues[2]
        if (body.contains(mainAction) && body.contains(launcherCategory)) {
            return m.groupValues[1]
        }
    }
    val activityAliasRe = Regex(
        """<activity-alias\b[^>]*android:targetActivity="([^"]+)"[^>]*>([\s\S]*?)</activity-alias>""",
        RegexOption.IGNORE_CASE
    )
    for (m in activityAliasRe.findAll(xml)) {
        val body = m.groupValues[2]
        if (body.contains(mainAction) && body.contains(launcherCategory)) {
            return m.groupValues[1]
        }
    }
    return null
}

internal fun absoluteClassName(pkg: String, raw: String): String =
    when {
        raw.isEmpty() -> raw
        raw.startsWith(".") -> pkg + raw
        !raw.contains('.') -> "$pkg.$raw"
        else -> raw
    }
