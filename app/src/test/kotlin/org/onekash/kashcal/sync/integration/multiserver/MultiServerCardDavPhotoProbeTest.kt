package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.ReadContact
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Records how each configured CardDAV server round-trips a contact PHOTO, in the two shapes
 * vCard allows:
 *  - URI (`PHOTO;VALUE=URI:` in 3.0, a bare URL in 4.0): a remote reference the sync layer
 *    fetches out-of-band. Maps to [org.onekash.kashcal.data.contacts.MappedContact.photoUrl]
 *    (deferred fetch).
 *  - Inline (`PHOTO;ENCODING=b`): embedded bytes. Maps to a Photo row with no deferred fetch.
 *
 * The URL-photo fetch depends on whether a server keeps a URI photo as a URI (so there is a URL
 * to fetch) or inlines, drops or rewrites it. The local servers mostly confirm the read path
 * carries the bytes or URL intact end to end; iCloud is the one server with a real photo
 * pipeline.
 *
 * Method: seed the first writable book with a synthetic URI-photo and inline-photo vCard
 * (idempotent raw authenticated PUT, test setup, never an app write path), read them back
 * through the production [CardDavClient] and [CardDavContactReader], run each through
 * [VCardContactMapper], and assert the mapped shape. The probe prints the observed per-server
 * outcome so the wire behavior is recorded even when the assertion tolerates it.
 *
 * The seeds are synthetic (RFC 6761 reserved `@example.test`, unassigned `+1-555-01xx`
 * numbers, and `photos.example.test` photo URLs that resolve to nothing), so no real person or
 * asset is ever contacted.
 *
 * PII: assertions key only on the seeds' own UIDs and the photo shape, and printed photo URLs
 * go through [redactPhotoUrl]. [redactContactBody] (which masks PHOTO alongside FN, N, TEL, ADR
 * and EMAIL) exists for any body that must surface for debugging. No non-seed body is printed
 * raw.
 *
 * Skips (never fails) servers without credentials, unreachable ones, and ones without CardDAV,
 * with a logged reason.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavPhotoProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavPhotoProbeTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val URL_UID = "kashcal-seed-photo-url-0002"
        private const val URL_FILENAME = "kashcal_seed_photo_url_0002.vcf"
        private const val EXPECTED_PHOTO_URL = "https://photos.example.test/seed/kashcal-url.jpg"

        private const val INLINE_UID = "kashcal-seed-photo-inline-0003"
        private const val INLINE_FILENAME = "kashcal_seed_photo_inline_0003.vcf"

        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()

        private fun fixture(name: String): String =
            MultiServerCardDavPhotoProbeTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$name")!!
                .use { it.readBytes().decodeToString() }

        private val URL_PHOTO_BODY: String by lazy { fixture(URL_FILENAME) }
        private val INLINE_PHOTO_BODY: String by lazy { fixture(INLINE_FILENAME) }
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
    fun `seeds URI and inline photo contacts and records how the server round-trips each`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to seed a photo into", book != null)

        // --- Idempotent seed of both photo shapes (test setup: raw PUT) ---
        val urlSeedUrl = book!!.url.trimEnd('/') + "/" + URL_FILENAME
        val inlineSeedUrl = book.url.trimEnd('/') + "/" + INLINE_FILENAME
        assumeTrue(
            "${config.name}: could not seed URI-photo contact",
            putSeed(urlSeedUrl, URL_PHOTO_BODY, cr),
        )
        assumeTrue(
            "${config.name}: could not seed inline-photo contact",
            putSeed(inlineSeedUrl, INLINE_PHOTO_BODY, cr),
        )

        // --- Read both back ---
        val hrefs = collectHrefs(c, book.url)
        assumeTrue("${config.name}: no contact hrefs after seeding", hrefs.isNotEmpty())
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()

        val urlSeed = read.firstOrNull { it.contact.uid == URL_UID }
        val inlineSeed = read.firstOrNull { it.contact.uid == INLINE_UID }
        assertNotNull("${config.name}: URI-photo seed $URL_UID not read back", urlSeed)
        assertNotNull("${config.name}: inline-photo seed $INLINE_UID not read back", inlineSeed)

        // --- Characterize + assert the URI-photo shape ---
        // A conformant passthrough keeps VALUE=URI a URI; the mapper then routes it to
        // photoUrl (deferred fetch) and emits no inline blob. A server may inline or drop it
        // instead; the print records which.
        val urlPhoto = urlSeed!!.contact.photo
        val urlMapped = VCardContactMapper.toEntity(urlSeed.contact)
        println(
            "=== ${config.name} URI photo: url=${redactPhotoUrl(urlPhoto?.url)}, " +
                "hasInlineBytes=${urlPhoto?.data != null}, " +
                "mappedPhotoUrl=${redactPhotoUrl(urlMapped.photoUrl)} ===",
        )

        // --- Characterize + assert the inline-photo shape ---
        val inlinePhoto = inlineSeed!!.contact.photo
        val inlineMapped = VCardContactMapper.toEntity(inlineSeed.contact)
        println(
            "=== ${config.name} inline photo: hasInlineBytes=${inlinePhoto?.data != null}, " +
                "byteCount=${inlinePhoto?.data?.size ?: 0}, " +
                "url=${redactPhotoUrl(inlinePhoto?.url)}, " +
                "mappedPhotoUrl=${redactPhotoUrl(inlineMapped.photoUrl)} ===",
        )

        // Each seed carried a PHOTO; assert the server didn't silently drop it. A server
        // without PHOTO support would show no photo on both, which fails here on purpose.
        // A server that keeps inline base64 but strips an external-URL photo
        // (PHOTO;VALUE=URI) is marked [CardDavServerConfig.dropsUriPhoto] and recorded, not
        // failed; Open-Xchange does this.
        if (config.dropsUriPhoto) {
            println("=== ${config.name}: URI-photo dropped on round-trip (known server policy; inline photo still asserted) ===")
        } else {
            assertTrue(
                "${config.name}: URI-photo seed lost its PHOTO entirely on round-trip",
                urlPhoto != null,
            )
        }
        assertTrue(
            "${config.name}: inline-photo seed lost its PHOTO entirely on round-trip",
            inlinePhoto != null,
        )

        // When the server kept the URI as a URI (the passthrough case), the mapper contract
        // must hold: photoUrl carries the URL and no inline blob is emitted. A server that
        // inlined the URI photo has no URL, which the print above records. A server that
        // dropped it (dropsUriPhoto) has a null urlPhoto; both skip this block.
        if (urlPhoto?.url != null) {
            assertEquals(
                "${config.name}: preserved URI photo should round-trip verbatim",
                EXPECTED_PHOTO_URL,
                urlPhoto.url,
            )
            assertEquals(
                "${config.name}: mapper must route a URI photo to photoUrl for deferred fetch",
                EXPECTED_PHOTO_URL,
                urlMapped.photoUrl,
            )
            assertNull(
                "${config.name}: a URI photo must not also emit an inline blob",
                urlPhoto.data,
            )
        }

        // An inline photo must never be treated as a deferred-fetch URL.
        if (inlinePhoto!!.data != null) {
            assertTrue(
                "${config.name}: inline photo bytes should be non-empty",
                inlinePhoto.data!!.isNotEmpty(),
            )
            assertNull(
                "${config.name}: inline photo must not set a deferred-fetch photoUrl",
                inlineMapped.photoUrl,
            )
        }
    }

    /**
     * Records the auth model of a server-minted photo URL, the case the deferred URL-photo
     * fetcher depends on. iCloud rewrites an inline photo to a `gateway.icloud.com` URL on read;
     * that URL isn't public, so the fetcher must know how to authenticate it. This probes the
     * first non-seed photo URL in the book three ways and prints the outcomes:
     *   1. GET with no auth: does the gateway 401/403, or serve it open?
     *   2. GET with the CardDAV basic credentials: does it accept them (200)?
     *   3. GET with redirects disabled: does it 30x to a signed or cookied URL first?
     *
     * Only the first is asserted: an unauthenticated GET must not return an image. Servers
     * that mint no photo URL (a passthrough server keeps the synthetic `*.example.test` URI)
     * skip. The probe never prints response bytes; only status, content-type, content-length,
     * and a host-only redirect target.
     */
    @Test
    fun `characterizes auth model of a server-minted photo URL`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to seed a photo into", book != null)

        // Seed the inline-photo contact, the server-mint case (iCloud turns inline bytes into a
        // gateway URL). Seeding is idempotent, so re-runs are cheap.
        val inlineSeedUrl = book!!.url.trimEnd('/') + "/" + INLINE_FILENAME
        assumeTrue(
            "${config.name}: could not seed inline-photo contact",
            putSeed(inlineSeedUrl, INLINE_PHOTO_BODY, cr),
        )

        val hrefs = collectHrefs(c, book.url)
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()

        // Find any read-back contact whose photo is a server-minted URL, not one of the
        // synthetic example.test seed URLs.
        val mintedUrl = read
            .mapNotNull { it.contact.photo?.url }
            .firstOrNull { !isSyntheticSeedUrl(it) }
        assumeTrue(
            "${config.name}: server mints no photo URL (keeps inline or the seed URI) — auth probe N/A",
            mintedUrl != null,
        )

        val noAuth = probeGet(mintedUrl!!, cr, withAuth = false, followRedirects = true)
        val basicAuth = probeGet(mintedUrl, cr, withAuth = true, followRedirects = true)
        val noRedirect = probeGet(mintedUrl, cr, withAuth = true, followRedirects = false)

        println(
            "=== ${config.name} photo-URL auth model (host=${hostOf(mintedUrl)}):\n" +
                "    no-auth       -> $noAuth\n" +
                "    basic-auth    -> $basicAuth\n" +
                "    no-redirect   -> $noRedirect ===",
        )

        // The fact the fetcher relies on: a server-minted photo URL isn't openly readable, so
        // an unauthenticated GET must not return an image. If this fails (the gateway serves
        // photos open), the fetcher could skip auth.
        assertTrue(
            "${config.name}: unauthenticated GET unexpectedly returned an image " +
                "(${noAuth.code}, ${noAuth.contentType}) — fetcher auth assumptions need revisiting",
            !(noAuth.code == 200 && noAuth.contentType?.startsWith("image/") == true),
        )
    }

    /** Holds the metadata of one GET probe; no response bytes are kept. */
    private data class GetOutcome(
        val code: Int,
        val contentType: String?,
        val contentLength: Long,
        val redirectHost: String?,
    ) {
        override fun toString(): String =
            "HTTP $code, type=$contentType, len=$contentLength" +
                (redirectHost?.let { ", redirect->$it/<redacted>" } ?: "")
    }

    /**
     * GETs [url], optionally with basic auth and following redirects, and returns metadata
     * only. An exception becomes code -1 with the exception type as the content type.
     */
    private fun probeGet(
        url: String,
        cr: ServerCredentials,
        withAuth: Boolean,
        followRedirects: Boolean,
    ): GetOutcome = try {
        val http = OkHttpClient.Builder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()
        val builder = Request.Builder().url(url).get()
        if (withAuth) {
            builder.header(
                "Authorization",
                OkHttpCredentials.basic(cr.username, cr.password, Charsets.UTF_8),
            )
        }
        http.newCall(builder.build()).execute().use { resp ->
            GetOutcome(
                code = resp.code,
                contentType = resp.header("Content-Type"),
                contentLength = resp.body?.contentLength() ?: -1L,
                redirectHost = resp.header("Location")?.let { hostOf(it) },
            )
        }
    } catch (e: Exception) {
        GetOutcome(code = -1, contentType = "exception:${e.javaClass.simpleName}", contentLength = -1L, redirectHost = null)
    }

    /** Returns the scheme and host of [url] for logging, without the account-identifying path. */
    private fun hostOf(url: String): String =
        Regex("""^(\w+://[^/]+)""").find(url)?.groupValues?.get(1) ?: "<opaque>"

    /**
     * Returns true when [url]'s host is `example.test` or under it (RFC 6761 reserves `.test`),
     * as the synthetic seed photo URLs are. A server-minted URL returns false. Matches the host
     * only, so an `example.test` path segment on a real host can't be mistaken for a seed.
     */
    private fun isSyntheticSeedUrl(url: String): Boolean {
        val host = Regex("""^\w+://([^/:]+)""").find(url)?.groupValues?.get(1) ?: return false
        return host == "example.test" || host.endsWith(".example.test")
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

    /** PUTs [body] with the harness credentials (idempotent). Returns true on 2xx or 412. */
    private fun putSeed(url: String, body: String, cr: ServerCredentials): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody(VCARD_MEDIA_TYPE))
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
     * Redacts a photo URL for printing. The synthetic `*.example.test` seed URLs print verbatim
     * (they identify no one and confirm passthrough). Any other URL is server-minted and can
     * embed an account identifier (an iCloud gateway URL carries the numeric DSID), so it is
     * reduced to `scheme://host/<redacted>`.
     */
    private fun redactPhotoUrl(url: String?): String? {
        if (url == null) return null
        if (isSyntheticSeedUrl(url)) return url
        return Regex("""^(\w+://[^/]+)/.*$""").find(url)
            ?.let { "${it.groupValues[1]}/<redacted>" }
            ?: "<redacted>"
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
