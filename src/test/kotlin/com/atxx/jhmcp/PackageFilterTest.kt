package com.atxx.jhmcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageFilterTest {
    @Test
    fun `empty filter keeps everything`() {
        val f = PackageFilter()
        assertFalse(f.active)
        assertTrue(f.keep("androidx.core.Foo"))
    }

    @Test
    fun `include matches package and nested, not sibling prefix`() {
        val f = PackageFilter(include = listOf("com.foo"))
        assertTrue(f.keep("com.foo.Bar"))
        assertTrue(f.keep("com.foo.bar.Baz"))
        assertTrue(f.keep("com.foo"))
        assertFalse(f.keep("com.foobar.X"))
        assertFalse(f.keep("com.other.X"))
    }

    @Test
    fun `include exact class also keeps inner via dollar or dot`() {
        val f = PackageFilter(include = listOf("com.foo.Bar"))
        assertTrue(f.keep("com.foo.Bar"))
        assertTrue(f.keep("com.foo.Bar.Inner", "com.foo.Bar\$Inner"))
        assertFalse(f.keep("com.foo.Baz"))
    }

    @Test
    fun `exclude drops matching packages even if included`() {
        val f = PackageFilter(include = listOf("com"), exclude = listOf("com.foo.internal"))
        assertTrue(f.keep("com.foo.Bar"))
        assertFalse(f.keep("com.foo.internal.Secret"))
    }

    @Test
    fun `parseList strips glob suffix and splits on comma`() {
        assertEquals(
            listOf("androidx", "kotlin", "com.foo"),
            PackageFilter.parseList(" androidx.* , kotlin. ,; com.foo "),
        )
        assertEquals(emptyList(), PackageFilter.parseList("  "))
        assertEquals(emptyList(), PackageFilter.parseList(null))
    }

    @Test
    fun `matchesPrefix unifies dollar inner names`() {
        assertTrue(PackageFilter.matchesPrefix("a.b.Outer\$Inner", "a.b.Outer"))
        assertFalse(PackageFilter.matchesPrefix("a.b.Outer2", "a.b.Outer"))
    }
}
