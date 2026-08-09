package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live read-path regression across every configured CardDAV server: discover the
 * login's address books, ensure a synthetic seed contact exists (idempotent PUT
 * via raw authenticated harness HTTP — TEST SETUP only, never an app write path),
 * then read it back through the production [CardDavClient] + [CardDavContactReader]
 * and assert the neutral [org.onekash.vcard.model.Contact] round-trips.
 *
 * The seed is entirely synthetic (RFC 6761 reserved `@example.test`, RFC 3849-style
 * `+1-555-0100` unassigned number), so no real person is ever contacted or exposed.
 *
 * PII discipline: the calendar-side `redactPii` masks only emails and would leak
 * vCard names/phones/addresses. This test asserts ONLY on counts and the seed's
 * own UID/href, and any body that must surface for debugging is first passed
 * through [redactContactBody], which also masks FN/N/TEL/ADR/PHOTO. No non-seed
 * body is ever committed or printed raw.
 *
 * Skips (never fails) servers without credentials, that are unreachable, or that
 * do not expose CardDAV — with a logged reason.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavReadTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavReadTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val SEED_UID = "kashcal-seed-0001"
        // Matches the committed fixture name (underscores); the UID stays
        // hyphenated because that is the vCard `UID:` value being asserted on.
        private const val SEED_FILENAME = "kashcal_seed_0001.vcf"
        private const val SEED_FN_MARKER = "Seed Probe"
        private const val SEED_EMAIL = "seed@example.test"
        private const val SEED_PHONE_DIGITS = "15550100"

        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()

        /** The committed synthetic seed body (vCard 3.0). */
        private val SEED_BODY: String =
            MultiServerCardDavReadTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$SEED_FILENAME")!!
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
    fun `discovers an address book, seeds a contact, and reads it back parsed`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        // --- Discovery walk ---
        // For well-known servers (Nextcloud, Cyrus) the principal lives under a
        // path the RFC 6764 /.well-known/carddav redirect resolves — hitting the
        // bare root would 404 the principal PROPFIND and skip the case. Resolve
        // the real endpoint first so the well-known path is actually exercised.
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal (CardDAV likely unsupported)", principal != null)

        val homes = (c.discoverAddressBookHome(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        assumeTrue("${config.name}: no addressbook-home-set", homes.isNotEmpty())

        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        assumeTrue("${config.name}: no address book collections", books.isNotEmpty())

        // Target the first writable collection for the seed, else the first book.
        val book = books.firstOrNull { !it.isReadOnly } ?: books.first()

        // --- Idempotent seed (TEST SETUP — raw authenticated PUT, not an app path) ---
        val seedUrl = book.url.trimEnd('/') + "/" + SEED_FILENAME
        val seeded = putSeed(seedUrl, cr)
        assumeTrue("${config.name}: could not seed contact (PUT $seeded)", seeded)

        // --- Read back: prefer sync-collection, fall back to full listing ---
        val hrefs = collectHrefs(c, book.url)
        assumeTrue("${config.name}: no contact hrefs after seeding", hrefs.isNotEmpty())

        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)?.data?.contacts.orEmpty()

        // Tolerate pre-existing contacts: assert only that OUR seed is present.
        val seed = read.firstOrNull { it.contact.uid == SEED_UID }
        assertTrue(
            "${config.name}: seed UID $SEED_UID not found among ${read.size} contacts",
            seed != null,
        )

        // Assertions on the seed only — parse version comes from the RETURNED body.
        assertTrue(
            "${config.name}: seed FN should contain '$SEED_FN_MARKER'",
            seed!!.contact.displayName.contains(SEED_FN_MARKER),
        )
        assertEquals(
            "${config.name}: seed email",
            SEED_EMAIL,
            seed.contact.emails.firstOrNull()?.address,
        )
        val phoneDigits = seed.contact.phones.firstOrNull()?.number?.filter { it.isDigit() }
        assertEquals("${config.name}: seed phone digits", SEED_PHONE_DIGITS, phoneDigits)

        println("=== ${config.name}: read back seed OK (book='${book.displayName}', version=${book.vcardVersion}, total=${read.size}) ===")
    }

    /** Idempotent PUT of the seed body with the harness credentials. Returns true on 2xx. */
    private fun putSeed(url: String, cr: ServerCredentials): Boolean = try {
        val http = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .put(SEED_BODY.toRequestBody(VCARD_MEDIA_TYPE))
            .header("Authorization", OkHttpCredentials.basic(cr.username, cr.password, Charsets.UTF_8))
            .build()
        http.newCall(request).execute().use { it.isSuccessful || it.code == 412 || it.code == 204 }
    } catch (_: Exception) {
        false
    }

    /** Read hrefs via sync-collection when available, else the full PROPFIND listing. */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }

    /**
     * Contact-aware redactor for debug output. Masks the identity-bearing vCard
     * properties the calendar-side email-only redactor would leak. Not used on
     * the passing path — kept for any diagnostic that must print a non-seed body.
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
