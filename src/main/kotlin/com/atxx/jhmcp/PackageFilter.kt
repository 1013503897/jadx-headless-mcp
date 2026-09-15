package com.atxx.jhmcp

/**
 * Keep/drop classes by package (or exact FQN) prefix.
 *
 * jadx 1.5.6 still parses every DEX class during `load()` — this filter cannot skip that.
 * After load we hide dropped classes from the indexes/tools and `JavaClass.unload()` them
 * so their method IR is eligible for GC.
 *
 * A prefix `com.foo` matches `com.foo.Bar` and `com.foo.bar.Baz`, not `com.foobar.X`.
 * Trailing `.*` / `.` on a token are stripped (`androidx.*` → `androidx`).
 */
data class PackageFilter(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
) {
    val active: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()

    fun keep(fullName: String, rawName: String = fullName): Boolean {
        if (!active) return true
        if (include.isNotEmpty() && !include.any { matchesPrefix(fullName, it) || matchesPrefix(rawName, it) }) {
            return false
        }
        if (exclude.any { matchesPrefix(fullName, it) || matchesPrefix(rawName, it) }) return false
        return true
    }

    companion object {
        fun parseList(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',', ';')
                .map { normalizePrefix(it) }
                .filter { it.isNotEmpty() }
        }

        fun normalizePrefix(raw: String): String {
            var s = raw.trim()
            if (s.endsWith(".*")) s = s.dropLast(2)
            s = s.trimEnd('.')
            return canonicalizeClassName(s).trim('.')
        }

        fun matchesPrefix(name: String, prefix: String): Boolean {
            if (prefix.isEmpty()) return true
            val n = canonicalizeClassName(name).trim('.')
            val p = canonicalizeClassName(prefix).trim('.')
            return n == p || n.startsWith("$p.")
        }
    }
}
