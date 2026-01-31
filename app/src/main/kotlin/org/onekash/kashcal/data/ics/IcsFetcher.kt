package org.onekash.kashcal.data.ics

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.onekash.kashcal.data.db.entity.IcsSubscription
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/**
 * Interface for fetching ICS content.
 * Allows mocking in tests.
 */
interface IcsFetcher {
    suspend fun fetch(subscription: IcsSubscription): FetchResult

    sealed class FetchResult {
        data class Success(
            val content: String,
            val etag: String?,
            val lastModified: String?
        ) : FetchResult()

        data object NotModified : FetchResult()
        data class Error(val message: String) : FetchResult()
    }
}

private const val TAG = "OkHttpIcsFetcher"

// Retry configuration (matches OkHttpCalDavClient pattern)
private const val MAX_RETRIES = 2
private const val INITIAL_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 2000L
private const val BACKOFF_MULTIPLIER = 2.0
private const val DEFAULT_RETRY_AFTER_MS = 500L

/**
 * OkHttp-based implementation of IcsFetcher with retry logic.
 *
 * Retry behavior:
 * - YES: SocketTimeoutException, ConnectException, UnknownHostException, HTTP 429/503/5xx
 * - NO: HTTP 401/403/404/413, SSLHandshakeException, invalid ICS content
 */
@Singleton
class OkHttpIcsFetcher @Inject constructor() : IcsFetcher {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override suspend fun fetch(subscription: IcsSubscription): IcsFetcher.FetchResult {
        val requestBuilder = Request.Builder()
            .url(subscription.getNormalizedUrl())
            .header("Accept", "text/calendar, */*")
            .header("User-Agent", "KashCal/1.0")

        // Add conditional headers if we have cached values
        subscription.etag?.let { etag ->
            requestBuilder.header("If-None-Match", etag)
        }
        subscription.lastModified?.let { lastModified ->
            requestBuilder.header("If-Modified-Since", lastModified)
        }

        val request = requestBuilder.build()

        var lastResult: IcsFetcher.FetchResult? = null
        var currentBackoff = INITIAL_BACKOFF_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()

                // Handle retryable HTTP codes
                when {
                    response.code == 429 && attempt < MAX_RETRIES - 1 -> {
                        val retryAfter = parseRetryAfterHeader(response) ?: currentBackoff
                        Log.w(TAG, "Rate limited (429), waiting ${retryAfter}ms before retry ${attempt + 1}")
                        delay(retryAfter)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        return@repeat // Continue to next retry
                    }

                    response.code == 503 && attempt < MAX_RETRIES - 1 -> {
                        val retryAfter = parseRetryAfterHeader(response) ?: currentBackoff
                        Log.w(TAG, "Service unavailable (503), waiting ${retryAfter}ms before retry ${attempt + 1}")
                        delay(retryAfter)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        return@repeat
                    }

                    response.code in 500..599 && attempt < MAX_RETRIES - 1 -> {
                        Log.w(TAG, "Server error (${response.code}), retry ${attempt + 1}/$MAX_RETRIES")
                        delay(currentBackoff)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        return@repeat
                    }
                }

                // Process response (no retry)
                val result = processResponse(response)
                if (result != null) {
                    return result
                }

                // Store error for potential retry exhaustion
                lastResult = IcsFetcher.FetchResult.Error("HTTP ${response.code}: ${response.message}")

            } catch (e: SSLHandshakeException) {
                // Don't retry SSL errors - likely a security issue
                Log.e(TAG, "SSL error fetching ICS (not retrying): ${e.message}", e)
                return IcsFetcher.FetchResult.Error("SSL error: ${e.message}")

            } catch (e: IOException) {
                if (isRetryableError(e) && attempt < MAX_RETRIES - 1) {
                    Log.w(TAG, "Retryable error, retry ${attempt + 1}/$MAX_RETRIES: ${e.javaClass.simpleName} - ${e.message}")
                    delay(currentBackoff)
                    currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    lastResult = IcsFetcher.FetchResult.Error("Network error: ${e.message}")
                } else {
                    Log.e(TAG, "Network error fetching ICS: ${e.javaClass.simpleName} - ${e.message}", e)
                    return IcsFetcher.FetchResult.Error("Network error: ${e.message}")
                }
            }
        }

        // All retries exhausted
        Log.e(TAG, "All $MAX_RETRIES retries exhausted for ${subscription.url}")
        return lastResult ?: IcsFetcher.FetchResult.Error("Failed after $MAX_RETRIES retries")
    }

    /**
     * Process HTTP response and return result.
     * Returns null for retryable error codes (caller handles retry).
     */
    private fun processResponse(response: Response): IcsFetcher.FetchResult? {
        return when {
            response.code == 304 -> {
                IcsFetcher.FetchResult.NotModified
            }

            response.isSuccessful -> {
                val content = response.body?.string()
                if (content.isNullOrBlank()) {
                    IcsFetcher.FetchResult.Error("Empty response from server")
                } else if (!IcsParserService.isValidIcs(content)) {
                    IcsFetcher.FetchResult.Error("Invalid ICS format")
                } else {
                    IcsFetcher.FetchResult.Success(
                        content = content,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified")
                    )
                }
            }

            response.code == 401 || response.code == 403 -> {
                IcsFetcher.FetchResult.Error("Authentication required")
            }

            response.code == 404 -> {
                IcsFetcher.FetchResult.Error("Calendar not found (404)")
            }

            response.code == 413 -> {
                IcsFetcher.FetchResult.Error("Calendar too large (413)")
            }

            // For 429/503/5xx, return null to signal retry
            response.code == 429 || response.code == 503 || response.code in 500..599 -> {
                null
            }

            else -> {
                IcsFetcher.FetchResult.Error("HTTP ${response.code}: ${response.message}")
            }
        }
    }

    /**
     * Check if an IOException is retryable.
     */
    private fun isRetryableError(e: IOException): Boolean {
        return when {
            e is SocketTimeoutException -> true
            e is UnknownHostException -> true
            e is ConnectException -> true
            e is SocketException -> true  // Connection reset
            e is EOFException -> true     // Connection closed unexpectedly
            e.message?.contains("connection", ignoreCase = true) == true -> true
            else -> false
        }
    }

    /**
     * Parse Retry-After header value.
     *
     * RFC 7231 Section 7.1.3 defines two formats:
     * - delay-seconds: "120" (seconds to wait)
     * - HTTP-date: "Sun, 06 Nov 1994 08:49:37 GMT"
     *
     * @return Delay in milliseconds, or null if header is missing/invalid
     */
    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null

        // Try parsing as seconds first
        retryAfter.toLongOrNull()?.let { seconds ->
            return (seconds * 1000).coerceAtLeast(0)
        }

        // Try parsing as HTTP-date
        return try {
            val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val date = dateFormat.parse(retryAfter)
            val delayMs = (date?.time ?: return null) - System.currentTimeMillis()
            delayMs.coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse Retry-After header: $retryAfter")
            null
        }
    }
}
