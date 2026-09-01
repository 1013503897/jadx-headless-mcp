package com.atxx.jhmcp

/**
 * Detection of jadx anti-decompile / decompile-failure banners. Extracted from [JadxSession]
 * so the (pure, APK-free) marker logic is unit-testable and shared.
 */

/** Substrings that reliably indicate jadx could NOT produce a usable Java body for a method. */
internal val STRONG_FAILURE_MARKERS = listOf(
    "Code decompiled incorrectly, please refer to instructions dump",
    "Method not decompiled",
    "Method code generation error",
    "Can't load method instructions",
    "Method dump skipped, instruction units count",
)

/** Softer signal — reported but does not, on its own, force a fallback (jadx often still emits usable code). */
internal val SOFT_FAILURE_MARKERS = listOf(
    "unreachable blocks",
    "Failed to decompile",
)

/** Return the failure markers present in [code] (strong markers first). Empty = clean decompile. */
internal fun detectDecompileFailure(code: String): List<String> {
    if (code.isEmpty()) return emptyList()
    val out = ArrayList<String>(2)
    for (m in STRONG_FAILURE_MARKERS) if (code.contains(m, ignoreCase = true)) out += m
    for (m in SOFT_FAILURE_MARKERS) if (code.contains(m, ignoreCase = true)) out += m
    return out
}
