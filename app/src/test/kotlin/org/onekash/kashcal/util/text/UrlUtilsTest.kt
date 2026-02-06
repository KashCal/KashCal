package org.onekash.kashcal.util.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for URL detection and handling utilities.
 */
class UrlUtilsTest {

    // ========== Basic URL Detection ==========

    @Test
    fun `extractUrls - detects http URL`() {
        val text = "Join at http://example.com/meeting"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("http://example.com/meeting", urls[0].url)
        assertEquals(UrlType.WEB, urls[0].type)
    }

    @Test
    fun `extractUrls - detects https URL`() {
        val text = "Link: https://example.com/path"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com/path", urls[0].url)
        assertEquals(UrlType.WEB, urls[0].type)
    }

    @Test
    fun `extractUrls - case insensitive detection`() {
        val text = "Visit HTTPS://EXAMPLE.COM/PATH"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("HTTPS://EXAMPLE.COM/PATH", urls[0].url)
    }

    @Test
    fun `extractUrls - detects multiple URLs`() {
        val text = "First https://a.com then https://b.com"
        val urls = extractUrls(text)

        assertEquals(2, urls.size)
        assertEquals("https://a.com", urls[0].url)
        assertEquals("https://b.com", urls[1].url)
    }

    // ========== Meeting URL Detection ==========

    @Test
    fun `extractUrls - detects Zoom meeting`() {
        val text = "Join: https://zoom.us/j/123456789"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Zoom", urls[0].displayText)
    }

    @Test
    fun `extractUrls - detects Teams meeting`() {
        val text = "Meeting: https://teams.microsoft.com/l/meetup-join/abc"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Microsoft Teams", urls[0].displayText)
    }

    @Test
    fun `extractUrls - detects Google Meet`() {
        val text = "Join: https://meet.google.com/abc-defg-hij"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Google Meet", urls[0].displayText)
    }

    @Test
    fun `extractUrls - detects Webex meeting`() {
        val text = "Webex: https://company.webex.com/meet/john"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Webex", urls[0].displayText)
    }

    @Test
    fun `isMeetingUrl - returns true for meeting platforms`() {
        assertTrue(isMeetingUrl("https://zoom.us/j/123"))
        assertTrue(isMeetingUrl("https://teams.microsoft.com/l/meetup"))
        assertTrue(isMeetingUrl("https://meet.google.com/abc"))
        assertTrue(isMeetingUrl("https://company.webex.com/meet"))
        assertTrue(isMeetingUrl("https://gotomeeting.com/join"))
        assertTrue(isMeetingUrl("https://whereby.com/room"))
    }

    @Test
    fun `isMeetingUrl - returns false for non-meeting URLs`() {
        assertFalse(isMeetingUrl("https://google.com"))
        assertFalse(isMeetingUrl("https://example.com/zoom"))
        assertFalse(isMeetingUrl("https://fake-zoom.us/meeting"))
    }

    // ========== Phone Pattern Detection ==========

    @Test
    fun `extractUrls - detects tel URI`() {
        val text = "Call tel:+1-555-123-4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:+1-555-123-4567", urls[0].url)
        assertEquals(UrlType.PHONE, urls[0].type)
        assertEquals("Phone number", urls[0].displayText)
    }

    @Test
    fun `extractUrls - detects parentheses phone format`() {
        val text = "Call (555) 123-4567 for help"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:5551234567", urls[0].url)
        assertEquals(UrlType.PHONE, urls[0].type)
    }

    @Test
    fun `extractUrls - detects dash phone format`() {
        val text = "Number: 555-123-4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:5551234567", urls[0].url)
        assertEquals(UrlType.PHONE, urls[0].type)
    }

    @Test
    fun `extractUrls - detects dot phone format`() {
        val text = "Call 555.123.4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:5551234567", urls[0].url)
    }

    @Test
    fun `extractUrls - detects international phone with country code`() {
        val text = "International: +1-555-123-4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:+15551234567", urls[0].url)
    }

    @Test
    fun `extractUrls - detects international with parentheses`() {
        val text = "Call +1 (555) 123-4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("tel:+15551234567", urls[0].url)
    }

    // ========== Email Detection ==========

    @Test
    fun `extractUrls - detects mailto URI`() {
        val text = "Email: mailto:test@example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("mailto:test@example.com", urls[0].url)
        assertEquals(UrlType.EMAIL, urls[0].type)
        assertEquals("test@example.com", urls[0].displayText)
    }

    // ========== Edge Cases ==========

    @Test
    fun `extractUrls - strips trailing period`() {
        val text = "Visit https://example.com."
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `extractUrls - strips trailing comma`() {
        val text = "Links: https://a.com, https://b.com"
        val urls = extractUrls(text)

        assertEquals(2, urls.size)
        assertEquals("https://a.com", urls[0].url)
        assertEquals("https://b.com", urls[1].url)
    }

    @Test
    fun `extractUrls - handles URL in parentheses`() {
        val text = "Check out (https://example.com) for more"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `extractUrls - preserves balanced parentheses in URL`() {
        val text = "Wiki: https://en.wikipedia.org/wiki/Example_(disambiguation)"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertTrue(urls[0].url.endsWith("(disambiguation)"))
    }

    @Test
    fun `extractUrls - respects limit parameter`() {
        val text = buildString {
            repeat(100) { append("https://example$it.com ") }
        }
        val urls = extractUrls(text, limit = 10)

        assertEquals(10, urls.size)
    }

    @Test
    fun `extractUrls - returns empty for blank text`() {
        assertEquals(emptyList<DetectedUrl>(), extractUrls(""))
        assertEquals(emptyList<DetectedUrl>(), extractUrls("   "))
    }

    @Test
    fun `extractUrls - returns empty for text without URLs`() {
        val text = "Just some regular text without any links"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    // ========== No False Positives ==========

    @Test
    fun `extractUrls - no false positive for decimal numbers`() {
        val text = "The value is 3.14159"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for Dr abbreviation`() {
        val text = "Meeting with Dr. Smith at the office"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for partial numbers`() {
        // 10-digit numbers without separators should NOT be detected
        val text = "Reference number: 5551234567"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    // ========== containsUrl ==========

    @Test
    fun `containsUrl - returns true for URL`() {
        assertTrue(containsUrl("Check https://example.com"))
        assertTrue(containsUrl("Call tel:+15551234567"))
        assertTrue(containsUrl("Email mailto:test@example.com"))
        assertTrue(containsUrl("Number: (555) 123-4567"))
    }

    @Test
    fun `containsUrl - returns false for plain text`() {
        assertFalse(containsUrl("Just plain text"))
        assertFalse(containsUrl("No links here"))
        assertFalse(containsUrl(""))
    }

    // ========== normalizeUrl ==========

    @Test
    fun `normalizeUrl - adds https protocol`() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
    }

    @Test
    fun `normalizeUrl - preserves existing protocol`() {
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
    }

    @Test
    fun `normalizeUrl - strips trailing punctuation`() {
        assertEquals("https://example.com", normalizeUrl("https://example.com."))
        assertEquals("https://example.com", normalizeUrl("https://example.com,"))
        assertEquals("https://example.com", normalizeUrl("https://example.com;"))
    }

    // ========== isValidUrl ==========

    @Test
    fun `isValidUrl - valid URLs return true`() {
        assertTrue(isValidUrl("https://example.com"))
        assertTrue(isValidUrl("http://example.com/path"))
        assertTrue(isValidUrl("tel:+15551234567"))
        assertTrue(isValidUrl("mailto:test@example.com"))
    }

    @Test
    fun `isValidUrl - invalid URLs return false`() {
        assertFalse(isValidUrl(""))
        assertFalse(isValidUrl("not a url"))
        assertFalse(isValidUrl("https://"))
        assertFalse(isValidUrl("ftp://example.com"))
    }

    // ========== shouldOpenExternally ==========

    @Test
    fun `shouldOpenExternally - allows http https tel mailto`() {
        assertTrue(shouldOpenExternally("https://example.com"))
        assertTrue(shouldOpenExternally("http://example.com"))
        assertTrue(shouldOpenExternally("tel:+15551234567"))
        assertTrue(shouldOpenExternally("mailto:test@example.com"))
    }

    @Test
    fun `shouldOpenExternally - blocks other schemes`() {
        assertFalse(shouldOpenExternally("kashcal://event/123"))
        assertFalse(shouldOpenExternally("file:///path/to/file"))
        assertFalse(shouldOpenExternally("javascript:alert('xss')"))
        assertFalse(shouldOpenExternally("intent://example"))
    }

    // ========== formatPhoneUri ==========

    @Test
    fun `formatPhoneUri - extracts digits`() {
        assertEquals("tel:5551234567", formatPhoneUri("(555) 123-4567"))
        assertEquals("tel:5551234567", formatPhoneUri("555-123-4567"))
        assertEquals("tel:+15551234567", formatPhoneUri("+1-555-123-4567"))
    }

    // ========== HTML Entity Handling ==========

    @Test
    fun `cleanHtmlEntities - decodes common entities`() {
        assertEquals("Tom & Jerry", cleanHtmlEntities("Tom &amp; Jerry"))
        assertEquals("<script>", cleanHtmlEntities("&lt;script&gt;"))
        assertEquals("Hello World", cleanHtmlEntities("Hello&nbsp;World"))
        assertEquals("\"quoted\"", cleanHtmlEntities("&quot;quoted&quot;"))
        assertEquals("it's", cleanHtmlEntities("it&#39;s"))
    }

    @Test
    fun `cleanHtmlEntities - handles numeric entities`() {
        assertEquals("A", cleanHtmlEntities("&#65;"))
        assertEquals("Z", cleanHtmlEntities("&#90;"))
    }

    @Test
    fun `cleanHtmlEntities - case insensitive`() {
        assertEquals("&", cleanHtmlEntities("&AMP;"))
        assertEquals("<", cleanHtmlEntities("&LT;"))
    }

    @Test
    fun `cleanHtmlEntities - preserves normal text`() {
        val text = "Normal text without entities"
        assertEquals(text, cleanHtmlEntities(text))
    }

    // ========== Reminder Duration Formatting ==========

    @Test
    fun `formatDuration - formats minutes`() {
        assertEquals("15 min before", formatDuration("-PT15M"))
        assertEquals("30 min before", formatDuration("-PT30M"))
        assertEquals("1 min before", formatDuration("-PT1M"))
    }

    @Test
    fun `formatDuration - formats hours`() {
        assertEquals("1 hour before", formatDuration("-PT1H"))
        assertEquals("2 hours before", formatDuration("-PT2H"))
    }

    @Test
    fun `formatDuration - formats days`() {
        assertEquals("1 day before", formatDuration("-P1D"))
        assertEquals("2 days before", formatDuration("-P2D"))
        assertEquals("7 days before", formatDuration("-P7D"))
    }

    @Test
    fun `formatDuration - formats combined durations`() {
        assertEquals("1 hour 30 min before", formatDuration("-PT1H30M"))
        assertEquals("1 day 2 hours before", formatDuration("-P1DT2H"))
    }

    @Test
    fun `formatDuration - handles positive duration (after)`() {
        assertEquals("30 min after", formatDuration("PT30M"))
        assertEquals("1 hour after", formatDuration("PT1H"))
    }

    @Test
    fun `formatDuration - handles zero duration`() {
        assertEquals("At event time", formatDuration("PT0M"))
        assertEquals("At event time", formatDuration("P0D"))
    }

    @Test
    fun `formatDuration - returns null for invalid duration`() {
        assertNull(formatDuration(""))
        assertNull(formatDuration("invalid"))
        assertNull(formatDuration("15 minutes"))
    }

    @Test
    fun `formatRemindersForDisplay - formats list`() {
        val reminders = listOf("-PT15M", "-P1D")
        assertEquals("15 min before, 1 day before", formatRemindersForDisplay(reminders))
    }

    @Test
    fun `formatRemindersForDisplay - returns null for empty`() {
        assertNull(formatRemindersForDisplay(null))
        assertNull(formatRemindersForDisplay(emptyList()))
    }

    @Test
    fun `formatRemindersForDisplay - skips invalid entries`() {
        val reminders = listOf("-PT15M", "invalid", "-PT1H")
        assertEquals("15 min before, 1 hour before", formatRemindersForDisplay(reminders))
    }

    // ========== URL Without Protocol ==========

    @Test
    fun `extractUrls - detects URL without protocol for meeting domains`() {
        val text = "Join: zoom.us/j/123456789"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://zoom.us/j/123456789", urls[0].url)
        assertEquals(UrlType.MEETING, urls[0].type)
    }

    @Test
    fun `extractUrls - detects Teams URL without protocol`() {
        val text = "Meeting at teams.microsoft.com/l/meetup-join/abc"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
    }

    @Test
    fun `extractUrls - detects Google Meet URL without protocol`() {
        val text = "Join meet.google.com/abc-defg-hij"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
    }

    // ========== Query Parameters and Fragments ==========

    @Test
    fun `extractUrls - preserves query parameters`() {
        val text = "Link: https://example.com/search?q=test&page=1"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertTrue(urls[0].url.contains("?q=test&page=1"))
    }

    @Test
    fun `extractUrls - preserves fragment identifier`() {
        val text = "Docs: https://example.com/docs#section-2"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertTrue(urls[0].url.endsWith("#section-2"))
    }

    @Test
    fun `extractUrls - preserves complex query with fragment`() {
        val text = "Full URL: https://example.com/path?foo=bar&baz=qux#anchor"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertTrue(urls[0].url.contains("?foo=bar&baz=qux"))
        assertTrue(urls[0].url.endsWith("#anchor"))
    }

    // ========== Additional Trailing Punctuation ==========

    @Test
    fun `extractUrls - strips trailing exclamation`() {
        val text = "Check this out https://example.com!"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `extractUrls - strips trailing question mark`() {
        val text = "Is this the link https://example.com?"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `extractUrls - strips multiple trailing punctuation`() {
        val text = "See https://example.com..."
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    // ========== Overlap Prevention ==========

    @Test
    fun `extractUrls - tel URI does not double-detect as phone pattern`() {
        val text = "Call tel:555-123-4567 for support"
        val urls = extractUrls(text)

        // Should only detect ONE phone, not two (tel: + pattern)
        assertEquals(1, urls.size)
        assertEquals("tel:555-123-4567", urls[0].url)
    }

    @Test
    fun `extractUrls - URL with protocol takes precedence over no-protocol pattern`() {
        val text = "Join https://zoom.us/j/123 now"
        val urls = extractUrls(text)

        // Should not double-detect as both https:// and no-protocol
        assertEquals(1, urls.size)
    }

    // ========== Additional False Positive Prevention ==========

    @Test
    fun `extractUrls - no false positive for version numbers`() {
        val text = "Updated to version v1.2.3"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for semantic version`() {
        val text = "Release 2.0.0-beta.1"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for time format with colon`() {
        val text = "Meeting at 10:30 AM"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for time format with dot`() {
        val text = "Starts at 10.30 in the morning"
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    @Test
    fun `extractUrls - no false positive for IP-like numbers`() {
        val text = "Server at 192.168.1.1"
        // IP addresses without protocol are not detected (intentional)
        assertEquals(emptyList<DetectedUrl>(), extractUrls(text))
    }

    // ========== Subdomain Handling ==========

    @Test
    fun `extractUrls - handles Zoom subdomain`() {
        val text = "Join: https://us02web.zoom.us/j/123456"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Zoom", urls[0].displayText)
    }

    @Test
    fun `extractUrls - handles Webex subdomain`() {
        val text = "Meeting: https://company.webex.com/meet/room"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Webex", urls[0].displayText)
    }

    @Test
    fun `isMeetingUrl - GoToMeeting with subdomain`() {
        assertTrue(isMeetingUrl("https://global.gotomeeting.com/join/123"))
    }

    @Test
    fun `isMeetingUrl - Jitsi Meet detection`() {
        assertTrue(isMeetingUrl("https://meet.jit.si/MyRoom"))
    }

    @Test
    fun `isMeetingUrl - Whereby detection`() {
        assertTrue(isMeetingUrl("https://whereby.com/my-room"))
    }

    // ========== URL Position Edge Cases ==========

    @Test
    fun `extractUrls - URL as entire text`() {
        val text = "https://example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(0, urls[0].startIndex)
        assertEquals(text.length, urls[0].endIndex)
    }

    @Test
    fun `extractUrls - adjacent URLs separated by space`() {
        val text = "https://a.com https://b.com"
        val urls = extractUrls(text)

        assertEquals(2, urls.size)
        assertEquals("https://a.com", urls[0].url)
        assertEquals("https://b.com", urls[1].url)
    }

    @Test
    fun `extractUrls - URL followed by newline`() {
        val text = "Check https://example.com\nMore text here"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `extractUrls - URL at end with no trailing space`() {
        val text = "Visit https://example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(text.length, urls[0].endIndex)
    }

    // ========== Performance ==========

    @Test
    fun `extractUrls - handles long text efficiently`() {
        val text = buildString {
            repeat(100) { append("Some text with https://example$it.com and more content. ") }
        }

        val startTime = System.currentTimeMillis()
        val urls = extractUrls(text, limit = 50)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(50, urls.size)
        assertTrue("Should complete in < 100ms, took ${duration}ms", duration < 100)
    }
}
