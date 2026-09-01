package com.atxx.jhmcp

/**
 * Method-level smali slicing. jadx has no per-method smali API, so we materialize the class
 * disassembly once (in [JadxSession]) and slice the `.method … .end method` blocks out here.
 * Pure string logic — extracted so it is unit-testable without loading an APK.
 */

private val METHOD_DIRECTIVE = Regex("""^\s*\.method\b(.*)$""")

/**
 * Extract the `.method`…`.end method` block(s) whose method name equals [methodName].
 * Returns every overload. Single-pass over lines (no full `List<String>` materialization);
 * an unterminated final method (no `.end method`) is still captured to end-of-input, matching
 * the previous slice-to-end behaviour.
 */
internal fun extractMethodBlocks(smali: String, methodName: String): List<String> {
    val out = ArrayList<String>(2)
    var inMethod = false
    var capture = false
    val buf = StringBuilder()
    for (raw in smali.lineSequence()) {
        if (!inMethod) {
            val m = METHOD_DIRECTIVE.find(raw)
            if (m != null) {
                inMethod = true
                capture = parseSmaliMethodName(m.groupValues[1]) == methodName
                if (capture) {
                    buf.setLength(0)
                    buf.append(raw)
                }
            }
        } else {
            if (capture) buf.append('\n').append(raw)
            if (raw.trimStart().startsWith(".end method")) {
                if (capture) out += buf.toString().trimEnd()
                inMethod = false
                capture = false
            }
        }
    }
    // Unterminated trailing method (malformed smali): capture what we have, as the old code did.
    if (inMethod && capture && buf.isNotEmpty()) out += buf.toString().trimEnd()
    return out
}

/** From the text after `.method` (e.g. `public static foo(Ljava/lang/String;)V`) pull the method name. */
internal fun parseSmaliMethodName(afterDirective: String): String {
    val paren = afterDirective.indexOf('(')
    val head = if (paren >= 0) afterDirective.substring(0, paren) else afterDirective
    // name is the last whitespace-delimited token before '('
    return head.trim().substringAfterLast(' ').substringAfterLast('\t').trim()
}
