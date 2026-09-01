package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmaliSlicingTest {
    @Test
    fun `parses a simple method name`() {
        assertEquals("foo", parseSmaliMethodName("public static foo(Ljava/lang/String;)V"))
    }

    @Test
    fun `parses a constructor name`() {
        assertEquals("<init>", parseSmaliMethodName("public constructor <init>()V"))
    }

    @Test
    fun `parses a name with no parenthesis`() {
        assertEquals("bar", parseSmaliMethodName("private bar"))
    }

    @Test
    fun `extracts a single method block`() {
        val smali = """
            .class public Lcom/Foo;
            .method public a()V
                return-void
            .end method
            .method public b()V
                return-void
            .end method
        """.trimIndent()
        val blocks = extractMethodBlocks(smali, "a")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].contains(".method public a()V"))
        assertTrue(blocks[0].endsWith(".end method"))
        assertFalse(blocks[0].contains("b()V"))
    }

    @Test
    fun `extracts all overloads of a name`() {
        val smali = """
            .method public a()V
                return-void
            .end method
            .method public a(I)V
                return-void
            .end method
        """.trimIndent()
        assertEquals(2, extractMethodBlocks(smali, "a").size)
    }

    @Test
    fun `returns empty when no method matches`() {
        val smali = ".method public a()V\n    return-void\n.end method"
        assertTrue(extractMethodBlocks(smali, "zzz").isEmpty())
    }

    @Test
    fun `captures an unterminated trailing method to end of input`() {
        val smali = ".method public a()V\n    nop\n    nop"
        val blocks = extractMethodBlocks(smali, "a")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].contains("nop"))
    }
}
