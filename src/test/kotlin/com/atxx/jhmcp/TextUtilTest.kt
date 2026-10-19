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

    // ── byteWindow ────────────────────────────────────────────────────────────

    @Test
    fun `byteWindow paginates ascii by byte offset`() {
        val w0 = byteWindow("abcdef", 0, 3)
        assertEquals("abc", w0.text)
        assertEquals(6, w0.totalBytes)
        assertEquals(3, w0.nextOffset)

        val w1 = byteWindow("abcdef", w0.nextOffset, 3)
        assertEquals("def", w1.text)
        assertEquals(6, w1.nextOffset) // == total → end
    }

    @Test
    fun `byteWindow never splits a multibyte char at the end`() {
        // "a中b" = a(1) + 中(3) + b(1) = 5 bytes. A 2-byte window from 0 must stop before 中.
        val w = byteWindow("a中b", 0, 2)
        assertEquals("a", w.text)
        assertEquals(5, w.totalBytes)
        assertEquals(1, w.nextOffset)
        assertFalse(w.text.contains('�'))
    }

    @Test
    fun `byteWindow emits a whole char even when the limit is smaller than it`() {
        // Resuming at the 中 (byte 1) with a 2-byte limit (< its 3 bytes) must still make progress.
        val w = byteWindow("a中b", 1, 2)
        assertEquals("中", w.text)
        assertEquals(4, w.nextOffset) // advanced past the whole 3-byte char
        assertFalse(w.text.contains('�'))
    }

    @Test
    fun `byteWindow reassembles the whole string across pages`() {
        val full = "头a中b尾xyz文字"
        val sb = StringBuilder()
        var off = 0
        val total = full.toByteArray(Charsets.UTF_8).size
        while (off < total) {
            val w = byteWindow(full, off, 2)
            assertTrue(w.nextOffset > off, "paging must advance") // no stall
            sb.append(w.text)
            off = w.nextOffset
        }
        assertEquals(full, sb.toString())
    }

    @Test
    fun `byteWindow past the end yields empty at total`() {
        val w = byteWindow("abc", 10, 5)
        assertEquals("", w.text)
        assertEquals(3, w.totalBytes)
        assertEquals(3, w.nextOffset)
    }
}
