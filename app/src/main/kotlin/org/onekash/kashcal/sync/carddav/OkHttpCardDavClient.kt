package org.onekash.kashcal.sync.carddav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.onekash.kashcal.data.contacts.MAX_PHOTO_SIZE_BYTES
import org.onekash.kashcal.network.DavMultistatus
import org.onekash.kashcal.network.DavTransportRefusedException
import org.onekash.kashcal.network.ResponseTooLargeException
import org.onekash.kashcal.network.readBoundedBody
import org.onekash.kashcal.network.readBoundedBytes
import org.onekash.kashcal.network.wellKnownEndpoint
import org.onekash.kashcal.network.withoutDavTransportGuard
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactSyncItem
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.util.EtagUtils
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLHandshakeException

/**
 * Resolves a URL's registrable (public-suffix + 1) domain, or null when it has none (a bare IP
 * or a public-suffix host) or the public-suffix data is unavailable.
 */
typealias RegistrableDomainResolver = (HttpUrl) -> String?

/**
 * Resolves registrable domains from OkHttp's public-suffix database
 * ([okhttp3.HttpUrl.topPrivateDomain]); the production [RegistrableDomainResolver].
 *
 * Fails closed. On the `okhttp-android` artifact this app resolves, `topPrivateDomain()` loads
 * its public-suffix list from an Android asset via app-startup: present on device, absent in a
 * JVM unit-test worker, where the call throws `IllegalStateException`. Any failure maps to null
 * so [shouldAttachCredentials] refuses a cross-host fetch rather than crashing the sync pass;
 * exact-host photo fetches still succeed. On device the asset loads, so this catch is a safety
 * net.
 */
val DefaultRegistrableDomainResolver: RegistrableDomainResolver = { url ->
    try {
        url.topPrivateDomain()
    } catch (_: Exception) {
        null
    }
}

/**
 * Returns whether the account credentials baked into the CardDAV client may be sent to
 * [photoUrl], given the client's CardDAV [endpointUrl]: true only when the hosts are equal or
 * resolve to the same registrable (public-suffix + 1) domain.
 *
 * A `PHOTO` URL is server-controlled, so this guards against credential leaks. iCloud serves
 * photos from `gateway.icloud.com` while its CardDAV hosts are `contacts.icloud.com` and
 * `pNN-contacts.icloud.com`: different hosts, same registrable `icloud.com`, so permitted. A
 * look-alike (`evil-icloud.com`) or suffix trick (`icloud.com.attacker.example`) resolves to a
 * different registrable domain and is refused. A null from [registrableDomainOf] (bare IP,
 * public-suffix host, unavailable list) refuses unless the hosts are equal. A malformed URL on
 * either side is refused.
 *
 * An https to http downgrade is always refused: an https endpoint's credentials must never
 * ride a cleartext photo GET, even to the same registrable domain. An http endpoint fetching
 * an http photo (local test servers) is permitted, since nothing is downgraded.
 */
fun shouldAttachCredentials(
    endpointUrl: String,
    photoUrl: String,
    registrableDomainOf: RegistrableDomainResolver = DefaultRegistrableDomainResolver,
): Boolean {
    val endpoint = endpointUrl.toHttpUrlOrNull() ?: return false
    val photo = photoUrl.toHttpUrlOrNull() ?: return false
    // Refuse an https -> http downgrade whatever the host or domain agreement below.
    if (endpoint.isHttps && !photo.isHttps) return false
    // An exact host match always qualifies: it covers IP and public-suffix hosts with no
    // registrable domain, and an unavailable list.
    if (endpoint.host.equals(photo.host, ignoreCase = true)) return true
    val endpointDomain = registrableDomainOf(endpoint) ?: return false
    val photoDomain = registrableDomainOf(photo) ?: return false
    return endpointDomain.equals(photoDomain, ignoreCase = true)
}

/**
 * Longest UID used as a resource-name segment before falling back to a random name. Well under
 * any server's path-length limit and long enough for real UID shapes (a UUID is 36 chars).
 */
private const val MAX_RESOURCE_NAME_UID_LENGTH = 200

/**
 * Characters permitted in a UID used verbatim as a `.vcf` resource-name segment: the RFC 3986
 * "unreserved" set minus `~`. Anything else (slash, space, `%`, `?`, `#`, `:`, `@`, `&`, `=`,
 * `+`, …) could change how the server parses the path, so such a UID isn't used verbatim.
 */
private val SAFE_RESOURCE_NAME_UID = Regex("[A-Za-z0-9._-]+")

/**
 * Names the resource for a contact whose vCard UID is [uid].
 *
 * A [uid] that is a safe path segment gives `<uid>.vcf`. Zoho rejects arbitrary resource names
 * with a misleading 401 (a name-policy refusal that looks like an auth failure), so a stable
 * UID-derived name keeps writes working there. A [uid] that isn't safe (blank, over
 * [MAX_RESOURCE_NAME_UID_LENGTH], `.` or `..`, or with a character outside
 * [SAFE_RESOURCE_NAME_UID]) gets a random `UUID.vcf`, which needs no percent-escaping.
 *
 * A device-created contact has no UID of its own (RFC 6350 §6.7.6 gives UID cardinality `*1`),
 * so the caller synthesizes a globally unique UID and persists it before the first PUT. Two
 * devices on the account then can't collide on one name the way a per-device RawContact `_ID`
 * would.
 */
fun contactResourceName(uid: String): String {
    safeResourceSegment(uid)?.let { return "$it.vcf" }
    return "${java.util.UUID.randomUUID()}.vcf"
}

/** Returns the trimmed [segment] if it is safe as a verbatim `.vcf` resource-name stem, or null. */
private fun safeResourceSegment(segment: String): String? {
    val trimmed = segment.trim()
    val safe = trimmed.isNotEmpty() &&
        trimmed.length <= MAX_RESOURCE_NAME_UID_LENGTH &&
        trimmed != "." &&
        trimmed != ".." &&
        SAFE_RESOURCE_NAME_UID.matches(trimmed)
    return if (safe) trimmed else null
}

/**
 * Reads and writes contacts on a CardDAV (RFC 6352) server over OkHttp.
 *
 * A standalone sibling of `OkHttpCalDavClient` inside `sync/carddav/`. It has the same HTTP
 * plumbing (retry and backoff, response mapping, well-known redirect cleaning, base-host
 * derivation) with CardDAV request bodies, but borrows no CalDAV client symbol
 * (`CardDavCalDavIsolationTest`); the duplicated WebDAV verbs are by design. It reuses the
 * generic [CalDavResult] envelope, [readBoundedBody], and the [CardDavQuirks] seam for parsing.
 *
 * [CardDavClientFactory] bakes the credentials into [httpClient]'s interceptor chain, so this
 * class holds none itself.
 */
class OkHttpCardDavClient(
    private val quirks: CardDavQuirks,
    private val httpClient: OkHttpClient,
) : CardDavClient {

    /**
     * A copy of [httpClient] for the photo GET only, with the same authenticated interceptor
     * chain and connection pool but no redirect following, so a server-controlled photo URL
     * can't bounce the account credentials to a foreign host (see [fetchPhoto]). The DAV
     * transport guard is removed too, since it would follow the redirect itself.
     */
    private val photoHttpClient: OkHttpClient by lazy {
        httpClient.withoutDavTransportGuard()
    }

    companion object {
        private const val TAG = "OkHttpCardDavClient"

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        /** Body content type for a contact PUT (RFC 6352 §6.3.2). */
        private val VCARD_MEDIA_TYPE = "text/vcard; charset=utf-8".toMediaType()

        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L

        /** PROPFIND body requesting `DAV:current-user-principal` (RFC 5397). */
        private val CURRENT_USER_PRINCIPAL_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        /**
         * Escapes a server-supplied value (sync-token, href) for request XML. The parser
         * XML-decodes these on the way in, so an unescaped `&`, `<` or `>` would produce
         * malformed XML the server rejects with 400.
         */
        private fun escapeXmlText(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        /**
         * Returns true only for raster image types the Contacts Photo column can render.
         * `image/svg+xml` has the `image/` prefix but is a vector XML document, so it's excluded.
         */
        private fun isRasterImageContentType(contentType: String): Boolean {
            val bare = contentType.substringBefore(';').trim().lowercase()
            return bare.startsWith("image/") && bare != "image/svg+xml"
        }
    }

    // ========== Discovery ==========

    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val baseHost = extractBaseHost(serverUrl)
            val wellKnownUrl = "$baseHost/.well-known/carddav"
            // Captured before any redirect, which a reverse proxy may send to another scheme.
            val originalScheme = baseHost.substringBefore("://")
            Log.d(TAG, "Trying well-known discovery: $wellKnownUrl")

            val request = Request.Builder()
                .url(wellKnownUrl)
                .method("PROPFIND", CURRENT_USER_PRINCIPAL_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            try {
                httpClient.newCall(request).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    Log.d(TAG, "Well-known response: ${resp.code}, final URL: $finalUrl")
                    when {
                        resp.isSuccessful || resp.code == 207 || resp.code == 401 || resp.code == 403 -> {
                            if (finalUrl != wellKnownUrl && !finalUrl.contains("/.well-known/")) {
                                CalDavResult.success(wellKnownEndpoint(finalUrl, originalScheme))
                            } else {
                                CalDavResult.success(serverUrl)
                            }
                        }
                        else -> CalDavResult.success(serverUrl)
                    }
                }
            } catch (e: DavTransportRefusedException) {
                // Not "no well-known here": the server sent us somewhere unsafe.
                transportRefused(e)
            } catch (e: Exception) {
                Log.w(TAG, "Well-known discovery failed: ${e.javaClass.simpleName}, using original URL")
                CalDavResult.success(serverUrl)
            }
        }

    override suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(serverUrl)
                .method("PROPFIND", CURRENT_USER_PRINCIPAL_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeWithRetry(request) { responseBody ->
                val principalPath = quirks.extractPrincipalUrl(responseBody)
                    ?: return@executeWithRetry CalDavResult.error(500, "Principal URL not found in response")
                val principalUrl = if (principalPath.startsWith("http")) {
                    principalPath
                } else {
                    "${extractBaseHost(serverUrl)}$principalPath"
                }
                CalDavResult.success(principalUrl)
            }
        }

    override suspend fun discoverAddressBookHome(principalUrl: String): CalDavResult<List<String>> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                    <d:prop>
                        <card:addressbook-home-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(principalUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeWithRetry(request) { responseBody ->
                val homePaths = quirks.extractAddressBookHomeUrls(responseBody)
                if (homePaths.isEmpty()) {
                    return@executeWithRetry CalDavResult.error(500, "Addressbook home URL not found in response")
                }
                val homeUrls = homePaths.map { homePath ->
                    if (homePath.startsWith("http")) homePath else "${extractBaseHost(principalUrl)}$homePath"
                }
                CalDavResult.success(homeUrls)
            }
        }

    override suspend fun listAddressBooks(addressBookHomeUrl: String): CalDavResult<List<CardDavAddressBook>> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav"
                            xmlns:cs="http://calendarserver.org/ns/">
                    <d:prop>
                        <d:displayname/>
                        <d:resourcetype/>
                        <card:addressbook-description/>
                        <cs:getctag/>
                        <d:current-user-privilege-set/>
                        <card:supported-address-data/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(addressBookHomeUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .build()

            executeWithRetry(request) { responseBody ->
                // Base host from the home URL, not an account root: iCloud serves address
                // books from a partition host (pNN-contacts.icloud.com), and hrefs must
                // resolve against the home's host.
                val baseHost = extractBaseHost(addressBookHomeUrl)
                val books = quirks.extractAddressBooks(responseBody).map { parsed ->
                    CardDavAddressBook(
                        href = parsed.href,
                        url = quirks.buildAddressBookUrl(parsed.href, baseHost),
                        displayName = parsed.displayName,
                        description = parsed.description,
                        ctag = parsed.ctag,
                        isReadOnly = parsed.isReadOnly,
                        vcardVersion = parsed.vcardVersion
                    )
                }
                CalDavResult.success(books)
            }
        }

    // ========== Change Detection ==========

    override suspend fun getCtag(addressBookUrl: String): CalDavResult<String?> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                    <d:prop>
                        <cs:getctag/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(addressBookUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeWithRetry(request) { responseBody ->
                CalDavResult.success(quirks.extractCtag(responseBody))
            }
        }

    override suspend fun getSyncToken(addressBookUrl: String): CalDavResult<String?> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:sync-token/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(addressBookUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeWithRetry(request) { responseBody ->
                CalDavResult.success(quirks.extractSyncToken(responseBody))
            }
        }

    // ========== Fetching ==========

    override suspend fun syncCollection(
        addressBookUrl: String,
        syncToken: String?
    ): CalDavResult<ContactSyncReport> = withContext(Dispatchers.IO) {
        val tokenElement = if (syncToken != null) {
            "<d:sync-token>${escapeXmlText(syncToken)}</d:sync-token>"
        } else {
            "<d:sync-token/>"
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:sync-collection xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                $tokenElement
                <d:sync-level>1</d:sync-level>
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:sync-collection>
        """.trimIndent()

        val request = Request.Builder()
            .url(addressBookUrl)
            // RFC 6578 §3.2: sync-collection is defined only with Depth "0"; the body's
            // <sync-level> sets the scope.
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.readBoundedBody()
            val responseCode = response.code
            val topLevel507 = responseCode == 507

            when {
                responseCode == 207 || topLevel507 -> {
                    // Both carry a multistatus (RFC 6578 section 3.6); a body that
                    // isn't one says nothing about what changed.
                    val reading = DavMultistatus.read(responseBody)
                    reading.problem?.let { problem ->
                        Log.w(TAG, "sync-collection: not a multistatus ($problem)")
                        return@withContext CalDavResult.notMultistatus(problem)
                    }
                    if (responseCode == 207 && quirks.isSyncTokenInvalid(207, responseBody)) {
                        return@withContext CalDavResult.error(403, "Sync token invalid", isRetryable = false)
                    }
                    val syncData = quirks.extractSyncCollectionData(responseBody)
                    val changed = syncData.changedItems.map { (href, etag) ->
                        ContactSyncItem(href = href, etag = etag)
                    }
                    // Truncation arrives two ways (RFC 6578 §3.6): a top-level HTTP 507, or an
                    // in-body 507 <status> on the collection's <response> inside a 207. Both
                    // count, so a large delta pages fully.
                    CalDavResult.success(
                        ContactSyncReport(
                            syncToken = syncData.syncToken,
                            changed = changed,
                            deleted = syncData.deletedHrefs,
                            truncated = topLevel507 || syncData.truncated || reading.truncated
                        )
                    )
                }
                responseCode in 200..299 -> CalDavResult.notMultistatus("status $responseCode, not 207")
                responseCode == 401 -> CalDavResult.authError("Authentication failed")
                responseCode == 403 || responseCode == 410 -> {
                    // On a sync-collection REPORT, 403/410 always means an expired sync-token
                    // (RFC 6578 §3.6). Not retryable: the caller falls back to a full listing.
                    CalDavResult.error(responseCode, "Sync token invalid", isRetryable = false)
                }
                else -> CalDavResult.error(responseCode, "sync-collection failed: $responseCode")
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun listAllContactHrefs(
        addressBookUrl: String
    ): CalDavResult<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
        // PROPFIND Depth:1 lists every member with its etag: the full listing. Request only
        // <d:getetag/>: as on the CalDAV side, adding <d:resourcetype/> makes iCloud emit a
        // per-member propstat-404 that bloats the response.
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(addressBookUrl)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        // rejectTruncated: an in-body 507 or a DAV:number-of-matches-within-limits
        // error (RFC 6578 section 3.6) on this listing is caught before parsing.
        executeWithRetry(request, rejectTruncated = true) { responseBody ->
            val data = quirks.extractSyncCollectionData(responseBody)
            if (data.truncated) {
                // RFC 6578 §3.6: an in-body 507 in an otherwise-207 multistatus marks the
                // listing truncated. rejectTruncated normally catches it first. Returning the
                // partial member set as Success would let the orphan sweep delete every
                // contact cut off the page, so it's a retryable failure: the book counts as
                // failed and the sweep is skipped.
                CalDavResult.error(507, "Full-listing PROPFIND truncated", isRetryable = true)
            } else {
                // The changed-items split skips the collection self-row by its trailing slash.
                CalDavResult.success(data.changedItems)
            }
        }
    }

    override suspend fun fetchContactsByHref(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String
    ): CalDavResult<List<CardDavContactData>> = withContext(Dispatchers.IO) {
        if (hrefs.isEmpty()) {
            return@withContext CalDavResult.success(emptyList())
        }

        // Drop the collection self-href. iCloud's sync-collection REPORT returns the
        // collection itself without a trailing slash or resourcetype, so the shared
        // parser's self-row filter misses it, and a multiget that includes the collection
        // href gets a 400 for the whole batch. Filter here, where the collection URL is known.
        val collectionPath = pathOf(addressBookUrl).trimEnd('/')
        val memberHrefs = hrefs.filter { pathOf(it).trimEnd('/') != collectionPath }
        if (memberHrefs.isEmpty()) {
            return@withContext CalDavResult.success(emptyList())
        }

        // Built without trimIndent(): the interpolated href lines would sit flush-left, leaving
        // whitespace before <?xml, which is invalid XML iCloud rejects with 400. The CalDAV
        // multiget body is built the same way.
        val body = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<card:addressbook-multiget xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">""")
            appendLine("""    <d:prop>""")
            appendLine("""        <d:getetag/>""")
            appendLine("""        <card:address-data content-type="text/vcard" version="$vcardVersion"/>""")
            appendLine("""    </d:prop>""")
            for (href in memberHrefs) {
                appendLine("""    <d:href>${escapeXmlText(href)}</d:href>""")
            }
            append("""</card:addressbook-multiget>""")
        }

        val request = Request.Builder()
            .url(addressBookUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            // RFC 6352 §8.7: addressbook-multiget targets the resources named by <href> in the
            // body, so the REPORT must be Depth: 0. The PROPFIND listing is Depth: 1.
            .header("Depth", "0")
            .build()

        executeWithRetry(request) { responseBody ->
            val contacts = quirks.extractAddressData(responseBody).map { parsed ->
                CardDavContactData(
                    href = parsed.href,
                    url = quirks.buildContactUrl(parsed.href, addressBookUrl),
                    etag = parsed.etag,
                    vcardBody = parsed.vcardBody
                )
            }
            CalDavResult.success(contacts)
        }
    }

    override suspend fun fetchPhoto(photoUrl: String): CalDavResult<PhotoBytes> =
        withContext(Dispatchers.IO) {
            // The shared client sends preemptive Basic and computes Digest on a 401, so a
            // foreign host gets no request at all; header suppression isn't trusted.
            if (!shouldAttachCredentials(quirks.baseUrl, photoUrl)) {
                Log.w(TAG, "Refusing photo fetch to a foreign host (credential-leak guard)")
                return@withContext CalDavResult.error(
                    0, "Photo URL host is not the CardDAV endpoint's domain", isRetryable = false
                )
            }

            val request = Request.Builder().url(photoUrl).get().build()
            try {
                // Never follow redirects on the photo GET. The shared client re-attaches
                // preemptive Basic auth on every network request, and OkHttp strips
                // Authorization only on a cross-host hop, so a same-host photo URL that
                // 302-redirects to a foreign host would leak the account credentials there.
                // The host guard above can't see the redirect target. The characterized
                // gateways serve the image with a direct 200.
                photoHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val contentType = response.header("Content-Type").orEmpty()
                            if (!isRasterImageContentType(contentType)) {
                                Log.w(TAG, "Photo fetch returned non-raster Content-Type; rejecting")
                                return@use CalDavResult.error(response.code, "Not a raster image response")
                            }
                            val bytes = response.readBoundedBytes(MAX_PHOTO_SIZE_BYTES)
                            if (bytes.isEmpty()) {
                                // A 0-byte 200 is a transient glitch, not "no photo": retryable,
                                // so the pending flag is kept and a later sync tries again.
                                // Writing the empty blob would pin a blank photo.
                                Log.w(TAG, "Photo fetch returned an empty body; rejecting")
                                return@use CalDavResult.error(
                                    response.code, "Empty image body", isRetryable = true
                                )
                            }
                            CalDavResult.success(PhotoBytes(bytes = bytes, contentType = contentType))
                        }
                        // A credential rotation fails the CardDAV read (same account
                        // credentials) before this GET is reached, so a 401 here is a
                        // transient photo-gateway rejection. Retryable, with code 401 kept so
                        // isAuthError() still recognizes it. Clearing the flag would lose the
                        // photo permanently: the gateway URL stays the same across a photo edit,
                        // so the vCard doesn't change and the flag never comes back.
                        response.code == 401 -> CalDavResult.error(
                            401, "Photo fetch unauthorized", isRetryable = true
                        )
                        response.code == 404 -> CalDavResult.notFoundError("Photo not found")
                        // Transient statuses stay retryable so the contact stays pending for a
                        // later sync: 5xx, 429 (the photo GET has no Retry-After backoff of its
                        // own) and 408. Any other code (e.g. 403, 410) is a refusal for this
                        // URL: permanent, so the fetcher clears the flag rather than looping
                        // every sync.
                        else -> CalDavResult.error(
                            response.code,
                            "Photo fetch failed: ${response.code}",
                            isRetryable = response.code in 500..599 ||
                                response.code == 429 ||
                                response.code == 408,
                        )
                    }
                }
            } catch (e: ResponseTooLargeException) {
                // Over the cap: a retry re-downloads the same oversized image forever, and it
                // could never be written to the Contacts blob. Non-retryable, so the fetcher
                // clears the pending flag; a later change to the contact's vCard re-arms it.
                Log.w(TAG, "Photo fetch rejected: body over the ${MAX_PHOTO_SIZE_BYTES / 1024}KB cap")
                CalDavResult.error(0, "Photo body over size cap", isRetryable = false)
            } catch (e: IOException) {
                // Transient transport failure (offline, reset, timeout): retryable, so the
                // photo stays pending for a later sync.
                Log.w(TAG, "Photo fetch failed: ${e.javaClass.simpleName}")
                CalDavResult.networkError("Photo fetch error: ${e.javaClass.simpleName}")
            }
        }

    // ========== Writing ==========

    // A write's Failed.isRetryable advises the caller; this client never retries a write
    // (see putContact). 5xx/429 mean the server rejected the request, so nothing landed and
    // a caller may re-derive the resource state (re-pull for a fresh etag) and try again. A
    // caller must not blindly re-send the same conditional write: if the original response
    // was lost in transit, the retry's If-None-Match:*/If-Match would fail the precondition
    // and misreport a real success.
    private fun isRetryableWriteStatus(code: Int): Boolean =
        code in 500..599 || code == 429

    // A stored etag must fit in an HTTP header value. OkHttp rejects any char outside HT and
    // visible ASCII (` `..`~`), so control chars and obs-text (%x80-FF), by throwing
    // IllegalArgumentException while building the request, which the IOException catches
    // don't catch. Server etags shouldn't carry such chars, but one malformed etag must not
    // throw and stall the account. The conditional header can't be expressed, so the
    // precondition can't hold: the write verbs return PreconditionFailed and the server wins
    // on the next pull.
    private fun isHeaderSafe(value: String): Boolean =
        value.all { it == '\t' || it in ' '..'~' }

    override suspend fun putContact(
        resourceUrl: String,
        vcardBody: String,
        precondition: ContactPrecondition,
    ): ContactUploadResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(resourceUrl)
            .put(vcardBody.toRequestBody(VCARD_MEDIA_TYPE))
        when (precondition) {
            // RFC 7232 §3.2: "*" matches any current representation, so If-None-Match:*
            // means "only if the resource doesn't exist yet".
            is ContactPrecondition.IfAbsent -> builder.header("If-None-Match", "*")
            // The stored etag is normalized (unquoted); quote it for the header, as the CalDAV
            // update path does.
            is ContactPrecondition.IfMatch -> {
                // Guard before OkHttp throws on an unusable etag (see isHeaderSafe).
                if (!isHeaderSafe(precondition.etag)) return@withContext ContactUploadResult.PreconditionFailed
                builder.header("If-Match", "\"${precondition.etag}\"")
            }
        }

        // Not routed through executeWithRetry: if the first attempt succeeded but the response
        // was lost, a retry's If-None-Match:*/If-Match would fail the precondition and
        // misreport a real success as PreconditionFailed.
        val request = builder.build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    // 201 on create; 200 or 204 on update. A conditional update PUT may
                    // answer 200, which the CalDAV update path accepts too.
                    200, 201, 204 -> {
                        // RFC 6352/4791: the server should return an ETag but may omit it. A
                        // null etag is fine; the next pull reconciles.
                        val etag = EtagUtils.normalizeEtag(response.header("ETag"))
                        // Where the server took it, when it redirected the PUT: the
                        // caller stores that href instead of the one it asked for.
                        val finalUrl = response.request.url.takeIf { it != request.url }?.toString()
                        ContactUploadResult.Success(etag, finalUrl = finalUrl)
                    }
                    // 412 is the conditional-header failure. 409 counts as one too because
                    // some servers answer a failed conditional write with 409; the server
                    // wins on the next pull either way. 409 can also mean a missing parent
                    // collection (RFC 4918 §9.7.1), which a pull won't fix, but that can't
                    // arise here: the collection URL comes from a just-discovered address book.
                    412, 409 -> ContactUploadResult.PreconditionFailed
                    403 -> ContactUploadResult.PermissionDenied
                    404, 410 -> ContactUploadResult.Gone
                    else -> ContactUploadResult.Failed(
                        response.code,
                        "putContact failed: ${response.code}",
                        isRetryable = isRetryableWriteStatus(response.code),
                    )
                }
            }
        } catch (e: DavTransportRefusedException) {
            // Retryable: the contact change waits for the server or network to be fixed.
            CalDavResult.transportRefused(e.message.orEmpty()).let { ContactUploadResult.Failed(it.code, it.message, it.isRetryable) }
        } catch (e: IOException) {
            ContactUploadResult.Failed(0, "Network error: ${e.javaClass.simpleName}", isRetryable = true)
        }
    }

    override suspend fun deleteContact(
        resourceUrl: String,
        etag: String,
    ): ContactDeleteResult = withContext(Dispatchers.IO) {
        // Guard before OkHttp throws on an unusable etag (see isHeaderSafe).
        if (!isHeaderSafe(etag)) return@withContext ContactDeleteResult.PreconditionFailed
        val request = Request.Builder()
            .url(resourceUrl)
            .delete()
            .header("If-Match", "\"$etag\"") // Optimistic locking
            .build()

        // Not retried, for the same reason as putContact.
        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 204 -> ContactDeleteResult.Deleted
                    404, 410 -> ContactDeleteResult.AlreadyGone
                    412, 409 -> ContactDeleteResult.PreconditionFailed
                    else -> ContactDeleteResult.Failed(
                        response.code,
                        "deleteContact failed: ${response.code}",
                        isRetryable = isRetryableWriteStatus(response.code),
                    )
                }
            }
        } catch (e: DavTransportRefusedException) {
            CalDavResult.transportRefused(e.message.orEmpty()).let { ContactDeleteResult.Failed(it.code, it.message, it.isRetryable) }
        } catch (e: IOException) {
            ContactDeleteResult.Failed(0, "Network error: ${e.javaClass.simpleName}", isRetryable = true)
        }
    }

    // ========== HTTP plumbing (same as OkHttpCalDavClient) ==========

    /** Maps a request the transport guard wouldn't send to [CalDavResult.transportRefused]. */
    private fun transportRefused(e: DavTransportRefusedException): CalDavResult.Error =
        CalDavResult.transportRefused(e.message.orEmpty())

    private suspend inline fun <T> executeWithRetry(
        request: Request,
        rejectTruncated: Boolean = false,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        var lastException: IOException? = null
        var lastResult: CalDavResult<T>? = null
        var currentBackoff = INITIAL_BACKOFF_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.readBoundedBody()
                val result = processResponse(response, responseBody, rejectTruncated, parser)

                if (response.code == 429) {
                    val retryAfter = parseRetryAfterHeader(response)
                    if (retryAfter != null && attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "Rate limited (429), waiting ${retryAfter}ms before retry ${attempt + 1}")
                        delay(retryAfter)
                        return@repeat
                    }
                    return result
                }

                if (response.code in 500..599 && attempt < MAX_RETRIES - 1) {
                    val retryDelay = if (response.code == 503) {
                        parseRetryAfterHeader(response) ?: currentBackoff
                    } else {
                        currentBackoff
                    }
                    Log.w(TAG, "Server error ${response.code}, retry ${attempt + 1} after ${retryDelay}ms")
                    delay(retryDelay)
                    currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    lastResult = result
                    return@repeat
                }

                return result
            } catch (e: DavTransportRefusedException) {
                // Deterministic: retrying in place would be refused the same way.
                return transportRefused(e)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Socket timeout on ${request.method} ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                lastException = e
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown host for ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                lastException = e
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "SSL error on ${request.url} (not retrying): ${e.javaClass.simpleName}")
                return CalDavResult.networkError("SSL error: ${e.message}")
            } catch (e: IOException) {
                if (isRetryableError(e)) {
                    Log.w(TAG, "Retryable IO error on ${request.method} ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                    lastException = e
                } else {
                    Log.e(TAG, "Non-retryable IO error on ${request.url}: ${e.javaClass.simpleName}")
                    return CalDavResult.networkError("Network error: ${e.javaClass.simpleName} - ${e.message}")
                }
            }

            if (attempt < MAX_RETRIES - 1) {
                delay(currentBackoff)
                currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        Log.e(TAG, "All $MAX_RETRIES retries exhausted for ${request.method} ${request.url}")
        return lastResult ?: when (lastException) {
            is SocketTimeoutException -> CalDavResult.timeoutError("Timeout after $MAX_RETRIES retries")
            else -> CalDavResult.networkError(
                "Network error after $MAX_RETRIES retries: ${lastException?.javaClass?.simpleName}"
            )
        }
    }

    private inline fun <T> processResponse(
        response: Response,
        responseBody: String,
        rejectTruncated: Boolean,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return when {
            // Every caller sends PROPFIND or REPORT, so only a 207 multistatus is an answer. A
            // hotspot page or a reply cut short would read as an empty book, and an empty book
            // lets the orphan sweep delete the user's contacts.
            response.isSuccessful || response.code == 207 -> {
                val reading = DavMultistatus.readReply(response.code, response.header("Content-Type"), responseBody)
                when {
                    reading.problem != null -> {
                        Log.w(TAG, "${response.request.method}: not a multistatus (${reading.problem})")
                        CalDavResult.notMultistatus(reading.problem)
                    }
                    rejectTruncated && reading.truncated ->
                        CalDavResult.error(507, "Listing truncated by the server", isRetryable = true)
                    else -> parser(responseBody)
                }
            }
            response.code == 401 -> CalDavResult.authError("Authentication failed")
            response.code == 403 -> {
                if (quirks.isSyncTokenInvalid(403, responseBody)) {
                    CalDavResult.error(403, "Sync token invalid", isRetryable = false)
                } else {
                    CalDavResult.error(403, "Permission denied")
                }
            }
            response.code == 404 -> CalDavResult.notFoundError("Resource not found")
            response.code == 429 -> CalDavResult.error(429, "Rate limited", isRetryable = true)
            response.code in 500..599 -> CalDavResult.error(
                response.code, "Server error: ${response.code}", isRetryable = true
            )
            else -> CalDavResult.error(response.code, "Request failed: ${response.code}")
        }
    }

    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null
        retryAfter.toLongOrNull()?.let { return it * 1000 }
        return try {
            val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val targetTime = httpDateFormat.parse(retryAfter)?.time ?: return DEFAULT_RETRY_AFTER_MS
            (targetTime - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: Exception) {
            Log.w(TAG, "Could not parse Retry-After header: $retryAfter, using default")
            DEFAULT_RETRY_AFTER_MS
        }
    }

    private fun isRetryableError(e: IOException): Boolean {
        return when {
            e is SocketTimeoutException -> true
            e is UnknownHostException -> true
            e is java.net.ConnectException -> true
            e.message?.contains("reset", ignoreCase = true) == true -> true
            e.message?.contains("connection", ignoreCase = true) == true -> true
            else -> false
        }
    }

    private fun extractBaseHost(url: String): String = baseHostOf(url)

    /**
     * Returns the path of a URL or relative href, or the input if it doesn't parse, so a
     * self-href compares to the collection URL whichever form the server returned.
     */
    private fun pathOf(urlOrPath: String): String =
        try {
            java.net.URI(urlOrPath).path ?: urlOrPath
        } catch (_: Exception) {
            urlOrPath
        }
}
