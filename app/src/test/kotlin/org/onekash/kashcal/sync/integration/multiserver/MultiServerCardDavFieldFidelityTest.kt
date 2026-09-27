package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips one rich vCard through every configured CardDAV server and asserts the
 * identity-shaping properties survive on the neutral [org.onekash.vcard.model.Contact].
 *
 * The seed goes up with an idempotent raw authenticated PUT (test setup, never an app write
 * path) and comes back through the production [CardDavClient] and [CardDavContactReader].
 *
 * [MultiServerCardDavReadTest] checks only FN, one email and one phone. The richer properties
 * (multi-value `N` components, `ORG`, `TITLE`, `ROLE`, `X-PHONETIC-*` reading aids, grouped
 * `itemN.X-ABLabel` labels on email, phone, adr and url, BDAY and the `X-ABDATE` anniversary)
 * are otherwise parsed only in the pure-JVM `VCardContactMapperTest`. This test confirms a real
 * server stores and re-serves the bytes those fields parse from, which is where server quirks
 * (an X-property dropped, a structured value flattened, a label rewritten) would surface.
 *
 * The assertions are hard, not tolerant: a dropped or mangled field fails on every server in
 * [CardDavServerConfig.allServers]. iCloud originated the `X-PHONETIC-*` and `X-ABLabel`
 * conventions and round-trips them natively, and the other servers are expected to store these
 * fields as authored, so a loss is a regression to surface. The phonetic middle name is printed,
 * not asserted: iCloud's contact UI exposes only first and last phonetics, so a server normalizing
 * the middle away is expected. A future server that legitimately normalizes X-properties gets a
 * documented per-server tolerance then.
 *
 * The seed is synthetic (RFC 6761 reserved `@example.test`, an unassigned `+1-555-00xx`
 * number, an `example.test` URL), so no real person is contacted or exposed. Assertions key
 * only on the seed's UID and its synthetic values, and no raw body is printed;
 * [redactContactBody] exists for a diagnostic that must print one.
 *
 * Skips (never fails) servers without credentials, unreachable ones, and ones without CardDAV.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavFieldFidelityTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavFieldFidelityTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val FIDELITY_UID = "kashcal-fidelity-0004"
        private const val FIDELITY_FILENAME = "kashcal_field_fidelity_v4.vcf"

        // Expected values, transcribed from the committed fixture body.
        private const val EXP_FAMILY = "Probe"
        private const val EXP_GIVEN = "KashCal"
        private const val EXP_ORG_COMPANY = "KashCal Test Org"
        private const val EXP_ORG_DEPARTMENT = "Sync Division"
        private const val EXP_TITLE = "Fixture Contact"
        private const val EXP_ROLE = "Chief Sync Officer"
        private const val EXP_PHONETIC_GIVEN = "kyashikaru"
        private const val EXP_PHONETIC_FAMILY = "puroobu"
        private const val EXP_EMAIL = "school@example.test"
        private const val EXP_EMAIL_LABEL = "School"
        private const val EXP_PHONE_DIGITS = "5550009999"
        private const val EXP_PHONE_LABEL = "Beeper"
        private const val EXP_ADR_STREET = "9 Custom Way"
        private const val EXP_ADR_LABEL = "Vacation Home"
        private const val EXP_URL = "https://example.test/blog"
        private const val EXP_URL_LABEL = "Blog"
        // Native BDAY (full calendar date) and the iCloud itemN.X-ABDATE + X-ABLabel
        // anniversary form (vCard 3.0 has no native ANNIVERSARY property).
        private const val EXP_BDAY = "1985-03-14"
        private const val EXP_ANNIVERSARY = "2010-09-22"

        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()

        /** The committed rich synthetic seed body (vCard 3.0). */
        private val FIDELITY_BODY: String =
            MultiServerCardDavFieldFidelityTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$FIDELITY_FILENAME")!!
                .use { it.readBytes().decodeToString() }
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
    fun `seeds a rich vCard and every mapped property survives the server round-trip`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to seed the fixture into", book != null)

        // --- Idempotent seed (test setup: raw authenticated PUT, not an app path) ---
        val seedUrl = book!!.url.trimEnd('/') + "/" + FIDELITY_FILENAME
        assumeTrue("${config.name}: could not seed the fidelity fixture", putSeed(seedUrl, cr))

        // --- Read back through the production reader ---
        val hrefs = collectHrefs(c, book.url)
        assumeTrue("${config.name}: no contact hrefs after seeding", hrefs.isNotEmpty())
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()

        // Tolerate pre-existing contacts: assert only on this test's seed, keyed by UID.
        val seed = read.firstOrNull { it.contact.uid == FIDELITY_UID }?.contact
        assertNotNull("${config.name}: seed UID $FIDELITY_UID not found among ${read.size} contacts", seed)
        val contact = seed!!

        // Characterization line: synthetic values are safe to print; no raw body.
        val email = contact.emails.firstOrNull { it.address == EXP_EMAIL }
        val phone = contact.phones.firstOrNull { it.number.filter { ch -> ch.isDigit() }.contains(EXP_PHONE_DIGITS) }
        val adr = contact.addresses.firstOrNull { it.street == EXP_ADR_STREET }
        val url = contact.urls.firstOrNull { it.url == EXP_URL }
        println(
            "=== ${config.name} field fidelity (book='${book.displayName}', version=${contact.version}):\n" +
                "    N: family=${contact.structuredName.family}, given=${contact.structuredName.given}, " +
                "middle=${contact.structuredName.middle}, prefix=${contact.structuredName.prefix}, " +
                "suffix=${contact.structuredName.suffix}\n" +
                "    phonetic: given=${contact.structuredName.phoneticGiven}, " +
                "middle=${contact.structuredName.phoneticMiddle}, family=${contact.structuredName.phoneticFamily}\n" +
                "    org=${contact.organization}, title=${contact.title}, role=${contact.role}\n" +
                "    email.label=${email?.label}, phone.label=${phone?.label}, " +
                "adr.label=${adr?.label}, url.label=${url?.label}\n" +
                "    bday=${contact.birthday?.date ?: contact.birthday?.text}, " +
                "anniversary=${contact.anniversary?.date ?: contact.anniversary?.text} ===",
        )

        // --- Structured N: multi-value components space-joined, not dropped ---
        assertEquals("${config.name}: N family", EXP_FAMILY, contact.structuredName.family)
        assertEquals("${config.name}: N given", EXP_GIVEN, contact.structuredName.given)
        assertContainsAll("${config.name}: N middle multi-values", contact.structuredName.middle, "Quincy", "Aloysius")
        assertContainsAll("${config.name}: N prefix multi-values", contact.structuredName.prefix, "Dr.", "Prof.")
        assertContainsAll("${config.name}: N suffix multi-values", contact.structuredName.suffix, "Jr.", "III")

        // --- ORG multi-value + TITLE + ROLE (ROLE distinct from TITLE) ---
        assertEquals("${config.name}: ORG company", EXP_ORG_COMPANY, contact.organization.getOrNull(0))
        assertTrue(
            "${config.name}: ORG department '$EXP_ORG_DEPARTMENT' lost (got ${contact.organization})",
            contact.organization.drop(1).any { it == EXP_ORG_DEPARTMENT },
        )
        assertEquals("${config.name}: TITLE", EXP_TITLE, contact.title)
        assertEquals("${config.name}: ROLE", EXP_ROLE, contact.role)

        // --- X-PHONETIC-* reading aids (first and last; middle printed only, see class doc) ---
        assertEquals("${config.name}: X-PHONETIC-FIRST-NAME", EXP_PHONETIC_GIVEN, contact.structuredName.phoneticGiven)
        assertEquals("${config.name}: X-PHONETIC-LAST-NAME", EXP_PHONETIC_FAMILY, contact.structuredName.phoneticFamily)

        // --- Grouped itemN.X-ABLabel custom labels survive on each property ---
        assertNotNull("${config.name}: labeled email $EXP_EMAIL lost", email)
        assertEquals("${config.name}: email X-ABLabel", EXP_EMAIL_LABEL, email!!.label)

        assertNotNull("${config.name}: labeled phone $EXP_PHONE_DIGITS lost", phone)
        assertEquals("${config.name}: phone X-ABLabel", EXP_PHONE_LABEL, phone!!.label)

        assertNotNull("${config.name}: labeled address '$EXP_ADR_STREET' lost", adr)
        assertEquals("${config.name}: adr X-ABLabel", EXP_ADR_LABEL, adr!!.label)

        assertNotNull("${config.name}: labeled url $EXP_URL lost", url)
        assertEquals("${config.name}: url X-ABLabel", EXP_URL_LABEL, url!!.label)

        // --- BDAY (native full-date) survives as a parsed calendar date ---
        // LocalDate.toString() is ISO-8601 (YYYY-MM-DD), matching the fixture literal.
        assertNotNull("${config.name}: BDAY lost (birthday null)", contact.birthday)
        assertEquals(
            "${config.name}: BDAY value",
            EXP_BDAY,
            contact.birthday!!.date?.toString(),
        )

        // --- ANNIVERSARY (iCloud itemN.X-ABDATE + X-ABLabel form) maps to the neutral date ---
        assertNotNull("${config.name}: ANNIVERSARY lost (anniversary null)", contact.anniversary)
        assertEquals(
            "${config.name}: ANNIVERSARY value",
            EXP_ANNIVERSARY,
            contact.anniversary!!.date?.toString(),
        )
    }

    /** Asserts [actual] is non-null and contains every one of [needles] as a substring. */
    private fun assertContainsAll(message: String, actual: String?, vararg needles: String) {
        assertNotNull("$message: value absent", actual)
        needles.forEach { needle ->
            assertTrue("$message: '$needle' missing from '$actual'", actual!!.contains(needle))
        }
    }

    /**
     * Returns the first writable address book in the login's first home (else its first book),
     * or null when discovery finds none.
     */
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
        if (books.isEmpty()) return@run null
        books.firstOrNull { !it.isReadOnly } ?: books.first()
    }

    /** PUTs the seed body with the harness credentials (idempotent). Returns true on 2xx or 412. */
    private fun putSeed(url: String, cr: ServerCredentials): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .put(FIDELITY_BODY.toRequestBody(VCARD_MEDIA_TYPE))
            .header("Authorization", OkHttpCredentials.basic(cr.username, cr.password, Charsets.UTF_8))
            .build()
        OkHttpClient().newCall(request).execute().use { it.isSuccessful || it.code == 412 || it.code == 204 }
    } catch (_: Exception) {
        false
    }

    /** Returns the book's hrefs from sync-collection when it lists any, else a full listing. */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }

    /**
     * Masks the identity-bearing vCard properties the calendar-side email-only redactor would
     * leak. Nothing calls it (the seed is synthetic); it is kept for any diagnostic that must
     * print a non-seed body.
     */
    @Suppress("unused")
    private fun redactContactBody(body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            val name = line.substringBefore(':').substringBefore(';').uppercase()
            when (name) {
                "FN", "N", "TEL", "ADR", "EMAIL", "PHOTO", "NICKNAME", "NOTE" ->
                    "$name:<redacted>"
                else -> line
            }
        }
}
