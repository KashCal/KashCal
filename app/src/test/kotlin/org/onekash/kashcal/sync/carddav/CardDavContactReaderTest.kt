package org.onekash.kashcal.sync.carddav

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CardDavContactReader]: end-to-end composition of the client's raw
 * vCard bodies through the real [VCardParser] into the neutral contact model,
 * plus the robustness contract (empty short-circuit, per-body parse isolation,
 * body-driven version, transport-error passthrough).
 *
 * The client is a hand-written [FakeCardDavClient] rather than a relaxed mock:
 * the data-bearing method returns real bodies whose parse we assert on, so a
 * silent wrong-stub can't hide behind a green suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CardDavContactReaderTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `parses a 3_0 body end-to-end`() = runTest {
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData(
                    href = "/ab/alice/v3.vcf",
                    url = "https://dav.example.test/ab/alice/v3.vcf",
                    etag = "e3",
                    vcardBody = VCARD_3_0,
                )
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts("https://dav.example.test/ab/alice/", listOf("/ab/alice/v3.vcf"), "3.0")

        val read = (result as CalDavResult.Success).data
        assertEquals(1, read.size)
        assertEquals("/ab/alice/v3.vcf", read.single().href)
        assertEquals("e3", read.single().etag)
        assertEquals("3.0", read.single().contact.version)
        assertEquals("Alice Example", read.single().contact.displayName)
        assertEquals("alice@example.test", read.single().contact.emails.single().address)
    }

    @Test
    fun `parses a 4_0 body end-to-end with the version from the body`() = runTest {
        // Request 3.0 over the wire but the body is 4.0 — the parsed version must
        // follow the body's VERSION line, never the requested version.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData(
                    href = "/ab/alice/v4.vcf",
                    url = "https://dav.example.test/ab/alice/v4.vcf",
                    etag = "e4",
                    vcardBody = VCARD_4_0,
                )
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts("https://dav.example.test/ab/alice/", listOf("/ab/alice/v4.vcf"), "3.0")

        val read = (result as CalDavResult.Success).data
        assertEquals("4.0", read.single().contact.version)
        assertEquals("Bob Example", read.single().contact.displayName)
    }

    @Test
    fun `a single unparseable body is skipped without aborting the batch`() = runTest {
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/good.vcf", "https://dav.example.test/ab/a/good.vcf", "eg", VCARD_3_0),
                CardDavContactData("/ab/a/bad.vcf", "https://dav.example.test/ab/a/bad.vcf", "eb", "not a vcard at all"),
                CardDavContactData("/ab/a/good2.vcf", "https://dav.example.test/ab/a/good2.vcf", "eg2", VCARD_4_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/good.vcf", "/ab/a/bad.vcf", "/ab/a/good2.vcf"),
            "4.0",
        )

        val read = (result as CalDavResult.Success).data
        // The two valid contacts survive; the malformed body is dropped.
        val hrefs = read.map { it.href }
        assertTrue(hrefs.contains("/ab/a/good.vcf"))
        assertTrue(hrefs.contains("/ab/a/good2.vcf"))
    }

    @Test
    fun `large href lists are fetched in bounded batches`() = runTest {
        // iCloud rejects/empties a single oversized addressbook-multiget, so the
        // reader must split hrefs into bounded batches. Give it more hrefs than one
        // batch holds and assert every batch stays within the cap and all bodies
        // still come back parsed.
        val count = 45
        val bodies = (0 until count).map { i ->
            CardDavContactData(
                href = "/ab/a/c$i.vcf",
                url = "https://dav.example.test/ab/a/c$i.vcf",
                etag = "e$i",
                vcardBody = VCARD_3_0.replace("UID:alice-3", "UID:alice-$i"),
            )
        }
        val client = FakeCardDavClient(bodies)
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            bodies.map { it.href },
            "3.0",
        )

        val read = (result as CalDavResult.Success).data
        assertEquals("all bodies should come back across batches", count, read.size)
        assertTrue(
            "no batch may exceed the multiget cap; saw ${client.batchSizes}",
            client.batchSizes.all { it <= 20 },
        )
        assertEquals(
            "45 hrefs at cap 20 must be 3 batches",
            3,
            client.fetchCalls,
        )
        assertEquals("no href may be dropped or duplicated across batches", count, client.batchSizes.sum())
    }

    @Test
    fun `empty hrefs short-circuits without calling the client`() = runTest {
        val client = FakeCardDavClient(emptyList())
        val reader = CardDavContactReader(client)

        val result = reader.readContacts("https://dav.example.test/ab/a/", emptyList(), "3.0")

        assertEquals(0, (result as CalDavResult.Success).data.size)
        assertEquals("client must not be called for empty hrefs", 0, client.fetchCalls)
    }

    @Test
    fun `transport error is passed through verbatim`() = runTest {
        val client = FakeCardDavClient(emptyList(), fetchError = CalDavResult.Error(503, "unavailable", isRetryable = true))
        val reader = CardDavContactReader(client)

        val result = reader.readContacts("https://dav.example.test/ab/a/", listOf("/ab/a/x.vcf"), "3.0")

        assertTrue(result is CalDavResult.Error)
        assertEquals(503, (result as CalDavResult.Error).code)
        assertTrue(result.isRetryable)
    }

    // ========== fixtures ==========

    private companion object {
        val VCARD_3_0 =
            "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "UID:alice-3\r\n" +
                "FN:Alice Example\r\n" +
                "N:Example;Alice;;;\r\n" +
                "EMAIL;TYPE=INTERNET:alice@example.test\r\n" +
                "END:VCARD\r\n"

        val VCARD_4_0 =
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "UID:urn:uuid:bob-4\r\n" +
                "FN:Bob Example\r\n" +
                "N:Example;Bob;;;\r\n" +
                "EMAIL:bob@example.test\r\n" +
                "END:VCARD\r\n"
    }
}

/**
 * Canonical fake of the read-path client surface. Only [fetchContactsByHref]
 * carries data; the discovery/change-detection methods are unused by the reader
 * and throw if touched (a call would signal the reader reaching past its seam).
 */
private class FakeCardDavClient(
    private val bodies: List<CardDavContactData>,
    private val fetchError: CalDavResult.Error? = null,
) : CardDavClient {

    var fetchCalls = 0
        private set

    /** Size of each fetchContactsByHref call, in call order — lets tests assert batching. */
    val batchSizes = mutableListOf<Int>()

    override suspend fun fetchContactsByHref(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String,
    ): CalDavResult<List<CardDavContactData>> {
        fetchCalls++
        batchSizes += hrefs.size
        fetchError?.let { return it }
        return CalDavResult.success(bodies.filter { it.href in hrefs })
    }

    override suspend fun discoverWellKnown(serverUrl: String) = unsupported()
    override suspend fun discoverPrincipal(serverUrl: String) = unsupported()
    override suspend fun discoverAddressBookHome(principalUrl: String) = unsupported()
    override suspend fun listAddressBooks(addressBookHomeUrl: String) = unsupported()
    override suspend fun getCtag(addressBookUrl: String) = unsupported()
    override suspend fun getSyncToken(addressBookUrl: String) = unsupported()
    override suspend fun syncCollection(addressBookUrl: String, syncToken: String?): CalDavResult<ContactSyncReport> =
        unsupported()
    override suspend fun listAllContactHrefs(addressBookUrl: String) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("reader must only call fetchContactsByHref")
}
