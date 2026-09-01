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
