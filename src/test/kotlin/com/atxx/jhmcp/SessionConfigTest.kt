package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionConfigTest {
    @Test
    fun `resource mode parse aliases`() {
        assertEquals(ResourceMode.FULL, ResourceMode.parse("full"))
        assertEquals(ResourceMode.LITE, ResourceMode.parse("LITE"))
        assertEquals(ResourceMode.NONE, ResourceMode.parse("none"))
        assertEquals(ResourceMode.NONE, ResourceMode.parse("skip"))
        assertFailsWith<IllegalArgumentException> { ResourceMode.parse("maybe") }
    }

    @Test
    fun `withLoadOverrides only replaces provided fields`() {
        val base = SessionConfig(
            threads = 3,
            includePackages = listOf("com.a"),
            excludePackages = listOf("kotlin"),
            codeCacheSize = 10,
            resourceMode = ResourceMode.FULL,
        )
        val merged = base.withLoadOverrides(
            includePackages = "com.gcash, com.mynt",
            resourceMode = "lite",
        )
        assertEquals(listOf("com.gcash", "com.mynt"), merged.includePackages)
        assertEquals(listOf("kotlin"), merged.excludePackages)
        assertEquals(3, merged.threads)
        assertEquals(10, merged.codeCacheSize)
        assertEquals(ResourceMode.LITE, merged.resourceMode)
    }

    @Test
    fun `thread 0 means all cores`() {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        assertEquals(cores, SessionConfig.resolveThreadCount(0))
        assertEquals(cores, SessionConfig.resolveThreadCount(-1))
        assertEquals(2, SessionConfig.resolveThreadCount(2))
    }
}
