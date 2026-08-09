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
 * End-to-end coverage of the SHIPPED photo-fetch code path against a live server.
 *
 * Its sibling [MultiServerCardDavPhotoProbeTest] characterizes the *reader/mapper*
 * (does a URI photo survive as a URI, does iCloud rewrite inline to a gateway URL)
 * and probes the gateway auth model with a hand-rolled OkHttp GET. That probe never
 * exercises the production download: [CardDavClient.fetchPhoto]. This test does — it
 * drives the real `fetchPhoto` (redirect-disabled photo client, same-registrable-
 * domain credential guard, raster-only + non-empty-body checks) against the URL the
 * server actually mints, so the guards ship with live coverage rather than only
 * MockWebServer coverage.
 *
 * Only servers that mint a *real* photo URL on a host they own exercise the fetch
 * (iCloud rewrites an inline photo to `gateway.icloud.com`). Passthrough servers keep
 * the synthetic `*.example.test` seed URI, which resolves to nothing — those skip via
 * assumeTrue (a real fetch would need a reachable seed photo, which we deliberately do
 * not host). So in practice this asserts the iCloud gateway path today.
 *
 * **Harness limitation this test skips around honestly:** the credential guard's
 * same-registrable-domain check calls OkHttp's `topPrivateDomain()`, whose public-suffix
 * list is an Android asset absent from a JVM/Robolectric worker — so the call throws and
 * the guard fails closed to exact-host. iCloud serves photos from `gateway.icloud.com`
 * while the CardDAV endpoint is on `pNN-contacts.icloud.com` (same registrable domain,
 * different host), so off-device the guard refuses the fetch before any request — the
 * documented `code == 0, isRetryable == false` sentinel. That is the on-device-only path
 * (asset present → cross-subdomain permitted); when this test sees it, it SKIPS rather
 * than asserting, since the harness can't reproduce the device's public-suffix data.
 *
 * PII discipline: never prints the minted URL or response bytes — only status, host,
 * content-type, and byte count; the minted iCloud URL embeds the account DSID.
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

        // Seed the inline-photo contact — the server-mint case (iCloud turns inline
        // bytes into an authenticated gateway URL); seeding is idempotent.
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

        // Drive the SHIPPED download path — same client instance the sync layer uses.
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
                // request — off-device that means only that the public-suffix list is
                // absent (a cross-subdomain host like gateway.icloud.com can't be proven
                // same-registrable-domain), NOT a product failure. Skip rather than fail:
                // on device the asset loads and the fetch is permitted.
                assumeTrue(
                    "${config.name}: credential guard fell back to exact-host (public-suffix " +
                        "list absent in the JVM worker) — cross-subdomain fetch is device-only",
                    !(result.code == 0 && !result.isRetryable),
                )
                // Any other error IS a real failure: a live minted URL should authenticate
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

    /** Discover the login's first writable address book (else the first book), or null. */
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

    /** Idempotent PUT of a body with the harness credentials. Returns true on 2xx / 412 / 204. */
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

    /** Read hrefs via sync-collection when available, else the full PROPFIND listing. */
    private suspend fun collectHrefs(c: CardDavClient, bookUrl: String): List<String> {
        (c.syncCollection(bookUrl, null) as? CalDavResult.Success)?.data?.let { report ->
            if (report.changed.isNotEmpty()) return report.changed.map { it.href }
        }
        return (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data?.map { it.first }.orEmpty()
    }

    /** Scheme+host of a URL for logging, without the account-identifying path. */
    private fun hostOf(url: String): String =
        Regex("""^(\w+://[^/]+)""").find(url)?.groupValues?.get(1) ?: "<opaque>"

    /** True when [url]'s host is under the RFC 6761 reserved `example.test` TLD (a synthetic seed). */
    private fun isSyntheticSeedUrl(url: String): Boolean {
        val host = Regex("""^\w+://([^/:]+)""").find(url)?.groupValues?.get(1) ?: return false
        return host == "example.test" || host.endsWith(".example.test")
    }
}
