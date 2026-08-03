package org.onekash.kashcal.sync.carddav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.onekash.kashcal.network.readBoundedBody
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactSyncItem
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLHandshakeException

/**
 * OkHttp-based CardDAV (RFC 6352) client — read path only.
 *
 * A standalone sibling of `OkHttpCalDavClient` living entirely inside
 * `sync/carddav/`. It mirrors that client's HTTP plumbing — retry/backoff,
 * response mapping, well-known redirect cleaning, base-host derivation — with
 * CardDAV XML request bodies, but borrows no CalDAV *client* symbol: the
 * duplicated generic WebDAV verbs are by design (no shared `WebDavClient` base
 * is extracted). It reuses the generic [CalDavResult] envelope,
 * [readBoundedBody], and the [CardDavQuirks] seam (which in turn delegates
 * parsing to the shared multistatus skeleton).
 *
 * The [httpClient] is pre-authenticated by [CardDavClientFactory] (credentials
 * baked into its interceptor chain), so this class holds no credentials itself.
 */
class OkHttpCardDavClient(
    private val quirks: CardDavQuirks,
    private val httpClient: OkHttpClient,
) : CardDavClient {

    companion object {
        private const val TAG = "OkHttpCardDavClient"

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

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
         * Escape a server-supplied value (sync-token, href) before interpolating
         * it into request XML. The parser XML-decodes these on the way in, so a
         * token/href containing `&`, `<`, or `>` would otherwise produce malformed
         * request XML the server rejects with 400, breaking the round-trip.
         */
        private fun escapeXmlText(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }

    // ========== Discovery ==========

    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val baseHost = extractBaseHost(serverUrl)
            val wellKnownUrl = "$baseHost/.well-known/carddav"
            // Capture original scheme before any redirect (reverse proxy may change it).
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
                                CalDavResult.success(cleanRedirectUrl(finalUrl, originalScheme))
                            } else {
                                CalDavResult.success(serverUrl)
                            }
                        }
                        else -> CalDavResult.success(serverUrl)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Well-known discovery failed: ${e.message}, using original URL")
                CalDavResult.success(serverUrl)
            }
        }

    /**
     * Clean a well-known redirect URL for use as the CardDAV endpoint. Preserves
     * the full path (the redirect target IS the service endpoint per RFC 6764)
     * and trailing slash; strips only query/fragment; restores the original
     * scheme when a reverse proxy rewrote it.
     */
    private fun cleanRedirectUrl(url: String, originalScheme: String? = null): String {
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        if (originalScheme == null) return cleanUrl
        val uri = try { java.net.URI(cleanUrl) } catch (_: Exception) { return cleanUrl }
        if (uri.scheme == originalScheme) return cleanUrl
        return cleanUrl.replaceFirst(Regex("^\\w+://"), "$originalScheme://")
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
                // Derive the base host from the HOME URL, not an account root: iCloud
                // serves address books from a partition host (pNN-contacts.icloud.com)
                // whose absolute hrefs must resolve against the home's host.
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
            // RFC 6578 §3.2: sync-collection is defined only with Depth "0"; the
            // body's <sync-level> controls scope.
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.readBoundedBody()
            val responseCode = response.code
            val isTruncated = responseCode == 507

            when {
                responseCode == 207 || isTruncated -> {
                    if (responseCode == 207 && quirks.isSyncTokenInvalid(207, responseBody)) {
                        return@withContext CalDavResult.error(403, "Sync token invalid", isRetryable = false)
                    }
                    val syncData = quirks.extractSyncCollectionData(responseBody)
                    val changed = syncData.changedItems.map { (href, etag) ->
                        ContactSyncItem(href = href, etag = etag)
                    }
                    CalDavResult.success(
                        ContactSyncReport(
                            syncToken = syncData.syncToken,
                            changed = changed,
                            deleted = syncData.deletedHrefs,
                            truncated = isTruncated
                        )
                    )
                }
                responseCode == 401 -> CalDavResult.authError("Authentication failed")
                responseCode == 403 || responseCode == 410 -> {
                    // In sync-collection context, 403/410 always means an expired sync
                    // token (RFC 6578 §3.6). Not retryable — caller falls back to a full listing.
                    CalDavResult.error(responseCode, "Sync token invalid", isRetryable = false)
                }
                else -> CalDavResult.error(responseCode, "sync-collection failed: $responseCode")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun listAllContactHrefs(
        addressBookUrl: String
    ): CalDavResult<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
        // PROPFIND Depth:1 lists all members with etags — the full-sync fallback.
        // Request only <d:getetag/>: as on the CalDAV side, adding <d:resourcetype/>
        // makes iCloud emit a per-member propstat-404 that bloats the response.
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

        executeWithRetry(request) { responseBody ->
            // The changed-items split skips the collection self-row via trailing-slash.
            CalDavResult.success(quirks.extractSyncCollectionData(responseBody).changedItems)
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
        // collection itself without a trailing slash and with no resourcetype, so the
        // shared parser's trailing-slash / resourcetype self-row filter misses it; a
        // multiget that includes a non-contact collection href gets a 400 for the
        // WHOLE batch, so filter here where the collection URL is known.
        val collectionPath = pathOf(addressBookUrl).trimEnd('/')
        val memberHrefs = hrefs.filter { pathOf(it).trimEnd('/') != collectionPath }
        if (memberHrefs.isEmpty()) {
            return@withContext CalDavResult.success(emptyList())
        }

        // Build without trimIndent(): the href lines are interpolated flush-left,
        // which would leave leading whitespace before <?xml and produce invalid XML
        // that iCloud rejects with 400 (mirrors the CalDAV multiget body builder).
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
            .header("Depth", "1")
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

    // ========== HTTP plumbing (mirrors OkHttpCalDavClient) ==========

    private suspend inline fun <T> executeWithRetry(
        request: Request,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        var lastException: IOException? = null
        var lastResult: CalDavResult<T>? = null
        var currentBackoff = INITIAL_BACKOFF_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.readBoundedBody()
                val result = processResponse(response, responseBody, parser)

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
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Socket timeout on ${request.method} ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                lastException = e
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown host for ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                lastException = e
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "SSL error on ${request.url} (not retrying): ${e.message}", e)
                return CalDavResult.networkError("SSL error: ${e.message}")
            } catch (e: IOException) {
                if (isRetryableError(e)) {
                    Log.w(TAG, "Retryable IO error on ${request.method} ${request.url}, retry ${attempt + 1}/$MAX_RETRIES")
                    lastException = e
                } else {
                    Log.e(TAG, "Non-retryable IO error on ${request.url}: ${e.message}", e)
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
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return when {
            response.isSuccessful || response.code == 207 -> parser(responseBody)
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
     * Path component of a URL or already-relative href. A bare path (the common
     * href shape) is returned as-is; a full URL is reduced to its path so a
     * self-href can be compared to the collection URL regardless of which form
     * the server returned.
     */
    private fun pathOf(urlOrPath: String): String =
        try {
            java.net.URI(urlOrPath).path ?: urlOrPath
        } catch (_: Exception) {
            urlOrPath
        }
}
