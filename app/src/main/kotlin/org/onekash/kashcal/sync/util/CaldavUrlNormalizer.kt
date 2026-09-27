package org.onekash.kashcal.sync.util

/**
 * Canonicalizes CalDAV resource URLs for comparison only, so two URLs for the same resource that
 * differ in percent-encoding compare equal.
 *
 * KashCal builds a resource URL from its generated UID, which contains an '@'
 * (`<uuid>@kashcal.onekash.org`), and stores that literal-'@' URL as the event's `caldav_url`. Some
 * servers (confirmed: Radicale) echo the href back with the '@' encoded as `%40`. When such a
 * server then reports the resource deleted (RFC 6578 sync-collection) or absent (etag comparison),
 * string equality against the stored URL fails and the local row is never matched: the deletion is
 * silently skipped, or the still-present event is misclassified as deleted. Both encodings coexist
 * in the local DB (the create path stores literal '@', a server-echoed path stores what the server
 * sent), so both sides must be canonicalized at comparison time.
 *
 * Per RFC 3986 §3.3 a path segment allows the sub-delims, ':' and '@' unencoded (`pchar`), so
 * their encoded and literal forms are equivalent in a path and decoding them can't merge two
 * distinct resources (a server can't host both `a,b.ics` and `a%2Cb.ics`). Only that set is
 * decoded. `%2F` stays encoded (an encoded slash would cross a segment boundary and change the
 * path structure), as does every other octet. This is not a general percent-decoder.
 */
object CaldavUrlNormalizer {

    /**
     * Characters whose percent-encoded form is decoded: the RFC 3986 sub-delims plus ':' and '@'.
     * Excludes '/' (%2F) and the path delimiters '?' and '#'.
     */
    private val DECODABLE: Map<Char, Char> = mapOf(
        '@' to '@',   // %40, the observed Radicale case
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
     * Returns [url] with each [DECODABLE] percent-encoding folded to its literal character.
     * Returns null, empty and `%`-free input as is. Idempotent.
     */
    fun canonicalize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
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
                // A malformed escape or one left encoded (such as %2F): keep the '%'.
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
