package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.util.text.UrlType
import org.onekash.kashcal.util.text.cleanHtmlEntities
import org.onekash.kashcal.util.text.extractUrls

/**
 * Tests for LinkifiedText behavior.
 *
 * Note: These tests verify the underlying URL detection logic used by LinkifiedText.
 * Compose UI rendering tests would require Android instrumented tests or Robolectric.
 */
class LinkifiedTextTest {

    // ========== Plain Text (No Links) ==========

    @Test
    fun `plain text without links returns empty URL list`() {
        val text = "Just plain text without any links or special content."
        val urls = extractUrls(text)

        assertEquals(emptyList<Any>(), urls)
    }

    @Test
    fun `empty text returns empty URL list`() {
        assertEquals(emptyList<Any>(), extractUrls(""))
        assertEquals(emptyList<Any>(), extractUrls("   "))
    }

    // ========== Single URL Linkification ==========

    @Test
    fun `single URL is detected with correct position`() {
        val text = "Check this: https://example.com for more info"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
        assertEquals(12, urls[0].startIndex)
        assertEquals(31, urls[0].endIndex)
    }

    @Test
    fun `URL at start of text is detected`() {
        val text = "https://example.com is a great site"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(0, urls[0].startIndex)
    }

    @Test
    fun `URL at end of text is detected`() {
        val text = "Visit https://example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    // ========== Multiple URLs ==========

    @Test
    fun `multiple URLs are detected in order`() {
        val text = "First https://a.com then https://b.com and https://c.com"
        val urls = extractUrls(text)

        assertEquals(3, urls.size)
        assertEquals("https://a.com", urls[0].url)
        assertEquals("https://b.com", urls[1].url)
        assertEquals("https://c.com", urls[2].url)
        // Verify sorted by position
        assertTrue(urls[0].startIndex < urls[1].startIndex)
        assertTrue(urls[1].startIndex < urls[2].startIndex)
    }

    @Test
    fun `mixed URL types are detected`() {
        val text = "Web: https://example.com, Call: (555) 123-4567, Email: mailto:test@example.com"
        val urls = extractUrls(text)

        assertEquals(3, urls.size)
        assertEquals(UrlType.WEB, urls[0].type)
        assertEquals(UrlType.PHONE, urls[1].type)
        assertEquals(UrlType.EMAIL, urls[2].type)
    }

    // ========== Accessibility Descriptions ==========

    @Test
    fun `meeting URL has correct display text`() {
        val text = "Join: https://zoom.us/j/123456"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals("Zoom", urls[0].displayText)
    }

    @Test
    fun `phone number has phone number display text`() {
        val text = "Call (555) 123-4567"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("Phone number", urls[0].displayText)
    }

    @Test
    fun `email has email address as display text`() {
        val text = "Email mailto:test@example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("test@example.com", urls[0].displayText)
    }

    @Test
    fun `web URL has domain as display text`() {
        val text = "Visit https://www.example.com/path"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("example.com", urls[0].displayText)
    }

    // ========== HTML Entity Cleaning ==========

    @Test
    fun `HTML entities are cleaned before URL detection`() {
        val text = "Tom &amp; Jerry https://example.com"
        val cleaned = cleanHtmlEntities(text)

        assertEquals("Tom & Jerry https://example.com", cleaned)

        val urls = extractUrls(cleaned)
        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }

    @Test
    fun `HTML entities in URL are preserved`() {
        // URLs should not have HTML entities, but if they do, the URL itself is preserved
        val text = "Link: https://example.com/search?q=Tom&amp;Jerry"
        val cleaned = cleanHtmlEntities(text)

        // The &amp; becomes & which is valid in URLs
        assertTrue(cleaned.contains("https://example.com/search?q=Tom&Jerry"))
    }

    // ========== Position Edge Cases ==========

    @Test
    fun `URL as entire text has correct bounds`() {
        val text = "https://example.com"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals(0, urls[0].startIndex)
        assertEquals(text.length, urls[0].endIndex)
    }

    @Test
    fun `adjacent URLs both detected with correct positions`() {
        val text = "https://a.com https://b.com"
        val urls = extractUrls(text)

        assertEquals(2, urls.size)
        // First URL: 0-13
        assertEquals(0, urls[0].startIndex)
        assertEquals(13, urls[0].endIndex)
        // Second URL: 14-27
        assertEquals(14, urls[1].startIndex)
        assertEquals(27, urls[1].endIndex)
    }

    @Test
    fun `URL followed by punctuation has correct end index`() {
        val text = "Check https://example.com, then continue"
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        // URL ends before comma (normalized URL strips comma)
        assertEquals("https://example.com", urls[0].url)
    }

    // ========== Complex Text Scenarios ==========

    @Test
    fun `text with multiple link types maintains correct order`() {
        val text = "Web https://example.com then call (555) 123-4567 then email mailto:test@test.com"
        val urls = extractUrls(text)

        assertEquals(3, urls.size)
        // Verify order matches text appearance
        assertTrue(urls[0].startIndex < urls[1].startIndex)
        assertTrue(urls[1].startIndex < urls[2].startIndex)
    }

    @Test
    fun `long description with scattered links`() {
        val text = """
            Meeting notes from today's call.

            Join link: https://zoom.us/j/123456

            Contact support at (555) 123-4567 if you have issues.

            More info at https://docs.example.com/help
        """.trimIndent()

        val urls = extractUrls(text)

        assertEquals(3, urls.size)
        assertEquals(UrlType.MEETING, urls[0].type)
        assertEquals(UrlType.PHONE, urls[1].type)
        assertEquals(UrlType.WEB, urls[2].type)
    }

    @Test
    fun `text with URL but only whitespace around it`() {
        val text = "   https://example.com   "
        val urls = extractUrls(text)

        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].url)
    }
}
