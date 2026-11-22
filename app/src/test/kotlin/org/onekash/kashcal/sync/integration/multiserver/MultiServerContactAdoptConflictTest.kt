package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.ReadContact
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.contacts.ContactPushStrategy
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.contacts.LocalContactChanges
import org.onekash.kashcal.sync.contacts.LocalContactEdit
import org.onekash.kashcal.sync.contacts.sameAsDevice
import org.onekash.vcard.VCardWriter
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.StructuredName
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks that a device-created contact whose create reply was lost never overwrites another
 * client's later edit (RFC 6352 section 9.2, avoiding lost updates), live against every
 * configured CardDAV server.
 *
 * The scenario:
 * 1. The device creates a contact. The PUT reaches the server and commits, but the reply is
 *    lost: a [CardDavClient] wrapper sends the real request and then reports a transport failure
 *    (code 0), the network event a dropped connection after the server's commit produces. The
 *    device row stays pending with no href.
 * 2. A second client edits the stored contact on the server with `If-Match` on its current etag.
 * 3. The device syncs again: its create gets 412, and the push adopts the resource it proves
 *    ours by UID. It syncs once more.
 * 4. The server copy must still hold the second client's edit, and the device must end with
 *    it: the adopt finds the copies differ and writes the server copy onto the row.
 *
 * A second case runs the same lost reply with no remote edit and reports, per server, whether
 * the adopt found the stored copy equal to what the device sent; either way server and device
 * must end with the same fields. The device side is the contact as the push sends it, not one
 * read back from Android rows, so device-side normalization is not measured here.
 *
 * Only the Android Contacts Provider is faked, by the canonical [FakeContactsProviderRepository];
 * every server request and response is live. A server that refuses the create, the remote edit,
 * or answers the repeated create with something other than 412 can't reach the adopt and is
 * skipped. A server that rewrites the UID never matches the adopt and is reported, not failed.
 *
 * All seed data is synthetic (`@example.test`), and logs and messages carry only the server name
 * and synthetic values, never an href or a body.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerContactAdoptConflictTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerContactAdoptConflictTest(private val config: CardDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val ACCOUNT = "adopt-conflict@example.test"
        private const val LOCAL_ID = 710L
        private const val UID = "kashcal-adopt-conflict-remote-edit"
        private const val DEVICE_NOTE = "device"
        private const val REMOTE_NOTE = "remote"
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var reader: CardDavContactReader
    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var strategy: ContactPushStrategy

    /** One case's server, address book, resource and the device's pending contact. */
    private class Setup(val c: CardDavClient, val book: CardDavAddressBook, val resourceUrl: String, val device: Contact)

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
            reader = CardDavContactReader(it.first)
        }
        provider = FakeContactsProviderRepository()
        strategy = ContactPushStrategy(provider)
    }

    @Test
    fun `a remote edit after a lost create reply survives the adopt`() = runBlocking {
        val s = prepare(UID, LOCAL_ID)
        try {
            // 1. The create commits on the server but its reply is lost.
            strategy.push(ACCOUNT, listOf(s.book), lostFirstCreateReply(s.c))
            assumeTrue("${config.name}: the create did not commit", currentEtag(s) != null)
            assumeTrue(
                "${config.name}: the device row should still be pending without an href",
                provider.pendingChanges.edited.singleOrNull()?.href == "",
            )

            // 2. A second client edits the stored contact.
            val stored = readStored(s, UID)
            assumeTrue("${config.name}: the created contact can't be read back", stored != null)
            val remoteBody = VCardWriter().write(stored!!.contact.copy(notes = listOf(REMOTE_NOTE)), s.book.vcardVersion)
            val remoteEdit = s.c.putContact(s.resourceUrl, remoteBody, ContactPrecondition.IfMatch(stored.etag.orEmpty()))
            assumeTrue(
                "${config.name}: the remote edit was refused (${remoteEdit::class.simpleName})",
                remoteEdit is ContactUploadResult.Success,
            )

            // 3. Two more device syncs: the repeated create must hit 412 and adopt.
            val puts = mutableListOf<ContactUploadResult>()
            strategy.push(ACCOUNT, listOf(s.book), recordingPuts(s.c, puts))
            assumeTrue(
                "${config.name}: the repeated create did not get 412 (${puts.firstOrNull()?.let { it::class.simpleName }})",
                puts.firstOrNull() == ContactUploadResult.PreconditionFailed,
            )
            val adopted = provider.markNewUploadedCalls.isNotEmpty()
            if (!adopted) println("=== ${config.name}: adopt never matched (UID rewritten?), lost update not reachable ===")
            strategy.push(ACCOUNT, listOf(s.book), recordingPuts(s.c, puts))

            // 4. The second client's edit must survive, and the device must end with it.
            val after = readStored(s, UID)
            println(
                "=== ${config.name}: adopted=$adopted, server notes after=${after?.contact?.notes}, " +
                    "device PUTs after the adopt=${puts.size - 1} ===",
            )
            assertEquals(
                "${config.name}: the device overwrote another client's edit (lost update)",
                listOf(REMOTE_NOTE),
                after?.contact?.notes,
            )
            if (adopted) {
                assertEquals(
                    "${config.name}: the device should end with the server copy",
                    listOf(REMOTE_NOTE),
                    deviceCopy(s)?.notes,
                )
            }
        } finally {
            deleteIfPresent(s)
        }
    }

    @Test
    fun `a lost create reply with no remote edit leaves server and device holding the same fields`() = runBlocking {
        val uid = "$UID-unchanged"
        val s = prepare(uid, LOCAL_ID + 1)
        try {
            strategy.push(ACCOUNT, listOf(s.book), lostFirstCreateReply(s.c))
            assumeTrue("${config.name}: the create did not commit", currentEtag(s) != null)

            val puts = mutableListOf<ContactUploadResult>()
            strategy.push(ACCOUNT, listOf(s.book), recordingPuts(s.c, puts))
            assumeTrue(
                "${config.name}: the repeated create did not get 412 (${puts.firstOrNull()?.let { it::class.simpleName }})",
                puts.firstOrNull() == ContactUploadResult.PreconditionFailed,
            )
            assumeTrue("${config.name}: adopt never matched", provider.markNewUploadedCalls.isNotEmpty())
            val replaced = provider.replaceCalls.isNotEmpty()
            strategy.push(ACCOUNT, listOf(s.book), recordingPuts(s.c, puts))

            val server = readStored(s, uid)?.contact
            val outcome = if (replaced) "different (replaced from the server)" else "same (marked synced)"
            println("=== ${config.name}: unchanged contact after a lost reply reads as $outcome; extra PUTs=${puts.size - 1} ===")
            assumeTrue("${config.name}: the stored contact can't be read back", server != null)
            assertTrue(
                "${config.name}: server and device fields differ after the adopt",
                sameAsDevice(deviceCopy(s) ?: s.device, server!!),
            )
            assertTrue("${config.name}: nothing should be left pending", provider.pendingChanges.edited.isEmpty())
        } finally {
            deleteIfPresent(s)
        }
    }

    /**
     * Skips unless the server is configured, reachable and has a writable book, clears any
     * leftover resource for [uid], and seeds the device with one pending net-new contact.
     */
    private suspend fun prepare(uid: String, localId: Long): Setup {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue("${config.name}: server unreachable", CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val c = client!!
        val book = resolveWritableBook(c, creds!!)
        assumeTrue("${config.name}: no writable address book", book != null)
        val device = Contact(
            version = book!!.vcardVersion,
            uid = uid,
            structuredName = StructuredName(family = "Conflict", given = "Adopt"),
            displayName = "Adopt Conflict",
            notes = listOf(DEVICE_NOTE),
            rawVCard = "",
        )
        val s = Setup(c, book, book.url.trimEnd('/') + "/" + contactResourceName(uid), device)
        deleteIfPresent(s)
        assumeTrue("${config.name}: a leftover resource survived the delete-before", currentEtag(s) == null)
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(
                LocalContactEdit(href = "", uid = uid, storedEtag = null, contact = device, rawContactVersion = 7L, localId = localId),
            ),
            deleted = emptyList(),
        )
        return s
    }

    /**
     * Sends the first create for real, then reports a transport failure as if its reply were
     * lost.
     */
    private fun lostFirstCreateReply(c: CardDavClient): CardDavClient {
        var lost = false
        return object : CardDavClient by c {
            override suspend fun putContact(
                resourceUrl: String,
                vcardBody: String,
                precondition: ContactPrecondition,
            ): ContactUploadResult {
                val result = c.putContact(resourceUrl, vcardBody, precondition)
                if (precondition is ContactPrecondition.IfAbsent && !lost) {
                    lost = true
                    return ContactUploadResult.Failed(code = 0, message = "reply lost", isRetryable = true)
                }
                return result
            }
        }
    }

    /** Records every PUT result into [sink]. */
    private fun recordingPuts(c: CardDavClient, sink: MutableList<ContactUploadResult>): CardDavClient =
        object : CardDavClient by c {
            override suspend fun putContact(
                resourceUrl: String,
                vcardBody: String,
                precondition: ContactPrecondition,
            ): ContactUploadResult = c.putContact(resourceUrl, vcardBody, precondition).also { sink += it }
        }

    /**
     * The device's copy at the resource: the server copy the adopt wrote, else the pending
     * edit.
     */
    private fun deviceCopy(s: Setup): Contact? {
        val name = s.resourceUrl.substringAfterLast('/')
        provider.replaceCalls.flatten().lastOrNull { it.href.substringAfterLast('/') == name }
            ?.let { return it.mapped.contact }
        return provider.pendingChanges.edited.firstOrNull { it.href.substringAfterLast('/') == name }?.contact
    }

    private suspend fun readStored(s: Setup, uid: String): ReadContact? =
        listing(s)?.first?.let { href ->
            (reader.readContacts(s.book.url, listOf(href), s.book.vcardVersion) as? CalDavResult.Success)
                ?.data?.contacts?.firstOrNull { it.contact.uid == uid }
        }

    private suspend fun deleteIfPresent(s: Setup) {
        currentEtag(s)?.let { s.c.deleteContact(s.resourceUrl, it) }
    }

    private suspend fun resolveWritableBook(c: CardDavClient, cr: ServerCredentials): CardDavAddressBook? {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull() ?: return null
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return null
        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        return books.firstOrNull { !it.isReadOnly }
    }

    /** The book member named like [Setup.resourceUrl], as (href, etag), or null. */
    private suspend fun listing(s: Setup): Pair<String, String?>? {
        val listed = (s.c.listAllContactHrefs(s.book.url) as? CalDavResult.Success)?.data.orEmpty()
        val name = s.resourceUrl.substringAfterLast('/')
        return listed.firstOrNull { it.first.substringAfterLast('/') == name }
    }

    private suspend fun currentEtag(s: Setup) = listing(s)?.second
}
