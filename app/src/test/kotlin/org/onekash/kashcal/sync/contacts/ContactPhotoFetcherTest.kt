package org.onekash.kashcal.sync.contacts

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.FakeAddressBook
import org.onekash.kashcal.sync.carddav.FakeCardDavClient
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [ContactPhotoFetcher]: the deferred step that drains the photo-pending
 * worklist after a pull. For each pending contact it re-reads the vCard (targeted
 * multiget of only the pending hrefs), recovers the remote-URL photo, fetches the
 * bytes, and writes them as a blob while clearing the pending flag.
 *
 * Doubles: the shared [FakeCardDavClient] (pending vCard bodies + programmed
 * [FakeCardDavClient.fetchPhoto] results) and the shared data-bearing
 * [FakeContactsProviderRepository] (seeded pending set + written-photos capture).
 * Robolectric only because the real [org.onekash.kashcal.data.contacts.VCardContactMapper]
 * builds `ContentValues` while recovering `photoUrl` from the re-read body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactPhotoFetcherTest {

    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var fetcher: ContactPhotoFetcher

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        provider = FakeContactsProviderRepository()
        fetcher = ContactPhotoFetcher(provider)
    }

    @After
    fun tearDown() = unmockkAll()

    // ---------- fixtures ----------

    private fun urlPhotoVcard(uid: String, photoUrl: String): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:$uid\r\nN:$uid;;;;\r\n" +
            "PHOTO;VALUE=URI:$photoUrl\r\nEND:VCARD\r\n"

    private fun noPhotoVcard(uid: String): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:$uid\r\nN:$uid;;;;\r\nEND:VCARD\r\n"

    private fun contact(href: String, body: String) =
        CardDavContactData(href = href, url = "$BOOK_HOST$href", etag = "e-$href", vcardBody = body)

    private fun addressBook(url: String = BOOK_URL, contacts: MutableList<CardDavContactData> = mutableListOf()) =
        FakeAddressBook(
            book = CardDavAddressBook(
                href = url, url = url, displayName = "Contacts", ctag = "ctag-1", vcardVersion = "3.0",
            ),
            contacts = contacts,
        )

    private fun clientWith(vararg books: FakeAddressBook) =
        FakeCardDavClient().apply { this.books += books }

    private fun books(client: FakeCardDavClient): List<CardDavAddressBook> = client.books.map { it.book }

    // ---------- happy path ----------

    @Test
    fun `a pending URL-photo contact is fetched, written as a blob, and cleared`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        val bytes = byteArrayOf(1, 2, 3, 4)
        client.photoResults[PHOTO_URL] = CalDavResult.success(PhotoBytes(bytes, "image/jpeg"))
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("the photo URL was fetched exactly once", listOf(PHOTO_URL), client.fetchPhotoCalls)
        assertArrayEquals("the fetched bytes are written verbatim", bytes, provider.writtenPhotoFor(ACCOUNT, href))
        assertFalse("the pending flag is cleared after a successful write", provider.isPhotoPending(ACCOUNT, href))
    }

    // ---------- empty worklist is a no-op ----------

    @Test
    fun `no pending photos is an exact no-op - no re-read, no fetch, no write`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        // Nothing seeded pending.

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("no vCard was re-read", 0, client.fetchCalls)
        assertTrue("no photo was fetched", client.fetchPhotoCalls.isEmpty())
        assertNull("nothing was written", provider.writtenPhotoFor(ACCOUNT, href))
    }

    // ---------- a failed GET leaves the contact pending for a later retry ----------

    @Test
    fun `a failed photo GET leaves the contact pending and writes nothing`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        client.photoResults[PHOTO_URL] = CalDavResult.error(503, "temporarily unavailable", isRetryable = true)
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("the fetch was attempted", listOf(PHOTO_URL), client.fetchPhotoCalls)
        assertNull("no blob written on a failed GET", provider.writtenPhotoFor(ACCOUNT, href))
        assertTrue("the contact stays pending, to be retried next sync", provider.isPhotoPending(ACCOUNT, href))
    }

    // ---------- a non-retryable failure gives up: clear the flag, don't loop forever ----------

    @Test
    fun `a foreign-host photo URL refused by the client clears the pending flag (give up)`() = runTest {
        val href = "/123/card/a.vcf"
        // The vCard names a photo on a host that is NOT the CardDAV registrable
        // domain; the client refuses it (no GET, credential-leak guard) -> a
        // NON-retryable Error. Retrying it every sync can never succeed as-is, so
        // the fetcher gives up and clears the flag (a later vCard change re-arms it).
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", FOREIGN_PHOTO_URL)))))
        client.photoResults[FOREIGN_PHOTO_URL] =
            CalDavResult.error(0, "refused: foreign host", isRetryable = false)
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertNull("no blob written for a refused foreign-host URL", provider.writtenPhotoFor(ACCOUNT, href))
        assertFalse("a permanent refusal clears the flag rather than retrying forever", provider.isPhotoPending(ACCOUNT, href))
    }

    @Test
    fun `a 404 gone photo clears the pending flag rather than retrying forever`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        client.photoResults[PHOTO_URL] = CalDavResult.notFoundError("Photo not found")
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("the fetch was attempted once", listOf(PHOTO_URL), client.fetchPhotoCalls)
        assertNull("no blob written on a 404", provider.writtenPhotoFor(ACCOUNT, href))
        assertFalse("a gone photo is not retried forever", provider.isPhotoPending(ACCOUNT, href))
    }

    @Test
    fun `a retryable photo-gateway 401 leaves the contact pending rather than clearing`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        // A 401 from the photo gateway (not a dead account credential — that fails the
        // re-read first) is transient; fetchPhoto marks it retryable. Clearing the flag
        // would permanently lose the photo since the gateway URL is stable and never
        // re-arms on a bytes-only change.
        client.photoResults[PHOTO_URL] = CalDavResult.error(401, "Photo fetch unauthorized", isRetryable = true)
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertNull("no blob written on a 401", provider.writtenPhotoFor(ACCOUNT, href))
        assertTrue("a retryable 401 leaves the contact pending", provider.isPhotoPending(ACCOUNT, href))
        assertTrue("the flag is not cleared on a retryable 401", provider.clearPhotoPendingCalls.isEmpty())
    }

    // ---------- credential revocation mid-fetch degrades gracefully ----------

    @Test
    fun `a repo write failure mid-fetch is swallowed - left pending, no crash`() = runTest {
        val href = "/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, urlPhotoVcard("a", PHOTO_URL)))))
        client.photoResults[PHOTO_URL] = CalDavResult.success(PhotoBytes(byteArrayOf(9), "image/png"))
        provider.seedPendingPhoto(ACCOUNT, href)
        // WRITE_CONTACTS revoked between the GET and the provider write.
        provider.writePhotoResult = Result.failure(SecurityException("permission revoked"))

        // Must not throw.
        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertNull("the write failed, so nothing is recorded", provider.writtenPhotoFor(ACCOUNT, href))
        assertTrue("the contact stays pending after a failed write", provider.isPhotoPending(ACCOUNT, href))
    }

    // ---------- a stale pending flag (no URL photo on re-read) is cleared ----------

    @Test
    fun `a pending contact whose re-read vCard has no URL photo has its stale flag cleared`() = runTest {
        val href = "/123/card/a.vcf"
        // Re-read body no longer carries a URL photo (removed on the server, or the
        // photo became inline and was already written on the pull).
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, noPhotoVcard("a")))))
        provider.seedPendingPhoto(ACCOUNT, href)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertTrue("no photo GET is issued when the re-read has no URL photo", client.fetchPhotoCalls.isEmpty())
        assertEquals("the stale pending flag is cleared", listOf(ACCOUNT to href), provider.clearPhotoPendingCalls)
        assertFalse("no longer pending, so it is not retried forever", provider.isPhotoPending(ACCOUNT, href))
    }

    // ---------- a pending href under no discovered book is skipped, left pending ----------

    @Test
    fun `a pending href matching no discovered book is skipped and left pending`() = runTest {
        // The book that owned this href is not in this run's discovered set (its home
        // failed to enumerate, or the book was removed). It must be left pending, never
        // mis-fetched against the wrong collection.
        val otherBook = addressBook(url = "$BOOK_HOST/999/other/", contacts = mutableListOf())
        val client = clientWith(otherBook)
        provider.seedPendingPhoto(ACCOUNT, "/123/card/a.vcf")

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("no vCard re-read for an unresolvable href", 0, client.fetchCalls)
        assertTrue("no photo fetched", client.fetchPhotoCalls.isEmpty())
        assertTrue("left pending, not cleared", provider.isPhotoPending(ACCOUNT, "/123/card/a.vcf"))
        assertTrue("the stale-clear path is NOT taken for a skip", provider.clearPhotoPendingCalls.isEmpty())
    }

    // ---------- pending hrefs are routed to the correct book (path-prefix grouping) ----------

    @Test
    fun `pending hrefs are grouped to their own book so each multiget targets the right collection`() = runTest {
        val bookA = addressBook(url = "$BOOK_HOST/123/A/", contacts = mutableListOf(contact("/123/A/a.vcf", urlPhotoVcard("a", PHOTO_URL))))
        val bookB = addressBook(url = "$BOOK_HOST/123/B/", contacts = mutableListOf(contact("/123/B/b.vcf", urlPhotoVcard("b", PHOTO_URL_2))))
        val client = clientWith(bookA, bookB)
        client.photoResults[PHOTO_URL] = CalDavResult.success(PhotoBytes(byteArrayOf(1), "image/jpeg"))
        client.photoResults[PHOTO_URL_2] = CalDavResult.success(PhotoBytes(byteArrayOf(2), "image/jpeg"))
        provider.seedPendingPhoto(ACCOUNT, "/123/A/a.vcf")
        provider.seedPendingPhoto(ACCOUNT, "/123/B/b.vcf")

        fetcher.fetchPending(ACCOUNT, books(client), client)

        // Each href was re-read against ITS OWN book URL, never the other's.
        val calls = client.fetchByHrefCalls.toMap()
        assertEquals("book A multiget carries only A's href", listOf("/123/A/a.vcf"), calls["$BOOK_HOST/123/A/"])
        assertEquals("book B multiget carries only B's href", listOf("/123/B/b.vcf"), calls["$BOOK_HOST/123/B/"])
        assertArrayEquals(byteArrayOf(1), provider.writtenPhotoFor(ACCOUNT, "/123/A/a.vcf"))
        assertArrayEquals(byteArrayOf(2), provider.writtenPhotoFor(ACCOUNT, "/123/B/b.vcf"))
    }

    // ---------- href-form invariant: an absolute-URL pending href still routes ----------

    @Test
    fun `a pending href in absolute-URL form still resolves to its book and is fetched`() = runTest {
        // SOURCE_ID (the pending href) is whatever the listing/sync-collection path stored.
        // Some servers return an ABSOLUTE href there while the discovered book URL is also
        // absolute; others return a server-relative path. Book routing must reduce BOTH forms
        // to comparable paths, or a pending photo silently never resolves to its collection.
        // This pins that cross-form invariant so a future href-normalization change can't
        // regress it.
        val absoluteHref = "$BOOK_HOST/123/card/a.vcf"
        val client = clientWith(addressBook(contacts = mutableListOf(contact(absoluteHref, urlPhotoVcard("a", PHOTO_URL)))))
        client.photoResults[PHOTO_URL] = CalDavResult.success(PhotoBytes(byteArrayOf(5), "image/jpeg"))
        provider.seedPendingPhoto(ACCOUNT, absoluteHref)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertEquals("the absolute-form href routed to its book and was fetched", listOf(PHOTO_URL), client.fetchPhotoCalls)
        assertArrayEquals("the photo was written for the absolute-form href", byteArrayOf(5), provider.writtenPhotoFor(ACCOUNT, absoluteHref))
        assertFalse("cleared after a successful write", provider.isPhotoPending(ACCOUNT, absoluteHref))
    }

    // ---------- a book whose re-read multiget fails leaves all its hrefs pending ----------

    @Test
    fun `a book whose re-read fails leaves every one of its pending hrefs pending`() = runTest {
        val hrefA = "/123/card/a.vcf"
        val hrefB = "/123/card/b.vcf"
        // The multiget for this book's pending hrefs errors out — the fetcher can't
        // recover any photo URL, so it must leave them all pending (never clear,
        // never write, never fetch a photo it couldn't resolve).
        val client = FakeCardDavClient(fetchError = CalDavResult.error(503, "book unavailable", isRetryable = true))
        client.books += addressBook(
            contacts = mutableListOf(
                contact(hrefA, urlPhotoVcard("a", PHOTO_URL)),
                contact(hrefB, urlPhotoVcard("b", PHOTO_URL_2)),
            ),
        )
        provider.seedPendingPhoto(ACCOUNT, hrefA)
        provider.seedPendingPhoto(ACCOUNT, hrefB)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertTrue("no photo GET when the re-read itself failed", client.fetchPhotoCalls.isEmpty())
        assertTrue("nothing cleared on a failed re-read", provider.clearPhotoPendingCalls.isEmpty())
        assertTrue("href A stays pending", provider.isPhotoPending(ACCOUNT, hrefA))
        assertTrue("href B stays pending", provider.isPhotoPending(ACCOUNT, hrefB))
    }

    // ---------- an href the re-read didn't return is left pending (not cleared) ----------

    @Test
    fun `a pending href absent from the re-read response is left pending`() = runTest {
        val present = "/123/card/a.vcf"
        val absent = "/123/card/gone.vcf"
        // Only `present` comes back on the multiget; `absent` was deleted between the
        // pull and now. It has no entry in the re-read, so it is left pending — a
        // later orphan sweep removes the RawContact entirely, so clearing here is moot.
        val client = clientWith(addressBook(contacts = mutableListOf(contact(present, urlPhotoVcard("a", PHOTO_URL)))))
        client.photoResults[PHOTO_URL] = CalDavResult.success(PhotoBytes(byteArrayOf(7), "image/jpeg"))
        provider.seedPendingPhoto(ACCOUNT, present)
        provider.seedPendingPhoto(ACCOUNT, absent)

        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertArrayEquals("the present href is fetched and written", byteArrayOf(7), provider.writtenPhotoFor(ACCOUNT, present))
        assertFalse("the present href is cleared after write", provider.isPhotoPending(ACCOUNT, present))
        assertTrue("the absent href is left pending, not cleared", provider.isPhotoPending(ACCOUNT, absent))
        assertTrue("the absent href is not force-cleared", provider.clearPhotoPendingCalls.none { it.second == absent })
    }

    // ---------- an unexpected collaborator throw degrades to left-pending, never propagates ----------

    @Test
    fun `an unexpected throw from a collaborator is contained and leaves the book pending`() = runTest {
        val href = "/123/card/a.vcf"
        // A no-URL re-read would take the clear path; make clearPhotoPending THROW an
        // unchecked exception to prove the fetcher's never-throws contract holds even
        // when a collaborator violates its Result envelope — the whole contact sync
        // must not fail, and the href must be left pending.
        val client = clientWith(addressBook(contacts = mutableListOf(contact(href, noPhotoVcard("a")))))
        provider.seedPendingPhoto(ACCOUNT, href)
        provider.clearPhotoPendingThrows = RuntimeException("provider blew up")

        // Must not throw.
        fetcher.fetchPending(ACCOUNT, books(client), client)

        assertTrue("the href stays pending after a contained throw", provider.isPhotoPending(ACCOUNT, href))
    }

    private companion object {
        const val ACCOUNT = "alice@example.test"
        const val BOOK_HOST = "https://p52-contacts.icloud.com"
        const val BOOK_URL = "https://p52-contacts.icloud.com/123/card/"
        const val PHOTO_URL = "https://gateway.icloud.com/photo/a.jpg"
        const val PHOTO_URL_2 = "https://gateway.icloud.com/photo/b.jpg"
        const val FOREIGN_PHOTO_URL = "https://attacker.example/photo/a.jpg"
    }
}
