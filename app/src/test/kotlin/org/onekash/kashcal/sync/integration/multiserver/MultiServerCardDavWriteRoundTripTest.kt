package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardWriter
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.StructuredName
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live write-path chaos reconcile across every configured CardDAV server. Unlike
 * the sibling [MultiServerCardDavFieldFidelityTest] (which seeds via a raw
 * authenticated PUT as test setup), this exercises the *application* write verb —
 * [CardDavClient.putContact] with a conditional header — then reads the resource
 * back through the production [CardDavContactReader] and asserts the mapped,
 * identity-shaping properties survive the server's store → serve round-trip.
 *
 * "Chaos reconcile": the assertions key on the neutral
 * [org.onekash.vcard.model.Contact] the reader maps, NOT on the raw wire bytes. A
 * server is free to downgrade what it stores — reorder or refold lines, drop an
 * X-property, re-serialize 3.0 vs 4.0, rewrite the resource href — as long as the
 * fields a user actually sees come back intact. Baikal and Cyrus (the passthrough
 * engines most likely to normalize on store) are in the matrix precisely to catch
 * a downgrade that loses a mapped field, which would be a real regression rather
 * than conformant server behavior.
 *
 * The body is entirely synthetic — RFC 6761 reserved `@example.test` and an
 * unassigned `+1-555-01xx` number — so no real person is contacted or exposed, and
 * the assertions key only on the write's own UID and its synthetic values.
 *
 * Each run cleans up after itself via the [CardDavClient.deleteContact] verb, so
 * the write is idempotent across repeated runs (a leftover from a prior run is
 * overwritten with `If-Match`, and a fresh resource is created with
 * `If-None-Match: *`).
 *
 * Skips (never fails) servers without credentials, unreachable, without CardDAV,
 * or with no writable address book to target.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavWriteRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavWriteRoundTripTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val WRITE_UID = "kashcal-write-roundtrip-0001"
        private const val EXP_FAMILY = "Writeback"
        private const val EXP_GIVEN = "Kashcal"
        private const val EXP_EMAIL = "writeback@example.test"
        private const val EXP_PHONE_DIGITS = "15550142"

        /** A known synthetic vCard 3.0 the app write verb uploads. */
        private val WRITE_BODY: String = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("UID:$WRITE_UID\r\n")
            append("FN:$EXP_GIVEN $EXP_FAMILY\r\n")
            append("N:$EXP_FAMILY;$EXP_GIVEN;;;\r\n")
            append("EMAIL;TYPE=HOME:$EXP_EMAIL\r\n")
            append("TEL;TYPE=CELL:+1-555-0142\r\n")
            append("END:VCARD\r\n")
        }
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var reader: CardDavContactReader

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
            reader = CardDavContactReader(it.first)
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    @Test
    fun `app write verb uploads a contact and every mapped field survives the round-trip`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to write into", book != null)

        val resourceUrl = book!!.url.trimEnd('/') + "/" + contactResourceName(WRITE_UID)

        // --- Write through the APP verb (conditional PUT), idempotent across runs ---
        val uploaded = when (val first = c.putContact(resourceUrl, WRITE_BODY, ContactPrecondition.IfAbsent)) {
            is ContactUploadResult.Success -> true
            // A leftover from a prior run: overwrite it with the version we hold.
            is ContactUploadResult.PreconditionFailed -> {
                val existingEtag = currentEtag(c, book.url, resourceUrl)
                existingEtag != null &&
                    c.putContact(resourceUrl, WRITE_BODY, ContactPrecondition.IfMatch(existingEtag)) is ContactUploadResult.Success
            }
            // The server rejected the write for a policy/permission reason (e.g. a
            // name-policy 401 or a read-only book that slipped the discovery gate) —
            // that is a server constraint, not a mapping regression, so skip.
            is ContactUploadResult.PermissionDenied,
            is ContactUploadResult.Gone,
            is ContactUploadResult.Failed -> false
        }
        assumeTrue("${config.name}: server would not accept the app write ($resourceUrl)", uploaded)

        try {
            // --- Read it back through the production reader ---
            val hrefs = collectHrefs(c, book.url)
            assumeTrue("${config.name}: no contact hrefs after the write", hrefs.isNotEmpty())
            val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
                ?.data?.contacts.orEmpty()

            val written = read.firstOrNull { it.contact.uid == WRITE_UID }?.contact
            assertNotNull(
                "${config.name}: wrote UID $WRITE_UID but it was not found among ${read.size} contacts on read-back",
                written,
            )
            val contact = written!!

            // Mapped identity fields must survive even if the server downgraded the
            // stored bytes. Synthetic values are safe to print; no raw body.
            val email = contact.emails.firstOrNull { it.address == EXP_EMAIL }
            val phone = contact.phones.firstOrNull {
                it.number.filter(Char::isDigit).contains(EXP_PHONE_DIGITS)
            }
            println(
                "=== ${config.name} write round-trip (book='${book.displayName}', version=${contact.version}): " +
                    "N=${contact.structuredName.family}/${contact.structuredName.given}, " +
                    "email=${email?.address}, phoneMatched=${phone != null} ===",
            )

            assertEquals("${config.name}: N family lost on write round-trip", EXP_FAMILY, contact.structuredName.family)
            assertEquals("${config.name}: N given lost on write round-trip", EXP_GIVEN, contact.structuredName.given)
            assertNotNull("${config.name}: EMAIL $EXP_EMAIL lost on write round-trip", email)
            assertNotNull("${config.name}: TEL $EXP_PHONE_DIGITS lost on write round-trip", phone)
        } finally {
            // Best-effort cleanup so repeated runs stay idempotent and no synthetic
            // contact lingers on a shared test server.
            currentEtag(c, book.url, resourceUrl)?.let { etag ->
                val deleted = c.deleteContact(resourceUrl, etag)
                assertTrue(
                    "${config.name}: cleanup delete returned an unexpected failure: $deleted",
                    deleted is ContactDeleteResult.Deleted ||
                        deleted is ContactDeleteResult.AlreadyGone ||
                        deleted is ContactDeleteResult.PreconditionFailed,
                )
            }
        }
    }

    /**
     * Diagnostic probe: does a freshly-created resource's server-returned enumerate
     * href BYTE-MATCH the path we would stamp onto SOURCE_ID after a net-new create
     * (`java.net.URI(putUrl).path`)? The net-new duplicate fix stamps that path as the
     * dedup key, and the pull dedups by an EXACT `SOURCE_ID = ?` match against the
     * server's returned href — so any server whose enumerate href is not byte-identical
     * to the PUT-url path would still insert a duplicate on the following pull. This
     * prints the verdict per server; it does not assert (it is evidence-gathering).
     */
    @Test
    fun `net-new SOURCE_ID stamp byte-matches the server enumerate href form`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!
        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to write into", book != null)

        val probeUid = "kashcal-hrefform-0001"
        val resourceUrl = book!!.url.trimEnd('/') + "/" + contactResourceName(probeUid)
        val body = buildString {
            append("BEGIN:VCARD\r\n"); append("VERSION:3.0\r\n")
            append("UID:$probeUid\r\n"); append("FN:Hrefform Probe\r\n")
            append("N:Probe;Hrefform;;;\r\n"); append("END:VCARD\r\n")
        }

        val uploaded = when (val first = c.putContact(resourceUrl, body, ContactPrecondition.IfAbsent)) {
            is ContactUploadResult.Success -> true
            is ContactUploadResult.PreconditionFailed -> {
                val e = currentEtag(c, book.url, resourceUrl)
                e != null && c.putContact(resourceUrl, body, ContactPrecondition.IfMatch(e)) is ContactUploadResult.Success
            }
            else -> false
        }
        assumeTrue("${config.name}: server would not accept the probe write", uploaded)

        try {
            val stamped = java.net.URI(resourceUrl).path  // what the net-new fix writes to SOURCE_ID
            val name = resourceUrl.substringAfterLast('/')
            val enumeratedHref = collectHrefs(c, book.url).firstOrNull { it.substringAfterLast('/') == name }
            assumeTrue("${config.name}: probe resource not enumerated after write", enumeratedHref != null)
            val exactMatch = enumeratedHref == stamped
            println(
                "=== HREF-FORM ${config.name}: stampedSOURCE_ID='$stamped' enumeratedHref='$enumeratedHref' " +
                    "byteMatch=$exactMatch ${if (exactMatch) "(no dup)" else "!!! WOULD DUPLICATE (form differs)"} ===",
            )
        } finally {
            currentEtag(c, book.url, resourceUrl)?.let { c.deleteContact(resourceUrl, it) }
        }
    }

    /**
     * A phone-only device contact (no name row at all) serializes through the app's
     * [VCardWriter] with a mandatory FN synthesized from the phone. The hard invariant
     * is RFC 6350 §6.2.1: FN MUST be present and non-blank on the written card — that
     * is the fix, and a regression here fails the test on every server.
     *
     * N is deliberately absent: RFC 6350 §6.2.2 makes N optional (`*1`), so a nameless
     * contact legitimately has none, and synthesizing a fake N from the phone would
     * round-trip back as a contact literally *named* after its number. Server
     * ACCEPTANCE of this RFC-conformant card is therefore characterized, not asserted:
     * conformant servers accept it (assert the FN round-trips); a server that rejects a
     * valid N-less card (iCloud requires N, contrary to §6.2.2) is a documented server
     * quirk, skipped like the sibling round-trip test's "server would not accept" path,
     * not a mapping bug we should paper over.
     */
    @Test
    fun `a nameless phone-only contact serializes with a mandatory FN`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!
        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to write into", book != null)

        val uid = "kashcal-nameless-0001"
        val phone = "+15550142"
        val nameless = Contact(
            version = book!!.vcardVersion,
            uid = uid,
            structuredName = StructuredName(),
            displayName = "",
            phones = listOf(Phone(number = phone, types = listOf("cell"))),
            rawVCard = "",
        )
        val body = VCardWriter().write(nameless, book.vcardVersion)
        // THE fix, asserted hard on every server (deterministic, no network): a written
        // card MUST carry a non-blank FN (RFC 6350 §6.2.1) AND a structurally-present N.
        // N is optional per §6.2.2, but strict servers (iCloud) 403 a card that omits it
        // while accepting an all-empty `N:;;;;`; emitting the empty form is what lets a
        // nameless contact sync everywhere without fabricating a name.
        assertTrue(
            "${config.name}: writer emitted no FN for a nameless contact (RFC 6350 §6.2.1 mandates it)",
            body.lineSequence().any { it.startsWith("FN") && it.substringAfter(":", "").isNotBlank() },
        )
        assertTrue(
            "${config.name}: writer omitted the N property for a nameless contact — strict servers reject that",
            body.lineSequence().any { it.startsWith("N:") || it.startsWith("N;") },
        )

        val resourceUrl = book.url.trimEnd('/') + "/" + contactResourceName(uid)
        val result = when (val first = c.putContact(resourceUrl, body, ContactPrecondition.IfAbsent)) {
            is ContactUploadResult.PreconditionFailed -> {
                val e = currentEtag(c, book.url, resourceUrl)
                if (e != null) c.putContact(resourceUrl, body, ContactPrecondition.IfMatch(e)) else first
            }
            else -> first
        }

        try {
            when (result) {
                is ContactUploadResult.Success -> Unit
                // 403: a server-POLICY refusal of an otherwise-valid card (a strict name
                // policy). Characterize and skip — it is the server's conformance stance,
                // not a mapping bug on our side.
                is ContactUploadResult.PermissionDenied -> {
                    println("=== ${config.name} nameless write: server REFUSED (403 policy) a valid card ===")
                    assumeTrue("${config.name}: server refuses this card on policy (403); characterized, not a mapping bug", false)
                }
                // Anything else (400-class Failed, 404/410 Gone, residual precondition):
                // NOT a documented policy quirk. A malformed body maps to 400 → Failed, so
                // fail loudly rather than swallowing a serialization regression as a skip.
                else -> fail(
                    "${config.name}: nameless write rejected unexpectedly (${result::class.simpleName}) — " +
                        "a malformed body or server error, not a policy refusal",
                )
            }
            val hrefs = collectHrefs(c, book.url)
            val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
                ?.data?.contacts.orEmpty()
            val written = read.firstOrNull { it.contact.uid == uid }?.contact
            assertNotNull("${config.name}: nameless contact not found on read-back", written)
            assertTrue(
                "${config.name}: synthesized FN lost on round-trip (readback displayName was blank)",
                written!!.displayName.isNotBlank(),
            )
            println("=== ${config.name} nameless write: accepted, readback displayName='${written.displayName}' ===")
        } finally {
            currentEtag(c, book.url, resourceUrl)?.let { c.deleteContact(resourceUrl, it) }
        }
    }

    /** Discover the login's first writable address book, or null if none is writable. */
    private suspend fun resolveWritableBook(c: CardDavClient, cr: ServerCredentials) = run {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull() ?: return@run null
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return@run null
        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        books.firstOrNull { !it.isReadOnly }
    }

    /** The current server ETag for [resourceUrl] in [bookUrl], or null if not listed. */
    private suspend fun currentEtag(c: CardDavClient, bookUrl: String, resourceUrl: String): String? {
        val listed = (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data.orEmpty()
        // Href may be an absolute URL or a server-root-relative path; match by suffix.
        val name = resourceUrl.substringAfterLast('/')
        return listed.firstOrNull { it.first.substringAfterLast('/') == name }?.second
    }

    /** Read hrefs via sync-collection when available, else the full PROPFIND listing. */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }
}
