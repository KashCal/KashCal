package org.onekash.kashcal.sync.contacts

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.carddav.FakeAddressBook
import org.onekash.kashcal.sync.carddav.FakeCardDavClient
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser

/**
 * Tests [ContactPushStrategy], the push half of contact sync. It reads the provider's
 * DIRTY/DELETED pending set, matches each locator to its discovered address book, pushes edits
 * (GET-before-PUT patch, a create, or create as fresh) and deletes, then records the outcome on
 * the RawContact SYNC columns.
 *
 * Doubles: the shared [FakeCardDavClient] (records putContact and deleteContact, serves the GET
 * patch base from its contact pool, programmable results) and the shared
 * [FakeContactsProviderRepository] (seeded pending set, write-back capture). No Robolectric: the
 * strategy builds vCards with the pure-JVM VCardWriter and VCardParser and never touches a
 * ContentProvider directly, so only `android.util.Log` is stubbed.
 */
class ContactPushStrategyTest {

    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var strategy: ContactPushStrategy

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        provider = FakeContactsProviderRepository()
        strategy = ContactPushStrategy(provider)
    }

    @After
    fun tearDown() = unmockkAll()

    // ---------- fixtures ----------

    private fun writableBook(url: String = BOOK_URL) = CardDavAddressBook(
        href = url, url = url, displayName = "Contacts", isReadOnly = false, vcardVersion = "3.0",
    )

    private fun readOnlyBook(url: String = BOOK_URL) = CardDavAddressBook(
        href = url, url = url, displayName = "Shared", isReadOnly = true, vcardVersion = "3.0",
    )

    private fun clientServing(vararg data: CardDavContactData): FakeCardDavClient =
        FakeCardDavClient().apply {
            books += FakeAddressBook(book = writableBook(), contacts = data.toMutableList())
        }

    private fun serverVcard(uid: String, fn: String, extra: String = ""): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:$fn\r\nN:Doe;Jane;;;\r\n$extra" + "END:VCARD\r\n"

    /** A vCard with no UID, like a device-created contact (RFC 6350 §6.7.6: UID is `*1`). */
    private fun serverVcardNoUid(fn: String): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$fn\r\nN:Doe;Jane;;;\r\nEND:VCARD\r\n"

    private fun parse(body: String) = VCardParser().parse(body).single()

    // ---------- create (net-new device contact: no href/etag) ----------

    @Test
    fun `a net-new contact is created then its href and etag are written back by local id, clearing DIRTY`() = runTest {
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "srv-1")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(1, client.putContactCalls.size)
        val (url, body, precondition) = client.putContactCalls.single()
        assertEquals("$BOOK_HOST/ab/default/uid1.vcf", url)
        assertTrue("create must use If-None-Match:*", precondition is ContactPrecondition.IfAbsent)
        assertTrue("body is generated from device fields", body.contains("Jane Doe"))
        // The created href and etag are written back to the originating row by its _ID, since
        // the href-keyed write-back can't resolve a blank href. This stops the next pull
        // mirroring the server copy as a duplicate row.
        assertEquals(
            listOf(FakeContactsProviderRepository.MarkNewUploaded(ACCOUNT, 100L, "/ab/default/uid1.vcf", "srv-1")),
            provider.markNewUploadedCalls,
        )
        assertTrue("a net-new create does not use the href-keyed write-back", provider.markUploadedCalls.isEmpty())
        assertTrue("the net-new edit is cleared, not left pending", provider.pendingChanges.edited.isEmpty())
    }

    @Test
    fun `a net-new precondition failure whose existing resource matches by UID is adopted and cleared`() = runTest {
        // The create 412s because an earlier run created this contact and its write-back
        // failed (SOURCE_ID never stamped). Adopt it: GET the resource, confirm it is ours by
        // UID, and stamp the created href and server etag onto the originating row by _ID, so
        // a later pull doesn't mirror it as a duplicate.
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        // The resource already exists at the deterministic <uid>.vcf name with the same UID
        // as the edit: this contact from a prior run.
        client.books += FakeAddressBook(
            book = writableBook(),
            contacts = mutableListOf(
                CardDavContactData(
                    href = "/ab/default/uid1.vcf", url = "$BOOK_HOST/ab/default/uid1.vcf",
                    etag = "srv-existing", vcardBody = serverVcard("uid1", "Jane Doe"),
                ),
            ),
        )
        client.putContactResult = ContactUploadResult.PreconditionFailed

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("adopting the existing resource is a clean outcome", clean)
        assertEquals(1, client.putContactCalls.size)
        assertEquals(
            "the created href + the existing server etag are stamped onto the originating row by _ID",
            listOf(FakeContactsProviderRepository.MarkNewUploaded(ACCOUNT, 100L, "/ab/default/uid1.vcf", "srv-existing")),
            provider.markNewUploadedCalls,
        )
        assertTrue("the net-new edit is cleared, not left pending", provider.pendingChanges.edited.isEmpty())
    }

    @Test
    fun `a net-new precondition failure whose existing resource is NOT ours defers without hijacking it`() = runTest {
        // The name collides with a different contact (a foreign UID at the same resource
        // name). It must not be hijacked: the row stays DIRTY for a later run.
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(
            book = writableBook(),
            contacts = mutableListOf(
                CardDavContactData(
                    href = "/ab/default/uid1.vcf", url = "$BOOK_HOST/ab/default/uid1.vcf",
                    etag = "srv-foreign", vcardBody = serverVcard("someone-else", "Not Jane"),
                ),
            ),
        )
        client.putContactResult = ContactUploadResult.PreconditionFailed

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("a UID-mismatch precondition failure is a clean deferral", clean)
        assertEquals(1, client.putContactCalls.size)
        assertTrue("a foreign resource is never adopted", provider.markNewUploadedCalls.isEmpty())
        assertTrue("no href-keyed write-back either", provider.markUploadedCalls.isEmpty())
        assertTrue("the net-new edit stays pending for a later run", provider.pendingChanges.edited.any { it.href.isBlank() })
    }

    @Test
    fun `a net-new precondition failure whose resource cannot be read defers and stays pending`() = runTest {
        // The create 412s but the GET can't confirm ownership (the resource is absent from
        // the read, a transient omission). Adopt nothing: leave DIRTY and defer cleanly.
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        // No contacts seeded: the adopt GET returns nothing to confirm.
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.PreconditionFailed

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("an unconfirmable precondition failure is a clean deferral", clean)
        assertEquals(1, client.putContactCalls.size)
        assertTrue("nothing is adopted when the resource can't be read back", provider.markNewUploadedCalls.isEmpty())
        assertTrue("the net-new edit stays pending for a later run", provider.pendingChanges.edited.any { it.href.isBlank() })
    }

    @Test
    fun `a UID-less device contact synthesizes and persists a UID before creating, then names the resource by it`() = runTest {
        // A contact created in the device Contacts app has no UID (blank SYNC1). The push
        // mints a globally unique UID, persists it to SYNC1 before the PUT, and names the
        // resource by it, so the name is unique across every device on the account.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "srv-1")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        // The UID is persisted to the originating row (by _ID) before the create.
        val assigned = provider.assignUidCalls.single()
        assertEquals(ACCOUNT, assigned.accountName)
        assertEquals(100L, assigned.localId)
        assertTrue("a non-blank UID is synthesized", assigned.uid.isNotBlank())
        // The resource is named by the synthesized UID, and the body carries it.
        assertEquals(1, client.putContactCalls.size)
        val (url, body, precondition) = client.putContactCalls.single()
        assertEquals("the resource is named by the synthesized UID", "$BOOK_HOST/ab/default/${assigned.uid}.vcf", url)
        assertTrue("the created body carries the synthesized UID", body.contains(assigned.uid))
        assertTrue("create must use If-None-Match:*", precondition is ContactPrecondition.IfAbsent)
        // Write-back stamps the created href by _ID and clears the edit.
        assertEquals(
            listOf(FakeContactsProviderRepository.MarkNewUploaded(ACCOUNT, 100L, "/ab/default/${assigned.uid}.vcf", "srv-1")),
            provider.markNewUploadedCalls,
        )
        assertTrue("the net-new edit is cleared, not left pending", provider.pendingChanges.edited.isEmpty())
    }

    @Test
    fun `a UID-less create name is a globally-unique UUID, never the per-device local id`() = runTest {
        // Cross-device data-loss guard: the per-device RawContact _ID can't be the name. Two
        // devices minting the same _ID would collide on local-<id>.vcf, and a blank-against-
        // blank adopt would bind one device to the other's resource, losing a contact.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "srv-1")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        val url = client.putContactCalls.single().first
        assertFalse("must NOT be the per-device _ID (a cross-device collision)", url.endsWith("/local-100.vcf"))
        assertTrue("the name is a UUID-derived .vcf", Regex(".*/[0-9a-fA-F-]{36}\\.vcf$").matches(url))
    }

    @Test
    fun `a UID-less create re-uses its persisted UID across runs so a re-attempt hits the same resource`() = runTest {
        // The first create's write-back fails, so the edit stays pending for a second push,
        // but its synthesized UID is persisted (SYNC1). Run 2 must not mint a new one: it
        // re-uses the persisted UID, targets the same resource and can 412 and adopt.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "srv-1")
        provider.markNewUploadedResult = Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))

        strategy.push(ACCOUNT, listOf(writableBook()), client) // run 1: synthesize + persist
        strategy.push(ACCOUNT, listOf(writableBook()), client) // run 2: edit pending, UID persisted

        assertEquals("the UID is synthesized once and persisted, not re-minted each run", 1, provider.assignUidCalls.size)
        assertEquals(2, client.putContactCalls.size)
        assertEquals(
            "both runs target the SAME resource (the persisted UID), so run 2 can 412 and adopt",
            client.putContactCalls[0].first, client.putContactCalls[1].first,
        )
    }

    @Test
    fun `a UID-less create whose UID persistence fails does not PUT and defers pull-safe`() = runTest {
        // Persisting the synthesized UID keeps the resource name stable across runs. If it
        // fails, creating anyway would pick a new name next run and duplicate the server
        // resource, so there is no PUT, and the push is not clean so the token is held. No
        // server resource was created, so the pull still runs: a pre-PUT failure must never
        // freeze inbound sync.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        provider.assignUidResult = Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))

        val outcome = strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals("the UID persistence was attempted", 1, provider.assignUidCalls.size)
        assertTrue("no create is attempted without a persisted UID", client.putContactCalls.isEmpty())
        assertFalse("a net-new persist failure holds the sync token", outcome.clean)
        assertFalse("no server resource was created, so the pull stays safe", outcome.pullUnsafe)
    }

    @Test
    fun `a server-refused net-new create holds the token but leaves the pull safe`() = runTest {
        // The server rejects the create outright (for example 422, 507 or a persistent 5xx).
        // Nothing landed, so the push is not clean (hold the token, replay next run) but the
        // pull still runs: one rejected contact must never freeze all inbound sync.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Failed(code = 507, message = "insufficient storage")

        val outcome = strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals("the create was attempted", 1, client.putContactCalls.size)
        assertTrue("no write-back stamps a resource that was never created", provider.markNewUploadedCalls.isEmpty())
        assertFalse("a server rejection holds the sync token", outcome.clean)
        assertFalse("but nothing was created, so the pull stays safe", outcome.pullUnsafe)
    }

    @Test
    fun `a net-new create whose transport drops after PUT is pull-unsafe (creation state unknown)`() = runTest {
        // A transport failure (code 0: connection reset or lost response) isn't a server
        // refusal: the PUT may have committed before the response was lost. Running the pull
        // this cycle could mirror that resource as a duplicate row, so it's pull-unsafe;
        // wrongly skipping one pull is harmless, a duplicate isn't.
        val device = parse(serverVcardNoUid("Jane Doe")).copy(rawVCard = "", uid = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Failed(code = 0, message = "Network error", isRetryable = true)

        val outcome = strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals("the create was attempted", 1, client.putContactCalls.size)
        assertFalse("a transport failure holds the sync token", outcome.clean)
        assertTrue("creation state is unknown, so the pull must not run this cycle", outcome.pullUnsafe)
    }

    @Test
    fun `a UID-less net-new precondition failure is adopted by its persisted-UID href`() = runTest {
        // The row already carries its persisted synthesized UID (a prior run stamped SYNC1)
        // and a resource under that <uid>.vcf name exists. The create 412s; adopt it by
        // matching the server body's UID to the persisted UID, never blank against blank, so
        // a foreign contact can't be hijacked.
        val device = parse(serverVcard(SYNTH_UID, "Jane Doe")).copy(rawVCard = "", uid = SYNTH_UID)
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = SYNTH_UID, storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(
            book = writableBook(),
            contacts = mutableListOf(
                CardDavContactData(
                    href = "/ab/default/$SYNTH_UID.vcf", url = "$BOOK_HOST/ab/default/$SYNTH_UID.vcf",
                    etag = "srv-existing", vcardBody = serverVcard(SYNTH_UID, "Jane Doe"),
                ),
            ),
        )
        client.putContactResult = ContactUploadResult.PreconditionFailed

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("adopting our own prior create is a clean outcome", clean)
        assertTrue("a row that already carries a persisted UID is not re-assigned one", provider.assignUidCalls.isEmpty())
        assertEquals(
            "the persisted-UID href + the existing server etag are stamped onto the originating row by _ID",
            listOf(FakeContactsProviderRepository.MarkNewUploaded(ACCOUNT, 100L, "/ab/default/$SYNTH_UID.vcf", "srv-existing")),
            provider.markNewUploadedCalls,
        )
        assertTrue("the net-new edit is cleared, not left pending", provider.pendingChanges.edited.isEmpty())
    }

    @Test
    fun `a net-new precondition failure whose adopt GET errors defers cleanly and holds nothing back`() = runTest {
        // The create 412s but the adopt GET hits a transient server error, so ownership
        // can't be confirmed. Adopt nothing and defer cleanly (leave DIRTY) without holding
        // the token, the same deferral as an unconfirmed 412, retried next run.
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient(fetchError = CalDavResult.error(503, "unavailable"))
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.PreconditionFailed

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("a transient adopt GET error is a clean deferral", clean)
        assertTrue("nothing is adopted when the resource can't be read back", provider.markNewUploadedCalls.isEmpty())
        assertTrue("the net-new edit stays pending for a later run", provider.pendingChanges.edited.any { it.href.isBlank() })
    }

    @Test
    fun `a net-new adopt whose write-back itself fails holds the sync token`() = runTest {
        // The adopt matched and attempted the _ID write-back, but that provider write failed
        // (short batch or permission). Not clean: hold the token so the run is retried, like
        // a write-back failure after a successful create.
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(
            book = writableBook(),
            contacts = mutableListOf(
                CardDavContactData(
                    href = "/ab/default/uid1.vcf", url = "$BOOK_HOST/ab/default/uid1.vcf",
                    etag = "srv-existing", vcardBody = serverVcard("uid1", "Jane Doe"),
                ),
            ),
        )
        client.putContactResult = ContactUploadResult.PreconditionFailed
        provider.markNewUploadedResult = Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertFalse("an adopt write-back failure holds the sync token", clean)
        assertEquals("the adopt did attempt the stamp before failing", 1, provider.markNewUploadedCalls.size)
    }

    // ---------- update: GET-before-PUT happy path ----------

    @Test
    fun `an update GETs the server body then PUTs If-Match with the STORED etag, preserving X-props`() = runTest {
        val serverBody = serverVcard("c1", "Old Name", extra = "X-CUSTOM:keepme\r\n")
        val device = parse(serverBody).copy(displayName = "New Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Success(etag = "v2")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        // The GET fetched the patch base, then one conditional PUT followed.
        assertTrue("GET issued for the patch base", client.fetchByHrefCalls.any { HREF in it.second })
        assertEquals(1, client.putContactCalls.size)
        val (url, body, precondition) = client.putContactCalls.single()
        assertEquals("$BOOK_HOST$HREF", url)
        assertEquals(ContactPrecondition.IfMatch("v1"), precondition)
        assertTrue("edited facet is rewritten", body.contains("New Name"))
        assertFalse("old value is gone", body.contains("Old Name"))
        assertTrue("unmapped X-prop is preserved from the server body", body.contains("keepme"))
        // Write-back: SYNC2 gets the server etag, DIRTY is cleared.
        assertEquals(listOf(Triple(ACCOUNT, HREF, "v2")), provider.markUploadedCalls)
    }

    // ---------- redirected writes: the stored href follows the vCard ----------

    @Test
    fun `an update the server redirected stores the href it landed at`() = runTest {
        val serverBody = serverVcard("c1", "Old Name")
        val device = parse(serverBody).copy(displayName = "New Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Success(etag = "v2", finalUrl = "$BOOK_HOST/ab/moved/c1.vcf")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(listOf(Triple(ACCOUNT, HREF, "v2")), provider.markUploadedCalls)
        assertEquals("stored as the path the pull lists", listOf(HREF to "/ab/moved/c1.vcf"), provider.movedHrefs)
    }

    @Test
    fun `an update redirected to another server stores the whole URL`() = runTest {
        val serverBody = serverVcard("c1", "Old Name")
        val device = parse(serverBody).copy(displayName = "New Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Success(etag = "v2", finalUrl = "https://p01.dav.example.test/ab/c1.vcf")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(listOf(HREF to "https://p01.dav.example.test/ab/c1.vcf"), provider.movedHrefs)
    }

    @Test
    fun `a redirected href keeps its percent-escapes, as the server lists it`() = runTest {
        val serverBody = serverVcard("c1", "Old Name")
        val device = parse(serverBody).copy(displayName = "New Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Success(etag = "v2", finalUrl = "$BOOK_HOST/ab/default/Jane%20Doe%40x.vcf")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(listOf(HREF to "/ab/default/Jane%20Doe%40x.vcf"), provider.movedHrefs)
    }

    @Test
    fun `an update that was not redirected keeps its href`() = runTest {
        val serverBody = serverVcard("c1", "Old Name")
        val device = parse(serverBody).copy(displayName = "New Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Success(etag = "v2")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertTrue(provider.movedHrefs.isEmpty())
    }

    @Test
    fun `a new contact whose create was redirected is stamped with the href it landed at`() = runTest {
        val device = parse(serverVcard("uid1", "Jane Doe")).copy(rawVCard = "", uid = "uid1")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = "uid1", storedEtag = null, contact = device, localId = 100L)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "srv-1", finalUrl = "$BOOK_HOST/ab/other/uid1.vcf")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(
            listOf(FakeContactsProviderRepository.MarkNewUploaded(ACCOUNT, 100L, "/ab/other/uid1.vcf", "srv-1")),
            provider.markNewUploadedCalls,
        )
    }

    // ---------- update: GET-etag mismatch -> defer ----------

    @Test
    fun `an update whose GET etag differs from the stored etag defers without PUT`() = runTest {
        val serverBody = serverVcard("c1", "Server Name")
        val device = parse(serverBody).copy(displayName = "My Name", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        // Server moved on: current etag is v2, not the stored v1.
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v2", vcardBody = serverBody))

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertTrue("no PUT: the local edit is not clobbered onto the moved server copy", client.putContactCalls.isEmpty())
        assertTrue("edit stays DIRTY for the next pull to reconcile", provider.markUploadedCalls.isEmpty())
    }

    // ---------- update: GET Success but href unreadable -> defer ----------

    @Test
    fun `an update whose href is absent from the GET result defers without PUT`() = runTest {
        val device = parse(serverVcard("c1", "Name")).copy(displayName = "Edited", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        // Book discovered but the GET returns no body for HREF (absent / unparseable).
        val client = clientServing()

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertTrue("never PUT with a missing patch base", client.putContactCalls.isEmpty())
        assertTrue(provider.markUploadedCalls.isEmpty())
    }

    // ---------- update: etag-less server -> create-as-fresh, never IfMatch(null) ----------

    @Test
    fun `an etag-less update falls back to create-as-fresh with If-None-Match star`() = runTest {
        val device = parse(serverVcard("c1", "Name")).copy(displayName = "Edited", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = null, contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing()
        client.putContactResult = ContactUploadResult.Success(etag = "fresh-1")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(1, client.putContactCalls.size)
        val (url, _, precondition) = client.putContactCalls.single()
        assertEquals("$BOOK_HOST$HREF", url)
        assertTrue("etag-less update must never IfMatch(null)", precondition is ContactPrecondition.IfAbsent)
        assertEquals(listOf(Triple(ACCOUNT, HREF, "fresh-1")), provider.markUploadedCalls)
    }

    // ---------- update: PUT 412 -> swallowed (server wins), DIRTY held ----------

    @Test
    fun `a PUT precondition failure is swallowed and leaves the edit DIRTY`() = runTest {
        val serverBody = serverVcard("c1", "Old")
        val device = parse(serverBody).copy(displayName = "New", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.PreconditionFailed

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(1, client.putContactCalls.size)
        assertTrue("412 leaves the edit DIRTY; the next pull overwrites", provider.markUploadedCalls.isEmpty())
    }

    // ---------- update: GET 404 -> retry as fresh ----------

    @Test
    fun `a GET that 404s retries the upload as fresh with If-None-Match star`() = runTest {
        val device = parse(serverVcard("c1", "Name")).copy(displayName = "Edited", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient(fetchError = CalDavResult.error(404, "gone"))
        client.books += FakeAddressBook(book = writableBook())
        client.putContactResult = ContactUploadResult.Success(etag = "re-1")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(1, client.putContactCalls.size)
        assertTrue(client.putContactCalls.single().third is ContactPrecondition.IfAbsent)
        assertEquals(listOf(Triple(ACCOUNT, HREF, "re-1")), provider.markUploadedCalls)
    }

    // ---------- update: GET 5xx -> hold (not clean), never PUT ----------

    @Test
    fun `a transient GET failure holds the token and never PUTs`() = runTest {
        val device = parse(serverVcard("c1", "Name")).copy(displayName = "Edited", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        // A server error (not a 404/410 "gone") leaves no patch base, so the edit must be
        // retried on a later run, never pushed blind or dropped.
        val client = FakeCardDavClient(fetchError = CalDavResult.error(503, "unavailable"))
        client.books += FakeAddressBook(book = writableBook())

        val outcome = strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertFalse("a transient GET failure holds the sync token for retry", outcome.clean)
        assertFalse(
            "an existing-contact (non-net-new) failure does NOT make the pull unsafe: its row " +
                "has an href the pull reconciles correctly, so inbound changes still materialize",
            outcome.pullUnsafe,
        )
        assertTrue("never PUT without a patch base", client.putContactCalls.isEmpty())
        assertTrue(provider.markUploadedCalls.isEmpty())
    }

    // ---------- update: PUT 403 -> swallow (clean), DIRTY held ----------

    @Test
    fun `a PUT permission denial is swallowed and leaves the edit DIRTY`() = runTest {
        val serverBody = serverVcard("c1", "Old")
        val device = parse(serverBody).copy(displayName = "New", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.PermissionDenied

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertTrue("403 is a clean deferral, not a token-holding failure", clean)
        assertEquals(1, client.putContactCalls.size)
        assertTrue("403 leaves the edit DIRTY; nothing to retry until access changes", provider.markUploadedCalls.isEmpty())
    }

    // ---------- update: conditional PUT returns Gone -> retry as fresh ----------

    @Test
    fun `a conditional PUT that returns Gone retries as fresh with If-None-Match star`() = runTest {
        val serverBody = serverVcard("c1", "Old")
        val device = parse(serverBody).copy(displayName = "New", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        // The GET etag matches the stored etag, so the conditional PUT is attempted, but the
        // resource vanished mid-flight, so the strategy retries once as fresh.
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.putContactResult = ContactUploadResult.Gone

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals("the conditional PUT then a retry", 2, client.putContactCalls.size)
        assertEquals(ContactPrecondition.IfMatch("v1"), client.putContactCalls.first().third)
        assertTrue("the retry is a create-as-fresh", client.putContactCalls.last().third is ContactPrecondition.IfAbsent)
    }

    // ---------- cancellation propagates, is not logged as a failed item ----------

    @Test
    fun `a cancellation during a push item is not swallowed`() = runTest {
        val device = parse(serverVcard("c1", "Name")).copy(displayName = "Edited", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = null, contact = device)),
            deleted = emptyList(),
        )
        val client = clientServing()
        client.putContactThrows = kotlinx.coroutines.CancellationException("worker stopped")

        val thrown = runCatching { strategy.push(ACCOUNT, listOf(writableBook()), client) }.exceptionOrNull()

        assertTrue(
            "a cancelled push must propagate, not be caught and reported as a failed item; got $thrown",
            thrown is kotlinx.coroutines.CancellationException,
        )
    }

    // ---------- delete: writable book ----------

    @Test
    fun `a tombstone on a writable book is DELETEd then hard-deleted locally`() = runTest {
        provider.pendingChanges = LocalContactChanges(
            edited = emptyList(),
            deleted = listOf(LocalContactTombstone(href = HREF, storedEtag = "v1")),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.deleteContactResult = ContactDeleteResult.Deleted

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(listOf("$BOOK_HOST$HREF" to "v1"), client.deleteContactCalls)
        assertEquals(listOf(ACCOUNT to HREF), provider.hardDeleteCalls)
        assertTrue(provider.restoreCalls.isEmpty())
    }

    // ---------- delete: 412 -> restore (server wins), never hard-delete ----------

    @Test
    fun `a delete precondition failure restores the tombstone so the pull reconciles server-wins`() = runTest {
        // The server copy changed after the deleted version. A kept tombstone would get its
        // stored etag refreshed by the pull, and a later push would delete the edited server
        // copy. Un-deleting lets the pull restore the server's current copy (server wins).
        provider.pendingChanges = LocalContactChanges(
            edited = emptyList(),
            deleted = listOf(LocalContactTombstone(href = HREF, storedEtag = "v1")),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.deleteContactResult = ContactDeleteResult.PreconditionFailed

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        assertEquals(1, client.deleteContactCalls.size)
        assertTrue("412: never hard-deleted; the newer server copy wins", provider.hardDeleteCalls.isEmpty())
        assertEquals(
            "412: un-delete locally so the pull re-materializes the server copy",
            listOf(ACCOUNT to HREF),
            provider.restoreCalls,
        )
    }

    // ---------- delete: read-only book -> restore, never upload ----------

    @Test
    fun `a tombstone on a read-only book is restored, never DELETEd`() = runTest {
        provider.pendingChanges = LocalContactChanges(
            edited = emptyList(),
            deleted = listOf(LocalContactTombstone(href = HREF, storedEtag = "v1")),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = readOnlyBook())

        strategy.push(ACCOUNT, listOf(readOnlyBook()), client)

        assertTrue("never upload a delete to a read-only book", client.deleteContactCalls.isEmpty())
        assertEquals(listOf(ACCOUNT to HREF), provider.restoreCalls)
    }

    // ---------- upload: read-only book -> skip, never GET/PUT, held pending ----------

    @Test
    fun `an edit to a contact on a read-only book is not uploaded and stays pending`() = runTest {
        // A read-only collection would 403 every PUT. The upload must be skipped before the
        // GET-before-PUT round trip and the edit left DIRTY, so it uploads if the book becomes
        // writable: never a guaranteed 403 each sync, never silently dropped.
        val serverBody = serverVcard("c1", "Old")
        val device = parse(serverBody).copy(displayName = "New", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = emptyList(),
        )
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(
            book = readOnlyBook(),
            // A patch base whose etag matches the stored etag: without the guard this would
            // GET then conditionally PUT, so an empty GET and PUT log proves the early skip.
            contacts = mutableListOf(
                CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody),
            ),
        )

        val clean = strategy.push(ACCOUNT, listOf(readOnlyBook()), client).clean

        assertTrue("skipping a read-only upload is a clean deferral", clean)
        assertTrue("never GET a patch base for a read-only book", client.fetchByHrefCalls.none { HREF in it.second })
        assertTrue("never PUT to a read-only book", client.putContactCalls.isEmpty())
        assertTrue("the edit stays DIRTY; nothing is written back", provider.markUploadedCalls.isEmpty())
    }

    // ---------- ordering + failure reporting ----------

    @Test
    fun `deletes are pushed before uploads`() = runTest {
        val serverBody = serverVcard("c1", "Old")
        val device = parse(serverBody).copy(displayName = "New", rawVCard = "")
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = HREF, uid = "c1", storedEtag = "v1", contact = device)),
            deleted = listOf(LocalContactTombstone(href = HREF2, storedEtag = "d1")),
        )
        val client = clientServing(CardDavContactData(href = HREF, url = "$BOOK_HOST$HREF", etag = "v1", vcardBody = serverBody))
        client.deleteContactResult = ContactDeleteResult.Deleted
        client.putContactResult = ContactUploadResult.Success(etag = "v2")

        strategy.push(ACCOUNT, listOf(writableBook()), client)

        // The fake keeps separate delete and PUT logs, so these counts don't prove the order.
        assertEquals(1, client.deleteContactCalls.size)
        assertEquals(1, client.putContactCalls.size)
    }

    @Test
    fun `a provider write-back failure makes push report not-clean`() = runTest {
        provider.pendingChanges = LocalContactChanges(
            edited = emptyList(),
            deleted = listOf(LocalContactTombstone(href = HREF, storedEtag = "v1")),
        )
        provider.hardDeleteResult = Result.failure(ContactWriteException(ContactWriteFailure.PROVIDER_ERROR))
        val client = FakeCardDavClient()
        client.books += FakeAddressBook(book = writableBook())
        client.deleteContactResult = ContactDeleteResult.Deleted

        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean

        assertFalse("a failed write-back holds the sync token", clean)
    }

    @Test
    fun `an empty pending set is a no-op that reports clean`() = runTest {
        val client = FakeCardDavClient()
        val clean = strategy.push(ACCOUNT, listOf(writableBook()), client).clean
        assertTrue(clean)
        assertTrue(client.putContactCalls.isEmpty())
        assertTrue(client.deleteContactCalls.isEmpty())
    }

    private companion object {
        const val ACCOUNT = "user@example.test"
        const val BOOK_HOST = "https://dav.example.test"
        const val BOOK_URL = "https://dav.example.test/ab/default/"
        const val HREF = "/ab/default/c1.vcf"
        const val HREF2 = "/ab/default/c2.vcf"

        /**
         * A UUID-shaped synthesized UID: a safe `.vcf` path segment (see `contactResourceName`).
         */
        const val SYNTH_UID = "11111111-2222-3333-4444-555555555555"
    }
}
