package org.onekash.kashcal.sync.carddav

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for the CardDAV quirks seam: sync-token invalidation, address
 * book skip rules, URL building, provider identity, and that extraction
 * delegates through to [CardDavXmlParser]. Both the generic default and the
 * iCloud specialization are exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CardDavQuirksTest {

    private lateinit var default: DefaultCardDavQuirks
    private lateinit var icloud: ICloudCardDavQuirks
    private lateinit var zoho: ZohoCardDavQuirks

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        default = DefaultCardDavQuirks("https://dav.example.test")
        icloud = ICloudCardDavQuirks()
        zoho = ZohoCardDavQuirks()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- provider identity ----

    @Test
    fun `default identity is generic carddav`() {
        assertEquals("carddav", default.providerId)
        assertEquals("CardDAV", default.displayName)
        assertEquals("https://dav.example.test", default.baseUrl)
        assertFalse(default.requiresAppSpecificPassword)
    }

    @Test
    fun `icloud identity requires app-specific password`() {
        assertEquals("icloud", icloud.providerId)
        assertEquals("iCloud", icloud.displayName)
        assertTrue(icloud.requiresAppSpecificPassword)
    }

    @Test
    fun `zoho pins the contacts host and requires an app-specific password`() {
        // Zoho serves contacts from contacts.zoho.com — a different host than its
        // calendar.zoho.com CalDAV endpoint and unrelated to the login email domain
        // (which can be a custom or Gmail-backed address). The host is therefore
        // pinned as a bootstrap constant, mirroring the iCloud precedent.
        assertEquals("zoho", zoho.providerId)
        assertEquals("Zoho", zoho.displayName)
        assertEquals("https://contacts.zoho.com", zoho.baseUrl)
        assertTrue(zoho.requiresAppSpecificPassword)
    }

    // ---- discoverHostViaDns: only generic servers discover from the email domain ----

    @Test
    fun `generic quirks discover the host via dns`() {
        // A generic CardDAV account's contacts host is unknown a priori, so RFC 6764
        // SRV/TXT discovery from the account's email domain is the right entry point.
        assertTrue(default.discoverHostViaDns)
    }

    @Test
    fun `pinned-host quirks never discover via dns`() {
        // iCloud and Zoho have a known contacts host unrelated to the login email
        // domain; running SRV on that domain could only misdirect them (a
        // same-registrable-domain _carddavs record would silently redirect sync).
        assertFalse(icloud.discoverHostViaDns)
        assertFalse(zoho.discoverHostViaDns)
    }

    // ---- isSyncTokenInvalid (410 / valid-sync-token body → true; bare 403 → false) ----

    @Test
    fun `410 marks the sync token invalid`() {
        assertTrue(default.isSyncTokenInvalid(410, ""))
        assertTrue(icloud.isSyncTokenInvalid(410, ""))
    }

    @Test
    fun `valid-sync-token precondition body marks the sync token invalid`() {
        val body = "<d:error xmlns:d=\"DAV:\"><d:valid-sync-token/></d:error>"
        assertTrue(default.isSyncTokenInvalid(403, body))
        assertTrue(icloud.isSyncTokenInvalid(403, body))
    }

    @Test
    fun `bare 403 is not a sync token invalidation`() {
        assertFalse(default.isSyncTokenInvalid(403, "Forbidden"))
        assertFalse(icloud.isSyncTokenInvalid(403, "Forbidden"))
    }

    // ---- shouldSkipAddressBook ----

    @Test
    fun `skips notification and inbox collections by path segment`() {
        assertTrue(default.shouldSkipAddressBook("/addressbooks/alice/notifications/", null))
        assertTrue(default.shouldSkipAddressBook("/addressbooks/alice/inbox/", "Inbox"))
    }

    @Test
    fun `keeps a normal address book`() {
        assertFalse(default.shouldSkipAddressBook("/addressbooks/alice/default/", "Personal"))
    }

    @Test
    fun `keeps a real address book regardless of its display name`() {
        // The display name never drives the skip. An address book carries the
        // <addressbook> resourcetype to even reach this filter, and the real
        // scheduling/notification collections are excluded by their own path
        // segment — so a user's book named "Inbox" or "Notifications" must survive.
        assertFalse(default.shouldSkipAddressBook("/addressbooks/alice/personal/", "Inbox"))
        assertFalse(default.shouldSkipAddressBook("/addressbooks/alice/family/", "Notifications"))
    }

    @Test
    fun `keeps a user book whose path merely contains a reserved word as a substring`() {
        // The reserved names (inbox/outbox/notification) identify scheduling and
        // notification COLLECTIONS by their own path segment, not any href that
        // happens to contain those letters. A user's real contacts book called
        // "notifications-contacts" or "my-inbox-friends" — or any account whose
        // very username contains one of these words — must NOT be swept away.
        // Radicale (arbitrary collection paths, path segment = username + book)
        // is where this bites: it silently hides real contacts.
        assertFalse(default.shouldSkipAddressBook("/testuser1/notifications-contacts/", "Notifications Contacts"))
        assertFalse(default.shouldSkipAddressBook("/testuser1/my-inbox-friends/", "Inbox Friends"))
        assertFalse(default.shouldSkipAddressBook("/inbox-user/contacts/", "Personal"))
        assertFalse(default.shouldSkipAddressBook("/u/outbox-archive/", "Outbox Archive"))
    }

    // ---- URL building ----

    @Test
    fun `builds absolute address book url from relative href`() {
        assertEquals(
            "https://dav.example.test/addressbooks/alice/default/",
            default.buildAddressBookUrl("/addressbooks/alice/default/", "https://dav.example.test")
        )
    }

    @Test
    fun `passes through an already-absolute href`() {
        val abs = "https://p42-contacts.icloud.com/123/carddavhome/card/"
        assertEquals(abs, default.buildAddressBookUrl(abs, "https://dav.example.test"))
    }

    // ---- extraction delegates through to the parser ----

    @Test
    fun `delegates address book extraction to the parser`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                <d:response>
                    <d:href>/ab/default/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>Personal</d:displayname>
                            <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                            <card:supported-address-data>
                                <card:address-data-type content-type="text/vcard" version="4.0"/>
                            </card:supported-address-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val books = default.extractAddressBooks(xml)
        assertEquals(1, books.size)
        assertEquals("Personal", books.single().displayName)
        assertEquals("4.0", books.single().vcardVersion)
    }

    @Test
    fun `delegates principal extraction to the parser`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response><d:propstat><d:prop>
                    <d:current-user-principal><d:href>/p/alice/</d:href></d:current-user-principal>
                </d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals("/p/alice/", default.extractPrincipalUrl(xml))
    }
}
