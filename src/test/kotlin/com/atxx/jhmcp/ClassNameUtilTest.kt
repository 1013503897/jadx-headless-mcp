package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClassNameUtilTest {
    @Test
    fun `dollar becomes dot`() {
        assertEquals("a.b.Outer.Inner", canonicalizeClassName("a.b.Outer\$Inner"))
    }

    @Test
    fun `already dotted is unchanged`() {
        assertEquals("a.b.C", canonicalizeClassName("a.b.C"))
    }

    @Test
    fun `companion is canonicalized`() {
        assertEquals("a.Outer.Companion", canonicalizeClassName("a.Outer\$Companion"))
    }

    @Test
    fun `multiple dollars all replaced`() {
        assertEquals("A.B.C", canonicalizeClassName("A\$B\$C"))
    }
}
