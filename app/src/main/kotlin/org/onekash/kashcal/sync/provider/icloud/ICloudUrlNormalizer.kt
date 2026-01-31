package org.onekash.kashcal.sync.provider.icloud

/**
 * Normalizes iCloud CalDAV URLs from regional servers to canonical form.
 *
 * iCloud uses regional servers (p180-caldav.icloud.com, p181-caldav.icloud.com, etc.)
 * that can become unreachable when Apple rotates server assignments. The canonical
 * hostname (caldav.icloud.com) transparently routes to the appropriate regional
 * server at the CDN level.
 *
 * By normalizing all URLs to canonical form at storage time, server rotation
 * becomes transparent and sync continues working without manual intervention.
 *
 * Example:
 * - Input:  "https://p180-caldav.icloud.com:443/123456/calendars/..."
 * - Output: "https://caldav.icloud.com/123456/calendars/..."
 */
object ICloudUrlNormalizer {

    private const val CANONICAL_HOST = "caldav.icloud.com"

    /**
     * Pattern matching regional iCloud CalDAV servers.
     *
     * Matches:
     * - p180-caldav.icloud.com
     * - p1-caldav.icloud.com
     * - P180-CALDAV.ICLOUD.COM (case insensitive)
     * - p180-caldav.icloud.com:443 (with explicit port)
     *
     * Does NOT match:
     * - caldav.icloud.com (canonical - already normalized)
     * - p180-caldav.notcloud.com (different domain)
     */
    private val REGIONAL_PATTERN = Regex(
        """p\d+-caldav\.icloud\.com(:\d+)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Normalize iCloud URL to canonical form.
     *
     * - Regional server (p180-caldav.icloud.com) → caldav.icloud.com
     * - Strips explicit :443 port (implicit for HTTPS)
     * - Case-insensitive matching
     * - Preserves path, query parameters, and fragments
     *
     * @param url The URL to normalize (may be null)
     * @return Normalized URL, or null if input was null
     */
    fun normalize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        return url.replace(REGIONAL_PATTERN, CANONICAL_HOST)
    }

    /**
     * Check if URL is a regional iCloud URL that needs normalization.
     *
     * @param url The URL to check
     * @return true if URL contains a regional iCloud hostname (p*-caldav.icloud.com)
     */
    fun isRegionalUrl(url: String?): Boolean {
        return url?.let { REGIONAL_PATTERN.containsMatchIn(it) } ?: false
    }

    /**
     * Check if URL is any iCloud URL (regional or canonical).
     *
     * @param url The URL to check
     * @return true if URL contains icloud.com
     */
    fun isICloudUrl(url: String?): Boolean {
        return url?.contains("icloud.com", ignoreCase = true) ?: false
    }
}
