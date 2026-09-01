package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecompileFailureTest {
    @Test
    fun `clean code has no markers`() {
        assertTrue(detectDecompileFailure("class Foo { void bar() {} }").isEmpty())
    }

    @Test
    fun `empty input has no markers`() {
        assertTrue(detectDecompileFailure("").isEmpty())
    }

    @Test
    fun `strong marker is detected`() {
        val m = detectDecompileFailure("/* Method not decompiled */\ncode")
        assertTrue(m.contains("Method not decompiled"))
    }

    @Test
    fun `strong markers come before soft markers`() {
        val code = "some unreachable blocks here ... Can't load method instructions"
        val m = detectDecompileFailure(code)
        assertEquals("Can't load method instructions", m.first())
        assertTrue(m.contains("unreachable blocks"))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertTrue(detectDecompileFailure("METHOD NOT DECOMPILED").isNotEmpty())
    }
}
