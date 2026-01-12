package org.onekash.kashcal.data.ics

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.onekash.kashcal.data.db.entity.IcsSubscription
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for fetching ICS content.
 * Allows mocking in tests.
 */
interface IcsFetcher {
    fun fetch(subscription: IcsSubscription): FetchResult

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

/**
 * OkHttp-based implementation of IcsFetcher.
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

    override fun fetch(subscription: IcsSubscription): IcsFetcher.FetchResult {
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

        return try {
            val response = httpClient.newCall(request).execute()

            when {
                response.code == 304 -> {
                    IcsFetcher.FetchResult.NotModified
                }

                response.isSuccessful -> {
                    val content = response.body?.string()
                    if (content.isNullOrBlank()) {
                        IcsFetcher.FetchResult.Error("Empty response from server")
                    } else if (!RfcIcsParser.isValidIcs(content)) {
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

                else -> {
                    IcsFetcher.FetchResult.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error fetching ICS", e)
            IcsFetcher.FetchResult.Error("Network error: ${e.message}")
        }
    }
}
