package org.onekash.icaldav.timezone

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches VTIMEZONE definitions from a timezone distribution service and caches them in memory.
 *
 * Works with the CalConnect TZURL service (https://www.tzurl.org) and any service that serves
 * `<serviceUrl>/<tzid>.ics`.
 *
 * @param serviceUrl base URL of the service; defaults to [DEFAULT_SERVICE_URL].
 *
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
class TimezoneServiceClient(
    private val serviceUrl: String = DEFAULT_SERVICE_URL,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) {

    private val cache = ConcurrentHashMap<String, CachedTimezone>()

    /**
     * Returns the service's VTIMEZONE body for [tzid] (an IANA ID such as "America/New_York").
     *
     * A successful body is cached for 24 hours; failures aren't cached. A non-200 status, an
     * empty body and every exception the request throws become a failure.
     */
    fun fetchTimezone(tzid: String): Result<String> {
        val cached = cache[tzid]
        if (cached != null && !cached.isExpired()) {
            return Result.success(cached.data)
        }

        val url = getTzurl(tzid)

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "text/calendar")

            try {
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    if (body.isNotEmpty()) {
                        cache[tzid] = CachedTimezone(body, System.currentTimeMillis())
                        Result.success(body)
                    } else {
                        Result.failure(IOException("Empty response from timezone service"))
                    }
                } else {
                    Result.failure(IOException("Timezone service returned $responseCode"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns the TZURL for [tzid]: `<serviceUrl>/<tzid>.ics`, with [tzid] not URL-encoded. */
    fun getTzurl(tzid: String): String {
        val baseUrl = serviceUrl.trimEnd('/')
        return "$baseUrl/$tzid.ics"
    }

    /**
     * Sends a HEAD request to the service URL and returns true when it answers 200 or 404 (a 404
     * still shows the service is reachable). Any other status or an exception returns false.
     */
    fun isAvailable(): Boolean {
        return try {
            val connection = URL(serviceUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            try {
                val responseCode = connection.responseCode
                responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NOT_FOUND
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearCache() {
        cache.clear()
    }

    fun cacheSize(): Int = cache.size

    /** A fetched body, expired once it is older than [CACHE_TTL_MS]. */
    private data class CachedTimezone(
        val data: String,
        val fetchedAt: Long
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS
    }

    companion object {
        /** CalConnect tzurl.org. */
        const val DEFAULT_SERVICE_URL = "https://www.tzurl.org/zoneinfo"

        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** A client with the default URL and timeouts. */
        val default = TimezoneServiceClient()
    }
}
