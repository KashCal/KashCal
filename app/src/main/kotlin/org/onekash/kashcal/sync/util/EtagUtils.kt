package org.onekash.kashcal.sync.util

/** ETag handling per RFC 7232. */
object EtagUtils {
    /**
     * Strips the weak `W/` prefix, surrounding quotes and `&quot;` entities from a raw etag from
     * a header or XML. RFC 7232 etags are strong (`"abc"`) or weak (`W/"abc"`).
     *
     * - `"abc123"` -> `abc123`
     * - `W/"abc123"` -> `abc123`
     * - `abc123` -> `abc123`
     * - `&quot;abc123&quot;` -> `abc123`
     *
     * @return the bare etag, or null if [etag] is null or nothing is left after stripping.
     */
    fun normalizeEtag(etag: String?): String? {
        if (etag == null) return null
        var result = etag.trim()
        // Decode quote entities first; regex-parsed XML leaves them encoded.
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
