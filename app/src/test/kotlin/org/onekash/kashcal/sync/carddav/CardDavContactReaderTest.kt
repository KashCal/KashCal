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

        val read = (result as CalDavResult.Success).data.contacts
        assertEquals(1, read.size)
        assertEquals("/ab/alice/v3.vcf", read.single().href)
        assertEquals("e3", read.single().etag)
        assertEquals("3.0", read.single().contact.version)
        assertEquals("Alice Example", read.single().contact.displayName)
        assertEquals("alice@example.test", read.single().contact.emails.single().address)
        // Seam guard: the reader is a pure multiget composer — it must reach ONLY
        // fetchContactsByHref, never the discovery/change-detection surface.
        assertEquals("reader must touch only the fetch surface", 0, client.nonFetchCalls)
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

        val read = (result as CalDavResult.Success).data.contacts
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

        val read = (result as CalDavResult.Success).data.contacts
        // The two valid contacts survive; the malformed body is dropped.
        val hrefs = read.map { it.href }
        assertTrue(hrefs.contains("/ab/a/good.vcf"))
        assertTrue(hrefs.contains("/ab/a/good2.vcf"))
    }

    @Test
    fun `a body that yields no contact is reported unreadable, not silently dropped`() = runTest {
        // The point of the sync-cursor fix: an href the read couldn't turn into a
        // contact must be reported so the caller holds its cursor rather than
        // advancing past it. ez-vcard 0.12.2 does NOT throw on junk — a non-vCard
        // string parses to an empty card list — so the zero-card body is the
        // testable stand-in for the production R8/stripped-ctor failure (which threw
        // only under minification). Its href lands in unreadableHrefs while the valid
        // sibling parses and is NOT reported.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/good.vcf", "https://dav.example.test/ab/a/good.vcf", "eg", VCARD_3_0),
                CardDavContactData("/ab/a/bad.vcf", "https://dav.example.test/ab/a/bad.vcf", "eb", "this is not a vcard at all"),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/good.vcf", "/ab/a/bad.vcf"),
            "3.0",
        )

        val data = (result as CalDavResult.Success).data
        assertEquals("only the valid contact parses", listOf("/ab/a/good.vcf"), data.contacts.map { it.href })
        assertEquals("the zero-card href is reported unreadable", setOf("/ab/a/bad.vcf"), data.unreadableHrefs)
    }

    @Test
    fun `an href the server omits from the multiget is reported unreadable`() = runTest {
        // The multiget can come back missing a requested href entirely (server dropped
        // it from the response). We requested it and got nothing, so it is unreadable —
        // the caller must not advance its cursor as if that href were reconciled.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/present.vcf", "https://dav.example.test/ab/a/present.vcf", "ep", VCARD_3_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/present.vcf", "/ab/a/missing.vcf"),
            "3.0",
        )

        val data = (result as CalDavResult.Success).data
        assertEquals("the returned href parses", listOf("/ab/a/present.vcf"), data.contacts.map { it.href })
        assertEquals("the omitted href is reported unreadable", setOf("/ab/a/missing.vcf"), data.unreadableHrefs)
    }

    @Test
    fun `the collection self-href is not counted unreadable`() = runTest {
        // iCloud's sync-collection REPORT lists the collection itself (no trailing
        // slash, no resourcetype), so the collection URL can slip into the requested
        // href set. The client's multiget deliberately drops it (it would 400 the
        // whole batch), so it never comes back — and here the fake mirrors that: no
        // body has the collection URL as its href, so it's simply absent from the
        // response. It must NOT be reported unreadable, or the caller would hold its
        // sync cursor forever and permanently disable the orphan sweep. Both the
        // slashless self-href and the canonical collection form must be excluded.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/alice.vcf", "https://dav.example.test/ab/a/alice.vcf", "ea", VCARD_3_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("https://dav.example.test/ab/a", "/ab/a/alice.vcf"),
            "3.0",
        )

        val data = (result as CalDavResult.Success).data
        assertEquals("the real contact parses", listOf("/ab/a/alice.vcf"), data.contacts.map { it.href })
        assertTrue("the collection self-href must never be unreadable", data.unreadableHrefs.isEmpty())
    }

    @Test
    fun `a valid batch reports no unreadable hrefs`() = runTest {
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/a.vcf", "https://dav.example.test/ab/a/a.vcf", "ea", VCARD_3_0),
                CardDavContactData("/ab/a/b.vcf", "https://dav.example.test/ab/a/b.vcf", "eb", VCARD_4_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/a.vcf", "/ab/a/b.vcf"),
            "4.0",
        )

        assertTrue("nothing unreadable in a clean batch", (result as CalDavResult.Success).data.unreadableHrefs.isEmpty())
    }

    @Test
    fun `a KIND group vCard is dropped so it never mirrors as a phantom contact`() = runTest {
        // A KIND:group vCard (RFC 6350 §6.1.4) is a distribution list, not a person.
        // Mirrored to the device it becomes an empty phantom contact, so the reader
        // drops it while keeping every real person in the same batch.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/alice.vcf", "https://dav.example.test/ab/a/alice.vcf", "ea", VCARD_3_0),
                CardDavContactData("/ab/a/team.vcf", "https://dav.example.test/ab/a/team.vcf", "et", VCARD_GROUP_4_0),
                CardDavContactData("/ab/a/bob.vcf", "https://dav.example.test/ab/a/bob.vcf", "eb", VCARD_4_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/alice.vcf", "/ab/a/team.vcf", "/ab/a/bob.vcf"),
            "4.0",
        )

        val read = (result as CalDavResult.Success).data.contacts
        val hrefs = read.map { it.href }
        assertEquals("the group vCard must be dropped, both people kept", 2, read.size)
        assertTrue(hrefs.contains("/ab/a/alice.vcf"))
        assertTrue(hrefs.contains("/ab/a/bob.vcf"))
        assertTrue("the KIND:group href must not survive", !hrefs.contains("/ab/a/team.vcf"))
    }

    @Test
    fun `a 3_0 Apple group vCard is dropped too`() = runTest {
        // The 3.0 X-ADDRESSBOOKSERVER-KIND:group form (Apple's pre-4.0 idiom) must be
        // filtered the same way as the native 4.0 KIND:group.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData("/ab/a/team3.vcf", "https://dav.example.test/ab/a/team3.vcf", "et3", VCARD_GROUP_3_0),
                CardDavContactData("/ab/a/alice.vcf", "https://dav.example.test/ab/a/alice.vcf", "ea", VCARD_3_0),
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts(
            "https://dav.example.test/ab/a/",
            listOf("/ab/a/team3.vcf", "/ab/a/alice.vcf"),
            "3.0",
        )

        val read = (result as CalDavResult.Success).data.contacts
        assertEquals("only the real person survives", 1, read.size)
        assertEquals("/ab/a/alice.vcf", read.single().href)
    }

    @Test
    fun `a body with a malformed tel is kept, not dropped as unparseable`() = runTest {
        // Regression: a contact whose TEL is a spec-violating tel URI (global number
        // without a leading "+") must still be read. The phone degrades to its raw
        // text; the contact itself is never discarded.
        val client = FakeCardDavClient(
            listOf(
                CardDavContactData(
                    href = "/ab/a/badtel.vcf",
                    url = "https://dav.example.test/ab/a/badtel.vcf",
                    etag = "et",
                    vcardBody = "BEGIN:VCARD\r\n" +
                        "VERSION:4.0\r\n" +
                        "UID:carol-badtel\r\n" +
                        "FN:Carol Example\r\n" +
                        "TEL;VALUE=uri:tel:5550100\r\n" +
                        "EMAIL:carol@example.test\r\n" +
                        "END:VCARD\r\n",
                )
            )
        )
        val reader = CardDavContactReader(client)

        val result = reader.readContacts("https://dav.example.test/ab/a/", listOf("/ab/a/badtel.vcf"), "4.0")

        val read = (result as CalDavResult.Success).data.contacts
        assertEquals("the contact must not be dropped over a bad phone", 1, read.size)
        assertEquals("Carol Example", read.single().contact.displayName)
        assertEquals("carol@example.test", read.single().contact.emails.single().address)
        assertEquals("5550100", read.single().contact.phones.single().number)
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

        val read = (result as CalDavResult.Success).data.contacts
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

        assertEquals(0, (result as CalDavResult.Success).data.contacts.size)
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

        val VCARD_GROUP_4_0 =
            "BEGIN:VCARD\r\n" +
                "VERSION:4.0\r\n" +
                "UID:urn:uuid:team-4\r\n" +
                "KIND:group\r\n" +
                "FN:Marketing Team\r\n" +
                "MEMBER:urn:uuid:bob-4\r\n" +
                "END:VCARD\r\n"

        val VCARD_GROUP_3_0 =
            "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "UID:team-3\r\n" +
                "FN:Marketing Team\r\n" +
                "N:Marketing Team;;;;\r\n" +
                "X-ADDRESSBOOKSERVER-KIND:group\r\n" +
                "X-ADDRESSBOOKSERVER-MEMBER:urn:uuid:bob-4\r\n" +
                "END:VCARD\r\n"
    }
}
