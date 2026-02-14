package org.onekash.kashcal.sync.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Standalone test for the proposed extractCalendarHomeUrls() pattern.
 *
 * Validates that the new multi-URL parsing logic:
 * 1. Returns all <href> values from all existing server fixtures (backward compat)
 * 2. Returns multiple <href> values for multi-home-set servers (SOGo/AEGEE)
 * 3. Handles edge cases (empty XML, malformed, empty href)
 *
 * This test does NOT modify production code — the parser logic is inlined here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ExtractCalendarHomeUrlsTest {

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    // ===== Proposed new method (inlined for testing without touching production code) =====

    private fun extractCalendarHomeUrls(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var inHomeSet = false
            val urls = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = true
                        } else if (inHomeSet && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val url = parser.text.trim()
                                if (url.isNotEmpty()) {
                                    urls.add(url)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = false
                        }
                    }
                }
                parser.next()
            }
            urls
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Backward compat wrapper
    private fun extractCalendarHomeUrl(xml: String): String? =
        extractCalendarHomeUrls(xml).firstOrNull()

    private fun loadResource(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")

    // ==================== Backward Compatibility: Existing Single-Home-Set Servers ====================

    @Test
    fun `iCloud - single home set - returns list of 1`() {
        val xml = loadResource("caldav/icloud/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("https://p180-caldav.icloud.com:443/123456789/calendars/", urls[0])
    }

    @Test
    fun `iCloud - backward compat - extractCalendarHomeUrl returns same as before`() {
        val xml = loadResource("caldav/icloud/02_calendar_home_set.xml")
        assertEquals(
            "https://p180-caldav.icloud.com:443/123456789/calendars/",
            extractCalendarHomeUrl(xml)
        )
    }

    @Test
    fun `Nextcloud - single home set - returns list of 1`() {
        val xml = loadResource("caldav/nextcloud/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("/remote.php/dav/calendars/testuser/", urls[0])
    }

    @Test
    fun `Nextcloud - backward compat - extractCalendarHomeUrl returns same as before`() {
        val xml = loadResource("caldav/nextcloud/02_calendar_home_set.xml")
        assertEquals(
            "/remote.php/dav/calendars/testuser/",
            extractCalendarHomeUrl(xml)
        )
    }

    @Test
    fun `Zoho - single home set non-prefixed namespace - returns list of 1`() {
        val xml = loadResource("caldav/zoho/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/", urls[0])
    }

    @Test
    fun `Zoho - backward compat - extractCalendarHomeUrl returns same as before`() {
        val xml = loadResource("caldav/zoho/02_calendar_home_set.xml")
        assertEquals(
            "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/",
            extractCalendarHomeUrl(xml)
        )
    }

    @Test
    fun `Stalwart - single home set uppercase prefix - returns list of 1`() {
        val xml = loadResource("caldav/stalwart/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("/dav/cal/admin/", urls[0])
    }

    @Test
    fun `Stalwart - backward compat - extractCalendarHomeUrl returns same as before`() {
        val xml = loadResource("caldav/stalwart/02_calendar_home_set.xml")
        assertEquals("/dav/cal/admin/", extractCalendarHomeUrl(xml))
    }

    @Test
    fun `Open-Xchange - single home set CAL prefix - returns list of 1`() {
        val xml = loadResource("caldav/openxchange/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("/caldav/testuser@mailbox.org/", urls[0])
    }

    @Test
    fun `Open-Xchange - backward compat - extractCalendarHomeUrl returns same as before`() {
        val xml = loadResource("caldav/openxchange/02_calendar_home_set.xml")
        assertEquals("/caldav/testuser@mailbox.org/", extractCalendarHomeUrl(xml))
    }

    // ==================== New: Multi-Home-Set Servers ====================

    @Test
    fun `SOGo - three home sets in single calendar-home-set element - returns all 3`() {
        // SOGo returns multiple <href> inside a single <calendar-home-set>
        // This is the pattern reported by the cal.aegee.org user (Issue #70)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/SOGo/dav/aaa/</D:href>
                    <D:propstat>
                        <D:prop>
                            <C:calendar-home-set>
                                <D:href>/SOGo/dav/aaa/Calendar/</D:href>
                                <D:href>/SOGo/dav/shared-calendars/group1/</D:href>
                                <D:href>/SOGo/dav/shared-calendars/group2/</D:href>
                            </C:calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(3, urls.size)
        assertEquals("/SOGo/dav/aaa/Calendar/", urls[0])
        assertEquals("/SOGo/dav/shared-calendars/group1/", urls[1])
        assertEquals("/SOGo/dav/shared-calendars/group2/", urls[2])
    }

    @Test
    fun `SOGo - backward compat returns first home set only`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/SOGo/dav/aaa/</D:href>
                    <D:propstat>
                        <D:prop>
                            <C:calendar-home-set>
                                <D:href>/SOGo/dav/aaa/Calendar/</D:href>
                                <D:href>/SOGo/dav/shared-calendars/group1/</D:href>
                                <D:href>/SOGo/dav/shared-calendars/group2/</D:href>
                            </C:calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        // Old behavior: only first URL
        assertEquals("/SOGo/dav/aaa/Calendar/", extractCalendarHomeUrl(xml))
    }

    @Test
    fun `Cyrus IMAP - two home sets - returns both`() {
        // Cyrus IMAP can return personal + shared calendar home sets
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/dav/principals/user/admin@example.com/</d:href>
                    <d:propstat>
                        <d:prop>
                            <cal:calendar-home-set>
                                <d:href>/dav/calendars/user/admin@example.com/</d:href>
                                <d:href>/dav/calendars/domain/example.com/</d:href>
                            </cal:calendar-home-set>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(2, urls.size)
        assertEquals("/dav/calendars/user/admin@example.com/", urls[0])
        assertEquals("/dav/calendars/domain/example.com/", urls[1])
    }

    @Test
    fun `AEGEE SOGo - realistic response with non-prefixed CalDAV namespace`() {
        // cal.aegee.org uses SOGo which may use non-prefixed CalDAV namespace
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/SOGo/dav/aaa/</D:href>
                    <D:propstat>
                        <D:prop>
                            <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <D:href>/SOGo/dav/aaa/Calendar/</D:href>
                                <D:href>/SOGo/dav/resources/Room-A/Calendar/</D:href>
                                <D:href>/SOGo/dav/resources/Room-B/Calendar/</D:href>
                            </calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(3, urls.size)
        assertEquals("/SOGo/dav/aaa/Calendar/", urls[0])
        assertEquals("/SOGo/dav/resources/Room-A/Calendar/", urls[1])
        assertEquals("/SOGo/dav/resources/Room-B/Calendar/", urls[2])
    }

    // ==================== Edge Cases ====================

    @Test
    fun `empty XML returns empty list`() {
        assertEquals(emptyList<String>(), extractCalendarHomeUrls(""))
    }

    @Test
    fun `blank XML returns empty list`() {
        assertEquals(emptyList<String>(), extractCalendarHomeUrls("   "))
    }

    @Test
    fun `malformed XML without calendar-home-set returns empty list`() {
        assertEquals(
            emptyList<String>(),
            extractCalendarHomeUrls("<response><href>/test</href></response>")
        )
    }

    @Test
    fun `empty href inside calendar-home-set is filtered out`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:propstat>
                        <D:prop>
                            <C:calendar-home-set>
                                <D:href>/dav/calendars/user/</D:href>
                                <D:href>   </D:href>
                                <D:href>/dav/calendars/shared/</D:href>
                            </C:calendar-home-set>
                        </D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(2, urls.size)
        assertEquals("/dav/calendars/user/", urls[0])
        assertEquals("/dav/calendars/shared/", urls[1])
    }

    @Test
    fun `missing calendar-home-set element returns empty list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Test</D:displayname>
                        </D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals(emptyList<String>(), extractCalendarHomeUrls(xml))
    }

    @Test
    fun `backward compat - empty XML returns null`() {
        assertEquals(null, extractCalendarHomeUrl(""))
    }

    @Test
    fun `backward compat - malformed XML returns null`() {
        assertEquals(null, extractCalendarHomeUrl("<response><href>/test</href></response>"))
    }

    // ==================== Verify All Existing Fixtures Match Production ====================

    @Test
    fun `all existing fixtures - extractCalendarHomeUrls firstOrNull matches extractCalendarHomeUrl`() {
        // This is the critical backward-compat check: for every existing fixture,
        // the new method's first result must match the old method's result exactly.
        val existingParser = CalDavXmlParser()

        val fixtures = listOf(
            "caldav/icloud/02_calendar_home_set.xml",
            "caldav/nextcloud/02_calendar_home_set.xml",
            "caldav/zoho/02_calendar_home_set.xml",
            "caldav/stalwart/02_calendar_home_set.xml",
            "caldav/openxchange/02_calendar_home_set.xml"
        )

        for (fixture in fixtures) {
            val xml = loadResource(fixture)
            val oldResult = existingParser.extractCalendarHomeUrl(xml)
            val newResult = extractCalendarHomeUrls(xml).firstOrNull()
            assertEquals(
                "Backward compat broken for $fixture: old=$oldResult, new=$newResult",
                oldResult,
                newResult
            )
        }
    }

    @Test
    fun `all existing fixtures return exactly 1 URL`() {
        val fixtures = listOf(
            "caldav/icloud/02_calendar_home_set.xml" to "https://p180-caldav.icloud.com:443/123456789/calendars/",
            "caldav/nextcloud/02_calendar_home_set.xml" to "/remote.php/dav/calendars/testuser/",
            "caldav/zoho/02_calendar_home_set.xml" to "/caldav/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4/",
            "caldav/stalwart/02_calendar_home_set.xml" to "/dav/cal/admin/",
            "caldav/openxchange/02_calendar_home_set.xml" to "/caldav/testuser@mailbox.org/"
        )

        for ((fixture, expectedUrl) in fixtures) {
            val xml = loadResource(fixture)
            val urls = extractCalendarHomeUrls(xml)
            assertEquals("Expected exactly 1 URL for $fixture", 1, urls.size)
            assertEquals("Wrong URL for $fixture", expectedUrl, urls[0])
        }
    }

    // ==================== Namespace Variations for Multi-Home-Set ====================

    @Test
    fun `multi-home-set with iCloud-style non-prefixed namespaces`() {
        // iCloud uses non-prefixed DAV namespace + non-prefixed CalDAV namespace
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <multistatus xmlns="DAV:">
                <response xmlns="DAV:">
                    <href>/123456789/principal/</href>
                    <propstat>
                        <prop>
                            <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <href xmlns="DAV:">/123456789/calendars/</href>
                                <href xmlns="DAV:">/shared/calendars/</href>
                            </calendar-home-set>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(2, urls.size)
        assertEquals("/123456789/calendars/", urls[0])
        assertEquals("/shared/calendars/", urls[1])
    }

    @Test
    fun `multi-home-set with Zoho-style mixed namespaces`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:propstat>
                        <D:prop>
                            <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                <D:href>/caldav/hash123/</D:href>
                                <D:href>/caldav/shared-hash456/</D:href>
                            </calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(2, urls.size)
        assertEquals("/caldav/hash123/", urls[0])
        assertEquals("/caldav/shared-hash456/", urls[1])
    }

    @Test
    fun `single home set still works - no regression`() {
        // Sanity: the most common case (1 href) still returns a list of 1
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cal:calendar-home-set>
                                <d:href>/dav/calendars/user/</d:href>
                            </cal:calendar-home-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val urls = extractCalendarHomeUrls(xml)
        assertEquals(1, urls.size)
        assertEquals("/dav/calendars/user/", urls[0])
        // Backward compat
        assertEquals("/dav/calendars/user/", extractCalendarHomeUrl(xml))
    }

    // ==================== Real Server Fixture: SOGo (cal.aegee.org) ====================

    @Test
    fun `SOGo real fixture - parses 3 home sets from cal_aegee_org`() {
        val xml = loadResource("caldav/aegee/02_calendar_home_set.xml")
        val urls = extractCalendarHomeUrls(xml)
        assertEquals(3, urls.size)
        assertEquals("/dav/calendars/user/aaa/", urls[0])
        assertEquals("/dav/calendars/user/bbb/", urls[1])
        assertEquals("/dav/calendars/user/cal/", urls[2])
    }

    @Test
    fun `SOGo real fixture - backward compat returns first only`() {
        val xml = loadResource("caldav/aegee/02_calendar_home_set.xml")
        assertEquals("/dav/calendars/user/aaa/", extractCalendarHomeUrl(xml))
    }

    @Test
    fun `SOGo real fixture - backward compat matches production parser`() {
        val xml = loadResource("caldav/aegee/02_calendar_home_set.xml")
        val existingParser = CalDavXmlParser()
        assertEquals(
            existingParser.extractCalendarHomeUrl(xml),
            extractCalendarHomeUrls(xml).firstOrNull()
        )
    }
}
