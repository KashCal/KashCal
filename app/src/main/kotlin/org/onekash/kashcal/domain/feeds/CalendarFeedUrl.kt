package org.onekash.kashcal.domain.feeds

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cleans a calendar feed URL a CalDAV server stored, so KashCal can download and compare it. */
object CalendarFeedUrl {

    private val WEBCAL_SCHEME = Regex("^webcals?://", RegexOption.IGNORE_CASE)

    /**
     * Returns [raw] as an http(s) URL to download, or null when it can't be one.
     *
     * - Surrounding whitespace is trimmed.
     * - `webcal://` and `webcals://`, in any case, become `https://` (the manual add rewrites
     *   only the lowercase forms). `http://` stays: a server feed may be plain http, like a
     *   typed one.
     * - Any other scheme, a relative path, or anything [HttpUrl] can't parse gives null. Servers
     *   return such values as stored: Nextcloud resolves a source with leading spaces into a
     *   path on itself.
     * - A `user:pass@` part is dropped. The ICS fetcher never sends it, and keeping it would put
     *   a password in the UI and in logs.
     *
     * The result is [HttpUrl]'s form (lowercase scheme and host, punycode host, encoded path),
     * so two spellings of one URL compare equal, and normalizing it again changes nothing.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = trimmed.replace(WEBCAL_SCHEME, "https://")
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.newBuilder().username("").password("").build().toString()
    }

    /** Returns the host of a URL [normalize] produced, or [url] itself if it isn't one. */
    fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
}
