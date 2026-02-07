package org.onekash.kashcal.sync.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.sync.client.model.*
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.util.EtagUtils
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException

/**
 * OkHttp-based CalDAV client implementation.
 *
 * Handles all HTTP communication with CalDAV servers, using the
 * CalDavQuirks interface to handle provider-specific response parsing.
 *
 * Two usage modes:
 * 1. Legacy singleton (Hilt-injected): Uses setCredentials() for mutable credentials
 * 2. Factory-created: Receives pre-authenticated OkHttpClient with immutable credentials
 *
 * Factory mode is preferred for multi-account sync to avoid credential race conditions.
 * @see CalDavClientFactory
 */
class OkHttpCalDavClient : CalDavClient {

    private val quirks: CalDavQuirks
    private val preAuthenticatedClient: OkHttpClient?

    /**
     * Primary constructor for Hilt injection (legacy singleton mode).
     * Uses setCredentials() for mutable credentials.
     */
    @Inject
    constructor(quirks: CalDavQuirks) {
        this.quirks = quirks
        this.preAuthenticatedClient = null
    }

    /**
     * Factory constructor with pre-authenticated OkHttpClient.
     * Credentials are immutable and baked into the client.
     *
     * @param quirks Provider-specific CalDAV quirks
     * @param preAuthenticatedClient OkHttpClient with credentials baked in
     */
    constructor(quirks: CalDavQuirks, preAuthenticatedClient: OkHttpClient) {
        this.quirks = quirks
        this.preAuthenticatedClient = preAuthenticatedClient
    }

    companion object {
        private const val TAG = "OkHttpCalDavClient"

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICAL_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        // Reduced timeouts for better UX (was 30/60/60)
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L

        // Retry configuration (reduced for better UX)
        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L  // RFC 7231 fallback

        // Connection pool configuration
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_MINUTES = 5L

        // Response body size limit (10MB) - prevents OOM on malicious/malformed responses
        private const val MAX_RESPONSE_SIZE_BYTES = 10L * 1024 * 1024

    }

    /**
     * Read response body with size limit to prevent OOM.
     * Uses .use {} to ensure body is always closed.
     */
    private fun Response.bodyWithLimit(): String {
        val body = this.body ?: return ""
        return body.use { b ->
            val source = b.source()
            val contentLength = b.contentLength()
            // contentLength is -1 when unknown (chunked/streaming) - falls through to buffer check
            if (contentLength > MAX_RESPONSE_SIZE_BYTES) {
                Log.w(TAG, "Response rejected: Content-Length $contentLength exceeds limit")
                throw IOException("Response too large: Content-Length $contentLength exceeds ${MAX_RESPONSE_SIZE_BYTES / 1024 / 1024}MB")
            }
            source.request(MAX_RESPONSE_SIZE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_SIZE_BYTES) {
                Log.w(TAG, "Response rejected: buffered ${source.buffer.size} bytes exceeds limit")
                throw IOException("Response too large: buffered ${source.buffer.size} bytes exceeds ${MAX_RESPONSE_SIZE_BYTES / 1024 / 1024}MB")
            }
            source.buffer.readUtf8()
        }
    }

    @Volatile
    private var username: String = ""
    @Volatile
    private var password: String = ""

    /**
     * HTTP logging interceptor - logs headers in debug mode.
     * Note: Set to HEADERS level for debugging, NONE for production.
     */
    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            // Truncate very long messages (like full iCal bodies)
            val truncated = if (message.length > 1000) {
                message.take(1000) + "... [truncated ${message.length - 1000} chars]"
            } else {
                message
            }
            Log.d(TAG, truncated)
        }.apply {
            // Use HEADERS level for debugging sync issues in debug builds
            // Disable logging in release builds for security and performance
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /**
     * HTTP client for requests.
     *
     * Factory mode: Uses pre-authenticated client with immutable credentials
     * Legacy mode: Uses lazy-initialized client with mutable credentials via setCredentials()
     */
    private val httpClient: OkHttpClient by lazy {
        // Factory mode: use pre-authenticated client (credentials immutable)
        preAuthenticatedClient ?: createLegacyClient()
    }

    /**
     * Create legacy client with mutable credentials.
     * Used when OkHttpCalDavClient is injected as singleton (backward compatibility).
     */
    private fun createLegacyClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            // Connection pool for better performance
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MINUTES, TimeUnit.MINUTES))
            // HTTP logging (debug builds only)
            .addInterceptor(loggingInterceptor)
            // Use NetworkInterceptor instead of Interceptor to ensure auth headers
            // survive redirects. iCloud often redirects requests, and application
            // interceptors only run on the initial request.
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Add authentication if credentials set
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    // Issue #49: Use UTF-8 encoding for non-ASCII passwords (RFC 7617)
                    requestBuilder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                }

                // Add provider-specific headers
                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    // ========== Discovery ==========

    /**
     * Discover CalDAV endpoint via RFC 6764 well-known URL.
     * Makes a PROPFIND request to /.well-known/caldav and follows redirects.
     *
     * @param serverUrl Base server URL (e.g., "https://nextcloud.example.com")
     * @return The final URL after following redirects, or original URL if well-known not supported
     */
    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            // Construct well-known URL
            val baseHost = extractBaseHost(serverUrl)
            val wellKnownUrl = "$baseHost/.well-known/caldav"
            // Issue #49: Capture original scheme before redirect (reverse proxy may change it)
            val originalScheme = baseHost.substringBefore("://")
            Log.d(TAG, "Trying well-known discovery: $wellKnownUrl")

            // Use PROPFIND (same as principal discovery) to trigger proper redirects
            // Some servers require PROPFIND, not GET, for well-known
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:current-user-principal/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(wellKnownUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val result: CalDavResult<String> = response.use { resp ->
                    val finalUrl = resp.request.url.toString()
                    Log.d(TAG, "Well-known response: ${resp.code}, final URL: $finalUrl")

                    when {
                        // Success or redirect was followed
                        resp.isSuccessful || resp.code == 207 -> {
                            // Return the final URL (after redirects) without the well-known part
                            // If redirected, use that URL; otherwise fall back to original
                            if (finalUrl != wellKnownUrl && !finalUrl.contains("/.well-known/")) {
                                // Issue #51: Use the redirect URL as-is (strip query/fragment only).
                                // The well-known redirect target IS the CalDAV endpoint by RFC 6764.
                                // Issue #49: Preserve original scheme through reverse proxy.
                                val caldavUrl = cleanRedirectUrl(finalUrl, originalScheme)
                                Log.i(TAG, "Well-known discovery successful: $caldavUrl")
                                CalDavResult.Success(caldavUrl)
                            } else {
                                // No redirect, return original URL
                                Log.d(TAG, "Well-known returned same URL, using original: $serverUrl")
                                CalDavResult.Success(serverUrl)
                            }
                        }
                        // 401/403 - auth required, but URL is valid
                        resp.code == 401 || resp.code == 403 -> {
                            Log.d(TAG, "Well-known requires auth, URL is valid: $finalUrl")
                            if (finalUrl != wellKnownUrl && !finalUrl.contains("/.well-known/")) {
                                // Issue #51: Preserve full redirect URL; Issue #49: preserve scheme
                                CalDavResult.Success(cleanRedirectUrl(finalUrl, originalScheme))
                            } else {
                                CalDavResult.Success(serverUrl)
                            }
                        }
                        // 404 - well-known not supported, use original URL
                        resp.code == 404 -> {
                            Log.d(TAG, "Well-known not supported, using original URL: $serverUrl")
                            CalDavResult.Success(serverUrl)
                        }
                        // Other errors
                        else -> {
                            Log.w(TAG, "Well-known returned ${resp.code}, using original URL")
                            CalDavResult.Success(serverUrl)
                        }
                    }
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "Well-known discovery failed: ${e.message}, using original URL")
                // Fall back to original URL on any error
                CalDavResult.Success(serverUrl)
            }
        }

    /**
     * Clean a well-known redirect URL for use as the CalDAV endpoint.
     *
     * Unlike [extractCaldavBaseUrl], this preserves the full path — the well-known
     * redirect target IS the CalDAV service endpoint by RFC 6764. Stripping path
     * components breaks providers like Fastmail (Issue #51).
     *
     * Only strips query parameters, fragments, and trailing slashes.
     * Preserves original scheme when provided (Issue #49 reverse proxy fix).
     */
    private fun cleanRedirectUrl(url: String, originalScheme: String? = null): String {
        val cleanUrl = url.substringBefore("?").substringBefore("#").trimEnd('/')
        if (originalScheme == null) return cleanUrl
        val uri = try { java.net.URI(cleanUrl) } catch (e: Exception) { return cleanUrl }
        if (uri.scheme == originalScheme) return cleanUrl
        return cleanUrl.replaceFirst(Regex("^\\w+://"), "$originalScheme://")
    }

    /**
     * Extract base CalDAV URL from a full URL path.
     * For Nextcloud: https://cloud.example.com/remote.php/dav/principals/users/foo
     *             -> https://cloud.example.com/remote.php/dav
     *
     * Uses java.net.URI for robust parsing (handles IPv6, userinfo, ports).
     *
     * Bug fix: https://github.com/KashCal/KashCal/issues/38
     * Previously matched "/dav" anywhere in URL, including hostname "dav.mailbox.org".
     * Now searches patterns only in URI path component.
     *
     * Bug fix: https://github.com/KashCal/KashCal/issues/49
     * When Baikal is behind a reverse proxy with SSL termination, the redirect URL
     * may have HTTP scheme while user connected via HTTPS. Pass originalScheme to
     * preserve the user's original connection scheme.
     *
     * @param url The URL to extract base CalDAV URL from
     * @param originalScheme The original scheme from user's connection (e.g., "https").
     *                       If provided, overrides the scheme from the URL.
     */
    private fun extractCaldavBaseUrl(url: String, originalScheme: String? = null): String {
        // Strip query parameters and fragments (some redirects include them)
        val cleanUrl = url.substringBefore("?").substringBefore("#")

        // Parse URL using stdlib - handles IPv6 [::1], userinfo, ports correctly
        val uri = try {
            java.net.URI(cleanUrl)
        } catch (e: Exception) {
            Log.w(TAG, "extractCaldavBaseUrl: invalid URL '$url', returning as-is")
            return url
        }

        val path = uri.path ?: ""
        // Issue #49: Preserve original scheme when provided (reverse proxy scenario)
        val effectiveScheme = originalScheme ?: uri.scheme
        val baseUrl = "$effectiveScheme://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"

        // Common CalDAV path patterns - order matters (longer/more specific first)
        val patterns = listOf(
            "/remote.php/dav",  // Nextcloud
            "/dav.php",         // Baikal
            "/caldav.php",      // Some servers
            "/caldav",          // Open-Xchange (mailbox.org)
            "/cal.php",         // Some servers
            "/dav/cal",         // Stalwart
            "/dav"              // Generic (safe - only matches path now)
        )

        for (pattern in patterns) {
            val index = path.indexOf(pattern)
            if (index != -1) {
                Log.d(TAG, "extractCaldavBaseUrl: matched '$pattern' in path")
                return baseUrl + path.substring(0, index + pattern.length)
            }
        }

        // No pattern matched - return base URL only
        Log.d(TAG, "extractCaldavBaseUrl: no pattern matched, returning base: $baseUrl")
        return baseUrl
    }

    override suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:current-user-principal/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(serverUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                val principalPath = quirks.extractPrincipalUrl(responseBody)
                    ?: return@executeRequest CalDavResult.error(
                        500, "Principal URL not found in response"
                    )

                val principalUrl = if (principalPath.startsWith("http")) {
                    principalPath
                } else {
                    // Use base host to avoid double-path issues (e.g., /dav.php/ + /dav.php/principals/)
                    val baseHost = extractBaseHost(serverUrl)
                    "$baseHost$principalPath"
                }

                CalDavResult.success(principalUrl)
            }
        }

    override suspend fun discoverCalendarHome(principalUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <c:calendar-home-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(principalUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                val homePath = quirks.extractCalendarHomeUrl(responseBody)
                    ?: return@executeRequest CalDavResult.error(
                        500, "Calendar home URL not found in response"
                    )

                // Home URL might be absolute or relative
                val homeUrl = if (homePath.startsWith("http")) {
                    homePath
                } else {
                    val baseHost = extractBaseHost(principalUrl)
                    "$baseHost$homePath"
                }

                CalDavResult.success(homeUrl)
            }
        }

    override suspend fun listCalendars(calendarHomeUrl: String): CalDavResult<List<CalDavCalendar>> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                            xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                    <d:prop>
                        <d:displayname/>
                        <d:resourcetype/>
                        <ic:calendar-color/>
                        <cs:getctag/>
                        <d:current-user-privilege-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(calendarHomeUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .build()

            executeRequest(request) { responseBody ->
                val baseHost = extractBaseHost(calendarHomeUrl)
                val parsedCalendars = quirks.extractCalendars(responseBody, baseHost)

                val calendars = parsedCalendars.map { parsed ->
                    CalDavCalendar(
                        href = parsed.href,
                        url = quirks.buildCalendarUrl(parsed.href, baseHost),
                        displayName = parsed.displayName,
                        color = parsed.color,
                        ctag = parsed.ctag,
                        isReadOnly = parsed.isReadOnly
                    )
                }

                CalDavResult.success(calendars)
            }
        }

    // ========== Change Detection ==========

    override suspend fun getCtag(calendarUrl: String): CalDavResult<String> =
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
                .url(calendarUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                val ctag = quirks.extractCtag(responseBody)
                    ?: return@executeRequest CalDavResult.error(
                        500, "Ctag not found in response"
                    )
                CalDavResult.success(ctag)
            }
        }

    override suspend fun getSyncToken(calendarUrl: String): CalDavResult<String?> =
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
                .url(calendarUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                val syncToken = quirks.extractSyncToken(responseBody)
                CalDavResult.success(syncToken)
            }
        }

    // ========== Fetching ==========

    /**
     * Perform sync-collection REPORT (RFC 6578).
     *
     * Handles special cases:
     * - 207 Multi-Status: Normal response with changes
     * - 507 Insufficient Storage: Server truncated results (RFC 6578 Section 3.6)
     *   - Response still contains partial results and new sync-token
     *   - SyncReport.truncated will be true, client should continue with new token
     * - 403/410: Sync token invalid/expired
     */
    override suspend fun syncCollection(
        calendarUrl: String,
        syncToken: String?
    ): CalDavResult<SyncReport> = withContext(Dispatchers.IO) {
        val tokenElement = if (syncToken != null) {
            "<d:sync-token>$syncToken</d:sync-token>"
        } else {
            "<d:sync-token/>"
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                $tokenElement
                <d:sync-level>1</d:sync-level>
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:sync-collection>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.bodyWithLimit()
            val responseCode = response.code

            // RFC 6578 Section 3.6: 507 means server truncated results
            // Response still contains partial results + new sync-token for continuation
            val isTruncated = responseCode == 507

            when {
                responseCode == 207 || isTruncated -> {
                    // Check if sync-token is invalid (only for 207, not 507)
                    if (responseCode == 207 && quirks.isSyncTokenInvalid(207, responseBody)) {
                        return@withContext CalDavResult.error(
                            403, "Sync token invalid", isRetryable = false
                        )
                    }

                    val newSyncToken = quirks.extractSyncToken(responseBody)

                    // Parse changed items (hrefs + etags only)
                    val changedItems = quirks.extractChangedItems(responseBody)
                    val changed = changedItems.map { (href, etag) ->
                        SyncItem(href = href, etag = etag, status = SyncItemStatus.OK)
                    }

                    // Parse deleted items from 404 responses
                    val deleted = quirks.extractDeletedHrefs(responseBody)

                    if (isTruncated) {
                        Log.w(TAG, "sync-collection returned 507 (truncated). " +
                            "Got ${changed.size} changed, ${deleted.size} deleted. " +
                            "Continue with token: ${newSyncToken?.take(20)}...")
                    }

                    CalDavResult.success(SyncReport(
                        syncToken = newSyncToken,
                        changed = changed,
                        deleted = deleted,
                        truncated = isTruncated
                    ))
                }
                responseCode == 401 -> CalDavResult.authError("Authentication failed")
                responseCode == 403 || responseCode == 410 -> {
                    // In sync-collection context, 403/410 always means expired sync token.
                    // Some servers (e.g., iCloud) return bare 403 without valid-sync-token
                    // error element. Unlike processResponse (generic handler), we don't need
                    // to distinguish permission-denied vs sync-token here.
                    CalDavResult.error(responseCode, "Sync token invalid", isRetryable = false)
                }
                else -> CalDavResult.error(responseCode, "sync-collection failed: $responseCode")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun fetchEventsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<CalDavEvent>> = withContext(Dispatchers.IO) {
        val startDate = quirks.formatDateForQuery(startMillis)
        val endDate = quirks.formatDateForQuery(endMillis)

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                </d:prop>
                <c:filter>
                    <c:comp-filter name="VCALENDAR">
                        <c:comp-filter name="VEVENT">
                            <c:time-range start="$startDate" end="$endDate"/>
                        </c:comp-filter>
                    </c:comp-filter>
                </c:filter>
            </c:calendar-query>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        executeRequest(request) { responseBody ->
            val baseHost = extractBaseHost(calendarUrl)
            val parsedEvents = quirks.extractICalData(responseBody)

            val events = parsedEvents.map { parsed ->
                CalDavEvent(
                    href = parsed.href,
                    url = quirks.buildEventUrl(parsed.href, calendarUrl),
                    etag = parsed.etag,
                    icalData = parsed.icalData
                )
            }

            CalDavResult.success(events)
        }
    }

    override suspend fun fetchEtagsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
        val startDate = quirks.formatDateForQuery(startMillis)
        val endDate = quirks.formatDateForQuery(endMillis)

        // Same calendar-query as fetchEventsInRange but WITHOUT <c:calendar-data/>
        // This returns only href+etag pairs, saving ~96% bandwidth (33KB vs 834KB for 231 events)
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <c:filter>
                    <c:comp-filter name="VCALENDAR">
                        <c:comp-filter name="VEVENT">
                            <c:time-range start="$startDate" end="$endDate"/>
                        </c:comp-filter>
                    </c:comp-filter>
                </c:filter>
            </c:calendar-query>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        executeRequest(request) { responseBody ->
            // Reuse extractChangedItems which parses href+etag pairs
            val items = quirks.extractChangedItems(responseBody)
            CalDavResult.success(items)
        }
    }

    override suspend fun fetchEventsByHref(
        calendarUrl: String,
        hrefs: List<String>
    ): CalDavResult<List<CalDavEvent>> = withContext(Dispatchers.IO) {
        if (hrefs.isEmpty()) {
            return@withContext CalDavResult.success(emptyList())
        }

        val hrefElements = hrefs.joinToString("\n") { href ->
            "<d:href>$href</d:href>"
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                </d:prop>
                $hrefElements
            </c:calendar-multiget>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        executeRequest(request) { responseBody ->
            val parsedEvents = quirks.extractICalData(responseBody)

            val events = parsedEvents.map { parsed ->
                CalDavEvent(
                    href = parsed.href,
                    url = quirks.buildEventUrl(parsed.href, calendarUrl),
                    etag = parsed.etag,
                    icalData = parsed.icalData
                )
            }

            CalDavResult.success(events)
        }
    }

    override suspend fun fetchEvent(eventUrl: String): CalDavResult<CalDavEvent> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(eventUrl)
                .get()
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.bodyWithLimit()

                when {
                    response.isSuccessful -> {
                        val etag = EtagUtils.normalizeEtag(response.header("ETag"))
                        CalDavResult.success(CalDavEvent(
                            href = extractHref(eventUrl),
                            url = eventUrl,
                            etag = etag,
                            icalData = responseBody
                        ))
                    }
                    response.code == 404 -> CalDavResult.notFoundError("Event not found")
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    else -> CalDavResult.error(response.code, "Failed to fetch event: ${response.code}")
                }
            } catch (e: IOException) {
                CalDavResult.networkError("Network error: ${e.message}")
            }
        }

    /**
     * Fetch only the ETag for an event URL using PROPFIND.
     *
     * This is a lightweight fallback when PUT response doesn't include ETag header.
     * Uses PROPFIND with Depth: 0 to get only the getetag property.
     */
    override suspend fun fetchEtag(eventUrl: String): CalDavResult<String?> =
        withContext(Dispatchers.IO) {
            val propfindBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:getetag/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(eventUrl)
                .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.bodyWithLimit()

                when {
                    response.isSuccessful -> {
                        // Extract ETag from PROPFIND response XML
                        val etag = extractEtagFromPropfind(responseBody)
                        Log.d(TAG, "fetchEtag for $eventUrl: ${etag ?: "(not found)"}")
                        CalDavResult.success(etag)
                    }
                    response.code == 404 -> {
                        Log.w(TAG, "fetchEtag: Event not found at $eventUrl")
                        CalDavResult.notFoundError("Event not found")
                    }
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    else -> {
                        Log.w(TAG, "fetchEtag failed: ${response.code} for $eventUrl")
                        CalDavResult.error(response.code, "Failed to fetch ETag: ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "fetchEtag network error for $eventUrl: ${e.message}")
                CalDavResult.networkError("Network error: ${e.message}")
            }
        }

    /**
     * Extract ETag from PROPFIND response XML.
     * Handles various namespace prefixes (d:, D:, DAV:, etc.) and XML entity encoding.
     *
     * Supports formats:
     * - <d:getetag>"abc123"</d:getetag>
     * - <D:getetag>"abc123"</D:getetag>
     * - <d:getetag>&quot;abc123&quot;</d:getetag>
     */
    private fun extractEtagFromPropfind(xml: String): String? {
        // Match getetag with any namespace prefix
        val regex = Regex(
            """<(?:[a-zA-Z0-9]+:)?getetag[^>]*>([^<]+)</""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(xml)?.groupValues?.get(1) ?: return null

        // Decode XML entities, then normalize (strips W/ prefix and quotes)
        val decoded = match.replace("&quot;", "\"").trim()
        return EtagUtils.normalizeEtag(decoded)
    }

    // ========== Mutations ==========

    override suspend fun createEvent(
        calendarUrl: String,
        uid: String,
        icalData: String
    ): CalDavResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        val eventUrl = "${calendarUrl.trimEnd('/')}/$uid.ics"

        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody(ICAL_MEDIA_TYPE))
            .header("If-None-Match", "*") // Ensure it doesn't exist
            .build()

        try {
            val response = httpClient.newCall(request).execute()

            when {
                response.code == 201 || response.code == 204 -> {
                    // Try to get ETag from response header first (normalize handles W/ prefix)
                    var etag = EtagUtils.normalizeEtag(response.header("ETag"))

                    // RFC 4791 Section 5.3.4: Server SHOULD return ETag, but MAY not.
                    // If missing, fetch via PROPFIND as fallback (e.g., Nextcloud).
                    if (etag.isNullOrEmpty()) {
                        Log.d(TAG, "createEvent: No ETag in response header, fetching via PROPFIND")
                        etag = fetchEtagFallback(eventUrl)
                    }

                    CalDavResult.success(Pair(eventUrl, etag ?: ""))
                }
                response.code == 412 -> CalDavResult.conflictError("Event already exists")
                response.code == 401 -> CalDavResult.authError("Authentication failed")
                response.code == 403 -> {
                    // RFC 4791: UID conflict returns 403 with Location header pointing to existing event
                    val existingUrl = response.header("Location")
                    if (existingUrl != null) {
                        CalDavResult.error(403, "UID conflict: event already exists at $existingUrl")
                    } else {
                        CalDavResult.error(403, "Permission denied")
                    }
                }
                // RFC 4791: Server rejects if event exceeds max-resource-size
                response.code == 413 -> CalDavResult.error(413, "Event too large for server")
                else -> CalDavResult.error(response.code, "Failed to create event: ${response.code}")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    /**
     * Internal helper to fetch ETag via PROPFIND when PUT response lacks ETag header.
     * This is a synchronous call meant to be used within withContext(Dispatchers.IO).
     */
    private fun fetchEtagFallback(eventUrl: String): String? {
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(eventUrl)
            .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.bodyWithLimit()
                val etag = extractEtagFromPropfind(responseBody)
                Log.d(TAG, "fetchEtagFallback: Got ETag '$etag' for $eventUrl")
                etag
            } else {
                Log.w(TAG, "fetchEtagFallback failed: ${response.code} for $eventUrl")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "fetchEtagFallback network error: ${e.message}")
            null
        }
    }

    override suspend fun updateEvent(
        eventUrl: String,
        icalData: String,
        etag: String
    ): CalDavResult<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody(ICAL_MEDIA_TYPE))
            .header("If-Match", "\"$etag\"") // Optimistic locking
            .build()

        try {
            val response = httpClient.newCall(request).execute()

            when {
                response.code == 200 || response.code == 204 -> {
                    // Try to get new ETag from response header first (normalize handles W/ prefix)
                    var newEtag = EtagUtils.normalizeEtag(response.header("ETag"))

                    // RFC 4791 Section 5.3.4: Server SHOULD return ETag, but MAY not.
                    // If missing, fetch via PROPFIND as fallback (e.g., Nextcloud).
                    if (newEtag.isNullOrEmpty()) {
                        Log.d(TAG, "updateEvent: No ETag in response header, fetching via PROPFIND")
                        newEtag = fetchEtagFallback(eventUrl) ?: etag
                    }

                    CalDavResult.success(newEtag)
                }
                response.code == 412 -> CalDavResult.conflictError("Event was modified on server")
                response.code == 404 -> CalDavResult.notFoundError("Event not found")
                response.code == 401 -> CalDavResult.authError("Authentication failed")
                response.code == 403 -> CalDavResult.error(403, "Permission denied")
                response.code == 413 -> CalDavResult.error(413, "Event too large for server")
                else -> CalDavResult.error(response.code, "Failed to update event: ${response.code}")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun deleteEvent(
        eventUrl: String,
        etag: String
    ): CalDavResult<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(eventUrl)
            .delete()
            .header("If-Match", "\"$etag\"") // Optimistic locking
            .build()

        try {
            val response = httpClient.newCall(request).execute()

            when {
                response.code == 200 || response.code == 204 || response.code == 404 -> {
                    // 404 is OK - event might have been deleted elsewhere
                    CalDavResult.success(Unit)
                }
                response.code == 412 -> CalDavResult.conflictError("Event was modified on server")
                response.code == 401 -> CalDavResult.authError("Authentication failed")
                response.code == 403 -> CalDavResult.error(403, "Permission denied")
                else -> CalDavResult.error(response.code, "Failed to delete event: ${response.code}")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    /**
     * Move an event to a different calendar using WebDAV MOVE (RFC 4918).
     *
     * This is an atomic operation preferred over DELETE+CREATE for same-account moves
     * because it avoids UID conflict race conditions on servers like iCloud.
     *
     * Returns the new event URL and etag on success.
     */
    override suspend fun moveEvent(
        sourceUrl: String,
        destinationCalendarUrl: String,
        uid: String
    ): CalDavResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        // Construct destination URL: calendar URL + uid.ics
        val destinationUrl = "${destinationCalendarUrl.trimEnd('/')}/$uid.ics"

        val request = Request.Builder()
            .url(sourceUrl)
            .method("MOVE", null)
            .header("Destination", destinationUrl)
            .header("Overwrite", "F")  // Don't overwrite if destination exists
            .build()

        try {
            val response = httpClient.newCall(request).execute()

            when (response.code) {
                201, 204 -> {
                    // Success - get etag from response header or fallback to PROPFIND
                    var newEtag = EtagUtils.normalizeEtag(response.header("ETag"))

                    if (newEtag.isNullOrEmpty()) {
                        Log.d(TAG, "moveEvent: No ETag in response header, fetching via PROPFIND")
                        val etagResult = fetchEtag(destinationUrl)
                        if (etagResult.isSuccess()) {
                            newEtag = etagResult.getOrNull()
                        }
                    }

                    Log.d(TAG, "moveEvent: Success, new URL=$destinationUrl, etag=$newEtag")
                    CalDavResult.success(destinationUrl to (newEtag ?: ""))
                }
                404 -> {
                    Log.d(TAG, "moveEvent: Source not found (404)")
                    CalDavResult.notFoundError("Source event not found")
                }
                412 -> {
                    Log.w(TAG, "moveEvent: Destination already exists (412)")
                    CalDavResult.conflictError("Destination event already exists")
                }
                403 -> {
                    Log.w(TAG, "moveEvent: Forbidden (403) - may be cross-server move")
                    CalDavResult.error(403, "MOVE forbidden (cross-server?)", false)
                }
                405 -> {
                    Log.w(TAG, "moveEvent: Method not allowed (405) - server doesn't support MOVE")
                    CalDavResult.error(405, "MOVE not supported by server", false)
                }
                401 -> CalDavResult.authError("Authentication failed")
                else -> {
                    val isRetryable = response.code >= 500
                    Log.w(TAG, "moveEvent: Failed with code ${response.code}")
                    CalDavResult.error(response.code, "MOVE failed: ${response.code}", isRetryable)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "moveEvent: Network error", e)
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    // ========== Configuration ==========

    /**
     * Check if the server is reachable and supports CalDAV.
     *
     * Per RFC 4791, CalDAV servers MUST include "calendar-access" in the DAV header.
     * This validates both connectivity and CalDAV capability.
     *
     * Includes retry logic for transient failures (429, 503, 5xx).
     */
    override suspend fun checkConnection(serverUrl: String): CalDavResult<Unit> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(serverUrl)
                .method("OPTIONS", null)
                .build()

            var lastResult: CalDavResult<Unit>? = null
            var currentBackoff = INITIAL_BACKOFF_MS

            repeat(MAX_RETRIES) { attempt ->
                try {
                    val response = httpClient.newCall(request).execute()

                    // Handle 429 rate limiting with Retry-After
                    if (response.code == 429 && attempt < MAX_RETRIES - 1) {
                        val retryAfter = parseRetryAfterHeader(response) ?: currentBackoff
                        Log.w(TAG, "Rate limited (429) on OPTIONS, waiting ${retryAfter}ms before retry")
                        delay(retryAfter)
                        return@repeat // Retry
                    }

                    // Handle 5xx server errors with retry
                    if (response.code in 500..599 && attempt < MAX_RETRIES - 1) {
                        val retryDelay = if (response.code == 503) {
                            parseRetryAfterHeader(response) ?: currentBackoff
                        } else {
                            currentBackoff
                        }
                        Log.w(TAG, "Server error ${response.code} on OPTIONS, retry after ${retryDelay}ms")
                        delay(retryDelay)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        return@repeat // Retry
                    }

                    return@withContext when {
                        response.isSuccessful -> {
                            // RFC 4791: CalDAV servers MUST advertise "calendar-access" in DAV header
                            val davHeader = response.header("DAV") ?: ""
                            if (!davHeader.contains("calendar-access", ignoreCase = true)) {
                                Log.w(TAG, "Server does not advertise CalDAV support. DAV header: $davHeader")
                                CalDavResult.error(
                                    501,
                                    "Server does not support CalDAV (missing calendar-access in DAV header)"
                                )
                            } else {
                                Log.d(TAG, "CalDAV server validated. DAV: $davHeader")
                                CalDavResult.success(Unit)
                            }
                        }
                        response.code == 401 -> CalDavResult.authError("Invalid credentials")
                        response.code == 429 -> CalDavResult.error(429, "Rate limited")
                        response.code in 500..599 -> CalDavResult.error(response.code, "Server error: ${response.code}")
                        else -> CalDavResult.error(response.code, "Connection failed: ${response.code}")
                    }
                } catch (e: IOException) {
                    if (attempt < MAX_RETRIES - 1 && isRetryableError(e)) {
                        Log.w(TAG, "Retryable error on OPTIONS, retry ${attempt + 1}: ${e.message}")
                        delay(currentBackoff)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        lastResult = CalDavResult.networkError("Cannot reach server: ${e.message}")
                        return@repeat // Retry
                    }
                    return@withContext CalDavResult.networkError("Cannot reach server: ${e.message}")
                }
            }

            // All retries exhausted
            lastResult ?: CalDavResult.networkError("Connection failed after $MAX_RETRIES attempts")
        }

    // ========== Private Helpers ==========

    private suspend inline fun <T> executeRequest(
        request: Request,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return executeWithRetry(request, parser)
    }

    /**
     * Execute request with retry logic for transient failures.
     * Handles:
     * - Network errors (SocketTimeout, UnknownHost, connection issues)
     * - Server errors (500-599)
     * - Rate limiting (429) with Retry-After header respect
     *
     * Uses delay() instead of Thread.sleep() for proper coroutine suspension.
     */
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
                val responseBody = response.bodyWithLimit()

                val result = processResponse(response, responseBody, parser)

                // Handle 429 rate limiting
                if (response.code == 429) {
                    val retryAfter = parseRetryAfterHeader(response)
                    if (retryAfter != null && attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "Rate limited (429), waiting ${retryAfter}ms before retry ${attempt + 1}")
                        delay(retryAfter) // Use delay() for proper coroutine suspension
                        return@repeat // Retry
                    }
                    return result
                }

                // Retry on server errors (5xx)
                // RFC 7231: 503 Service Unavailable MAY include Retry-After
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
                    return@repeat // Retry
                }

                return result

            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Socket timeout on ${request.method} ${request.url}, " +
                        "retry ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                lastException = e
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown host for ${request.url}, " +
                        "retry ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                lastException = e
            } catch (e: SSLHandshakeException) {
                // Don't retry SSL errors - likely a security issue
                Log.e(TAG, "SSL error on ${request.url} (not retrying): ${e.message}", e)
                return CalDavResult.networkError("SSL error: ${e.message}")
            } catch (e: IOException) {
                if (isRetryableError(e)) {
                    Log.w(TAG, "Retryable IO error on ${request.method} ${request.url}, " +
                            "retry ${attempt + 1}/$MAX_RETRIES: ${e.javaClass.simpleName} - ${e.message}")
                    lastException = e
                } else {
                    Log.e(TAG, "Non-retryable IO error on ${request.url}: ${e.javaClass.simpleName} - ${e.message}", e)
                    return CalDavResult.networkError("Network error: ${e.javaClass.simpleName} - ${e.message}")
                }
            }

            // Wait before retry with exponential backoff (use delay() not Thread.sleep())
            if (attempt < MAX_RETRIES - 1) {
                delay(currentBackoff)
                currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        // All retries exhausted
        Log.e(TAG, "All $MAX_RETRIES retries exhausted for ${request.method} ${request.url}: " +
                "${lastException?.javaClass?.simpleName} - ${lastException?.message}")
        return lastResult ?: when (lastException) {
            is SocketTimeoutException -> CalDavResult.timeoutError(
                "Timeout after $MAX_RETRIES retries: ${lastException.message}"
            )
            else -> CalDavResult.networkError(
                "Network error after $MAX_RETRIES retries: " +
                "${lastException?.javaClass?.simpleName} - ${lastException?.message}"
            )
        }
    }

    /**
     * Process HTTP response and map to CalDavResult.
     */
    private inline fun <T> processResponse(
        response: Response,
        responseBody: String,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return when {
            response.isSuccessful || response.code == 207 -> parser(responseBody)
            response.code == 401 -> CalDavResult.authError("Authentication failed")
            response.code == 403 -> {
                // Check if it's a sync-token error
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

    /**
     * Parse Retry-After header to get delay in milliseconds.
     * Supports both formats per RFC 7231 Section 7.1.3:
     * - delay-seconds: "120" (wait 120 seconds)
     * - HTTP-date: "Sun, 06 Nov 1994 08:49:37 GMT" (wait until this time)
     */
    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null

        // Try parsing as seconds (most common)
        retryAfter.toLongOrNull()?.let { return it * 1000 }

        // Try parsing as HTTP-date (RFC 7231)
        return try {
            val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val targetTime = httpDateFormat.parse(retryAfter)?.time ?: return DEFAULT_RETRY_AFTER_MS
            val delayMs = targetTime - System.currentTimeMillis()
            // If date is in the past, retry immediately (0 delay)
            delayMs.coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse Retry-After header: $retryAfter, using default")
            DEFAULT_RETRY_AFTER_MS
        }
    }

    /**
     * Check if an IOException is retryable.
     */
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

    private fun extractBaseHost(url: String): String {
        return if (url.contains("://")) {
            val afterProtocol = url.substringAfter("://")
            val host = afterProtocol.substringBefore("/")
            url.substringBefore("://") + "://" + host
        } else {
            url.substringBefore("/")
        }
    }

    private fun extractHref(url: String): String {
        return if (url.contains("://")) {
            "/" + url.substringAfter("://").substringAfter("/")
        } else {
            url
        }
    }
}
