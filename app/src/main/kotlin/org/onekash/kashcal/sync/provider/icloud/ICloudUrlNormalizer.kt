package org.onekash.kashcal.sync.provider.icloud

/**
 * Normalizes iCloud CalDAV URLs from regional servers to the canonical host.
 *
 * iCloud's regional servers (p180-caldav.icloud.com, p181-caldav.icloud.com) can become
 * unreachable when Apple rotates server assignments, while the canonical host
 * (caldav.icloud.com) routes to the right regional server at the CDN level. Storing only
 * canonical URLs keeps sync working across a rotation.
 *
 * "https://p180-caldav.icloud.com:443/123456/calendars/..." becomes
 * "https://caldav.icloud.com/123456/calendars/...".
 */
object ICloudUrlNormalizer {

    private const val CANONICAL_HOST = "caldav.icloud.com"

    /**
     * Matches a regional host with any explicit port, case-insensitively: p180-caldav.icloud.com,
     * p1-caldav.icloud.com, P180-CALDAV.ICLOUD.COM, p180-caldav.icloud.com:443. It doesn't
     * match the canonical caldav.icloud.com or another domain such as p180-caldav.notcloud.com.
     */
    private val REGIONAL_PATTERN = Regex(
        """p\d+-caldav\.icloud\.com(:\d+)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Replaces a regional host and its explicit port with caldav.icloud.com, keeping path,
     * query and fragment. Other URLs are returned unchanged; null stays null.
     */
    fun normalize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        return url.replace(REGIONAL_PATTERN, CANONICAL_HOST)
    }

    /**
     * Returns whether a bare host name is iCloud's CalDAV host, canonical or a numbered
     * partition (p*-caldav.icloud.com).
     */
    fun isCalDavHost(host: String): Boolean =
        host.equals(CANONICAL_HOST, ignoreCase = true) || REGIONAL_PATTERN.matches(host)

    /** Returns true if [url] contains a regional host (p*-caldav.icloud.com) to normalize. */
    fun isRegionalUrl(url: String?): Boolean {
        return url?.let { REGIONAL_PATTERN.containsMatchIn(it) } ?: false
    }

    /** Returns true if [url] contains "icloud.com", regional or canonical. Only tests call it. */
    fun isICloudUrl(url: String?): Boolean {
        return url?.contains("icloud.com", ignoreCase = true) ?: false
    }
}
