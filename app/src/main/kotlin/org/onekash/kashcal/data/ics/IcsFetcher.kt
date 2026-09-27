package org.onekash.kashcal.data.ics

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.network.AiaCertificateChainCompleter
import org.onekash.kashcal.network.ResponseTooLargeException
import org.onekash.kashcal.network.readBoundedBody
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/** Fetches a subscription's ICS feed; an interface so tests can substitute a fake. */
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

// Same retry values as OkHttpCalDavClient. MAX_RETRIES counts attempts, not retries.
private const val MAX_RETRIES = 2
private const val INITIAL_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 2000L
private const val BACKOFF_MULTIPLIER = 2.0
/**
 * Fetches ICS feeds over OkHttp with conditional headers and retries.
 *
 * Retries with backoff (honoring Retry-After on 429 and 503): HTTP 429, 503 and other 5xx, and
 * the network errors [isRetryableError] accepts. An SSL handshake failure gets one retry after
 * AIA chain completion. Nothing else is retried: HTTP 401, 403, 404, 413 or any other code, an
 * oversize body, invalid ICS.
 */
@Singleton
class OkHttpIcsFetcher @Inject constructor() : IcsFetcher {

    private val aiaCertChainCompleter by lazy { AiaCertificateChainCompleter() }

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

        // Conditional request from the cached validators; a match returns 304.
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

                // Retryable codes wait and retry, except on the last attempt.
                when {
                    response.code == 429 && attempt < MAX_RETRIES - 1 -> {
                        val retryAfter = parseRetryAfterHeader(response) ?: currentBackoff
                        Log.w(TAG, "Rate limited (429), waiting ${retryAfter}ms before retry ${attempt + 1}")
                        delay(retryAfter)
                        currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                        return@repeat // next attempt
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

                val result = processResponse(response)
                if (result != null) {
                    return result
                }

                // A retryable code on the last attempt; reported after the loop.
                lastResult = IcsFetcher.FetchResult.Error("HTTP ${response.code}: ${response.message}")

            } catch (e: SSLHandshakeException) {
                // Some servers (e.g., Moodle) serve an incomplete chain: the intermediate is
                // missing but the leaf cert's AIA extension points to it. Complete the chain
                // and retry once.
                Log.w(TAG, "SSL handshake failed, attempting AIA chain completion: ${e.message}")
                val parsedUrl = URL(subscription.getNormalizedUrl())
                val aiaResult = aiaCertChainCompleter.attemptChainCompletion(
                    hostname = parsedUrl.host,
                    port = if (parsedUrl.port > 0) parsedUrl.port else 443,
                    baseClientBuilder = httpClient.newBuilder()
                )
                when (aiaResult) {
                    is AiaCertificateChainCompleter.Result.Success -> {
                        Log.i(TAG, "AIA succeeded, retrying fetch")
                        return try {
                            val retryResponse = aiaResult.client.newCall(request).execute()
                            processResponse(retryResponse)
                                ?: IcsFetcher.FetchResult.Error("HTTP ${retryResponse.code}: ${retryResponse.message}")
                        } catch (retryEx: Exception) {
                            Log.e(TAG, "Retry after AIA failed: ${retryEx.message}", retryEx)
                            IcsFetcher.FetchResult.Error("SSL error: ${e.message}")
                        }
                    }
                    is AiaCertificateChainCompleter.Result.Failed -> {
                        Log.e(TAG, "AIA chain completion failed: ${aiaResult.reason}")
                        return IcsFetcher.FetchResult.Error("SSL error: ${e.message}")
                    }
                }

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

        // Reached only when the last attempt got 429, 503 or another 5xx.
        Log.e(TAG, "All $MAX_RETRIES retries exhausted for ${subscription.url}")
        return lastResult ?: IcsFetcher.FetchResult.Error("Failed after $MAX_RETRIES retries")
    }

    /**
     * Maps a response to a result, validating a 2xx body as ICS.
     *
     * Returns null for 429, 503 and other 5xx; the caller retries or reports them as an error.
     */
    private fun processResponse(response: Response): IcsFetcher.FetchResult? {
        return when {
            response.code == 304 -> {
                IcsFetcher.FetchResult.NotModified
            }

            response.isSuccessful -> {
                val content = try {
                    response.readBoundedBody()
                } catch (e: ResponseTooLargeException) {
                    // Reported like the server's 413, not as a network error.
                    Log.w(TAG, "ICS feed too large: ${e.message}")
                    return IcsFetcher.FetchResult.Error("Calendar too large")
                }
                if (content.isBlank()) {
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

            response.code == 429 || response.code == 503 || response.code in 500..599 -> {
                null
            }

            else -> {
                IcsFetcher.FetchResult.Error("HTTP ${response.code}: ${response.message}")
            }
        }
    }

    /** Returns true for timeout, DNS, connect and socket errors, EOF, or a "connection" message. */
    private fun isRetryableError(e: IOException): Boolean {
        return when {
            e is SocketTimeoutException -> true
            e is UnknownHostException -> true
            e is ConnectException -> true
            e is SocketException -> true  // connection reset
            e is EOFException -> true     // connection closed early
            e.message?.contains("connection", ignoreCase = true) == true -> true
            else -> false
        }
    }

    /**
     * Returns the Retry-After delay in milliseconds, or null if the header is missing or invalid.
     *
     * RFC 7231 §7.1.3 allows delay-seconds ("120") or an HTTP-date
     * ("Sun, 06 Nov 1994 08:49:37 GMT"). A past date gives 0.
     */
    private fun parseRetryAfterHeader(response: Response): Long? {
        val retryAfter = response.header("Retry-After") ?: return null

        retryAfter.toLongOrNull()?.let { seconds ->
            return (seconds * 1000).coerceAtLeast(0)
        }

        return try {
            val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val date = dateFormat.parse(retryAfter)
            val delayMs = (date?.time ?: return null) - System.currentTimeMillis()
            delayMs.coerceAtLeast(0)
        } catch (_: Exception) {
            Log.w(TAG, "Could not parse Retry-After header: $retryAfter")
            null
        }
    }
}
