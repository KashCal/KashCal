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
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.vcard.VCardParser
import org.onekash.vcard.VCardWriter
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.Photo
import org.onekash.vcard.model.StructuredName
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

/**
 * Live PUSH-side characterization of contact PHOTO handling — the complement to
 * [MultiServerCardDavPhotoProbeTest], which seeds via a raw authenticated PUT and
 * characterizes only the READ path. This test drives the *application* write verb
 * end-to-end: it builds a neutral [Contact] carrying a [Photo], serializes it
 * through the production [VCardWriter], uploads it with [CardDavClient.putContact],
 * reads it back through [CardDavContactReader], and records what each server did to
 * the photo. That is the exact path a later photo-push feature would ride, and the
 * risks it surfaces (a serializer that mislabels a format, a server that transcodes
 * or rewrites inline bytes to a minted URL, a size ceiling) live only on the write
 * side and are invisible to the read-only probe.
 *
 * Four axes, each its own test:
 *  1. **Format matrix** — JPEG/PNG/GIF (genuinely valid rasters), plus WebP and
 *     HEIF (signature-correct blobs). The writer's magic-byte sniffer now recognizes
 *     WebP and HEIF, so an un-typed one is labeled with its true format (webp/heic)
 *     rather than defaulting to JPEG — asserted deterministically client-side. Each
 *     is pushed with and without a declared content type.
 *  2. **Adversarial** — empty bytes, a truncated image, non-image bytes, a
 *     bytes/label mismatch, and an oversized (~1 MB) inline photo. Records the
 *     server's accept/reject status code without ever throwing.
 *  3. **URI reference** — a `PHOTO;VALUE=URI`/bare-URL photo through the writer;
 *     does the server preserve, inline, rewrite, or drop it?
 *  4. **Patch fidelity** — editing ONLY the photo bytes on a contact that carries a
 *     verbatim prior body must change the PHOTO and preserve an unmapped X-property
 *     (the round-trip guarantee the writer exists to keep).
 *  5. **Convergence** — after pushing inline bytes, does the read-back photo match
 *     byte-for-byte (stable), or did the server transcode / mint a URL (which would
 *     make the next sync see the photo as changed and re-push or overwrite)?
 *
 * Everything is synthetic: RFC 6761 reserved `@example.test`, an unassigned
 * `+1-555-01xx` number, `photos.example.test` URLs that resolve to nothing, and
 * generated images. No real person or asset is contacted. Photo bytes are never
 * printed; a server-minted photo URL is host-redacted before it hits any log.
 *
 * Skips (never fails) servers without credentials, unreachable, or with no writable
 * address book. Parameterized over the widest configured set so hosted providers
 * (iCloud, Zoho, Fastmail, mailbox.org) are characterized alongside the local
 * Docker servers when their credentials are present.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavPhotoPushProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavPhotoPushProbeTest(
    private val config: CardDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CardDavServerConfig.allDiscoveryProbeServers().map { arrayOf<Any>(it) }

        private const val EXP_FAMILY = "Photopush"
        private const val EXP_GIVEN = "Kashcal"
        private const val PUSH_PHOTO_URL = "https://photos.example.test/push/kashcal-push.jpg"

        /** Canonical 1x1 transparent PNG (67 bytes) — a genuinely valid raster, no encoder needed. */
        private val PNG_1x1: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )

        /** Canonical 1x1 GIF89a (43 bytes) — genuinely valid, no encoder needed. */
        private val GIF_1x1: ByteArray = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )

        /**
         * A RIFF/WEBP-signature blob. Not a decodable frame — WebP has no JDK
         * encoder — but it carries the `RIFF....WEBP` magic, which is what a server's
         * (or our sniffer's) format detection keys on. A verbatim-store server keeps
         * it; a validating server rejecting it is itself a recorded finding.
         */
        private val WEBP_SIG: ByteArray = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x1A, 0x00, 0x00, 0x00) +
            "WEBPVP8 ".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x0E, 0x00, 0x00, 0x00, 0x10, 0x14, 0x10, 0x00, 0x01, 0x00, 0x01, 0x00, 0x02, 0x00)

        /** An ISOBMFF `ftyp` box with the `heic` major brand — HEIF's signature. */
        private val HEIF_SIG: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x18) +
            "ftypheic".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "heicmif1".toByteArray(Charsets.US_ASCII)

        /** Encode a valid raster of [format] via the JDK; empty array if the codec is unavailable. */
        private fun encode(format: String, w: Int, h: Int, noise: Boolean): ByteArray {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val rnd = Random(42)
            for (y in 0 until h) for (x in 0 until w) {
                img.setRGB(x, y, if (noise) rnd.nextInt() else 0xFF3366)
            }
            val baos = ByteArrayOutputStream()
            return if (ImageIO.write(img, format, baos)) baos.toByteArray() else ByteArray(0)
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

    /** One image variant to push: [label] for logging, [bytes], and a declared [contentType] (or null). */
    private data class PushImage(val label: String, val bytes: ByteArray, val contentType: String?)

    // -----------------------------------------------------------------------------------------------
    // 1. Format matrix — valid rasters + the un-sniffed WebP/HEIF, with and without a declared type.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `pushes each image format through the app write verb and records the server round-trip`() = runBlocking {
        assumeReady()
        val (c, book) = readyBook() ?: return@runBlocking

        val jpeg = encode("jpeg", 4, 4, noise = false)
        val cases = buildList {
            if (jpeg.isNotEmpty()) add(PushImage("jpeg-typed", jpeg, "jpeg"))
            add(PushImage("png-typed", PNG_1x1, "png"))
            add(PushImage("png-untyped", PNG_1x1, null))       // sniffer path: recognizes PNG magic
            add(PushImage("gif-typed", GIF_1x1, "gif"))
            add(PushImage("webp-typed", WEBP_SIG, "webp"))
            add(PushImage("webp-untyped", WEBP_SIG, null))     // sniffer path: recognizes WebP magic -> labeled webp
            add(PushImage("heif-typed", HEIF_SIG, "heic"))
            add(PushImage("heif-untyped", HEIF_SIG, null))     // sniffer path: recognizes HEIF magic -> labeled heic
        }

        for (img in cases) {
            val uid = "kashcal-photopush-fmt-${img.label}"
            val contact = photoContact(uid, book.vcardVersion, Photo(data = img.bytes, contentType = img.contentType))
            val body = VCardWriter().write(contact, book.vcardVersion)

            // Deterministic (no network): the writer MUST serialize a non-empty inline PHOTO.
            val photoLine = photoLineOf(body)
            assertNotNull("${config.name}/${img.label}: writer emitted no PHOTO for an inline photo", photoLine)
            assertTrue(
                "${config.name}/${img.label}: writer emitted a PHOTO with no content",
                photoLine!!.substringAfter(":", "").isNotBlank(),
            )
            // Version-agnostic label: 3.0 carries TYPE=<fmt>; 4.0 carries a data:image/<fmt> URI.
            val writtenType = paramValue(photoLine, "TYPE")
                ?: Regex("""data:image/([A-Za-z0-9]+)""").find(photoLine)?.groupValues?.get(1)?.lowercase()

            // The magic-byte sniffer now recognizes WebP and HEIF, so a photo pushed WITHOUT a
            // declared content type is no longer mislabeled JPEG. Assert the true format rode
            // through the writer, deterministically (no network).
            when (img.label) {
                "webp-untyped" -> assertEquals(
                    "${config.name}: un-typed WebP mislabeled (expected webp)", "webp", writtenType,
                )
                "heif-untyped" -> assertEquals(
                    "${config.name}: un-typed HEIF mislabeled (expected heic)", "heic", writtenType,
                )
            }

            val resourceUrl = resourceUrlFor(book, uid)
            val upload = putIdempotent(c, book.url, resourceUrl, body)
            try {
                val readback = if (upload is ContactUploadResult.Success) readContact(c, book, uid) else null
                println(
                    "=== PHOTO-PUSH ${config.name} fmt=${img.label} writtenType=$writtenType " +
                        "upload=${describe(upload)} -> ${describePhoto(readback?.photo, img.bytes)} ===",
                )
                if (upload is ContactUploadResult.Success) {
                    // Serialization-corruption invariant: an accepted card must read back
                    // with its identity intact (the photo shape itself is characterized).
                    assertNotNull("${config.name}/${img.label}: accepted push not found on read-back", readback)
                    assertTrue(
                        "${config.name}/${img.label}: identity field N corrupted by the photo push",
                        readback!!.structuredName.family == EXP_FAMILY,
                    )
                }
            } finally {
                cleanup(c, book.url, resourceUrl)
            }
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 2. Adversarial — degenerate / hostile photo payloads. Record the status; never throw.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `adversarial photo payloads through the app write verb are handled without corruption`() = runBlocking {
        assumeReady()
        val (c, book) = readyBook() ?: return@runBlocking

        val oversized = encode("png", 700, 700, noise = true) // ~1 MB incompressible raster
        val cases = buildList {
            add(PushImage("empty-bytes", ByteArray(0), "jpeg"))
            add(PushImage("truncated-png", PNG_1x1.copyOf(24), "png"))
            add(PushImage("nonimage-labeled-jpeg", "this is plainly not an image".toByteArray(), "jpeg"))
            add(PushImage("bytes-label-mismatch", PNG_1x1, "jpeg")) // real PNG bytes, declared JPEG
            if (oversized.isNotEmpty()) add(PushImage("oversized-~1MB", oversized, "png"))
        }

        for (img in cases) {
            val uid = "kashcal-photopush-adv-${img.label}"
            val contact = photoContact(uid, book.vcardVersion, Photo(data = img.bytes, contentType = img.contentType))
            val body = VCardWriter().write(contact, book.vcardVersion)
            val resourceUrl = resourceUrlFor(book, uid)

            val upload = putIdempotent(c, book.url, resourceUrl, body)
            try {
                val readback = if (upload is ContactUploadResult.Success) readContact(c, book, uid) else null
                println(
                    "=== PHOTO-PUSH ${config.name} adversarial=${img.label} bytes=${img.bytes.size} " +
                        "upload=${describe(upload)}" +
                        (readback?.let { " readbackPhoto=${describePhoto(it.photo, img.bytes)}" } ?: "") +
                        " ===",
                )
                // The write verb must always return a well-formed outcome (a transport
                // crash surfaces as Failed(code=0), not an exception) — asserted by
                // simply reaching here. If the server ACCEPTED the payload, the rest of
                // the contact must not be collateral damage.
                if (upload is ContactUploadResult.Success) {
                    assertNotNull("${config.name}/${img.label}: accepted adversarial push vanished on read-back", readback)
                    assertTrue(
                        "${config.name}/${img.label}: identity field N corrupted by an adversarial photo",
                        readback!!.structuredName.family == EXP_FAMILY,
                    )
                }
            } finally {
                cleanup(c, book.url, resourceUrl)
            }
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 3. URI-reference photo push.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `pushes a URI-reference photo and records whether the server preserves it`() = runBlocking {
        assumeReady()
        val (c, book) = readyBook() ?: return@runBlocking

        val uid = "kashcal-photopush-uri-0001"
        val contact = photoContact(uid, book.vcardVersion, Photo(url = PUSH_PHOTO_URL))
        val body = VCardWriter().write(contact, book.vcardVersion)

        // Deterministic: the writer must emit a PHOTO carrying the URL verbatim.
        val photoLine = photoLineOf(body)
        assertNotNull("${config.name}: writer emitted no PHOTO for a URI photo", photoLine)
        assertTrue(
            "${config.name}: writer dropped the photo URL from the serialized PHOTO",
            photoLine!!.contains(PUSH_PHOTO_URL),
        )

        val resourceUrl = resourceUrlFor(book, uid)
        val upload = putIdempotent(c, book.url, resourceUrl, body)
        try {
            val readback = if (upload is ContactUploadResult.Success) readContact(c, book, uid) else null
            val p = readback?.photo
            println(
                "=== PHOTO-PUSH ${config.name} uri upload=${describe(upload)} " +
                    "readbackUrl=${redactPhotoUrl(p?.url)} hasInlineBytes=${p?.data != null} " +
                    "preservedVerbatim=${p?.url == PUSH_PHOTO_URL} ===",
            )
            if (upload is ContactUploadResult.Success) {
                assertNotNull("${config.name}: URI-photo push not found on read-back", readback)
            }
        } finally {
            cleanup(c, book.url, resourceUrl)
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 4. Patch fidelity — editing ONLY the photo must preserve an unmapped X-property.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `editing only the photo preserves unmapped properties and changes the PHOTO`() = runBlocking {
        assumeReady()
        val (c, book) = readyBook() ?: return@runBlocking

        val uid = "kashcal-photopush-patch-0001"
        // A prior body carrying a mapped photo AND an unmapped X-property + grouping the
        // neutral model does not represent. The writer must patch, not regenerate.
        val priorBody = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("UID:$uid\r\n")
            append("FN:$EXP_GIVEN $EXP_FAMILY\r\n")
            append("N:$EXP_FAMILY;$EXP_GIVEN;;;\r\n")
            append("item1.X-ABLABEL:custom-label\r\n")
            append("X-KASHCAL-PROBE:keep-me-verbatim\r\n")
            append("PHOTO;ENCODING=b;TYPE=GIF:").append(base64(GIF_1x1)).append("\r\n")
            append("END:VCARD\r\n")
        }
        val parsed = VCardParser().parse(priorBody).single()
        val edited = parsed.copy(photo = Photo(data = PNG_1x1, contentType = "png"))
        val body = VCardWriter().write(edited, "3.0")

        // Deterministic patch guarantees: the unmapped X-property survives, and the PHOTO
        // actually changed (GIF -> PNG bytes).
        assertTrue(
            "${config.name}: photo-only edit dropped the unmapped X-KASHCAL-PROBE property",
            unfold(body).contains("X-KASHCAL-PROBE:keep-me-verbatim"),
        )
        assertTrue(
            "${config.name}: photo-only edit dropped the item1.X-ABLABEL grouping",
            unfold(body).contains("X-ABLABEL:custom-label"),
        )
        val newPhotoLine = photoLineOf(body)
        assertNotNull("${config.name}: patched body lost its PHOTO", newPhotoLine)
        assertTrue(
            "${config.name}: photo-only edit did not change the PHOTO content",
            newPhotoLine!!.substringAfter(":", "") != photoLineOf(priorBody)?.substringAfter(":", ""),
        )

        // Server round-trip is characterization: does the server keep the unmapped X-prop too?
        val resourceUrl = resourceUrlFor(book, uid)
        val upload = putIdempotent(c, book.url, resourceUrl, body)
        try {
            println("=== PHOTO-PUSH ${config.name} patch upload=${describe(upload)} (X-prop + PHOTO change asserted client-side) ===")
        } finally {
            cleanup(c, book.url, resourceUrl)
        }
    }

    // -----------------------------------------------------------------------------------------------
    // 5. Convergence — would the next sync see the pushed photo as unchanged, or re-push/overwrite?
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `characterizes whether an inline photo survives push byte-for-byte or would oscillate`() = runBlocking {
        assumeReady()
        val (c, book) = readyBook() ?: return@runBlocking

        val uid = "kashcal-photopush-converge-0001"
        val contact = photoContact(uid, book.vcardVersion, Photo(data = PNG_1x1, contentType = "png"))
        val body = VCardWriter().write(contact, book.vcardVersion)
        val resourceUrl = resourceUrlFor(book, uid)

        val upload = putIdempotent(c, book.url, resourceUrl, body)
        assumeTrue("${config.name}: server would not accept the inline-photo push", upload is ContactUploadResult.Success)
        try {
            val p = readContact(c, book, uid)?.photo
            val verdict = when {
                p == null -> "PHOTO DROPPED — pushed inline bytes came back with no photo"
                p.url != null && p.data == null ->
                    "URL MINTED — server replaced inline bytes with a URL; next sync's byte-vs-URL " +
                        "compare would treat the photo as changed (re-push / server-wins overwrite risk)"
                p.data != null && p.data!!.contentEquals(PNG_1x1) ->
                    "STABLE — inline bytes preserved byte-for-byte; no re-push"
                p.data != null ->
                    "TRANSCODED — inline bytes changed (${PNG_1x1.size} -> ${p.data!!.size}); " +
                        "next sync would see the photo as changed (re-push risk)"
                else -> "UNKNOWN shape"
            }
            println("=== PHOTO-PUSH ${config.name} convergence: $verdict ===")
        } finally {
            cleanup(c, book.url, resourceUrl)
        }
    }

    // ------------------------------- shared helpers -------------------------------

    /** Build a synthetic, fully-named contact carrying [photo] at [version]. */
    private fun photoContact(uid: String, version: String, photo: Photo): Contact = Contact(
        version = version,
        uid = uid,
        structuredName = StructuredName(family = EXP_FAMILY, given = EXP_GIVEN),
        displayName = "$EXP_GIVEN $EXP_FAMILY",
        photo = photo,
        rawVCard = "",
    )

    /** Assume-ready + resolve a writable book; returns null (via a skip) when there is none. */
    private suspend fun readyBook(): Pair<CardDavClient, CardDavAddressBook>? {
        val c = client!!
        val book = resolveWritableBook(c, creds!!)
        assumeTrue("${config.name}: no writable address book to push a photo into", book != null)
        return c to book!!
    }

    private fun resourceUrlFor(book: CardDavAddressBook, uid: String): String =
        book.url.trimEnd('/') + "/" + contactResourceName(uid)

    /** Idempotent conditional PUT: create-if-absent, else overwrite the leftover by its current etag. */
    private suspend fun putIdempotent(
        c: CardDavClient,
        bookUrl: String,
        resourceUrl: String,
        body: String,
    ): ContactUploadResult =
        when (val first = c.putContact(resourceUrl, body, ContactPrecondition.IfAbsent)) {
            is ContactUploadResult.PreconditionFailed -> {
                val etag = currentEtag(c, bookUrl, resourceUrl)
                if (etag != null) c.putContact(resourceUrl, body, ContactPrecondition.IfMatch(etag)) else first
            }
            else -> first
        }

    private suspend fun readContact(
        c: CardDavClient,
        book: CardDavAddressBook,
        uid: String,
    ): Contact? {
        val hrefs = collectHrefs(c, book.url)
        val read = (reader.readContacts(book.url, hrefs, book.vcardVersion) as? CalDavResult.Success)
            ?.data?.contacts.orEmpty()
        return read.firstOrNull { it.contact.uid == uid }?.contact
    }

    private suspend fun cleanup(c: CardDavClient, bookUrl: String, resourceUrl: String) {
        currentEtag(c, bookUrl, resourceUrl)?.let { c.deleteContact(resourceUrl, it) }
    }

    /** Compact one-line description of an upload outcome; never leaks a server response body. */
    private fun describe(r: ContactUploadResult): String = when (r) {
        is ContactUploadResult.Success -> "Success"
        is ContactUploadResult.PreconditionFailed -> "PreconditionFailed(412/409)"
        is ContactUploadResult.PermissionDenied -> "PermissionDenied(403)"
        is ContactUploadResult.Gone -> "Gone(404/410)"
        is ContactUploadResult.Failed -> "Failed(code=${r.code})"
    }

    /** How the server round-tripped a pushed photo; no bytes, host-redacted URL only. */
    private fun describePhoto(p: Photo?, pushed: ByteArray): String = when {
        p == null -> "dropped"
        p.url != null && p.data == null -> "minted-url=${redactPhotoUrl(p.url)}"
        p.data != null && p.data!!.contentEquals(pushed) -> "inline-preserved(${p.data!!.size}B)"
        p.data != null -> "inline-transcoded(${pushed.size}->${p.data!!.size}B)"
        else -> "empty"
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** Unfold RFC 6350 §3.2 continuation lines so a long/folded PHOTO reads as one logical line. */
    private fun unfold(body: String): String = body.replace(Regex("""\r?\n[ \t]"""), "")

    /** The single logical PHOTO line of a (possibly folded) body, or null. */
    private fun photoLineOf(body: String): String? =
        unfold(body).lineSequence().firstOrNull { it.startsWith("PHOTO") }

    /** Value of the [name] parameter on a property line (e.g. TYPE), or null. */
    private fun paramValue(line: String, name: String): String? =
        Regex("""(?:;|^[^:]*;)$name=([^;:]+)""", RegexOption.IGNORE_CASE)
            .find(line.substringBefore(':'))?.groupValues?.get(1)

    /** True when [url] is one of our synthetic `*.example.test` URLs (resolves to nothing). */
    private fun isSyntheticSeedUrl(url: String): Boolean {
        val host = Regex("""^\w+://([^/:]+)""").find(url)?.groupValues?.get(1) ?: return false
        return host == "example.test" || host.endsWith(".example.test")
    }

    /** Print-safe photo URL: synthetic seed URLs verbatim; a server-minted URL host-redacted. */
    private fun redactPhotoUrl(url: String?): String? {
        if (url == null) return null
        if (isSyntheticSeedUrl(url)) return url
        return Regex("""^(\w+://[^/]+)/.*$""").find(url)?.let { "${it.groupValues[1]}/<redacted>" } ?: "<redacted>"
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
