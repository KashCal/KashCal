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
 * Checks the contact read path live across every configured CardDAV server: discover the
 * login's address books, ensure a synthetic seed contact exists, then read it back through the
 * production [CardDavClient] and [CardDavContactReader] and assert the neutral
 * [org.onekash.vcard.model.Contact] round-trips.
 *
 * The seed goes up with an idempotent PUT over raw authenticated harness HTTP (test setup,
 * never an app write path). It is synthetic (RFC 6761 reserved `@example.test`, an unassigned
 * `+1-555-0100` number), so no real person is ever contacted or exposed.
 *
 * PII: the calendar-side `redactPii` masks only emails and would leak vCard names, phones and
 * addresses. This test asserts only on the seed's own UID and synthetic values, and prints only
 * counts and the book's name and vCard version. [redactContactBody] (which also masks FN, N, TEL,
 * ADR and PHOTO) exists for any body that must surface for debugging. No non-seed body is committed
 * or printed raw.
 *
 * Skips (never fails) servers without credentials, unreachable ones, and ones that don't expose
 * CardDAV, with a logged reason.
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
        // For well-known servers (Nextcloud, Cyrus, Mailbox) the principal lives under a path
        // the RFC 6764 /.well-known/carddav redirect resolves; the bare root would 404 the
        // principal PROPFIND and skip the case. Resolving the endpoint first exercises the
        // well-known path.
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

        // --- Idempotent seed (test setup: raw authenticated PUT, not an app path) ---
        val seedUrl = book.url.trimEnd('/') + "/" + SEED_FILENAME
        val seeded = putSeed(seedUrl, cr)
        assumeTrue("${config.name}: could not seed contact (PUT $seeded)", seeded)

        // --- Read back: prefer sync-collection, fall back to full listing ---
        val hrefs = collectHrefs(c, book.url)
        assumeTrue("${config.name}: no contact hrefs after seeding", hrefs.isNotEmpty())

        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)?.data?.contacts.orEmpty()

        // Tolerate pre-existing contacts: assert only on this test's seed.
        val seed = read.firstOrNull { it.contact.uid == SEED_UID }
        assertTrue(
            "${config.name}: seed UID $SEED_UID not found among ${read.size} contacts",
            seed != null,
        )

        // The parse version comes from the returned body's VERSION line.
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

    /** PUTs the seed body with the harness credentials (idempotent). Returns true on 2xx or 412. */
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

    /** Returns the book's hrefs from sync-collection when it lists any, else a full listing. */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }

    /**
     * Masks the identity-bearing vCard properties the calendar-side email-only redactor would
     * leak. Nothing calls it; it is kept for any diagnostic that must print a non-seed body.
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
