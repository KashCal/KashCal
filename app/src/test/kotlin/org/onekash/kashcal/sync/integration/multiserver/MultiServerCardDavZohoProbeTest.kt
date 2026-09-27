package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterizes whether Zoho exposes a usable CardDAV surface.
 *
 * Zoho's CardDAV lives on `contacts.zoho.com`, a different host from its
 * `calendar.zoho.com` CalDAV endpoint ([CalDavServerConfig.ZOHO]), and it stays out of
 * [CardDavServerConfig.allServers] until this probe confirms the shape. The tests use the
 * standalone [CardDavServerConfig.ZOHO] entry and record, never assert, each step of the
 * discovery walk plus a best-effort seed and read-back:
 *  - does `/.well-known/carddav` resolve, or does the bare contacts host answer?
 *  - is there a discoverable principal, addressbook-home and address book?
 *  - does a raw authenticated PUT of a synthetic seed succeed?
 *  - does the seed read back through the production [CardDavContactReader]?
 *
 * Every step that can't complete logs a line and skips through `assumeTrue`, so a
 * third-party server we don't yet understand never fails the build. Move Zoho into
 * `allServers()` with real assertions only once the walk is known to work.
 *
 * The seeds are synthetic fixtures (RFC 6761 `@example.test`), so no real person is
 * contacted. The Zoho login is a real address, so the probe prints host shapes, counts,
 * the book's display name and a redacted snippet of a rejected PUT's body; never the
 * account address or a minted URL.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavZohoProbeTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavZohoProbeTest {

    private val config = CardDavServerConfig.ZOHO

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

    @Test
    fun `characterizes Zoho's CardDAV discovery walk and round-trip`() = runBlocking {
        assumeTrue("Zoho: no credentials in local.properties", client != null)
        val c = client!!
        val cr = creds!!
        assumeTrue(
            "Zoho: contacts host unreachable at ${cr.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(cr.davEndpoint),
        )

        val root = c.discoverWellKnown(cr.serverUrl).getOrNull()
        println("=== Zoho CardDAV: well-known -> ${hostShape(root)} (input ${hostShape(cr.serverUrl)}) ===")
        val discoveryRoot = root ?: cr.serverUrl

        val principal = c.discoverPrincipal(discoveryRoot).getOrNull()
        println("=== Zoho CardDAV: principal -> ${hostShape(principal)} ===")
        assumeTrue("Zoho: no CardDAV principal discoverable — not exposing CardDAV", principal != null)

        val homes = (c.discoverAddressBookHome(principal!!) as? CalDavResult.Success)?.data.orEmpty()
        println("=== Zoho CardDAV: ${homes.size} addressbook-home-set(s) ===")
        assumeTrue("Zoho: no addressbook-home-set", homes.isNotEmpty())

        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        println("=== Zoho CardDAV: ${books.size} address book(s); writable=${books.count { !it.isReadOnly }} ===")
        assumeTrue("Zoho: no address book collections", books.isNotEmpty())

        val book = books.firstOrNull { !it.isReadOnly } ?: books.first()

        // Best-effort seed through a raw authenticated PUT, recorded rather than gated: Zoho
        // may reject a client-chosen href or require a UID-named resource. When the first
        // shape is rejected, try the next in a small matrix so the reason is characterized:
        // vCard version (3.0 or 4.0), href form (filename or UID.vcf), Content-Type, and an
        // If-None-Match precondition. The first accepted shape stops the walk. Each attempt
        // logs its HTTP status and redacted body.
        val bookBase = book.url.trimEnd('/')
        val attempts = listOf(
            SeedAttempt("v3.0 filename href, text/vcard", bookBase + "/" + SEED_FILENAME, SEED_BODY_V3, VCARD_MEDIA_TYPE, ifNoneMatch = false),
            SeedAttempt("v3.0 UID.vcf href", bookBase + "/" + SEED_UID + ".vcf", SEED_BODY_V3, VCARD_MEDIA_TYPE, ifNoneMatch = false),
            SeedAttempt("v3.0 If-None-Match:*", bookBase + "/" + SEED_UID + ".vcf", SEED_BODY_V3, VCARD_MEDIA_TYPE, ifNoneMatch = true),
            SeedAttempt("v4.0 UID.vcf href", bookBase + "/" + SEED_UID + ".vcf", SEED_BODY_V4, VCARD_MEDIA_TYPE, ifNoneMatch = false),
            SeedAttempt("v3.0 text/x-vcard", bookBase + "/" + SEED_UID + ".vcf", SEED_BODY_V3, X_VCARD_MEDIA_TYPE, ifNoneMatch = false),
        )
        var seeded = false
        var seedUrl = attempts.first().url
        for (a in attempts) {
            val outcome = putSeed(a.url, a.body, a.mediaType, a.ifNoneMatch, cr)
            println("=== Zoho CardDAV seed [${a.label}] -> HTTP ${outcome.code}${outcome.bodyNote} ===")
            if (outcome.ok) { seeded = true; seedUrl = a.url; break }
        }
        println("=== Zoho CardDAV: seed PUT succeeded=$seeded (book='${book.displayName}', version=${book.vcardVersion}) ===")
        assumeTrue("Zoho: could not seed a contact — write shape differs, characterize separately", seeded)

        val hrefs = collectHrefs(c, book.url)
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)?.data?.contacts.orEmpty()
        val found = read.any { it.contact.uid == SEED_UID }
        println("=== Zoho CardDAV: read back ${read.size} contact(s); seed present=$found ===")
    }

    /**
     * Characterizes how Zoho round-trips a contact photo in both vCard shapes: a `VALUE=URI`
     * remote reference and inline `ENCODING=b` bytes. The parameterized
     * [MultiServerCardDavPhotoProbeTest] can't cover Zoho: it seeds arbitrary-filename hrefs,
     * which Zoho rejects with 401, and Zoho is absent from `allServers()`. Seeds with the
     * UID-named href form Zoho requires, reads back through the production reader and
     * [VCardContactMapper], and records the mapped shape. Nothing fails: a step that can't
     * complete skips, and a seed that doesn't read back is logged.
     */
    @Test
    fun `characterizes how Zoho round-trips URI and inline contact photos`() = runBlocking {
        assumeTrue("Zoho: no credentials in local.properties", client != null)
        val c = client!!
        val cr = creds!!
        assumeTrue(
            "Zoho: contacts host unreachable at ${cr.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(cr.davEndpoint),
        )

        val book = resolveWritableBook(c, cr)
        assumeTrue("Zoho: no writable address book to seed a photo into", book != null)
        val bookBase = book!!.url.trimEnd('/')

        // Zoho requires <UID>.vcf hrefs; arbitrary filenames get 401 (see the walk test).
        val urlOk = putSeed(bookBase + "/$URL_PHOTO_UID.vcf", URL_PHOTO_BODY, VCARD_MEDIA_TYPE, false, cr)
        val inlineOk = putSeed(bookBase + "/$INLINE_PHOTO_UID.vcf", INLINE_PHOTO_BODY, VCARD_MEDIA_TYPE, false, cr)
        println("=== Zoho CardDAV photo seed: url PUT=HTTP ${urlOk.code}, inline PUT=HTTP ${inlineOk.code} ===")
        assumeTrue("Zoho: could not seed photo contacts", urlOk.ok && inlineOk.ok)

        val hrefs = collectHrefs(c, book.url)
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()

        val urlSeed = read.firstOrNull { it.contact.uid == URL_PHOTO_UID }
        val inlineSeed = read.firstOrNull { it.contact.uid == INLINE_PHOTO_UID }

        urlSeed?.let {
            val photo = it.contact.photo
            val mapped = VCardContactMapper.toEntity(it.contact)
            println(
                "=== Zoho URI photo: preservedAsUrl=${photo?.url != null} " +
                    "(matchesSeed=${photo?.url == EXPECTED_PHOTO_URL}), " +
                    "hasInlineBytes=${photo?.data != null}, " +
                    "mappedToPhotoUrl=${mapped.photoUrl == EXPECTED_PHOTO_URL} ===",
            )
        } ?: println("=== Zoho URI photo: seed $URL_PHOTO_UID not read back ===")

        inlineSeed?.let {
            val photo = it.contact.photo
            val mapped = VCardContactMapper.toEntity(it.contact)
            println(
                "=== Zoho inline photo: hasInlineBytes=${photo?.data != null} " +
                    "(byteCount=${photo?.data?.size ?: 0}), " +
                    "rewrittenToUrl=${photo?.url != null}, " +
                    "mappedPhotoUrl=${mapped.photoUrl != null} ===",
            )
        } ?: println("=== Zoho inline photo: seed $INLINE_PHOTO_UID not read back ===")
    }

    /**
     * Characterizes whether Zoho's `addressbook-multiget` returns bodies for a multi-href batch or
     * only for a single href. That decides whether [CardDavContactReader] needs the per-href
     * fallback the CalDAV pull has: `PullStrategy.fetchEventsBatched` retries single-href when a
     * multi-href `calendar-multiget` comes back as an empty 200, Zoho's documented calendar quirk.
     * The walk test seeds one contact, which can't surface this: the CalDAV guard only fires for
     * `batch.size > 1`.
     *
     * Seeds 25 contacts, then records, never asserts, three reads:
     *  - a single-href `fetchContactsByHref` (control: the resource is fetchable)
     *  - a `fetchContactsByHref` over every seed href (more than [MULTIGET_PAGE_SIZE]), sent
     *    directly so the reader's chunking can't hide an empty multi-href response
     *  - the production [CardDavContactReader.readContacts] over the same hrefs (chunked)
     *
     * Multi-href 0 while single-href is above 0 means Zoho shares the calendar quirk on
     * CardDAV and the reader needs a single-href fallback. Multi-href returning the full batch
     * means the gap is a generic-CardDAV robustness item, not a Zoho one.
     */
    @Test
    fun `characterizes Zoho's multi-href addressbook-multiget batch behavior`() = runBlocking {
        assumeTrue("Zoho: no credentials in local.properties", client != null)
        val c = client!!
        val cr = creds!!
        assumeTrue(
            "Zoho: contacts host unreachable at ${cr.davEndpoint}",
            CardDavTestServerLoader.isServerReachable(cr.davEndpoint),
        )

        val book = resolveWritableBook(c, cr)
        assumeTrue("Zoho: no writable address book to seed a batch into", book != null)
        val bookBase = book!!.url.trimEnd('/')

        // Seed more than one multiget page. Zoho requires <UID>.vcf hrefs; arbitrary
        // filenames get 401 (see the walk test).
        var seededCount = 0
        for (i in 0 until BATCH_SEED_COUNT) {
            val uid = batchUid(i)
            val outcome = putSeed(bookBase + "/$uid.vcf", batchBody(uid), VCARD_MEDIA_TYPE, false, cr)
            if (outcome.ok) seededCount++ else println("=== Zoho batch seed [$i] -> HTTP ${outcome.code}${outcome.bodyNote} ===")
        }
        println("=== Zoho CardDAV batch: seeded $seededCount / $BATCH_SEED_COUNT contact(s) ===")
        assumeTrue("Zoho: could not seed a multi-page batch", seededCount > MULTIGET_PAGE_SIZE)

        val hrefs = collectHrefs(c, book.url)
        val batchHrefs = hrefs.filter { it.substringAfterLast('/').removeSuffix(".vcf").startsWith(BATCH_UID_PREFIX) }
        println("=== Zoho CardDAV batch: collected ${batchHrefs.size} seed href(s) of ${hrefs.size} total ===")
        assumeTrue("Zoho: fewer seed hrefs than a full page — cannot exercise a multi-href read", batchHrefs.size > MULTIGET_PAGE_SIZE)

        // Control: a single-href multiget proves the resources are individually fetchable.
        val single = c.fetchContactsByHref(book.url, listOf(batchHrefs.first()), book.vcardVersion)
        val singleCount = (single as? CalDavResult.Success)?.data?.size ?: -1
        println("=== Zoho CardDAV batch: single-href multiget -> ${resultShape(single)} (bodies=$singleCount) ===")

        // The measurement: a multi-href multiget larger than a page, sent directly so the
        // reader's chunking can't mask an empty multi-href response.
        val multi = c.fetchContactsByHref(book.url, batchHrefs, book.vcardVersion)
        val multiCount = (multi as? CalDavResult.Success)?.data?.size ?: -1
        println("=== Zoho CardDAV batch: multi-href multiget (${batchHrefs.size} hrefs) -> ${resultShape(multi)} (bodies=$multiCount) ===")

        // The production read path (chunked at MULTIGET_PAGE_SIZE) for comparison.
        val read = (reader.readContacts(book.url, batchHrefs, book.vcardVersion) as? CalDavResult.Success)?.data?.contacts.orEmpty()
        val seedsRead = read.count { it.contact.uid.startsWith(BATCH_UID_PREFIX) }
        println("=== Zoho CardDAV batch: chunked reader read back $seedsRead / ${batchHrefs.size} seed(s) ===")

        val quirkPresent = singleCount > 0 && multiCount == 0
        println(
            "=== Zoho CardDAV batch VERDICT: empty-multi-href-quirk=$quirkPresent " +
                "(single=$singleCount, multi=$multiCount, chunkedReader=$seedsRead) " +
                "-> CardDavContactReader ${if (quirkPresent) "NEEDS" else "does NOT need"} a single-href fallback ===",
        )
    }

    /** Returns a [CalDavResult]'s outcome shape for logging, never its PII-bearing body. */
    private fun resultShape(result: CalDavResult<*>): String = when (result) {
        is CalDavResult.Success -> "Success"
        is CalDavResult.Error -> "Error(${result.code})"
    }

    /** Returns the login's first writable address book, else its first book, or null if none. */
    private suspend fun resolveWritableBook(c: CardDavClient, cr: ServerCredentials) = run {
        val root = c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        val principal = c.discoverPrincipal(root).getOrNull() ?: return@run null
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return@run null
        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        if (books.isEmpty()) return@run null
        books.firstOrNull { !it.isReadOnly } ?: books.first()
    }

    private class SeedAttempt(
        val label: String,
        val url: String,
        val body: String,
        val mediaType: okhttp3.MediaType,
        val ifNoneMatch: Boolean,
    )

    private class SeedOutcome(val ok: Boolean, val code: Int, val bodyNote: String)

    /**
     * PUTs a synthetic seed with basic auth, capturing the status and, on a rejection, a
     * short redacted body snippet so the cause is legible (Zoho commonly answers 4xx with an
     * explanatory XML or text error). Any 2xx (201 created, 204 updated) counts as written;
     * so does 412, which still proves writes are permitted, though with a different If-*
     * header. An exception gives code -1.
     */
    private fun putSeed(
        url: String,
        body: String,
        mediaType: okhttp3.MediaType,
        ifNoneMatch: Boolean,
        cr: ServerCredentials,
    ): SeedOutcome = try {
        val builder = Request.Builder()
            .url(url)
            .put(body.toRequestBody(mediaType))
            .header("Authorization", OkHttpCredentials.basic(cr.username, cr.password, Charsets.UTF_8))
        if (ifNoneMatch) builder.header("If-None-Match", "*")
        OkHttpClient().newCall(builder.build()).execute().use { resp ->
            val ok = resp.isSuccessful || resp.code == 412 || resp.code == 204
            val note = if (ok) "" else {
                val snippet = resp.body?.string().orEmpty().take(200).replace(Regex("""\s+"""), " ").trim()
                if (snippet.isEmpty()) "" else " body='" + redactPii(snippet) + "'"
            }
            SeedOutcome(ok, resp.code, note)
        }
    } catch (e: Exception) {
        SeedOutcome(false, -1, " exception=" + (e.message ?: e.javaClass.simpleName))
    }

    /**
     * Masks every non-`@example.test` address so a Zoho-echoed account address never reaches
     * junit-xml or CI logs.
     */
    private fun redactPii(text: String): String {
        val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        return emailRegex.replace(text) { m ->
            if (m.value.endsWith("@example.test")) m.value else "<redacted>@<redacted>"
        }
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

    /** Returns a URL's scheme and host for logging, with the account-identifying path masked. */
    private fun hostShape(url: String?): String =
        url?.let { Regex("""^(\w+://[^/]+)""").find(it)?.groupValues?.get(1)?.plus("/<path>") ?: "<opaque>" }
            ?: "(none)"

    private companion object {
        private const val SEED_UID = "kashcal-seed-0001"
        private const val SEED_FILENAME = "kashcal_seed_0001.vcf"
        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()
        private val X_VCARD_MEDIA_TYPE = "text/x-vcard".toMediaType()

        /** The shared fixture is vCard 3.0; used verbatim for the primary attempt. */
        private val SEED_BODY_V3: String =
            MultiServerCardDavZohoProbeTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$SEED_FILENAME")!!
                .use { it.readBytes().decodeToString() }

        /** The same contact as vCard 4.0; only the VERSION line changes. */
        private val SEED_BODY_V4: String = SEED_BODY_V3.replace("VERSION:3.0", "VERSION:4.0")

        // Photo seeds: shared synthetic fixtures reused from the parameterized probe.
        private const val URL_PHOTO_UID = "kashcal-seed-photo-url-0002"
        private const val INLINE_PHOTO_UID = "kashcal-seed-photo-inline-0003"
        private const val EXPECTED_PHOTO_URL = "https://photos.example.test/seed/kashcal-url.jpg"

        private fun fixture(name: String): String =
            MultiServerCardDavZohoProbeTest::class.java.classLoader!!
                .getResourceAsStream("carddav/fixtures/$name")!!
                .use { it.readBytes().decodeToString() }

        private val URL_PHOTO_BODY: String by lazy { fixture("kashcal_seed_photo_url_0002.vcf") }
        private val INLINE_PHOTO_BODY: String by lazy { fixture("kashcal_seed_photo_inline_0003.vcf") }

        /**
         * Copies the production reader's private batch size by hand. An exact match isn't
         * required: any value at or above the real one still forces a multi-page batch.
         */
        private const val MULTIGET_PAGE_SIZE = 20

        /** Seed comfortably past one page so the multi-href read spans batches. */
        private const val BATCH_SEED_COUNT = 25
        private const val BATCH_UID_PREFIX = "kashcal-seed-batch-"

        private fun batchUid(index: Int): String = BATCH_UID_PREFIX + index.toString().padStart(4, '0')

        /** Minimal synthetic vCard 3.0 (no email/PII); UID must round-trip for read-back. */
        private fun batchBody(uid: String): String =
            "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "UID:$uid\r\n" +
                "FN:Batch Seed $uid\r\n" +
                "N:Seed;Batch;;;\r\n" +
                "END:VCARD\r\n"
    }
}
