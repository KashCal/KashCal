package org.onekash.kashcal.sync.util

/**
 * Utility functions for ETag handling per RFC 7232.
 */
object EtagUtils {
    /**
     * Normalize etag by removing W/ prefix, surrounding quotes, and decoding XML entities.
     * RFC 7232: ETags can be "strong" ("abc") or "weak" (W/"abc").
     *
     * Handles formats:
     * - "abc123" -> abc123
     * - W/"abc123" -> abc123
     * - abc123 -> abc123 (unquoted)
     * - &quot;abc123&quot; -> abc123 (XML entity encoded)
     *
     * @param etag Raw etag string from server or XML
     * @return Normalized etag without W/ prefix, quotes, or XML entities, or null if input is null/blank
     */
    fun normalizeEtag(etag: String?): String? {
        if (etag == null) return null
        var result = etag.trim()
        // Decode XML entity for quotes first (from regex parsing)
        result = result.replace("&quot;", "\"")
        if (result.startsWith("W/")) {
            result = result.substring(2)
        }
        if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
            result = result.substring(1, result.length - 1)
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
