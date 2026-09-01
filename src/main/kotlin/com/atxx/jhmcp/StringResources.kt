package com.atxx.jhmcp

/**
 * String-resource collection from res/values/strings*.xml (and the arsc res-table). Regex-based,
 * consistent with [ManifestParsing]. Extracted from Main.kt to keep the tool dispatcher thin.
 */

private val STRING_RES_RE = Regex(
    """<string\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</string>""",
    RegexOption.IGNORE_CASE
)

internal fun collectStringResources(
    s: JadxSession,
    filter: String?,
    limit: Int,
): List<Pair<String, String>> {
    val filterLc = filter?.lowercase()
    val out = mutableListOf<Pair<String, String>>()
    for (res in s.resources) {
        val deobf = res.deobfName.lowercase()
        val looksLikeStrings = "/values" in deobf && "strings" in deobf && deobf.endsWith(".xml")
        val isResTable = deobf.endsWith("resources.arsc") || deobf == "resources"
        if (!looksLikeStrings && !isResTable) continue
        val container = runCatching { res.loadContent() }.getOrNull() ?: continue
        val candidates = mutableListOf<jadx.core.xmlgen.ResContainer>()
        candidates += container
        candidates += container.subFiles
        for (c in candidates) {
            if (c.dataType != jadx.core.xmlgen.ResContainer.DataType.TEXT) continue
            val name = c.name.lowercase()
            if (!("/values" in name && "strings" in name && name.endsWith(".xml"))) continue
            val xml = c.text.codeStr ?: continue
            for (m in STRING_RES_RE.findAll(xml)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2]
                if (filterLc != null &&
                    !key.lowercase().contains(filterLc) &&
                    !value.lowercase().contains(filterLc)
                ) continue
                out += key to value
                if (out.size >= limit) return out
            }
        }
    }
    return out
}
