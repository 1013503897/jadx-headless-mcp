package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextUtilTest {
    @Test
    fun `returns input unchanged when within budget`() {
        assertEquals("hello", truncateToBytes("hello", 100))
    }

    @Test
    fun `null becomes empty`() {
        assertEquals("", truncateToBytes(null, 100))
    }

    @Test
    fun `zero or negative budget yields empty`() {
        assertEquals("", truncateToBytes("hello", 0))
        assertEquals("", truncateToBytes("hello", -5))
    }

    @Test
    fun `ascii truncates with a notice`() {
        val out = truncateToBytes("abcdefghij", 5)
        assertTrue(out.startsWith("abcde"))
        assertTrue(out.contains("truncated"))
    }

    @Test
    fun `does not split a multibyte char`() {
        // "中" is 3 bytes in UTF-8; budget 4 must keep exactly one char, not a broken second.
        val out = truncateToBytes("中中中", 4)
        val head = out.substringBefore("\n")
        assertEquals("中", head)
        assertFalse(head.contains('�')) // no replacement char from a severed sequence
    }

    @Test
    fun `multibyte within budget unchanged`() {
        assertEquals("中文", truncateToBytes("中文", 100))
    }
}
