package org.onekash.kashcal.domain.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [CalendarFeedUrl.normalize] on the source shapes Nextcloud and Radicale return for a
 * stored feed URL (one case per shape), plus idempotence.
 */
class CalendarFeedUrlTest {

    private fun n(raw: String?) = CalendarFeedUrl.normalize(raw)

    @Test
    fun `webcal becomes https`() {
        assertEquals("https://h.example.test/a.ics", n("webcal://h.example.test/a.ics"))
    }

    @Test
    fun `webcal in capitals becomes https with a lowercase host`() {
        assertEquals("https://h.example.test/a.ics", n("WEBCAL://H.Example.test/a.ics"))
    }

    @Test
    fun `webcals becomes https`() {
        assertEquals("https://h.example.test/a.ics", n("webcals://h.example.test/a.ics"))
    }

    @Test
    fun `https with a query keeps the query`() {
        assertEquals("https://h.example.test/a.ics?x=1&y=2", n("https://h.example.test/a.ics?x=1&y=2"))
    }

    @Test
    fun `a literal amp entity in the query is kept as the server sent it`() {
        assertEquals(
            "https://h.example.test/a.ics?x=1&amp;y=2",
            n("https://h.example.test/a.ics?x=1&amp;y=2"),
        )
    }

    @Test
    fun `user and password are dropped`() {
        assertEquals("https://h.example.test/a.ics", n("https://u:p@h.example.test/a.ics"))
    }

    @Test
    fun `a user without a password is dropped`() {
        assertEquals("https://h.example.test/a.ics", n("https://u@h.example.test/a.ics"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://h.example.test/a.ics", n("  https://h.example.test/a.ics  "))
    }

    @Test
    fun `a value the server resolved as a path is rejected`() {
        assertNull(n("/remote.php/dav/  https://h.example.test/a.ics  "))
    }

    @Test
    fun `a relative path is rejected`() {
        assertNull(n("/remote.php/dav/public-calendars/abc?export"))
    }

    @Test
    fun `a space in the path is encoded`() {
        assertEquals("https://h.example.test/a%20b.ics", n("https://h.example.test/a b.ics"))
    }

    @Test
    fun `a non-ASCII host becomes punycode, raw or percent-encoded`() {
        assertEquals("https://xn--feds-0ra.example.test/a.ics", n("https://füeds.example.test/a.ics"))
        assertEquals("https://xn--feds-0ra.example.test/a.ics", n("https://f%C3%BCeds.example.test/a.ics"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertNull(n("ftp://h.example.test/a.ics"))
        assertNull(n("file:///sdcard/a.ics"))
        assertNull(n("mailto:a@example.test"))
    }

    @Test
    fun `plain http on a LAN address is kept`() {
        assertEquals("http://192.168.1.5:5232/user/feed.ics", n("http://192.168.1.5:5232/user/feed.ics"))
    }

    @Test
    fun `https in capitals is accepted`() {
        assertEquals("https://h.example.test/a.ics", n("HTTPS://H.Example.test/a.ics"))
    }

    @Test
    fun `empty, blank, null and garbage are rejected`() {
        assertNull(n(null))
        assertNull(n(""))
        assertNull(n("   "))
        assertNull(n("not a url"))
        assertNull(n("https://"))
    }

    @Test
    fun `normalizing twice gives the same result`() {
        listOf(
            "webcal://h.example.test/a.ics",
            "WEBCAL://H.Example.test/a.ics",
            "https://u:p@h.example.test/a b.ics",
            "https://füeds.example.test/a.ics",
            "http://192.168.1.5:5232/user/feed.ics",
        ).forEach { raw ->
            val once = n(raw)
            assertEquals(raw, once, n(once))
        }
    }
}
