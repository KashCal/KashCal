package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [CalDavXmlParser.extractCalendarUserAddresses] against real-shape fixtures captured from
 * seven CalDAV servers during a calendar-user-address discovery probe.
 *
 * Fixtures: `app/src/test/resources/caldav/<server>/06_calendar_user_address_set.xml` (Zoho uses
 * 07_ because 06_calendar_multiget.xml is taken).
 *
 * Personal email addresses in the iCloud and Zoho probe data are redacted to example domains. The
 * shape (count, schemes, preferred attribute, wire order) is kept verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalDavXmlParserAddressSetTest {

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

    private fun loadResource(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")

    // ========== Server fixtures ==========

    @Test
    fun `iCloud — 7 entries with preferred mailto hoisted to position 0`() {
        val xml = loadResource("caldav/icloud/06_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(7, addresses.size)
        // iCloud puts preferred="1" on a mailto that isn't first on the wire; it must be
        // hoisted to position 0 ahead of the path-relative principal href.
        assertEquals("mailto:alice@example.com", addresses[0])
        // The other six are present (the parser keeps their wire order; not asserted here)
        assertTrue(addresses.contains("/123456789/principal"))
        assertTrue(addresses.contains("urn:uuid:123456789"))
        assertTrue(addresses.contains("mailto:alice.work@example.com"))
        assertTrue(addresses.contains("mailto:alice@example.me"))
        assertTrue(addresses.contains("mailto:alice.alt@example.com"))
        assertTrue(addresses.any { it.startsWith("/aOpaqueAccountToken") })
    }

    @Test
    fun `Zoho — 2 entries mailto first then path-relative`() {
        val xml = loadResource("caldav/zoho/07_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(2, addresses.size)
        assertEquals("mailto:alice@example.com", addresses[0])
        assertEquals("/caldav/0123456789abcdef0123456789abcdef/user", addresses[1])
    }

    @Test
    fun `Nextcloud with email — 2 entries`() {
        val xml = loadResource("caldav/nextcloud/06_calendar_user_address_set_with_email.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf(
            "mailto:admin@example.com",
            "/remote.php/dav/principals/users/admin/"
        ), addresses)
    }

    @Test
    fun `Nextcloud without email — 1 path-relative only`() {
        val xml = loadResource("caldav/nextcloud/07_calendar_user_address_set_no_email.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf("/remote.php/dav/principals/users/admin/"), addresses)
    }

    @Test
    fun `Radicale — 1 path-relative only`() {
        val xml = loadResource("caldav/radicale/06_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf("/aliceprobe/"), addresses)
    }

    @Test
    fun `Baikal — 2 entries`() {
        val xml = loadResource("caldav/baikal/06_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf(
            "mailto:alice@baikal.test",
            "/dav.php/principals/alice/"
        ), addresses)
    }

    @Test
    fun `SoGo — 2 entries`() {
        val xml = loadResource("caldav/sogo/06_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf(
            "mailto:testuser1@test.local",
            "/SOGo/dav/testuser1/"
        ), addresses)
    }

    @Test
    fun `Stalwart — 2 entries`() {
        val xml = loadResource("caldav/stalwart/06_calendar_user_address_set.xml")
        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf(
            "mailto:admin@stalwart.test",
            "/dav/principal/admin/"
        ), addresses)
    }

    // ========== preferred attribute behavior ==========

    @Test
    fun `multiple preferred entries — first preferred wins, rest in wire order`() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <response>
                <href>/p/</href>
                <propstat>
                  <prop>
                    <C:calendar-user-address-set>
                      <href>/p/</href>
                      <href preferred="1">mailto:first-pref@x.com</href>
                      <href>mailto:plain@x.com</href>
                      <href preferred="1">mailto:second-pref@x.com</href>
                    </C:calendar-user-address-set>
                  </prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()

        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf(
            "mailto:first-pref@x.com",
            "mailto:second-pref@x.com",
            "/p/",
            "mailto:plain@x.com"
        ), addresses)
    }

    @Test
    fun `no preferred attribute — wire order preserved unchanged`() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <response>
                <href>/p/</href>
                <propstat>
                  <prop>
                    <C:calendar-user-address-set>
                      <href>/path/</href>
                      <href>mailto:a@x.com</href>
                      <href>urn:uuid:abc</href>
                    </C:calendar-user-address-set>
                  </prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()

        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf("/path/", "mailto:a@x.com", "urn:uuid:abc"), addresses)
    }

    @Test
    fun `preferred=0 is not treated as preferred`() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <response>
                <href>/p/</href>
                <propstat>
                  <prop>
                    <C:calendar-user-address-set>
                      <href preferred="0">mailto:a@x.com</href>
                      <href preferred="1">mailto:b@x.com</href>
                    </C:calendar-user-address-set>
                  </prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()

        val addresses = parser.extractCalendarUserAddresses(xml)

        assertEquals(listOf("mailto:b@x.com", "mailto:a@x.com"), addresses)
    }

    // ========== Negative cases ==========

    @Test
    fun `empty calendar-user-address-set element returns empty list`() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <response><href>/p/</href><propstat><prop>
                <C:calendar-user-address-set/>
              </prop><status>HTTP/1.1 200 OK</status></propstat></response>
            </multistatus>
        """.trimIndent()

        assertEquals(emptyList<String>(), parser.extractCalendarUserAddresses(xml))
    }

    @Test
    fun `property absent from response returns empty list`() {
        val xml = """
            <multistatus xmlns="DAV:">
              <response><href>/p/</href><propstat><prop>
                <displayname>alice</displayname>
              </prop><status>HTTP/1.1 200 OK</status></propstat></response>
            </multistatus>
        """.trimIndent()

        assertEquals(emptyList<String>(), parser.extractCalendarUserAddresses(xml))
    }

    @Test
    fun `malformed XML returns empty list without throwing`() {
        val xml = "<not valid xml"
        assertEquals(emptyList<String>(), parser.extractCalendarUserAddresses(xml))
    }

    @Test
    fun `blank input returns empty list`() {
        assertEquals(emptyList<String>(), parser.extractCalendarUserAddresses(""))
        assertEquals(emptyList<String>(), parser.extractCalendarUserAddresses("   "))
    }
}
