package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.contacts.ContactPushStrategy
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.contacts.LocalContactChanges
import org.onekash.kashcal.sync.contacts.LocalContactEdit
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Phone
import org.onekash.vcard.model.PostalAddress
import org.onekash.vcard.model.Relation
import org.onekash.vcard.model.StructuredName
import org.onekash.vcard.model.WebAddress
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Round-trips one mapped [Contact] field at a time through the full application push
 * reconcile, live against every configured CardDAV server.
 *
 * ## What this adds over the siblings
 *
 * - [MultiServerCardDavFieldFidelityTest] seeds a rich card with a raw authenticated PUT,
 *   bypassing the serializer, to prove a server stores and re-serves the wire bytes the
 *   reader parses.
 * - [MultiServerCardDavWriteRoundTripTest] calls the low-level [CardDavClient.putContact]
 *   directly for a handful of basic fields.
 *
 * This test drives the real [ContactPushStrategy.push], the path the sync worker runs:
 * [org.onekash.vcard.VCardWriter] serializes the field, the production [CardDavClient] PUTs
 * it, the server stores it, and the production [CardDavContactReader] reads it back into the
 * neutral model. A serialization or reconcile-routing regression for any single field shows
 * up here as a lost or mangled field on read-back, which neither sibling would catch.
 *
 * Only the Android Contacts Provider is faked: Robolectric's `ShadowContentResolver` can't
 * execute Contacts Provider writes, so the pending edit set comes from the canonical
 * [FakeContactsProviderRepository]. Serialize, PUT, store and read-back are all live.
 *
 * ## Re-serialize on every run
 *
 * Each field has its own stable synthetic UID. The test deletes any existing resource for
 * that UID, creates it through the create path (a blank-href edit, serialized by the writer
 * and PUT with `If-None-Match: *`), asserts on read-back, and deletes it again in a finally.
 * Without both deletes, a crashed prior run's resource would fail the create's precondition
 * and the reconcile would adopt it without re-serializing, so a later writer regression
 * would read back the old correct body and pass. Deleting first makes the writer run every
 * time, and no per-field resource is left behind.
 *
 * ## Conformant refusal vs. lost field
 *
 * A server that refuses the write (the create is deferred and no resource is confirmed, e.g.
 * a strict server rejecting an Apple X-idiom body) is a per-(server, field) `assumeTrue`
 * skip, never a build failure. Only a field that was accepted but comes back lost or mangled
 * fails.
 *
 * PHOTO is excluded from the field matrix; [MultiServerCardDavPhotoPushProbeTest] covers the
 * photo push path.
 *
 * All seed data is synthetic: RFC 6761 reserved `@example.test` addresses, an unassigned
 * `+1-555-01xx` number and `example.test` URLs, so no real person is contacted or exposed.
 * Logs and assertion messages carry only the server name, the field label, the book's
 * display name, the vCard version and synthetic values (the seed fields and UIDs); never an
 * href or a body.
 *
 * Skips, never fails, a server without credentials, unreachable, or with no writable
 * address book.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerContactPushReconcileTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerContactPushReconcileTest(
    private val config: CardDavServerConfig,
    private val field: FieldCase,
) {
    companion object {
        /** Every server × every mapped field is its own test instance. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} · {1}")
        fun cases(): List<Array<Any>> =
            CardDavServerConfig.allServers().flatMap { server ->
                FIELD_CASES.map { arrayOf<Any>(server, it) }
            }

        private const val ACCOUNT = "reconcile@example.test"
        private const val BASE_FAMILY = "Reconcile"
        private const val BASE_GIVEN = "Kashcal"

        /** Any non-zero provider `_ID`; the net-new write-back keys on it. */
        private const val LOCAL_ID = 700L

        /** Fixed UID for the persistent net-new inspection contact left in place across runs. */
        private const val INSPECTION_UID = "kashcal-reconcile-inspection"

        /**
         * One mapped field, its distinct UID, how to add it onto a base contact, and how
         * to assert it survived the round-trip. `toString` is the field label so it names
         * the parameterized case.
         */
        class FieldCase(
            private val label: String,
            val uidSlug: String,
            val decorate: (Contact) -> Contact,
            val verify: (Contact, String) -> Unit,
        ) {
            override fun toString(): String = label
        }

        private val FIELD_CASES: List<FieldCase> = listOf(
            FieldCase(
                "FN",
                "fn",
                { it.copy(displayName = "$BASE_GIVEN $BASE_FAMILY Formatted") },
                { c, m -> assertEquals("$m FN", "$BASE_GIVEN $BASE_FAMILY Formatted", c.displayName) },
            ),
            FieldCase(
                "N-full",
                "n-full",
                {
                    it.copy(
                        structuredName = StructuredName(
                            family = BASE_FAMILY,
                            given = BASE_GIVEN,
                            middle = "Quincy",
                            prefix = "Dr.",
                            suffix = "Jr.",
                            phoneticGiven = "kyashikaru",
                            phoneticFamily = "rekonsairu",
                        ),
                    )
                },
                { c, m ->
                    val n = c.structuredName
                    assertEquals("$m N family", BASE_FAMILY, n.family)
                    assertEquals("$m N given", BASE_GIVEN, n.given)
                    assertEquals("$m N middle", "Quincy", n.middle)
                    assertEquals("$m N prefix", "Dr.", n.prefix)
                    assertEquals("$m N suffix", "Jr.", n.suffix)
                    // phoneticMiddle is neither set nor asserted: Apple exposes only first
                    // and last phonetics, the same reason the field-fidelity sibling only
                    // prints it.
                    assertEquals("$m X-PHONETIC-FIRST-NAME", "kyashikaru", n.phoneticGiven)
                    assertEquals("$m X-PHONETIC-LAST-NAME", "rekonsairu", n.phoneticFamily)
                },
            ),
            FieldCase(
                "NICKNAME",
                "nickname",
                { it.copy(nickname = "Kash") },
                { c, m -> assertEquals("$m NICKNAME", "Kash", c.nickname) },
            ),
            FieldCase(
                "ORG",
                "org",
                { it.copy(organization = listOf("Example Test Org", "Sync Division")) },
                { c, m ->
                    assertEquals("$m ORG company", "Example Test Org", c.organization.getOrNull(0))
                    assertTrue(
                        "$m ORG department lost (got ${c.organization})",
                        c.organization.drop(1).any { it == "Sync Division" },
                    )
                },
            ),
            FieldCase(
                "TITLE",
                "title",
                { it.copy(title = "Fixture Contact") },
                { c, m -> assertEquals("$m TITLE", "Fixture Contact", c.title) },
            ),
            FieldCase(
                "ROLE",
                "role",
                { it.copy(role = "Chief Sync Officer") },
                { c, m -> assertEquals("$m ROLE", "Chief Sync Officer", c.role) },
            ),
            FieldCase(
                "EMAIL",
                "email",
                {
                    it.copy(
                        emails = listOf(
                            Email(
                                address = "school@example.test",
                                types = listOf("home"),
                                preferred = true,
                                label = "School",
                            ),
                        ),
                    )
                },
                { c, m ->
                    val e = c.emails.firstOrNull { it.address == "school@example.test" }
                    assertNotNull("$m EMAIL lost", e)
                    assertEquals("$m EMAIL X-ABLabel", "School", e!!.label)
                    assertTrue("$m EMAIL PREF lost", e.preferred)
                },
            ),
            FieldCase(
                "TEL",
                "tel",
                {
                    it.copy(
                        phones = listOf(
                            Phone(
                                number = "+1-555-0142",
                                types = listOf("cell"),
                                preferred = true,
                                label = "Beeper",
                            ),
                        ),
                    )
                },
                { c, m ->
                    val p = c.phones.firstOrNull {
                        it.number.filter(Char::isDigit).contains("15550142")
                    }
                    assertNotNull("$m TEL lost", p)
                    assertEquals("$m TEL X-ABLabel", "Beeper", p!!.label)
                    assertTrue("$m TEL PREF lost", p.preferred)
                },
            ),
            FieldCase(
                "ADR",
                "adr",
                {
                    it.copy(
                        addresses = listOf(
                            PostalAddress(
                                street = "9 Custom Way",
                                locality = "Testville",
                                region = "TS",
                                postalCode = "00001",
                                country = "Exampleland",
                                types = listOf("home"),
                                label = "Vacation Home",
                            ),
                        ),
                    )
                },
                { c, m ->
                    val a = c.addresses.firstOrNull { it.street == "9 Custom Way" }
                    assertNotNull("$m ADR lost", a)
                    assertEquals("$m ADR X-ABLabel", "Vacation Home", a!!.label)
                },
            ),
            FieldCase(
                "URL",
                "url",
                { it.copy(urls = listOf(WebAddress(url = "https://example.test/blog", label = "Blog"))) },
                { c, m ->
                    val u = c.urls.firstOrNull { it.url == "https://example.test/blog" }
                    assertNotNull("$m URL lost", u)
                    assertEquals("$m URL X-ABLabel", "Blog", u!!.label)
                },
            ),
            FieldCase(
                "NOTE",
                "note",
                { it.copy(notes = listOf("Synthetic reconcile note")) },
                { c, m ->
                    assertTrue(
                        "$m NOTE lost (got ${c.notes})",
                        c.notes.any { it == "Synthetic reconcile note" },
                    )
                },
            ),
            FieldCase(
                "CATEGORIES",
                "categories",
                { it.copy(categories = listOf("Friends", "Testers")) },
                { c, m ->
                    assertTrue("$m CATEGORIES 'Friends' lost", c.categories.any { it == "Friends" })
                    assertTrue("$m CATEGORIES 'Testers' lost", c.categories.any { it == "Testers" })
                },
            ),
            FieldCase(
                "BDAY",
                "bday",
                { it.copy(birthday = ContactDate(date = LocalDate.parse("1985-03-14"))) },
                { c, m ->
                    assertNotNull("$m BDAY lost", c.birthday)
                    assertEquals("$m BDAY value", "1985-03-14", c.birthday!!.date?.toString())
                },
            ),
            FieldCase(
                "ANNIVERSARY",
                "anniversary",
                { it.copy(anniversary = ContactDate(date = LocalDate.parse("2010-09-22"))) },
                { c, m ->
                    assertNotNull("$m ANNIVERSARY lost", c.anniversary)
                    assertEquals("$m ANNIVERSARY value", "2010-09-22", c.anniversary!!.date?.toString())
                },
            ),
            FieldCase(
                "RELATED",
                "related",
                { it.copy(relations = listOf(Relation(name = "Kashcal Spouse", type = "spouse"))) },
                { c, m ->
                    assertTrue(
                        "$m RELATED name lost (got ${c.relations.map { it.name }})",
                        c.relations.any { it.name == "Kashcal Spouse" },
                    )
                },
            ),
            FieldCase(
                "IMPP",
                "impp",
                { it.copy(imHandles = listOf(ImHandle(protocol = "xmpp", handle = "kash@example.test"))) },
                { c, m ->
                    assertTrue(
                        "$m IMPP handle lost (got ${c.imHandles.map { it.handle }})",
                        c.imHandles.any { it.handle.contains("kash@example.test") },
                    )
                },
            ),
            FieldCase(
                // KIND is pinned to a non-group value: the reader drops a KIND:group card
                // (a distribution list, not a person), which would come back as nothing and
                // be misread as a lost field.
                "KIND",
                "kind",
                { it.copy(kind = "org") },
                { c, m -> assertEquals("$m KIND", "org", c.kind) },
            ),
        )
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var reader: CardDavContactReader
    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var strategy: ContactPushStrategy

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

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    @Test
    fun `field survives the full push reconcile round-trip`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to push into", book != null)

        val uid = "kashcal-reconcile-${field.uidSlug}"
        val resourceUrl = book!!.url.trimEnd('/') + "/" + contactResourceName(uid)

        // Clear any resource a crashed prior run left, so the create runs through
        // VCardWriter instead of the 412 adopt path, which keeps the old body and would let a
        // writer regression pass (see the class doc). The delete is best-effort, so confirm
        // nothing is listed before trusting the create; a lingering resource is a
        // per-(server, field) skip, not a false pass.
        deleteIfPresent(c, book.url, resourceUrl)
        assumeTrue(
            "${config.name}: a pre-existing '$field' resource survived the delete-before; " +
                "skipping to avoid adopting a stale body as a false pass",
            currentEtag(c, book.url, resourceUrl) == null,
        )

        val contact = field.decorate(baseContact(book.vcardVersion, uid))
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(
                LocalContactEdit(href = "", uid = uid, storedEtag = null, contact = contact, localId = LOCAL_ID),
            ),
            deleted = emptyList(),
        )
        provider.markNewUploadedCalls.clear()

        // The push and the refusal skip sit inside the try so the finally-delete runs even
        // when the create's fate is unknown: a code-0 transport failure may have committed
        // the resource without a confirmed write-back, and it must not linger.
        try {
            strategy.push(ACCOUNT, listOf(book), c)

            // A confirmed create writes its server href back by _ID (markNewContactUploaded).
            // No write-back means the server refused the create (deferred, nothing persisted):
            // a conformant refusal, skipped, not a lost field.
            assumeTrue(
                "${config.name}: server did not persist a '$field' contact (conformant refusal / deferred)",
                provider.markNewUploadedCalls.isNotEmpty(),
            )

            val hrefs = collectHrefs(c, book.url)
            assumeTrue("${config.name}: no contact hrefs after the push", hrefs.isNotEmpty())
            val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
                ?.data?.contacts.orEmpty()
            val readBack = read.firstOrNull { it.contact.uid == uid }?.contact
            assertNotNull(
                "${config.name}: pushed '$field' UID $uid but it was not found among ${read.size} contacts on read-back",
                readBack,
            )
            println("=== ${config.name} push reconcile '$field' (book='${book.displayName}', version=${readBack!!.version}) ===")
            // Some servers accept a contact but silently drop a specific property by policy;
            // the same push keeps it on conformant servers, so it isn't a client regression.
            // Those are a per-(server, field) skip, not a lost-field failure. Open-Xchange
            // drops vCard KIND ([CardDavServerConfig.dropsKind]).
            assumeTrue(
                "${config.name}: '$field' is a documented server-side drop on this provider " +
                    "(accepted on write, not persisted) — characterized, not a client regression",
                !(config.dropsKind && field.toString() == "KIND"),
            )
            field.verify(readBack, "${config.name} $field:")
        } finally {
            deleteIfPresent(c, book.url, resourceUrl)
        }
    }

    /**
     * Pushes one fixed-UID contact through the net-new create path of
     * [ContactPushStrategy.push] and leaves it on the server on purpose, so a maintainer
     * inspecting a server after a run finds a recognizable synthetic card. It isn't a
     * per-field fidelity resource: on a repeat run the create hits `If-None-Match: *` and a
     * 412, and the reconcile adopts the existing resource without re-serializing. That is why
     * it stays separate from the per-field cases, which must re-serialize every run.
     *
     * Idempotent across runs and across the field parameterization: a first-run create and a
     * repeat-run adopt both write the server href back (markNewContactUploaded), so the check
     * holds every time without leaving duplicates.
     */
    @Test
    fun `net-new create path leaves a persistent inspection contact`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to push into", book != null)

        val contact = Contact(
            version = book!!.vcardVersion,
            uid = INSPECTION_UID,
            structuredName = StructuredName(family = "Inspection", given = "Kashcal"),
            displayName = "Kashcal Inspection",
            notes = listOf("Persistent synthetic contact left in place for manual inspection."),
            rawVCard = "",
        )
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(
                LocalContactEdit(href = "", uid = INSPECTION_UID, storedEtag = null, contact = contact, localId = LOCAL_ID + 1),
            ),
            deleted = emptyList(),
        )
        provider.markNewUploadedCalls.clear()

        strategy.push(ACCOUNT, listOf(book), c)

        // No finally-delete: the resource is meant to persist. No href write-back means a
        // conformant refusal.
        assumeTrue(
            "${config.name}: server did not persist the inspection contact (conformant refusal)",
            provider.markNewUploadedCalls.isNotEmpty(),
        )
        println("=== ${config.name} net-new inspection contact persisted (UID $INSPECTION_UID) ===")
    }

    private fun baseContact(version: String, uid: String) = Contact(
        version = version,
        uid = uid,
        structuredName = StructuredName(family = BASE_FAMILY, given = BASE_GIVEN),
        displayName = "$BASE_GIVEN $BASE_FAMILY",
        rawVCard = "",
    )

    /** Deletes [resourceUrl] with its listed etag if the server still lists it; best-effort. */
    private suspend fun deleteIfPresent(c: CardDavClient, bookUrl: String, resourceUrl: String) {
        currentEtag(c, bookUrl, resourceUrl)?.let { c.deleteContact(resourceUrl, it) }
    }

    /** Returns the login's first writable address book, or null if none is writable. */
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

    /**
     * Returns the server ETag of the [bookUrl] member whose last path segment matches
     * [resourceUrl]'s, or null if none is listed or the listing fails.
     */
    private suspend fun currentEtag(c: CardDavClient, bookUrl: String, resourceUrl: String): String? {
        val listed = (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data.orEmpty()
        val name = resourceUrl.substringAfterLast('/')
        return listed.firstOrNull { it.first.substringAfterLast('/') == name }?.second
    }

    /**
     * Returns the book's hrefs from sync-collection, or from the PROPFIND listing when that
     * fails or lists none.
     */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }
}
