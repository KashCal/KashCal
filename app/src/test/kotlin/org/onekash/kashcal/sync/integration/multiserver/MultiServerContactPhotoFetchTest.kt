package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertFalse
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
 * Covers the production photo download, [CardDavClient.fetchPhoto], end to end against a live
 * server.
 *
 * The sibling [MultiServerCardDavPhotoProbeTest] characterizes the reader and mapper (does a
 * URI photo survive as a URI, does iCloud rewrite inline bytes to a gateway URL) and probes
 * the gateway auth model with a hand-rolled OkHttp GET, so it never calls `fetchPhoto`. This
 * test drives the real `fetchPhoto` (redirect-disabled photo client, same-registrable-domain
 * credential guard, raster-only and non-empty-body checks) against the URL the server mints,
 * so those guards have live coverage beyond MockWebServer.
 *
 * Only servers that mint a real photo URL on a host they own exercise the fetch (iCloud
 * rewrites an inline photo to `gateway.icloud.com`). Servers that keep the inline bytes or
 * the synthetic `*.example.test` seed URI mint none and skip through `assumeTrue`; a real
 * fetch would need a reachable seed photo, which we deliberately don't host. In practice this
 * asserts the iCloud gateway path.
 *
 * Harness limitation: the credential guard's same-registrable-domain check calls OkHttp's
 * `topPrivateDomain()`, whose public-suffix list is an Android asset absent from a JVM
 * Robolectric worker, so the call throws and the guard falls back to exact-host. iCloud
 * serves photos from `gateway.icloud.com` while the CardDAV endpoint is on
 * `pNN-contacts.icloud.com` (same registrable domain, different host), so off-device the
 * guard refuses the fetch before any request, with `code == 0, isRetryable == false`. On
 * device the asset is present and the cross-subdomain fetch is permitted, so this test skips
 * on that result instead of asserting.
 *
 * Never prints the minted URL or response bytes, only status, host, content type and byte
 * count: the minted iCloud URL embeds the account DSID.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerContactPhotoFetchTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerContactPhotoFetchTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val INLINE_UID = "kashcal-seed-photo-inline-0003"
        private const val INLINE_FILENAME = "kashcal_seed_photo_inline_0003.vcf"
        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()

        private fun fixture(name: String): String =
            MultiServerContactPhotoFetchTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$name")!!
                .use { it.readBytes().decodeToString() }

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
    fun `production fetchPhoto downloads a real image from a server-minted photo URL`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val book = resolveWritableBook(c, cr)
        assumeTrue("${config.name}: no writable address book to seed a photo into", book != null)

        // Seed the inline-photo contact, the server-mint case (iCloud turns inline bytes
        // into an authenticated gateway URL). Seeding is idempotent.
        val inlineSeedUrl = book!!.url.trimEnd('/') + "/" + INLINE_FILENAME
        assumeTrue(
            "${config.name}: could not seed inline-photo contact",
            putSeed(inlineSeedUrl, INLINE_PHOTO_BODY, cr),
        )

        val hrefs = collectHrefs(c, book.url)
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()

        // A server-minted photo URL: not one of our synthetic example.test seeds.
        val mintedUrl = read
            .mapNotNull { it.contact.photo?.url }
            .firstOrNull { !isSyntheticSeedUrl(it) }
        assumeTrue(
            "${config.name}: server mints no photo URL (keeps inline or the seed URI) — production fetch N/A",
            mintedUrl != null,
        )

        // Drive the production download path on the same client type the sync layer uses.
        val result = c.fetchPhoto(mintedUrl!!)

        when (result) {
            is CalDavResult.Success -> {
                val photo = result.data
                println(
                    "=== ${config.name} production fetchPhoto (host=${hostOf(mintedUrl)}): " +
                        "OK, contentType=${photo.contentType}, byteCount=${photo.bytes.size} ===",
                )
                assertTrue(
                    "${config.name}: fetchPhoto returned an empty body — the non-empty guard should have rejected it",
                    photo.bytes.isNotEmpty(),
                )
                assertTrue(
                    "${config.name}: fetchPhoto accepted a non-raster content type (${photo.contentType})",
                    photo.contentType.substringBefore(';').trim().lowercase().let {
                        it.startsWith("image/") && it != "image/svg+xml"
                    },
                )
            }
            is CalDavResult.Error -> {
                println(
                    "=== ${config.name} production fetchPhoto (host=${hostOf(mintedUrl)}): " +
                        "ERROR code=${result.code} retryable=${result.isRetryable} ===",
                )
                // code == 0 && !retryable is the credential guard refusing before any
                // request. Off-device that only means the public-suffix list is absent (a
                // cross-subdomain host like gateway.icloud.com can't be proven
                // same-registrable-domain), not a product failure; on device the asset
                // loads and the fetch is permitted. An over-cap body gives the same result
                // and also skips here.
                assumeTrue(
                    "${config.name}: credential guard fell back to exact-host (public-suffix " +
                        "list absent in the JVM worker) — cross-subdomain fetch is device-only",
                    !(result.code == 0 && !result.isRetryable),
                )
                // Any other error is a real failure: a live minted URL should authenticate
                // with the account's Basic creds (the auth-model probe confirmed 200
                // image/jpeg). Surface the status, never the URL (DSID) or body.
                assertFalse(
                    "${config.name}: production fetchPhoto failed against a server-minted photo URL " +
                        "(code=${result.code}); the account's Basic creds should authenticate the gateway",
                    true,
                )
            }
        }
    }

    /** Returns the login's first writable address book, else its first book, or null if none. */
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

    /**
     * PUTs [body] with the harness credentials (idempotent). Returns true on any 2xx or a 412,
     * false on another status or an exception.
     */
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

    /** Returns a URL's scheme and host for logging, without the account-identifying path. */
    private fun hostOf(url: String): String =
        Regex("""^(\w+://[^/]+)""").find(url)?.groupValues?.get(1) ?: "<opaque>"

    /** Returns whether [url]'s host is `example.test` or under it, a synthetic seed (RFC 6761). */
    private fun isSyntheticSeedUrl(url: String): Boolean {
        val host = Regex("""^\w+://([^/:]+)""").find(url)?.groupValues?.get(1) ?: return false
        return host == "example.test" || host.endsWith(".example.test")
    }
}
