package com.atxx.jhmcp

/**
 * Per-process defaults (CLI) that `load_apk` can override per APK.
 *
 * [threads] `<= 0` means "use every CPU" (legacy default). When the flag is omitted the
 * CLI fills in [defaultLoadThreads] (`min(cores, 4)`) to cap peak RAM during pre-decompile.
 *
 * [codeCacheSize] `0` keeps every decompiled class in memory (legacy). The default LRU
 * unloads the least-recently-used class via `JavaClass.unload()` once the cap is hit.
 */
data class SessionConfig(
    val maxSourceBytes: Int = 60_000,
    val codeScanCap: Int = 0,
    val decompileTimeoutMs: Long = JadxSession.DEFAULT_DECOMPILE_TIMEOUT_MS,
    val threads: Int = defaultLoadThreads(),
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val codeCacheSize: Int = DEFAULT_CODE_CACHE_SIZE,
    val resourceMode: ResourceMode = ResourceMode.FULL,
) {
    val packageFilter: PackageFilter
        get() = PackageFilter(include = includePackages, exclude = excludePackages)

    fun resolvedThreads(): Int = resolveThreadCount(threads)

    fun withLoadOverrides(
        includePackages: String? = null,
        excludePackages: String? = null,
        threads: Int? = null,
        codeCacheSize: Int? = null,
        resourceMode: String? = null,
    ): SessionConfig = copy(
        includePackages = includePackages?.let { PackageFilter.parseList(it) } ?: this.includePackages,
        excludePackages = excludePackages?.let { PackageFilter.parseList(it) } ?: this.excludePackages,
        threads = threads ?: this.threads,
        codeCacheSize = codeCacheSize ?: this.codeCacheSize,
        resourceMode = resourceMode?.let { ResourceMode.parse(it) } ?: this.resourceMode,
    )

    companion object {
        const val DEFAULT_CODE_CACHE_SIZE: Int = 64

        fun defaultLoadThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(1)

        /** `<= 0` → all cores (escape hatch back to the pre-v0.8 default). */
        fun resolveThreadCount(n: Int): Int {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return if (n <= 0) cores else n
        }
    }
}

enum class ResourceMode {
    /** Decode and expose every resource file (historical default). */
    FULL,

    /**
     * Still lets jadx parse resources.arsc at load (1.5.6 has no load-time skip),
     * but tools only see Manifest + values/strings XML + the resource table.
     */
    LITE,

    /**
     * Tools hide non-manifest resources. get_strings / listing pngs etc. are unavailable.
     * Manifest still works. Does not skip arsc parse inside jadx 1.5.6 load().
     */
    NONE,
    ;

    companion object {
        fun parse(raw: String): ResourceMode = when (raw.trim().lowercase()) {
            "full" -> FULL
            "lite" -> LITE
            "none", "skip", "no" -> NONE
            else -> throw IllegalArgumentException(
                "unknown resources mode '$raw' (expected full|lite|none)"
            )
        }
    }
}
