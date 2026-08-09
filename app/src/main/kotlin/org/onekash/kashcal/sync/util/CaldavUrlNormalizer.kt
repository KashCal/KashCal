package org.onekash.kashcal.sync.util

/**
 * Canonicalizes CalDAV resource URLs for *comparison only*, so that two URLs that
 * denote the same resource but differ in percent-encoding compare equal.
 *
 * Motivation: KashCal builds a resource URL from its generated UID, which contains
 * an '@' (`<uuid>@kashcal.onekash.org`), and stores that literal-'@' URL as the
 * event's `caldav_url`. Some servers (confirmed: Radicale) echo the resource href
 * back with the '@' percent-encoded as `%40`. When such a server later reports the
 * resource as deleted (RFC 6578 sync-collection) or absent (etag comparison),
 * strict string equality against the stored literal-'@' URL fails, so the local row
 * is never matched — the deletion is silently skipped, or the still-present event is
 * misclassified as deleted. Both encodings legitimately coexist in the local DB
 * depending on how the row was written (create path stores literal '@'; server-echoed
 * path stores whatever the server sent), so the fix must canonicalize *both* sides at
 * comparison time.
 *
 * Per RFC 3986 §3.3, a path segment permits the sub-delimiters and the ':' and '@'
 * characters unencoded (`pchar`). For those characters, the percent-encoded and
 * literal forms are semantically equivalent within a path, so decoding them cannot
 * merge two distinct server resources (a server cannot host both `a,b.ics` and
 * `a%2Cb.ics`). This canonicalizer therefore decodes exactly that set and nothing
 * else. In particular it deliberately does NOT decode `%2F` (an encoded slash would
 * cross a segment boundary and change the path structure) or any octet that maps to a
 * character outside the pchar reserved set.
 *
 * This is intentionally NOT a general percent-decoder — it is the minimal
 * canonicalization needed for stable resource-identity comparison.
 */
object CaldavUrlNormalizer {

    /**
     * Percent-encoded octets that are safe to decode for comparison: the RFC 3986
     * sub-delims plus ':' and '@', all of which are legal unencoded in a path segment
     * (`pchar`). Notably excludes '/' (%2F) and '?' '#' (which delimit the path).
     */
    private val DECODABLE: Map<Char, Char> = mapOf(
        '@' to '@',   // %40 — the observed Radicale case
        '!' to '!',   // %21
        '$' to '$',   // %24
        '&' to '&',   // %26
        '\'' to '\'', // %27
        '(' to '(',   // %28
        ')' to ')',   // %29
        '*' to '*',   // %2A
        '+' to '+',   // %2B
        ',' to ',',   // %2C
        ';' to ';',   // %3B
        '=' to '=',   // %3D
        ':' to ':'    // %3A
    )

    /**
     * Return a canonical form of [url] in which percent-encoded pchar-legal reserved
     * octets are folded to their literal character. Returns [url] unchanged when it
     * contains no such encoding (the common case) and passes null/empty straight
     * through. Idempotent.
     */
    fun canonicalize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        // Fast path: nothing to decode.
        if (!url.contains('%')) return url

        val sb = StringBuilder(url.length)
        var i = 0
        while (i < url.length) {
            val c = url[i]
            if (c == '%' && i + 2 < url.length) {
                val hi = hexValue(url[i + 1])
                val lo = hexValue(url[i + 2])
                if (hi >= 0 && lo >= 0) {
                    val decoded = ((hi shl 4) or lo).toChar()
                    val folded = DECODABLE[decoded]
                    if (folded != null) {
                        sb.append(folded)
                        i += 3
                        continue
                    }
                }
                // Not a decodable escape (malformed, or an octet we intentionally
                // leave encoded such as %2F): keep the '%' verbatim and continue.
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
