package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CalDavXmlParser.extractCalendarMetadata], the Depth:0-response
 * parser powering the calendar-metadata refresh on every PullStrategy.pull().
 *
 * Null semantics differ from [CalDavXmlParser.extractCalendars]: this helper
 * must return isReadOnly=null when the server omits the privilege-set element
 * so the refresh path preserves local state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavXmlParserMetadataTest {

    private lateinit var parser: CalDavXmlParser

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        parser = CalDavXmlParser()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Happy path ==========

    @Test
    fun `extracts all four fields when server returns full response`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"
                           xmlns:ic="http://apple.com/ns/ical/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Work</d:displayname>
                            <ic:calendar-color>#FF5733FF</ic:calendar-color>
                            <cs:getctag>abc123</cs:getctag>
                            <d:current-user-privilege-set>
                                <d:privilege><d:read/></d:privilege>
                                <d:privilege><d:write/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val probe = parser.extractCalendarMetadata(xml)
        assertEquals("abc123", probe?.ctag)
        assertEquals("Work", probe?.displayName)
        assertEquals("#FF5733FF", probe?.color)
        assertEquals(false, probe?.isReadOnly)
    }

    // ========== ctag handling ==========

    @Test
    fun `returns null when ctag is absent from response`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Work</d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertNull(
            "ctag is required; caller treats null as getCtag failure (Zoho fallback path)",
            parser.extractCalendarMetadata(xml)
        )
    }

    @Test
    fun `minimal response with only ctag yields probe with null other fields`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>only-ctag</cs:getctag>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val probe = parser.extractCalendarMetadata(xml)
        assertEquals("only-ctag", probe?.ctag)
        assertNull(probe?.displayName)
        assertNull(probe?.color)
        assertNull(
            "no privilege-set element → isReadOnly must be null so refresh preserves local",
            probe?.isReadOnly
        )
    }

    // ========== color ==========

    @Test
    fun `iCloud RRGGBBAA color is passed through verbatim`() {
        // Parser returns the raw string; ServerColorParser does the ARGB math.
        val xml = ctagPlusColor("#FF5733CC")
        assertEquals("#FF5733CC", parser.extractCalendarMetadata(xml)?.color)
    }

    @Test
    fun `empty color element yields null`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"
                           xmlns:ic="http://apple.com/ns/ical/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>abc</cs:getctag>
                            <ic:calendar-color/>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val probe = parser.extractCalendarMetadata(xml)
        assertEquals("abc", probe?.ctag)
        assertNull(probe?.color)
    }

    // ========== displayName ==========

    @Test
    fun `empty displayname yields null displayName`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>abc</cs:getctag>
                            <d:displayname></d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertNull(parser.extractCalendarMetadata(xml)?.displayName)
    }

    @Test
    fun `displayname with XML entities is decoded`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>abc</cs:getctag>
                            <d:displayname>Home &amp; Work</d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("Home & Work", parser.extractCalendarMetadata(xml)?.displayName)
    }

    // ========== isReadOnly (privilege-set) ==========

    @Test
    fun `privilege-set with write element yields isReadOnly false`() {
        val xml = ctagPlusPrivileges(
            """
                <d:privilege><d:read/></d:privilege>
                <d:privilege><d:write/></d:privilege>
            """.trimIndent()
        )
        assertEquals(false, parser.extractCalendarMetadata(xml)?.isReadOnly)
    }

    @Test
    fun `privilege-set with write-content element yields isReadOnly false`() {
        val xml = ctagPlusPrivileges(
            """
                <d:privilege><d:read/></d:privilege>
                <d:privilege><d:write-content/></d:privilege>
            """.trimIndent()
        )
        assertEquals(false, parser.extractCalendarMetadata(xml)?.isReadOnly)
    }

    @Test
    fun `privilege-set without write element yields isReadOnly true`() {
        val xml = ctagPlusPrivileges(
            "<d:privilege><d:read/></d:privilege>"
        )
        assertEquals(true, parser.extractCalendarMetadata(xml)?.isReadOnly)
    }

    @Test
    fun `absent privilege-set element yields isReadOnly null`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>abc</cs:getctag>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertNull(
            "Deliberate divergence from extractCalendars: refresh must preserve local on absent privilege-set",
            parser.extractCalendarMetadata(xml)?.isReadOnly
        )
    }

    // ========== resilience ==========

    @Test
    fun `malformed XML returns null without throwing`() {
        assertNull(parser.extractCalendarMetadata("<not valid xml>"))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(parser.extractCalendarMetadata(""))
        assertNull(parser.extractCalendarMetadata("   "))
    }

    // ========== helpers ==========

    private fun ctagPlusColor(color: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"
                       xmlns:ic="http://apple.com/ns/ical/">
            <d:response>
                <d:propstat>
                    <d:prop>
                        <cs:getctag>abc</cs:getctag>
                        <ic:calendar-color>$color</ic:calendar-color>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun ctagPlusPrivileges(privileges: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:cs=\"http://calendarserver.org/ns/\">" +
            "<d:response>" +
            "<d:propstat>" +
            "<d:prop>" +
            "<cs:getctag>abc</cs:getctag>" +
            "<d:current-user-privilege-set>$privileges</d:current-user-privilege-set>" +
            "</d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status>" +
            "</d:propstat>" +
            "</d:response>" +
            "</d:multistatus>"
}
