package org.feeluown.mobile

/**
 * Validates an HTTP 206 Content-Range before appending bytes to a partial download.
 * A range must start exactly at the existing file length and describe a non-empty interval.
 */
fun isExpectedPartialContentRange(header: String?, expectedStart: Long): Boolean {
    if (expectedStart < 0L || header.isNullOrBlank()) return false
    val match = PARTIAL_CONTENT_RANGE.matchEntire(header.trim()) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    return start == expectedStart && end >= start && (total == null || total > end)
}

private val PARTIAL_CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
