package com.atxx.jhmcp

/**
 * Unify nested-class separators so `$` and `.` forms collapse to one key
 * (`Outer$Inner` and `Outer.Inner` become identical). Used by class resolution.
 */
internal fun canonicalizeClassName(s: String): String = s.replace('$', '.')
