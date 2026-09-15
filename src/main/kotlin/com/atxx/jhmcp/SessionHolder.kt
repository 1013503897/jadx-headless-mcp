package com.atxx.jhmcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionHolder(
    internal val defaults: SessionConfig = SessionConfig(),
) {
    private val mutex = Mutex()

    @Volatile
    private var session: JadxSession? = null

    @Volatile
    private var loadDurationMs: Long = 0L

    @Volatile
    private var loadedAt: Long = 0L

    fun current(): JadxSession? = session

    suspend fun load(apkPath: String, options: SessionConfig = defaults): LoadResult = mutex.withLock {
        session?.close()
        session = null
        val started = System.currentTimeMillis()
        val s = JadxSession.open(apkPath, options)
        val elapsed = System.currentTimeMillis() - started
        loadDurationMs = elapsed
        loadedAt = System.currentTimeMillis()
        session = s
        LoadResult(
            apkPath = s.apkPath,
            classCount = s.classes.size,
            rawClassCount = s.rawClassCount,
            resourceCount = s.resources.size,
            loadDurationMs = elapsed,
            decompileTimeoutMs = s.decompileTimeoutMs,
            threads = s.threads,
            codeCacheSize = s.codeCacheSize,
            resourceMode = s.resourceMode.name.lowercase(),
            includePackages = s.packageFilter.include,
            excludePackages = s.packageFilter.exclude,
        )
    }

    suspend fun unload(): Boolean = mutex.withLock {
        val s = session ?: return@withLock false
        s.close()
        session = null
        loadedAt = 0
        loadDurationMs = 0
        true
    }

    fun snapshot(): Snapshot {
        val s = session
        return if (s == null) {
            Snapshot(state = "EMPTY")
        } else {
            Snapshot(
                state = "LOADED",
                apkPath = s.apkPath,
                classCount = s.classes.size,
                rawClassCount = s.rawClassCount,
                resourceCount = s.resources.size,
                loadDurationMs = loadDurationMs,
                loadedAtEpochMs = loadedAt,
                decompileTimeoutMs = s.decompileTimeoutMs,
                threads = s.threads,
                codeCacheSize = s.codeCacheSize,
                resourceMode = s.resourceMode.name.lowercase(),
                includePackages = s.packageFilter.include,
                excludePackages = s.packageFilter.exclude,
            )
        }
    }

    data class LoadResult(
        val apkPath: String,
        val classCount: Int,
        val rawClassCount: Int,
        val resourceCount: Int,
        val loadDurationMs: Long,
        val decompileTimeoutMs: Long = JadxSession.DEFAULT_DECOMPILE_TIMEOUT_MS,
        val threads: Int = 0,
        val codeCacheSize: Int = SessionConfig.DEFAULT_CODE_CACHE_SIZE,
        val resourceMode: String = ResourceMode.FULL.name.lowercase(),
        val includePackages: List<String> = emptyList(),
        val excludePackages: List<String> = emptyList(),
    )

    data class Snapshot(
        val state: String,
        val apkPath: String? = null,
        val classCount: Int? = null,
        val rawClassCount: Int? = null,
        val resourceCount: Int? = null,
        val loadDurationMs: Long? = null,
        val loadedAtEpochMs: Long? = null,
        val decompileTimeoutMs: Long? = null,
        val threads: Int? = null,
        val codeCacheSize: Int? = null,
        val resourceMode: String? = null,
        val includePackages: List<String>? = null,
        val excludePackages: List<String>? = null,
    )
}
