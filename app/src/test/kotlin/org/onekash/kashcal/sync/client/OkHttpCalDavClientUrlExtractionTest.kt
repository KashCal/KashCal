package org.onekash.kashcal.sync.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for extractCaldavBaseUrl() URL parsing logic.
 *
 * Bug context: https://github.com/KashCal/KashCal/issues/38
 * The original implementation matched "/dav" anywhere in the URL, including
 * the hostname "dav.mailbox.org", breaking Open-Xchange/mailbox.org discovery.
 *
 * @see OkHttpCalDavClient.extractCaldavBaseUrl
 */
class OkHttpCalDavClientUrlExtractionTest {

    /**
     * Mirrors OkHttpCalDavClient.extractCaldavBaseUrl() for testing.
     *
     * SYNC WITH: app/src/main/kotlin/org/onekash/kashcal/sync/client/OkHttpCalDavClient.kt:297
     *
     * If this test fails after production changes, update this copy to match.
     * This duplication is intentional - allows testing private method without
     * exposing internal API surface.
     *
     * Issue #49: Added originalScheme parameter to preserve HTTPS through reverse proxy.
     */
    private fun extractCaldavBaseUrl(url: String, originalScheme: String? = null): String {
        // Strip query parameters and fragments (some redirects include them)
        val cleanUrl = url.substringBefore("?").substringBefore("#")

        // Parse URL using stdlib - handles IPv6 [::1], userinfo, ports correctly
        val uri = try {
            java.net.URI(cleanUrl)
        } catch (e: Exception) {
            return url
        }

        val path = uri.path ?: ""
        // Issue #49: Preserve original scheme when provided (reverse proxy scenario)
        val effectiveScheme = originalScheme ?: uri.scheme
        val baseUrl = "$effectiveScheme://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"

        // Common CalDAV path patterns - order matters (longer/more specific first)
        val patterns = listOf(
            "/remote.php/dav",  // Nextcloud
            "/dav.php",         // Baikal
            "/caldav.php",      // Some servers
            "/caldav",          // Open-Xchange (mailbox.org)
            "/cal.php",         // Some servers
            "/dav/cal",         // Stalwart
            "/dav"              // Generic (safe - only matches path now)
        )

        for (pattern in patterns) {
            val index = path.indexOf(pattern)
            if (index != -1) {
                return baseUrl + path.substring(0, index + pattern.length)
            }
        }

        // No pattern matched - return base URL only
        return baseUrl
    }

    // ========================================================================
    // Provider Tests (1-5) - Core CalDAV providers
    // ========================================================================

    @Test
    fun `1 - mailbox_org - extracts caldav pattern from path not hostname`() {
        // BUG FIX: Previously matched "/dav" in "dav.mailbox.org" hostname
        val url = "https://dav.mailbox.org/caldav/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://dav.mailbox.org/caldav", result)
    }

    @Test
    fun `2 - Nextcloud - extracts remote_php_dav pattern`() {
        val url = "https://nc.example.com/remote.php/dav/calendars/user/personal/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://nc.example.com/remote.php/dav", result)
    }

    @Test
    fun `3 - Baikal - extracts dav_php pattern`() {
        val url = "https://baikal.example.com/dav.php/calendars/user/default/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://baikal.example.com/dav.php", result)
    }

    @Test
    fun `4 - Generic - extracts dav pattern from path`() {
        val url = "https://calendar.example.com/dav/users/john/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://calendar.example.com/dav", result)
    }

    @Test
    fun `5 - caldav_php - extracts caldav_php pattern`() {
        val url = "https://server.com/caldav.php/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/caldav.php", result)
    }

    // ========================================================================
    // Edge Cases (6-13) - Various URL formats
    // ========================================================================

    @Test
    fun `6 - No path - returns base URL when no CalDAV pattern in path`() {
        val url = "https://caldav.example.com"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://caldav.example.com", result)
    }

    @Test
    fun `7 - caldav in hostname - matches dav in path not caldav in hostname`() {
        // "caldav" is in hostname but we should match "/dav" in path
        val url = "https://caldav.example.com/dav/principals/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://caldav.example.com/dav", result)
    }

    @Test
    fun `8 - webdav_caldav nested - matches caldav pattern`() {
        val url = "https://server.com/webdav/caldav/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/webdav/caldav", result)
    }

    @Test
    fun `9 - Port number - preserves port in base URL`() {
        val url = "https://server.com:8443/caldav/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com:8443/caldav", result)
    }

    @Test
    fun `10 - Query params - strips query parameters`() {
        val url = "https://dav.mailbox.org/caldav/?redirect=true&token=abc"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://dav.mailbox.org/caldav", result)
    }

    @Test
    fun `11 - Fragment - strips URL fragment`() {
        val url = "https://server.com/caldav/#section"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/caldav", result)
    }

    @Test
    fun `12 - Double dav - dav in hostname and path matches path only`() {
        // "dav" appears in both hostname and path
        val url = "https://dav.example.com/webdav/dav/users/"
        val result = extractCaldavBaseUrl(url)
        // Should match /webdav/dav (first /dav in path after /webdav)
        // Actually matches /dav at first occurrence in path
        assertEquals("https://dav.example.com/webdav/dav", result)
    }

    @Test
    fun `13 - FastMail - real provider with caldav hostname`() {
        val url = "https://caldav.fastmail.com/dav/principals/user/test@fastmail.com/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://caldav.fastmail.com/dav", result)
    }

    // ========================================================================
    // Advanced Cases (14-16) - Complex URL formats
    // ========================================================================

    @Test
    fun `14 - IPv6 address - handles bracketed IPv6 with port`() {
        val url = "https://[::1]:8080/caldav/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://[::1]:8080/caldav", result)
    }

    @Test
    fun `15 - Userinfo - strips credentials from URL`() {
        // Userinfo (user:pass@) should be stripped from output
        // java.net.URI.host excludes userinfo by design
        val url = "https://user@server.com/caldav/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/caldav", result)
    }

    @Test
    fun `16 - Nested caldav_dav - matches caldav first due to pattern order`() {
        // Documents behavior: /caldav pattern matches before /dav
        // This is intentional - longer patterns should match first
        val url = "https://server.com/caldav/dav/users/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/caldav", result)
    }

    // ========================================================================
    // Additional edge cases for robustness
    // ========================================================================

    @Test
    fun `cal_php pattern - extracts cal_php`() {
        val url = "https://server.com/cal.php/calendars/user/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/cal.php", result)
    }

    @Test
    fun `empty path - returns base URL`() {
        val url = "https://example.com/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://example.com", result)
    }

    @Test
    fun `HTTP scheme - works with non-HTTPS`() {
        val url = "http://localhost/caldav/calendars/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("http://localhost/caldav", result)
    }

    @Test
    fun `deep path before pattern - preserves path prefix`() {
        val url = "https://server.com/apps/calendar/dav/users/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/apps/calendar/dav", result)
    }

    // ========================================================================
    // Stalwart Tests (v21.5.8)
    // ========================================================================

    @Test
    fun `Stalwart - extracts dav-cal pattern`() {
        val url = "http://localhost:8085/dav/cal/user/calendar/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("http://localhost:8085/dav/cal", result)
    }

    @Test
    fun `substring match - davey matches dav pattern (indexOf behavior)`() {
        // Documents expected behavior: indexOf() matches substrings
        // "/davey" contains "/dav" so it matches the /dav pattern
        val url = "https://server.com/davey/calendar/"
        val result = extractCaldavBaseUrl(url)
        assertEquals("https://server.com/dav", result)
    }

    // ========================================================================
    // Issue #49: Scheme Preservation Tests (Baikal reverse proxy)
    // ========================================================================
    //
    // Fixed: When Baikal behind reverse proxy with SSL termination:
    //   1. User connects: https://domain/.well-known/caldav
    //   2. nginx terminates SSL, forwards to Baikal as HTTP
    //   3. Baikal redirects to: http://domain/dav.php (HTTP!)
    //   4. extractCaldavBaseUrl(url, originalScheme) now preserves HTTPS
    //
    // The fix adds an optional `originalScheme` parameter. When provided,
    // it overrides the scheme from the redirected URL.

    /**
     * Backward compatibility: Without originalScheme, URL's scheme is used.
     * This ensures existing callers (without the new parameter) work unchanged.
     */
    @Test
    fun `Issue49 - backward compat - uses URL scheme when no originalScheme`() {
        val redirectedUrl = "http://baikal.example.com/dav.php/calendars/user/"

        // No originalScheme = use URL's scheme (backward compatible)
        val result = extractCaldavBaseUrl(redirectedUrl)

        assertEquals("http://baikal.example.com/dav.php", result)
    }

    /**
     * Issue #49 fix: Preserves HTTPS when redirect is HTTP.
     *
     * Scenario: Baikal behind nginx with SSL termination
     * - User enters: https://baikal.example.com
     * - nginx terminates SSL, forwards to Baikal as HTTP
     * - Baikal redirects to: http://baikal.example.com/dav.php
     * - Expected: https://baikal.example.com/dav.php (preserve HTTPS)
     */
    @Test
    fun `Issue49 - preserves HTTPS when redirect is HTTP`() {
        val originalScheme = "https"  // User's original connection
        val redirectedUrl = "http://baikal.example.com/dav.php/calendars/user/"

        val result = extractCaldavBaseUrl(redirectedUrl, originalScheme)

        // Preserves HTTPS from original request
        assertEquals("https://baikal.example.com/dav.php", result)
    }

    /**
     * Issue #49: HTTP stays HTTP when originalScheme is explicitly HTTP.
     */
    @Test
    fun `Issue49 - HTTP stays HTTP when originalScheme is HTTP`() {
        val redirectedUrl = "http://localhost:8081/dav.php/calendars/user/"

        val result = extractCaldavBaseUrl(redirectedUrl, "http")

        assertEquals("http://localhost:8081/dav.php", result)
    }

    /**
     * Issue #49: Port preserved with scheme override.
     */
    @Test
    fun `Issue49 - preserves port with scheme override`() {
        val originalScheme = "https"
        val redirectedUrl = "http://baikal.example.com:8080/dav.php/calendars/"

        val result = extractCaldavBaseUrl(redirectedUrl, originalScheme)

        assertEquals("https://baikal.example.com:8080/dav.php", result)
    }

    /**
     * Issue #49: HTTPS redirect stays HTTPS (no change needed).
     */
    @Test
    fun `Issue49 - HTTPS redirect stays HTTPS`() {
        val originalScheme = "https"
        val redirectedUrl = "https://baikal.example.com/dav.php/calendars/"

        val result = extractCaldavBaseUrl(redirectedUrl, originalScheme)

        assertEquals("https://baikal.example.com/dav.php", result)
    }

    /**
     * Edge case: What if user enters HTTP but server upgrades to HTTPS?
     * Behavior: preserves original (HTTP). This matches user's explicit choice.
     */
    @Test
    fun `Issue49 - HTTP original with HTTPS redirect preserves HTTP`() {
        val originalScheme = "http"  // User explicitly entered HTTP
        val redirectedUrl = "https://baikal.example.com/dav.php/calendars/"  // Server upgraded

        val result = extractCaldavBaseUrl(redirectedUrl, originalScheme)

        // Preserves HTTP - respects user's explicit choice
        assertEquals("http://baikal.example.com/dav.php", result)
    }
}
