package com.atxx.jhmcp

/**
 * Truncate [s] so its UTF-8 encoding is at most [maxBytes] bytes, never splitting a
 * multi-byte character.
 *
 * The previous implementation counted UTF-16 `String.length` as "bytes" and could cut
 * through a surrogate pair (producing a lone surrogate that corrupts JSON). This version
 * measures and cuts on real UTF-8 byte boundaries, so the `maxSourceBytes` budget is honest
 * for non-ASCII content (Chinese string resources, emoji, …). Appends a truncation notice
 * when the input overflows.
 */
internal fun truncateToBytes(s: String?, maxBytes: Int): String {
    val text = s ?: return ""
    if (maxBytes <= 0) return ""
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var end = maxBytes
    // Walk back off any UTF-8 continuation byte (10xxxxxx) so we cut on a char boundary.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    val head = String(bytes, 0, end, Charsets.UTF_8)
    return head + "\n\n... [truncated: exceeds $maxBytes bytes, total ${bytes.size} bytes]"
}

/** A UTF-8 byte window into a string: the slice, the string's total UTF-8 size, and where to resume. */
internal data class ByteWindow(val text: String, val totalBytes: Int, val nextOffset: Int)

/**
 * Slice a window out of [s]'s UTF-8 encoding for paging a large value. [offset] and [limit] are real
 * UTF-8 byte counts — matching the `max_bytes` budget used everywhere else — so a caller can advance
 * by the returned [ByteWindow.nextOffset] and reassemble the whole value byte-for-byte.
 *
 * Boundaries never split a character: the start snaps forward off a continuation byte, the end snaps
 * back to the last whole character that fits. If [limit] is smaller than the one character at the
 * start, that whole character is emitted anyway (slightly over budget) so paging always progresses
 * instead of stalling on an empty window.
 */
internal fun byteWindow(s: String?, offset: Int, limit: Int): ByteWindow {
    val bytes = (s ?: "").toByteArray(Charsets.UTF_8)
    val total = bytes.size
    if (total == 0 || limit <= 0) return ByteWindow("", total, total)
    var from = offset.coerceIn(0, total)
    while (from < total && (bytes[from].toInt() and 0xC0) == 0x80) from++
    if (from >= total) return ByteWindow("", total, total)
    var to = (from + limit).coerceAtMost(total)
    while (to > from && to < total && (bytes[to].toInt() and 0xC0) == 0x80) to--
    if (to <= from) {
        // Window too small for even one character — emit exactly that character to guarantee progress.
        to = (from + 1).coerceAtMost(total)
        while (to < total && (bytes[to].toInt() and 0xC0) == 0x80) to++
    }
    return ByteWindow(String(bytes, from, to - from, Charsets.UTF_8), total, to)
}
