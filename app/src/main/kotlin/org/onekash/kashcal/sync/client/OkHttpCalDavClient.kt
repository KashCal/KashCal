package org.onekash.kashcal.sync.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.network.wellKnownEndpoint
import org.onekash.kashcal.network.DavMultistatus
import org.onekash.kashcal.network.DavTransportRefusedException
import org.onekash.kashcal.network.installDavTransport
import org.onekash.kashcal.network.readBoundedBody
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.OutboxResponse
import org.onekash.kashcal.sync.client.model.SyncItem
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.util.EtagUtils
import org.onekash.kashcal.util.maskHost
import org.onekash.kashcal.util.maskUid
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
 * Sends CalDAV requests over OkHttp; [CalDavQuirks] parses the provider-specific replies.
 *
 * Sync builds each client through [CalDavClientFactory], which passes an OkHttpClient with one
 * account's credentials fixed in it, so concurrent accounts can't mix credentials. The
 * `@Inject` constructor builds a client with empty credentials; it exists for tests.
 */
class OkHttpCalDavClient : CalDavClient {

    private val quirks: CalDavQuirks
    private val preAuthenticatedClient: OkHttpClient?

    /** Builds a client that sends no credentials (see [createLegacyClient]). */
    @Inject
    constructor(quirks: CalDavQuirks) {
        this.quirks = quirks
        this.preAuthenticatedClient = null
    }

    /** Builds a client over [preAuthenticatedClient], which carries the account's credentials. */
    constructor(quirks: CalDavQuirks, preAuthenticatedClient: OkHttpClient) {
        this.quirks = quirks
        this.preAuthenticatedClient = preAuthenticatedClient
    }

    companion object {
        private const val TAG = "OkHttpCalDavClient"

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICAL_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L

        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L  // Retry-After date we can't parse

        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_MINUTES = 5L

        // Largest signed 32-bit UNIX time_t (2038-01-19T03:14:07Z), in epoch millis. Some
        // servers (SOGo/GNUstep) evaluate calendar-query time-range bounds with 32-bit time
        // and silently omit events past this instant from a successful 207, so a query up to
        // 2100 drops every far-future event (#326). An upper bound past this is sent as an
        // open-ended time-range (start only), which RFC 4791 §9.9 permits and can't overflow.
        private const val TIME_RANGE_UPPER_BOUND_MS = 2_147_483_647_000L

        /**
         * Escapes a server-supplied sync-token or href for request XML. The parser decodes
         * them on the way in, so an unescaped `&`, `<` or `>` would make malformed XML that
         * the server rejects with 400.
         */
        private fun escapeXmlText(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }

    /**
     * Builds the CALDAV:time-range element for a calendar-query filter, open-ended past
     * [TIME_RANGE_UPPER_BOUND_MS].
     */
    private fun buildTimeRangeElement(startMillis: Long, endMillis: Long): String {
        val start = quirks.formatDateForQuery(startMillis)
        return if (endMillis > TIME_RANGE_UPPER_BOUND_MS) {
            """<c:time-range start="$start"/>"""
        } else {
            val end = quirks.formatDateForQuery(endMillis)
            """<c:time-range start="$start" end="$end"/>"""
        }
    }

    private val username: String = ""
    private val password: String = ""

    /** Logs request and response headers in debug builds, nothing in release. */
    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            // Cut long lines, such as whole iCalendar bodies, to 1000 chars.
            val truncated = if (message.length > 1000) {
                message.take(1000) + "... [truncated ${message.length - 1000} chars]"
            } else {
                message
            }
            Log.d(TAG, truncated)
        }.apply {
            // Release builds log nothing, for security and performance.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /** The factory's authenticated client, or [createLegacyClient] for the `@Inject` one. */
    private val httpClient: OkHttpClient by lazy {
        preAuthenticatedClient ?: createLegacyClient()
    }

    /**
     * Builds the client for the `@Inject` constructor. Its [username] and [password] are
     * always empty: it adds no Basic header, and answers a Digest challenge with empty
     * credentials.
     */
    private fun createLegacyClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Redirects are followed by the DAV transport guard (same method and body).
            // Cleartext is allowed: this client never attaches credentials (they are empty).
            .installDavTransport(allowCleartext = true)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MINUTES, TimeUnit.MINUTES))
            .addInterceptor(loggingInterceptor)
            // Answers 401 Digest challenges (RFC 2617/7616).
            .authenticator(DigestAuthenticator(username, password, allowCleartext = true))
            // A network interceptor, so the header is added on every hop the transport
            // guard follows. (This client's credentials are empty, so nothing is added.)
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()

                // Preemptive Basic auth, unless DigestAuthenticator already set a Digest
                // Authorization on a retry.
                if (username.isNotEmpty() && password.isNotEmpty() &&
                    chain.request().header("Authorization") == null) {
                    // Issue #49: Use UTF-8 encoding for non-ASCII passwords (RFC 7617)
                    requestBuilder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                }

                quirks.getAdditionalHeaders().forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    // ========== Discovery ==========

    /**
     * Finds the CalDAV endpoint by PROPFIND on `/.well-known/caldav` (RFC 6764).
     *
     * Returns where the redirects led, or [serverUrl] when nothing redirected or the request
     * failed. It is an error only when the transport guard refused to send the request.
     */
    override suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String> =
        withContext(Dispatchers.IO) {
            val baseHost = extractBaseHost(serverUrl)
            val wellKnownUrl = "$baseHost/.well-known/caldav"
            // Issue #49: Capture original scheme before redirect (reverse proxy may change it)
            val originalScheme = baseHost.substringBefore("://")
            Log.d(TAG, "Trying well-known discovery: $wellKnownUrl")

            // PROPFIND, not GET: some servers redirect well-known only for PROPFIND.
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
                        resp.isSuccessful || resp.code == 207 -> {
                            if (finalUrl != wellKnownUrl && !finalUrl.contains("/.well-known/")) {
                                // Issue #51: Use the redirect URL as-is (strip query/fragment
                                // only). The well-known redirect target is the CalDAV endpoint by
                                // RFC 6764. Issue #49: Preserve original scheme through reverse
                                // proxy.
                                val caldavUrl = wellKnownEndpoint(finalUrl, originalScheme)
                                Log.i(TAG, "Well-known discovery successful: $caldavUrl")
                                CalDavResult.Success(caldavUrl)
                            } else {
                                Log.d(TAG, "Well-known returned same URL, using original: $serverUrl")
                                CalDavResult.Success(serverUrl)
                            }
                        }
                        // 401/403: the redirect target is still the endpoint; auth comes later.
                        resp.code == 401 || resp.code == 403 -> {
                            Log.d(TAG, "Well-known requires auth, URL is valid: $finalUrl")
                            if (finalUrl != wellKnownUrl && !finalUrl.contains("/.well-known/")) {
                                // Issue #51: Preserve full redirect URL; Issue #49: preserve scheme
                                CalDavResult.Success(wellKnownEndpoint(finalUrl, originalScheme))
                            } else {
                                CalDavResult.Success(serverUrl)
                            }
                        }
                        resp.code == 404 -> {
                            Log.d(TAG, "Well-known not supported, using original URL: $serverUrl")
                            CalDavResult.Success(serverUrl)
                        }
                        else -> {
                            Log.w(TAG, "Well-known returned ${resp.code}, using original URL")
                            CalDavResult.Success(serverUrl)
                        }
                    }
                }
                result
            } catch (e: DavTransportRefusedException) {
                // Not a "no well-known here": the server sent us somewhere unsafe, so say so.
                transportRefused(e)
            } catch (e: Exception) {
                Log.w(TAG, "Well-known discovery failed: ${e.message}, using original URL")
                CalDavResult.Success(serverUrl)
            }
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
                    // Use base host to avoid double-path issues (e.g., /dav.php/ +
                    // /dav.php/principals/)
                    val baseHost = extractBaseHost(serverUrl)
                    "$baseHost$principalPath"
                }

                CalDavResult.success(principalUrl)
            }
        }

    override suspend fun discoverCalendarUserAddresses(principalUrl: String): CalDavResult<List<String>> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <c:calendar-user-address-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(principalUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                CalDavResult.success(quirks.extractCalendarUserAddresses(responseBody))
            }
        }

    override suspend fun discoverScheduleOutboxUrl(principalUrl: String): CalDavResult<String?> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <c:schedule-outbox-URL/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(principalUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                CalDavResult.success(quirks.extractScheduleOutboxUrl(responseBody))
            }
        }

    override suspend fun supportsAutoSchedule(calendarUrl: String): CalDavResult<Boolean> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(calendarUrl)
                .method("OPTIONS", null)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            // RFC 6638 §2: a "calendar-auto-schedule" token in the DAV
                            // header means the server schedules. Probed on the collection,
                            // not the root. Servers may split tokens over several DAV lines
                            // and header() returns only one, so join them all.
                            val davHeader = response.headers("DAV").joinToString(", ")
                            CalDavResult.success(
                                davHeader.contains("calendar-auto-schedule", ignoreCase = true)
                            )
                        }
                        response.code == 401 -> CalDavResult.authError("Invalid credentials")
                        else -> CalDavResult.error(
                            response.code,
                            "OPTIONS failed: ${response.code}"
                        )
                    }
                }
            } catch (e: DavTransportRefusedException) {
                transportRefused(e)
            } catch (e: IOException) {
                Log.w(TAG, "supportsAutoSchedule: network error: ${e.message}")
                CalDavResult.networkError("Cannot reach server: ${e.message}")
            }
        }

    override suspend fun discoverCalendarHome(principalUrl: String): CalDavResult<List<String>> =
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
                val homePaths = quirks.extractCalendarHomeUrls(responseBody)
                if (homePaths.isEmpty()) {
                    return@executeRequest CalDavResult.error(
                        500, "Calendar home URL not found in response"
                    )
                }

                val homeUrls = homePaths.map { homePath ->
                    if (homePath.startsWith("http")) {
                        homePath
                    } else {
                        val baseHost = extractBaseHost(principalUrl)
                        "$baseHost$homePath"
                    }
                }

                CalDavResult.success(homeUrls)
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
                        <c:supported-calendar-component-set/>
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
                        isReadOnly = parsed.isReadOnly,
                        supportedComponents = parsed.supportedComponents
                    )
                }

                CalDavResult.success(calendars)
            }
        }

    override suspend fun probeCalendarCollection(calendarUrl: String): CalDavResult<Boolean> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:resourcetype/>
                        <d:displayname/>
                        <c:supported-calendar-component-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(calendarUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            // One attempt, no retry loop: every failure here means "keep the
            // calendar", so retrying would only slow the refresh down.
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (cameThroughRedirect(response) && !isSameResourceRedirect(request, response)) {
                        Log.w(TAG, "probeCalendarCollection: redirected elsewhere, ignoring the answer")
                        return@use CalDavResult.error(500, "Redirected away from the calendar URL")
                    }
                    processResponse(response, response.readBoundedBody()) { body ->
                        quirks.classifyCalendarProbe(body, request.url.encodedPath)
                            ?.let { CalDavResult.success(it) }
                            ?: CalDavResult.error(500, "resourcetype not found in response")
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "probeCalendarCollection: timeout: ${e.message}")
                CalDavResult.timeoutError("Request timed out")
            } catch (e: DavTransportRefusedException) {
                transportRefused(e)
            } catch (e: IOException) {
                Log.w(TAG, "probeCalendarCollection: network error: ${e.message}")
                CalDavResult.networkError("Cannot reach server: ${e.message}")
            }
        }

    /** Returns true when an earlier hop of this exchange was a redirect, not an auth retry. */
    private fun cameThroughRedirect(response: Response): Boolean =
        generateSequence(response.priorResponse) { it.priorResponse }.any { it.isRedirect }

    /**
     * Returns whether a redirected answer still speaks for the requested calendar: same
     * server, and the same path apart from a trailing slash. A redirect to another
     * path (a login route, a moved collection) says nothing about the calendar at
     * the stored URL, so its 404 or "not a calendar" must not remove it.
     */
    private fun isSameResourceRedirect(request: Request, response: Response): Boolean {
        val finalUrl = response.request.url
        return quirks.isSameServerRedirect(request.url.host, finalUrl.host) &&
            request.url.encodedPath.trimEnd('/') == finalUrl.encodedPath.trimEnd('/')
    }

    // ========== Change Detection ==========

    override suspend fun getCtag(calendarUrl: String): CalDavResult<CalendarMetadataProbe> =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"
                            xmlns:ic="http://apple.com/ns/ical/">
                    <d:prop>
                        <cs:getctag/>
                        <d:displayname/>
                        <ic:calendar-color/>
                        <d:current-user-privilege-set/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(calendarUrl)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .build()

            executeRequest(request) { responseBody ->
                val probe = quirks.extractCalendarMetadata(responseBody)
                    ?: return@executeRequest CalDavResult.error(
                        500, "Ctag not found in response"
                    )
                CalDavResult.success(probe)
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
     * Sends a sync-collection REPORT (RFC 6578) from [syncToken], or from the start when null.
     *
     * - 207: the changes. A truncated report (see [SyncReport.truncated]) still carries
     *   partial results and a sync-token to continue from; so does a top-level 507.
     * - 403 or 410, or a 207 the quirks read as an invalid token: the token expired.
     * - Any other 2xx: [CalDavResult.CODE_NOT_MULTISTATUS], like a 207 that isn't a multistatus.
     */
    override suspend fun syncCollection(
        calendarUrl: String,
        syncToken: String?
    ): CalDavResult<SyncReport> = withContext(Dispatchers.IO) {
        val tokenElement = if (syncToken != null) {
            "<d:sync-token>${escapeXmlText(syncToken)}</d:sync-token>"
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
            // RFC 6578 §3.2: sync-collection report is only defined when the
            // Depth header is "0"; the body's <sync-level> element controls
            // scope.
            .header("Depth", "0")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.readBoundedBody()
            val responseCode = response.code

            // RFC 6578 Section 3.6: a server truncates results either with a top-level
            // HTTP 507 or, more commonly, an in-body 507 <status> on the collection's
            // <response> inside a 207. Either way, partial results + a new sync-token
            // for continuation are present.
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
                    // A 507 is never read as an invalid token.
                    if (responseCode == 207 && quirks.isSyncTokenInvalid(207, responseBody)) {
                        return@withContext CalDavResult.error(
                            403, "Sync token invalid", isRetryable = false
                        )
                    }

                    val syncData = quirks.extractSyncCollectionData(responseBody)
                    val newSyncToken = syncData.syncToken

                    val changed = syncData.changedItems.map { (href, etag) ->
                        SyncItem(href = href, etag = etag, status = SyncItemStatus.OK)
                    }

                    val deleted = syncData.deletedHrefs

                    // number-of-matches-within-limits alone also marks a truncated report.
                    val isTruncated = topLevel507 || syncData.truncated || reading.truncated
                    if (isTruncated) {
                        Log.w(TAG, "sync-collection truncated (RFC 6578 §3.6). " +
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
                responseCode in 200..299 -> {
                    // A 2xx that isn't the 207 the report answers with: a hotspot or
                    // proxy page, not a list of changes.
                    Log.w(TAG, "sync-collection: $responseCode instead of 207")
                    CalDavResult.notMultistatus("status $responseCode, not 207")
                }
                responseCode == 401 -> CalDavResult.authError("Authentication failed")
                responseCode == 403 || responseCode == 410 -> {
                    // Here 403/410 always means an expired sync-token: some servers (iCloud)
                    // send a bare 403 without the valid-sync-token element. processResponse,
                    // for other requests, tells a token error from a permission denial.
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

    override suspend fun fetchEventsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<CalDavEvent>> = withContext(Dispatchers.IO) {
        val timeRange = buildTimeRangeElement(startMillis, endMillis)

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
                            $timeRange
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

        executeRequest(request, rejectTruncated = true) { responseBody ->
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

    override suspend fun fetchAllEtags(
        calendarUrl: String
    ): CalDavResult<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
        // PROPFIND Depth:1 lists every resource with its etag, with no time-range filter.
        // Used on servers without sync-token (e.g., Purelymail) whose calendar-query index
        // may be stale. The parser skips the collection's own row by its trailing slash
        // (RFC 4918 §5.2: collection hrefs SHOULD end with `/`, member hrefs don't), which
        // also works for servers that store events at extensionless UID hrefs.
        // Never add <d:resourcetype/>: iCloud answers it with a separate propstat-404 per
        // member (~360 bytes each on RK iCal-class calendars), bloating the reply past the
        // read timeout.
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        executeRequest(request, rejectTruncated = true) { responseBody ->
            // extractChangedItems reads href+etag pairs and skips the collection's own row.
            val items = quirks.extractChangedItems(responseBody)
            CalDavResult.success(items)
        }
    }

    override suspend fun fetchEtagsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<Pair<String, String?>>> = withContext(Dispatchers.IO) {
        val timeRange = buildTimeRangeElement(startMillis, endMillis)

        // fetchEventsInRange's calendar-query without <c:calendar-data/>: href+etag pairs
        // only, ~96% less data (33KB vs 834KB for 231 events). The collection row is skipped
        // as in fetchAllEtags, and <d:resourcetype/> must stay out for the same iCloud reason.
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <c:filter>
                    <c:comp-filter name="VCALENDAR">
                        <c:comp-filter name="VEVENT">
                            $timeRange
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

        executeRequest(request, rejectTruncated = true) { responseBody ->
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

        // Never trimIndent() here: interpolated href lines have no indent, so trimIndent()
        // would leave whitespace before the <?xml declaration, which iCloud rejects with 400.
        val body = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""")
            appendLine("""    <d:prop>""")
            appendLine("""        <d:getetag/>""")
            appendLine("""        <c:calendar-data/>""")
            appendLine("""    </d:prop>""")
            for (href in hrefs) {
                appendLine("""    <d:href>${escapeXmlText(href)}</d:href>""")
            }
            append("""</c:calendar-multiget>""")
        }

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

    /**
     * Sends the request [build] makes for the stored resource [url]; on 404, when the
     * resource name holds an '@' (literal or `%40`), sends it once more with that '@'
     * written the other way. Returns the response and the URL it answered for.
     *
     * KashCal names a resource after its UID (`<uuid>@kashcal.onekash.org.ics`), and a
     * server may store the name with the '@' percent-encoded and then answer only that
     * spelling (Stalwart answers GET and DELETE on the literal '@' with 404). '@' is legal
     * either way in a path segment (RFC 3986 section 3.3), so a 404 on one spelling isn't
     * proof the resource is gone. A retried PUT or DELETE keeps its If-Match, so it can
     * only change a resource whose ETag matches. A MOVE has no precondition; both
     * spellings still name the same event, because the resource name comes from the UID,
     * which is unique within a calendar (RFC 4791 section 4.1). Its Destination follows the
     * spelling that answered (see [moveEvent]).
     */
    private fun executeForResource(url: String, build: (String) -> Request): Pair<Response, String> {
        val first = httpClient.newCall(build(url)).execute()
        if (first.code != 404) return first to url
        val other = atSignAlternate(url) ?: return first to url
        first.close()
        return httpClient.newCall(build(other)).execute() to other
    }

    override suspend fun fetchEvent(eventUrl: String): CalDavResult<CalDavEvent> =
        withContext(Dispatchers.IO) {
            try {
                val (response, _) = executeForResource(eventUrl) { url -> Request.Builder().url(url).get().build() }
                val responseBody = response.readBoundedBody()

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
            } catch (e: DavTransportRefusedException) {
                transportRefused(e)
            } catch (e: IOException) {
                CalDavResult.networkError("Network error: ${e.message}")
            }
        }

    /**
     * Reads an event's ETag with a Depth 0 PROPFIND.
     *
     * When the PROPFIND fails with a status other than 404 or 401, falls back to a
     * single-href multiget ([fetchEtagViaMultiget]).
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

            try {
                val (response, foundUrl) = executeForResource(eventUrl) { url ->
                    Request.Builder()
                        .url(url)
                        .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
                        .header("Depth", "0")
                        .build()
                }
                val responseBody = response.readBoundedBody()

                when {
                    response.isSuccessful -> {
                        // A login page or a cut-off reply holds no ETag; saying "none"
                        // would fail the edit for good instead of waiting for the network.
                        unusableReply(response, responseBody)?.let { return@withContext it }
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
                        // PROPFIND failed (e.g., Zoho returns 501). Try single-href multiget.
                        response.close()  // readBoundedBody() already closed the body.
                        Log.w(TAG, "fetchEtag: PROPFIND failed (${response.code}) for $eventUrl, trying multiget")
                        val calendarUrl = foundUrl.substringBeforeLast("/") + "/"
                        when (val multiget = fetchEtagViaMultiget(calendarUrl, foundUrl)) {
                            is CalDavResult.Success -> multiget.data?.let { CalDavResult.success(it) }
                                ?: CalDavResult.error(response.code, "Failed to fetch ETag: ${response.code}")
                            // A transient answer (network, hotspot, refused) stays transient.
                            is CalDavResult.Error ->
                                if (multiget.isRetryable) multiget
                                else CalDavResult.error(response.code, "Failed to fetch ETag: ${response.code}")
                        }
                    }
                }
            } catch (e: DavTransportRefusedException) {
                transportRefused(e)
            } catch (e: IOException) {
                Log.e(TAG, "fetchEtag network error for $eventUrl: ${e.message}")
                CalDavResult.networkError("Network error: ${e.message}")
            }
        }

    /**
     * Returns the first getetag value in a PROPFIND or multiget reply, normalized, or null.
     *
     * Accepts any namespace prefix and a `&quot;`-encoded value:
     * - <d:getetag>"abc123"</d:getetag>
     * - <D:getetag>"abc123"</D:getetag>
     * - <d:getetag>&quot;abc123&quot;</d:getetag>
     */
    private fun extractEtagFromPropfind(xml: String): String? {
        val regex = Regex(
            """<(?:[a-zA-Z0-9]+:)?getetag[^>]*>([^<]+)</""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(xml)?.groupValues?.get(1) ?: return null

        // Decode &quot; only, then normalize (strips the W/ prefix and quotes).
        val decoded = match.replace("&quot;", "\"").trim()
        return EtagUtils.normalizeEtag(decoded)
    }

    /**
     * Reads one event's ETag with a single-href calendar-multiget, getetag only.
     * [fetchEtag] uses it when PROPFIND fails (Zoho returns 501 for PROPFIND on an event).
     */
    private fun fetchEtagViaMultiget(calendarUrl: String, eventUrl: String): CalDavResult<String?> {
        val href = java.net.URI(eventUrl).path
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <d:href>${escapeXmlText(href)}</d:href>
            </c:calendar-multiget>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.readBoundedBody()
                    unusableReply(response, responseBody)?.let { return@use it }
                    val etag = extractEtagFromPropfind(responseBody)
                    Log.d(TAG, "fetchEtagViaMultiget: Got ETag '$etag' for $eventUrl")
                    CalDavResult.success(etag)
                } else {
                    Log.w(TAG, "fetchEtagViaMultiget failed: ${response.code} for $eventUrl")
                    CalDavResult.error(response.code, "multiget failed: ${response.code}")
                }
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            Log.e(TAG, "fetchEtagViaMultiget network error: ${e.message}")
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    // ========== Mutations ==========

    override suspend fun createEvent(
        calendarUrl: String,
        uid: String,
        icalData: String
    ): CalDavResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        // The UID becomes one path segment: a '/', '?', '#' or space in another client's
        // UID is percent-encoded instead of splitting the path. KashCal's own UIDs
        // (`<uuid>@kashcal.onekash.org`) come out unchanged ('@' is legal in a segment).
        val eventUrl = "${calendarUrl.trimEnd('/')}/${encodedPathSegment("$uid.ics")}"

        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody(ICAL_MEDIA_TYPE))
            .header("If-None-Match", "*") // 412 when a resource already exists there
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                // Where the server took it, when it redirected the PUT.
                val redirected = redirectedUrl(eventUrl, response)
                val storedUrl = redirected ?: eventUrl
                when {
                    response.code == 201 || response.code == 204 -> {
                        var etag = EtagUtils.normalizeEtag(response.header("ETag"))

                        // RFC 4791 section 5.3.4: a server SHOULD return an ETag but may not
                        // (e.g., Nextcloud, Zoho); then ask for it.
                        if (etag.isNullOrEmpty()) {
                            Log.d(TAG, "createEvent: No ETag in response header, fetching via fallback")
                            etag = fetchEtag(storedUrl).getOrNull()
                        }

                        CalDavResult.Success(Pair(storedUrl, etag.orEmpty()), finalUrl = redirected)
                    }
                    response.code == 412 -> CalDavResult.conflictError("Event already exists")
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    response.code == 403 -> {
                        // RFC 4791: a UID conflict is a 403 whose Location names the existing
                        // event. Servers (iCloud) also 403 a body they dislike; the body then
                        // usually holds a precondition or error element with details.
                        val existingUrl = response.header("Location")
                        val body = response.body?.string().orEmpty().take(2000)
                        if (existingUrl != null) {
                            Log.w(TAG, "createEvent 403 UID conflict at $existingUrl; body=$body")
                            CalDavResult.error(403, "UID conflict: event already exists at $existingUrl")
                        } else {
                            Log.w(TAG, "createEvent 403 (no Location); body=$body")
                            CalDavResult.error(403, "Permission denied")
                        }
                    }
                    // RFC 4791: the event exceeds the calendar's max-resource-size.
                    response.code == 413 -> CalDavResult.error(413, "Event too large for server")
                    else -> CalDavResult.error(response.code, "Failed to create event: ${response.code}")
                }
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun updateEvent(
        eventUrl: String,
        icalData: String,
        etag: String
    ): CalDavResult<String> = withContext(Dispatchers.IO) {
        try {
            val (response, foundUrl) = executeForResource(eventUrl) { url ->
                Request.Builder()
                    .url(url)
                    .put(icalData.toRequestBody(ICAL_MEDIA_TYPE))
                    .header("If-Match", "\"$etag\"")
                    .build()
            }

            response.use {
                // Where the server took it, when it redirected the PUT: the
                // caller stores it so the next edit goes straight there.
                val redirected = redirectedUrl(foundUrl, response)
                when {
                    response.code in listOf(200, 201, 204) -> {
                        var newEtag = EtagUtils.normalizeEtag(response.header("ETag"))

                        // No ETag in the reply (RFC 4791 section 5.3.4, as in createEvent): ask
                        // for it, and keep the old one if that fails too.
                        if (newEtag.isNullOrEmpty()) {
                            Log.d(TAG, "updateEvent: No ETag in response header, fetching via fallback")
                            newEtag = fetchEtag(redirected ?: foundUrl).getOrNull() ?: etag
                        }

                        CalDavResult.Success(newEtag, finalUrl = redirected)
                    }
                    response.code == 412 -> CalDavResult.conflictError("Event was modified on server")
                    response.code == 404 -> CalDavResult.notFoundError("Event not found")
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    response.code == 403 -> CalDavResult.error(403, "Permission denied")
                    response.code == 413 -> CalDavResult.error(413, "Event too large for server")
                    else -> CalDavResult.error(response.code, "Failed to update event: ${response.code}")
                }
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun deleteEvent(
        eventUrl: String,
        etag: String?
    ): CalDavResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val (response, _) = executeForResource(eventUrl) { url ->
                Request.Builder()
                    .url(url)
                    .delete()
                    .apply { if (etag != null) header("If-Match", "\"$etag\"") }
                    .build()
            }

            response.use {
                when {
                    response.code == 200 || response.code == 204 || response.code == 404 -> {
                        // A 404 is success: the event is already gone.
                        CalDavResult.success(Unit)
                    }
                    response.code == 412 -> CalDavResult.conflictError("Event was modified on server")
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    response.code == 403 -> CalDavResult.error(403, "Permission denied")
                    else -> CalDavResult.error(response.code, "Failed to delete event: ${response.code}")
                }
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    override suspend fun postToOutbox(
        outboxUrl: String,
        originator: String,
        recipients: List<String>,
        icalData: String
    ): CalDavResult<OutboxResponse> = withContext(Dispatchers.IO) {
        // The stored outbox URL may be server-relative (Zoho returns "/caldav/<id>/outbox/")
        // and OkHttp needs an absolute URL, so resolve it against the account's base host.
        val absoluteOutboxUrl = if (outboxUrl.startsWith("http", ignoreCase = true)) {
            outboxUrl
        } else {
            extractBaseHost(quirks.baseUrl) + (if (outboxUrl.startsWith("/")) outboxUrl else "/$outboxUrl")
        }

        val requestBuilder = Request.Builder()
            .url(absoluteOutboxUrl)
            .post(icalData.toRequestBody(ICAL_MEDIA_TYPE))
            // RFC 6638 §6 envelope headers. Callers pass bare addresses; mailto: is added here.
            .header("Originator", "mailto:$originator")
        // addHeader, because header() would keep only the last recipient.
        for (recipient in recipients) {
            requestBuilder.addHeader("Recipient", "mailto:$recipient")
        }
        val request = requestBuilder.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    // 200 and 207 both carry a schedule-response.
                    response.code == 200 || response.code == 207 -> {
                        val body = response.readBoundedBody()
                        CalDavResult.success(OutboxResponse.parse(body))
                    }
                    response.code == 401 -> CalDavResult.authError("Authentication failed")
                    response.code == 403 -> CalDavResult.error(403, "Outbox POST forbidden")
                    // e.g., Sabre free/busy-only (501), Stalwart invalid-scheduling-message
                    // (400): the outbox doesn't take event REQUESTs.
                    else -> CalDavResult.error(
                        response.code,
                        "Outbox POST failed: ${response.code}"
                    )
                }
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    /**
     * Moves an event to another calendar with WebDAV MOVE (RFC 4918) and returns its new URL
     * and ETag.
     *
     * MOVE is atomic, so a same-account move avoids the UID conflict a DELETE then create can
     * race into on servers like iCloud.
     */
    override suspend fun moveEvent(
        sourceUrl: String,
        destinationCalendarUrl: String,
        uid: String
    ): CalDavResult<Pair<String, String>> = withContext(Dispatchers.IO) {
        // Destination: the destination calendar plus the source's own resource name, in
        // the spelling the server answered. Resource names are opaque (RFC 4918), and a
        // name the server itself chose (another client's event: UID with '@', name
        // without) must be kept: Stalwart leaves a literal-'@' destination unwritable
        // (every If-Match PUT gets 412). For KashCal's own names (`<uid>.ics`) servers
        // disagree about a Destination's '@': Radicale keeps a "%40" in it verbatim,
        // while Stalwart only answers the %40 spelling of what it stores. So the source
        // is asked for with a literal '@' first, and the destination carries %40 only
        // when the server refused that and answered the %40 source. The name is reused
        // as the already-encoded path segment it is (re-encoding would turn %40 into %2540).
        val sourceName = sourceUrl.substringAfterLast('/')
        val literalSource = if ('@' !in sourceName) atSignAlternate(sourceUrl) ?: sourceUrl else sourceUrl
        fun destinationFor(source: String): String =
            "${destinationCalendarUrl.trimEnd('/')}/${source.substringAfterLast('/')}"

        try {
            val (response, foundSource) = executeForResource(literalSource) { url ->
                Request.Builder()
                    .url(url)
                    .method("MOVE", null)
                    .header("Destination", destinationFor(url))
                    .header("Overwrite", "F")  // 412 when the destination exists
                    .build()
            }

            val destinationUrl = destinationFor(foundSource)
            response.use {
                when (response.code) {
                    201, 204 -> {
                        var newEtag = EtagUtils.normalizeEtag(response.header("ETag"))

                        if (newEtag.isNullOrEmpty()) {
                            Log.d(TAG, "moveEvent: No ETag in response header, fetching via PROPFIND")
                            val etagResult = fetchEtag(destinationUrl)
                            if (etagResult.isSuccess()) {
                                newEtag = etagResult.getOrNull()
                            }
                        }

                        Log.d(TAG, "moveEvent: Success for ${uid.maskUid()}, new URL=$destinationUrl, etag=$newEtag")
                        CalDavResult.success(destinationUrl to newEtag.orEmpty())
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
            }
        } catch (e: DavTransportRefusedException) {
            transportRefused(e)
        } catch (e: IOException) {
            Log.e(TAG, "moveEvent: Network error", e)
            CalDavResult.networkError("Network error: ${e.message}")
        }
    }

    // ========== Configuration ==========

    /**
     * Checks with OPTIONS that the server is reachable and advertises CalDAV
     * ("calendar-access" in the DAV header, which RFC 4791 requires).
     *
     * Retries a 429, a 5xx or a retryable network error, up to [MAX_RETRIES] attempts.
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
                    // Every response is closed here, including the 429 and 5xx ones that
                    // are retried; only the wait happens outside the use block.
                    val retryDelay: Long = httpClient.newCall(request).execute().use { response ->
                        when {
                            response.code == 429 && attempt < MAX_RETRIES - 1 -> {
                                val retryAfter = parseRetryAfterHeader(response) ?: currentBackoff
                                Log.w(TAG, "Rate limited (429) on OPTIONS, waiting ${retryAfter}ms before retry")
                                retryAfter
                            }
                            response.code in 500..599 && attempt < MAX_RETRIES - 1 -> {
                                val retryDelay = if (response.code == 503) {
                                    parseRetryAfterHeader(response) ?: currentBackoff
                                } else {
                                    currentBackoff
                                }
                                Log.w(TAG, "Server error ${response.code} on OPTIONS, retry after ${retryDelay}ms")
                                currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                                retryDelay
                            }
                            else -> return@withContext connectionResult(response)
                        }
                    }
                    delay(retryDelay)
                    return@repeat // Retry
                } catch (e: DavTransportRefusedException) {
                    return@withContext transportRefused(e)
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

    /**
     * Returns the error a 2xx reply to a PROPFIND or REPORT stands for (RFC 4918 section
     * 9.1), or null when it can be parsed. It is an error when it isn't a 207 WebDAV
     * multistatus, or when [rejectTruncated] and the server marked the listing cut short
     * (RFC 6578 section 3.6, RFC 4791 section 7.8: events past the cut would look deleted).
     */
    private fun unusableReply(response: Response, body: String, rejectTruncated: Boolean = false): CalDavResult.Error? {
        val reading = DavMultistatus.readReply(response.code, response.header("Content-Type"), body)
        reading.problem?.let { problem ->
            Log.w(TAG, "${response.request.method} ${response.request.url.host.maskHost()}: not a multistatus ($problem)")
            return CalDavResult.notMultistatus(problem)
        }
        if (rejectTruncated && reading.truncated) {
            Log.w(TAG, "${response.request.method}: the server truncated the listing")
            return CalDavResult.error(507, "Listing truncated by the server", isRetryable = true)
        }
        return null
    }

    /** Maps an OPTIONS reply to [checkConnection]'s result. */
    private fun connectionResult(response: Response): CalDavResult<Unit> = when {
        response.isSuccessful -> {
            // Join every DAV line, as in supportsAutoSchedule.
            val davHeader = response.headers("DAV").joinToString(", ")
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

    /** Maps a transport-guard refusal to [CalDavResult.transportRefused]. */
    private fun transportRefused(e: DavTransportRefusedException): CalDavResult.Error =
        CalDavResult.transportRefused(e.message.orEmpty())

    /** Returns where [response] ended up when a redirect moved it off [requested], else null. */
    private fun redirectedUrl(requested: String, response: Response): String? {
        val requestedUrl = requested.toHttpUrlOrNull() ?: return null
        return response.request.url.takeIf { it != requestedUrl }?.toString()
    }

    private suspend inline fun <T> executeRequest(
        request: Request,
        rejectTruncated: Boolean = false,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return executeWithRetry(request, rejectTruncated, parser)
    }

    /**
     * Sends [request] and parses the reply, up to [MAX_RETRIES] attempts.
     *
     * Retries a 5xx (waiting a 503's Retry-After, else the backoff), a 429 only when it
     * carries a Retry-After, and a timeout, unknown host or other retryable network error.
     * A TLS handshake failure or a transport-guard refusal is returned at once.
     */
    private suspend inline fun <T> executeWithRetry(
        request: Request,
        rejectTruncated: Boolean,
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
                        return@repeat // Retry
                    }
                    return result
                }

                // RFC 7231: a 503 may carry Retry-After.
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

            } catch (e: DavTransportRefusedException) {
                // Deterministic: retrying in place would be refused the same way.
                return transportRefused(e)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Socket timeout on ${request.method} ${request.url}, " +
                        "retry ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                lastException = e
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown host for ${request.url}, " +
                        "retry ${attempt + 1}/$MAX_RETRIES: ${e.message}")
                lastException = e
            } catch (e: SSLHandshakeException) {
                // Never retry a TLS failure: it is likely a security problem, not a flake.
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

            // Exponential backoff before the next attempt.
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
     * Maps a reply to a [CalDavResult], parsing a success with [parser].
     *
     * Every caller sends PROPFIND or REPORT, so a success must be a 207 whose body is
     * a WebDAV multistatus. Any other 2xx (a hotspot or proxy page, a reply cut
     * short) is an error, never an empty answer a sync could delete events on.
     */
    private inline fun <T> processResponse(
        response: Response,
        responseBody: String,
        rejectTruncated: Boolean = false,
        parser: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return when {
            response.isSuccessful || response.code == 207 ->
                unusableReply(response, responseBody, rejectTruncated) ?: parser(responseBody)
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

    /**
     * Returns the Retry-After wait in milliseconds, or null without the header. Reads both
     * RFC 7231 section 7.1.3 forms, delay-seconds ("120") and HTTP-date ("Sun, 06 Nov 1994
     * 08:49:37 GMT"); a date it can't parse waits [DEFAULT_RETRY_AFTER_MS].
     */
    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null

        retryAfter.toLongOrNull()?.let { return it * 1000 }

        return try {
            val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val targetTime = httpDateFormat.parse(retryAfter)?.time ?: return DEFAULT_RETRY_AFTER_MS
            val delayMs = targetTime - System.currentTimeMillis()
            // A date in the past retries at once.
            delayMs.coerceAtLeast(0)
        } catch (_: Exception) {
            Log.w(TAG, "Could not parse Retry-After header: $retryAfter, using default")
            DEFAULT_RETRY_AFTER_MS
        }
    }

    /**
     * Returns true for a timeout, unknown host or refused connection, or an error whose message
     * mentions a reset or a connection.
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

/**
 * [url] with the '@' in its last path segment written the other way ('@' becomes
 * `%40`, else `%40` becomes '@'), or null when that segment has neither. Only the
 * resource name changes: a collection path may itself hold a `%40`
 * (`/dav/cal/user%40host/`), which must be sent as it is.
 */
internal fun atSignAlternate(url: String): String? {
    val cut = url.lastIndexOf('/')
    if (cut < 0 || cut == url.length - 1) return null
    val name = url.substring(cut + 1)
    val swapped = when {
        // A name holding both spellings is tried all-%40.
        name.contains('@') -> name.replace("@", "%40")
        name.contains("%40") -> name.replace("%40", "@")
        else -> return null
    }
    return url.substring(0, cut + 1) + swapped
}

/**
 * [name] percent-encoded as a single URL path segment (RFC 3986 section 3.3): '/', '?',
 * '#', '%', spaces and non-ASCII are encoded; '@' and other characters legal in a
 * segment are kept as they are.
 */
internal fun encodedPathSegment(name: String): String =
    HttpUrl.Builder().scheme("https").host("localhost").addPathSegment(name).build()
        .encodedPathSegments.last()
