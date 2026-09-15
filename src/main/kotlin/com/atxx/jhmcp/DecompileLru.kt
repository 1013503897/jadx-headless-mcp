package com.atxx.jhmcp

import jadx.api.JavaClass

/**
 * Access-order LRU of decompiled top-level classes. Evicted entries are
 * `JavaClass.unload()`'d so jadx drops the in-memory `ICodeInfo` (MCP truncation
 * does not). [limit] `<= 0` disables the cache (legacy unbounded behaviour).
 */
internal class DecompileLru(private val limit: Int) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, JavaClass>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JavaClass>): Boolean {
            if (limit <= 0 || size <= limit) return false
            pendingUnload = eldest.value
            return true
        }
    }

    @Volatile
    private var pendingUnload: JavaClass? = null

    val size: Int
        get() = synchronized(lock) { map.size }

    fun remember(cls: JavaClass) {
        if (limit <= 0) return
        val top = runCatching { cls.topParentClass ?: cls }.getOrDefault(cls)
        val key = top.rawName
        val evicted: JavaClass?
        synchronized(lock) {
            map[key] = top
            evicted = pendingUnload
            pendingUnload = null
        }
        if (evicted != null && evicted !== top) {
            runCatching { evicted.unload() }
        }
    }
}
