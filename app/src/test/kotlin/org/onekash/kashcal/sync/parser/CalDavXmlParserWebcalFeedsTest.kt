package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [CalDavXmlParser.extractWebcalFeeds] on replies captured from local Nextcloud and
 * Radicale (`caldav/webcal_feeds/`), on every committed calendar-home capture
 * (`caldav/resourcetype_proof/`), and on hand-written edge cases.
 *
 * The resourcetype_proof files are regenerated from live servers, so they are checked against a
 * rule (one feed per subscribed collection) rather than fixed counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavXmlParserWebcalFeedsTest {

    private lateinit var parser: CalDavXmlParser

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        parser = CalDavXmlParser()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun load(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }

    @Test
    fun `nextcloud capture lists its three feeds with stored sources, names and colors`() {
        val feeds = parser.extractWebcalFeeds(load("caldav/webcal_feeds/nextcloud.xml"))!!

        assertEquals(3, feeds.size)
        val byHref = feeds.associateBy { it.href }
        val de = byHref.getValue("/remote.php/dav/calendars/admin/kc68-de-holidays/")
        assertEquals("German Holidays", de.displayName)
        assertEquals("#E53935FF", de.color)
        assertEquals("webcal://holidays.example.test/media/caldata/autogen/GermanHolidays.ics", de.source)
        assertEquals(
            "webcals://holidays.example.test/media/caldata/autogen/UKHolidays.ics",
            byHref.getValue("/remote.php/dav/calendars/admin/kc68-uk-holidays/").source,
        )
        assertEquals(
            "https://holidays.example.test/media/caldata/autogen/USHolidays.ics",
            byHref.getValue("/remote.php/dav/calendars/admin/kc68-us-holidays/").source,
        )
    }

    @Test
    fun `radicale capture lists all seven feeds including the empty source and the duplicate URL`() {
        val feeds = parser.extractWebcalFeeds(load("caldav/webcal_feeds/radicale.xml"))!!

        assertEquals(7, feeds.size)
        val byHref = feeds.associateBy { it.href }
        assertNull(byHref.getValue("/testuser1/webcal-broken/").source)
        assertEquals("Broken Sub (no URL)", byHref.getValue("/testuser1/webcal-broken/").displayName)
        val german = "https://holidays.example.test/media/caldata/GermanHolidays.ics"
        assertEquals(german, byHref.getValue("/testuser1/webcal-holidays/").source)
        assertEquals(german, byHref.getValue("/testuser1/webcal-german-copy/").source)
        assertEquals(
            "webcal://p24-caldav.icloud.com/published/2/example-sports-feed.ics",
            byHref.getValue("/testuser1/webcal-sports/").source,
        )
        assertEquals(
            "webcals://holidays.example.test/media/caldata/autogen/UKHolidays.ics",
            byHref.getValue("/testuser1/kc68-uk-holidays/").source,
        )
    }

    @Test
    fun `captures never return a calendar collection`() {
        for (name in listOf("nextcloud", "radicale")) {
            val xml = load("caldav/webcal_feeds/$name.xml")
            val calendars = parser.extractCalendars(xml).map { it.href }.toSet()
            val feeds = parser.extractWebcalFeeds(xml)!!.map { it.href }.toSet()
            assertTrue("$name: a calendar was listed as a feed", calendars.intersect(feeds).isEmpty())
            assertTrue("$name: no calendars parsed", calendars.isNotEmpty())
        }
    }

    @Test
    fun `every resourcetype proof capture gives one feed per subscribed collection and no calendar`() {
        val servers = listOf(
            "baikal", "baikaldigest", "cyrus", "icloud", "mailbox", "nextcloud",
            "radicale", "sogo", "stalwart", "xandikos", "zoho",
        )
        val responseBlock = Regex("""<(?:\w+:)?response\b.*?</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        val propstat = Regex("""<(?:\w+:)?propstat\b.*?</(?:\w+:)?propstat>""", RegexOption.DOT_MATCHES_ALL)
        for (server in servers) {
            val xml = load("caldav/resourcetype_proof/$server.xml")
            val expected = responseBlock.findAll(xml).count { block ->
                propstat.findAll(block.value).any { ps ->
                    ps.value.contains("subscribed") &&
                        Regex("""HTTP/1\.[01] 2\d\d""").containsMatchIn(ps.value)
                }
            }
            val feeds = parser.extractWebcalFeeds(xml)
            assertNotNull("$server: proof capture unreadable", feeds)
            assertEquals("$server: feed count", expected, feeds!!.size)
            val calendars = parser.extractCalendars(xml).map { it.href }.toSet()
            assertTrue("$server: a calendar was listed as a feed", feeds.none { it.href in calendars })
        }
    }

    @Test
    fun `a subscribed resourcetype under a failing propstat is not a feed`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/a/</d:href>
              <d:propstat><d:prop><d:displayname>A</d:displayname></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response>
            """
        )
        assertEquals(emptyList<Any>(), parser.extractWebcalFeeds(xml))
    }

    @Test
    fun `properties split across propstats still form one feed`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/a/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>
                <cs:source><d:href>https://h.example.test/a.ics</d:href></cs:source></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:displayname/><ic:calendar-color/></d:prop>
                <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response>
            """
        )
        val feed = parser.extractWebcalFeeds(xml)!!.single()
        assertEquals("/home/a/", feed.href)
        assertEquals("https://h.example.test/a.ics", feed.source)
        assertNull(feed.displayName)
        assertNull(feed.color)
    }

    @Test
    fun `a collection that is both calendar and subscribed is not a feed`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/a/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/><cs:subscribed/></d:resourcetype>
                <cs:source><d:href>https://h.example.test/a.ics</d:href></cs:source></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            """
        )
        assertEquals(emptyList<Any>(), parser.extractWebcalFeeds(xml))
    }

    @Test
    fun `missing source and empty href both give a null source`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/none/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            <d:response><d:href>/home/empty/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>
                <cs:source><d:href/></cs:source></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            """
        )
        val feeds = parser.extractWebcalFeeds(xml)!!
        assertEquals(listOf("/home/none/", "/home/empty/"), feeds.map { it.href })
        assertTrue(feeds.all { it.source == null })
    }

    @Test
    fun `the first href in source is used and the response href is not taken for it`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/a/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>
                <cs:source><d:href>https://one.example.test/a.ics</d:href><d:href>https://two.example.test/b.ics</d:href></cs:source></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            """
        )
        val feed = parser.extractWebcalFeeds(xml)!!.single()
        assertEquals("/home/a/", feed.href)
        assertEquals("https://one.example.test/a.ics", feed.source)
    }

    @Test
    fun `the display name is entity-decoded but the source is not decoded a second time`() {
        val xml = multistatus(
            """
            <d:response><d:href>/home/a/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>
                <d:displayname>Tom &amp;amp; Jerry</d:displayname>
                <cs:source><d:href>https://h.example.test/a.ics?x=1&amp;amp;y=2</d:href></cs:source></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            """
        )
        val feed = parser.extractWebcalFeeds(xml)!!.single()
        assertEquals("Tom & Jerry", feed.displayName)
        assertEquals("https://h.example.test/a.ics?x=1&amp;y=2", feed.source)
    }

    @Test
    fun `an unreadable reply gives null, not an empty list`() {
        assertNull(parser.extractWebcalFeeds("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>"))
    }

    @Test
    fun `a parsed feed's toString leaves out the source, which can carry a password`() {
        val feed = org.onekash.kashcal.sync.quirks.CalDavQuirks.ParsedWebcalFeed(
            href = "/home/a/", displayName = "A", color = null,
            source = "https://user:secret@h.example.test/a.ics",
        )

        val text = feed.toString()

        assertTrue(text.contains("source=<set>"))
        assertTrue(!text.contains("secret") && !text.contains("h.example.test"))
    }

    private fun multistatus(responses: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
            xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
        $responses
        </d:multistatus>
    """.trimIndent()
}
