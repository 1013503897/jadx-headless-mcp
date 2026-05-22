package com.atxx.jhmcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionHolder(
    private val maxSourceBytes: Int,
    private val codeScanCap: Int = 0,
) {
    private val mutex = Mutex()

    @Volatile
    private var session: JadxSession? = null

    @Volatile
    private var loadDurationMs: Long = 0L

    @Volatile
    private var loadedAt: Long = 0L

    fun current(): JadxSession? = session

    suspend fun load(apkPath: String): LoadResult = mutex.withLock {
        session?.close()
        session = null
        val started = System.currentTimeMillis()
        val s = JadxSession.open(apkPath, maxSourceBytes, codeScanCap)
        val elapsed = System.currentTimeMillis() - started
        loadDurationMs = elapsed
        loadedAt = System.currentTimeMillis()
        session = s
        LoadResult(s.apkPath, s.classes.size, s.resources.size, elapsed)
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
                resourceCount = s.resources.size,
                loadDurationMs = loadDurationMs,
                loadedAtEpochMs = loadedAt,
            )
        }
    }

    data class LoadResult(
        val apkPath: String,
        val classCount: Int,
        val resourceCount: Int,
        val loadDurationMs: Long,
    )

    data class Snapshot(
        val state: String,
        val apkPath: String? = null,
        val classCount: Int? = null,
        val resourceCount: Int? = null,
        val loadDurationMs: Long? = null,
        val loadedAtEpochMs: Long? = null,
    )
}
