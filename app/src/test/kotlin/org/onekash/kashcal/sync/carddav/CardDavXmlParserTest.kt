package org.onekash.kashcal.sync.carddav

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [CardDavXmlParser]: the CardDAV extractors (addressbook-home-set, address book listing
 * with privileges, supported-address-data version negotiation, addressbook-multiget
 * address-data), and that principal, sync-collection and ctag parsing delegate to the shared
 * CalDAV parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CardDavXmlParserTest {

    private lateinit var parser: CardDavXmlParser

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        parser = CardDavXmlParser()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== addressbook-home-set (§7.1.1) ==========

    @Test
    fun `extracts addressbook home from principal response`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/principals/users/alice/</d:href>
                    <d:propstat>
                        <d:prop>
                            <card:addressbook-home-set>
                                <d:href>/addressbooks/users/alice/</d:href>
                            </card:addressbook-home-set>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals(listOf("/addressbooks/users/alice/"), parser.extractAddressBookHomeUrls(xml))
    }

    @Test
    fun `extracts multiple addressbook home hrefs`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <card:addressbook-home-set>
                                <d:href>/addressbooks/users/alice/</d:href>
                                <d:href>https://p42-contacts.icloud.com/123/carddavhome/</d:href>
                            </card:addressbook-home-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals(
            listOf(
                "/addressbooks/users/alice/",
                "https://p42-contacts.icloud.com/123/carddavhome/"
            ),
            parser.extractAddressBookHomeUrls(xml)
        )
    }

    @Test
    fun `addressbook home is empty when property absent`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/x/</d:href></d:response></d:multistatus>
        """.trimIndent()
        assertTrue(parser.extractAddressBookHomeUrls(xml).isEmpty())
    }

    // ========== address book listing (§5.2 / §6.2.1) + version negotiation (§6.2.2) ==========

    @Test
    fun `lists address book with name description ctag and negotiates 4_0 when offered`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav"
                           xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype>
                                <d:collection/>
                                <card:addressbook/>
                            </d:resourcetype>
                            <card:addressbook-description>My contacts</card:addressbook-description>
                            <cs:getctag>ctag-77</cs:getctag>
                            <card:supported-address-data>
                                <card:address-data-type content-type="text/vcard" version="3.0"/>
                                <card:address-data-type content-type="text/vcard" version="4.0"/>
                            </card:supported-address-data>
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

        val books = parser.extractAddressBooks(xml)
        assertEquals(1, books.size)
        val book = books.first()
        assertEquals("/addressbooks/users/alice/default/", book.href)
        assertEquals("Personal", book.displayName)
        assertEquals("My contacts", book.description)
        assertEquals("ctag-77", book.ctag)
        assertEquals("4.0", book.vcardVersion)
        assertFalse(book.isReadOnly)
    }

    @Test
    fun `negotiates 3_0 when only 3_0 offered`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/ab/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Book</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                            <card:supported-address-data>
                                <card:address-data-type content-type="text/vcard" version="3.0"/>
                            </card:supported-address-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("3.0", parser.extractAddressBooks(xml).single().vcardVersion)
    }

    @Test
    fun `negotiates 3_0 when supported-address-data absent`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/ab/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Book</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("3.0", parser.extractAddressBooks(xml).single().vcardVersion)
    }

    @Test
    fun `skips non-addressbook collections`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Home</d:displayname>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Contacts</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val books = parser.extractAddressBooks(xml)
        assertEquals(1, books.size)
        assertEquals("/addressbooks/users/alice/default/", books.single().href)
    }

    @Test
    fun `address book with read-only privileges is marked read-only`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/shared/team/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Team</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                            <d:current-user-privilege-set>
                                <d:privilege><d:read/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertTrue(parser.extractAddressBooks(xml).single().isReadOnly)
    }

    @Test
    fun `an aggregated all privilege marks the address book writable`() {
        // Some servers advertise the RFC 3744 aggregate DAV:all instead of the leaf DAV:write
        // or DAV:write-content. DAV:all includes write, so the book is writable and must not
        // be read-only; misreading it silently blocks every contact push to that server
        // (issue #281).
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                            <d:current-user-privilege-set>
                                <d:privilege><d:all/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertFalse(parser.extractAddressBooks(xml).single().isReadOnly)
    }

    @Test
    fun `a write-content privilege marks the address book writable`() {
        // DAV:write-content is the leaf content-write privilege (RFC 3744 §3.12); a server may
        // advertise it without the DAV:write aggregate, and the book must be writable.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                            <d:current-user-privilege-set>
                                <d:privilege><d:read/></d:privilege>
                                <d:privilege><d:write-content/></d:privilege>
                            </d:current-user-privilege-set>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertFalse(parser.extractAddressBooks(xml).single().isReadOnly)
    }

    @Test
    fun `an address book advertising no privilege set at all is treated as read-only`() {
        // With no current-user-privilege-set, write capability is unknown, so the book fails
        // closed to read-only instead of attempting a push that would 403.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertTrue(parser.extractAddressBooks(xml).single().isReadOnly)
    }

    @Test
    fun `skips addressbook whose resourcetype propstat returned non-200 (RFC 4918 multi-propstat)`() {
        // A multi-propstat server (Radicale, Stalwart) can echo the addressbook resourcetype
        // inside a 404 or 403 propstat for a collection the user can't read, while the 200
        // propstat carries the readable props. The book must not be listed.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/forbidden/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Forbidden</d:displayname>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                        </d:prop>
                        <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertTrue(parser.extractAddressBooks(xml).isEmpty())
    }

    @Test
    fun `includes addressbook whose resourcetype propstat is 200 in a multi-propstat response`() {
        // Same multi-propstat shape, but the resourcetype propstat is 200: the book is real and
        // must be listed, so the non-200 filter doesn't over-filter.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/addressbooks/users/alice/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                    <d:propstat>
                        <d:prop><card:addressbook-description/></d:prop>
                        <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("/addressbooks/users/alice/default/", parser.extractAddressBooks(xml).single().href)
    }

    // ========== addressbook-multiget address-data (§10.4 / §8.7) ==========

    @Test
    fun `extracts address data bodies with normalized etags`() {
        // Flush-left (no trimIndent): the vCard body's lines have no indentation, as a server
        // emits them. An indented wrapper would leave those lines un-dedented and corrupt the
        // payload, so the whole document sits at the margin.
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
            "<d:response>\n" +
            "<d:href>/ab/alice.vcf</d:href>\n" +
            "<d:propstat>\n" +
            "<d:prop>\n" +
            "<d:getetag>\"etag-abc\"</d:getetag>\n" +
            "<card:address-data>BEGIN:VCARD\n" +
            "VERSION:3.0\n" +
            "UID:alice-1\n" +
            "FN:Alice Example\n" +
            "END:VCARD</card:address-data>\n" +
            "</d:prop>\n" +
            "<d:status>HTTP/1.1 200 OK</d:status>\n" +
            "</d:propstat>\n" +
            "</d:response>\n" +
            "</d:multistatus>\n"

        val data = parser.extractAddressData(xml)
        assertEquals(1, data.size)
        val entry = data.single()
        assertEquals("/ab/alice.vcf", entry.href)
        assertEquals("etag-abc", entry.etag)
        assertTrue(entry.vcardBody.contains("BEGIN:VCARD"))
        assertTrue(entry.vcardBody.contains("FN:Alice Example"))
        assertTrue(entry.vcardBody.contains("VERSION:3.0"))
    }

    @Test
    fun `keeps the whole vcard body when it contains an escaped ampersand`() {
        // Guards the address-data reader's single-token text read. A contact whose ORG, NOTE
        // or FN contains '&' arrives escaped as "&amp;" (likewise '<' as "&lt;"). If an entity
        // reference split the element's text into segments, a one-token read would lose
        // everything after the '&' (here END:VCARD), failing the BEGIN:VCARD check and
        // silently dropping the contact. The pull parser resolves the five predefined XML
        // entities inline and reports the whole run as one text token; this test fails if a
        // parser swap changes that.
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
            "<d:response>\n" +
            "<d:href>/ab/acme.vcf</d:href>\n" +
            "<d:propstat>\n" +
            "<d:prop>\n" +
            "<d:getetag>\"etag-acme\"</d:getetag>\n" +
            "<card:address-data>BEGIN:VCARD\n" +
            "VERSION:3.0\n" +
            "UID:acme-1\n" +
            "FN:Acme Contact\n" +
            "ORG:Johnson &amp; Johnson\n" +
            "END:VCARD</card:address-data>\n" +
            "</d:prop>\n" +
            "<d:status>HTTP/1.1 200 OK</d:status>\n" +
            "</d:propstat>\n" +
            "</d:response>\n" +
            "</d:multistatus>\n"

        val data = parser.extractAddressData(xml)
        assertEquals("contact with '&' in body must not be dropped", 1, data.size)
        val body = data.single().vcardBody
        assertTrue("literal '&' must be unescaped in the body", body.contains("Johnson & Johnson"))
        assertTrue("body must survive past the '&' to END:VCARD", body.contains("END:VCARD"))
    }

    @Test
    fun `skips response with etag but no address-data`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/ab/</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"col"</d:getetag></d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertTrue(parser.extractAddressData(xml).isEmpty())
    }

    // ========== delegation to the shared CalDAV skeleton ==========

    @Test
    fun `delegates principal extraction to the shared parser`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <d:current-user-principal>
                                <d:href>/principals/users/alice/</d:href>
                            </d:current-user-principal>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("/principals/users/alice/", parser.extractPrincipalUrl(xml))
    }

    @Test
    fun `delegates sync-collection parsing to the shared parser`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/ab/one.vcf</d:href>
                    <d:propstat>
                        <d:prop><d:getetag>"e1"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/ab/gone.vcf</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
                <d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val data = parser.extractSyncCollectionData(xml)
        assertEquals("http://sabre.io/ns/sync/42", data.syncToken)
        assertEquals(listOf("/ab/one.vcf" to "e1"), data.changedItems)
        assertEquals(listOf("/ab/gone.vcf"), data.deletedHrefs)
    }

    @Test
    fun `delegates ctag extraction to the shared parser`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat><d:prop><cs:getctag>ct-9</cs:getctag></d:prop></d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("ct-9", parser.extractCtag(xml))
    }

    @Test
    fun `blank input yields empty results`() {
        assertTrue(parser.extractAddressBookHomeUrls("").isEmpty())
        assertTrue(parser.extractAddressBooks("").isEmpty())
        assertTrue(parser.extractAddressData("").isEmpty())
        assertNull(parser.extractPrincipalUrl(""))
    }
}
