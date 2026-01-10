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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
                    requestBuilder.header("Authorization", Credentials.basic(username, password))
                }

                // Add provider-specific headers
                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    /**
     * Set credentials for authentication.
     * Must be called before making any requests.
     *
     * @throws UnsupportedOperationException if client was created by factory (credentials immutable)
     */
    override fun setCredentials(username: String, password: String) {
        if (preAuthenticatedClient != null) {
            throw UnsupportedOperationException(
                "Cannot set credentials on factory-created client. Credentials are immutable."
            )
        }
        this.username = username
        this.password = password
    }

    /**
     * Check if credentials have been set.
     *
     * For factory-created clients, always returns true (credentials baked in).
     */
    override fun hasCredentials(): Boolean {
        // Factory-created clients always have credentials baked in
        if (preAuthenticatedClient != null) {
            return true
        }
        return username.isNotEmpty() && password.isNotEmpty()
    }

    /**
     * Clear stored credentials.
     *
     * @throws UnsupportedOperationException if client was created by factory (credentials immutable)
     */
    override fun clearCredentials() {
        if (preAuthenticatedClient != null) {
            throw UnsupportedOperationException(
                "Cannot clear credentials on factory-created client. Credentials are immutable."
            )
        }
        this.username = ""
        this.password = ""
    }

    // ========== Discovery ==========

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
                    "$serverUrl$principalPath"
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

        executeRequest(request) { responseBody ->
            // Check if sync-token is invalid
            if (quirks.isSyncTokenInvalid(207, responseBody)) {
                return@executeRequest CalDavResult.error(
                    403, "Sync token invalid", isRetryable = false
                )
            }

            val newSyncToken = quirks.extractSyncToken(responseBody)

            // Parse changed items (hrefs + etags only, no calendar-data in sync-collection)
            val changedItems = quirks.extractChangedItems(responseBody)
            val changed = changedItems.map { (href, etag) ->
                SyncItem(
                    href = href,
                    etag = etag,
                    status = SyncItemStatus.OK
                )
            }

            // Parse deleted items from 404 responses
            val deleted = quirks.extractDeletedHrefs(responseBody)

            CalDavResult.success(SyncReport(
                syncToken = newSyncToken,
                changed = changed,
                deleted = deleted
            ))
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
                        val etag = response.header("ETag")?.trim('"')
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
                    val etag = response.header("ETag")?.trim('"') ?: ""
                    CalDavResult.success(Pair(eventUrl, etag))
                }
                response.code == 412 -> CalDavResult.conflictError("Event already exists")
                response.code == 401 -> CalDavResult.authError("Authentication failed")
                response.code == 403 -> CalDavResult.error(403, "Permission denied")
                else -> CalDavResult.error(response.code, "Failed to create event: ${response.code}")
            }
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
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
                    val newEtag = response.header("ETag")?.trim('"') ?: etag
                    CalDavResult.success(newEtag)
                }
                response.code == 412 -> CalDavResult.conflictError("Event was modified on server")
                response.code == 404 -> CalDavResult.notFoundError("Event not found")
                response.code == 401 -> CalDavResult.authError("Authentication failed")
                response.code == 403 -> CalDavResult.error(403, "Permission denied")
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

    // ========== Configuration ==========

    override suspend fun checkConnection(serverUrl: String): CalDavResult<Unit> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(serverUrl)
                .method("OPTIONS", null)
                .build()

            try {
                val response = httpClient.newCall(request).execute()

                when {
                    response.isSuccessful -> CalDavResult.success(Unit)
                    response.code == 401 -> CalDavResult.authError("Invalid credentials")
                    else -> CalDavResult.error(response.code, "Connection failed: ${response.code}")
                }
            } catch (e: IOException) {
                CalDavResult.networkError("Cannot reach server: ${e.message}")
            }
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
                if (response.code in 500..599 && attempt < MAX_RETRIES - 1) {
                    Log.w(TAG, "Server error ${response.code}, retry ${attempt + 1} after ${currentBackoff}ms")
                    delay(currentBackoff) // Use delay() for proper coroutine suspension
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
     * Supports both seconds format and HTTP-date format.
     */
    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null

        // Try parsing as seconds
        retryAfter.toLongOrNull()?.let { return it * 1000 }

        // Could add HTTP-date parsing here if needed
        // For now, default to 30 seconds if we can't parse
        return 30_000L
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
