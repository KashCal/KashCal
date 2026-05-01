package org.onekash.icaldav.timezone

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Client for fetching timezone definitions from timezone distribution services.
 *
 * Supports the CalConnect TZURL service (https://www.tzurl.org) and
 * compatible services following the same URL pattern.
 *
 * Features:
 * - In-memory caching of fetched timezones
 * - Configurable service URL
 * - Connection timeout handling
 *
 * @param serviceUrl Base URL of the timezone service (defaults to tzurl.org)
 * @param connectTimeoutMs Connection timeout in milliseconds
 * @param readTimeoutMs Read timeout in milliseconds
 *
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
class TimezoneServiceClient(
    private val serviceUrl: String = DEFAULT_SERVICE_URL,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) {

    // In-memory cache for fetched timezone definitions
    private val cache = ConcurrentHashMap<String, CachedTimezone>()

    /**
     * Fetch a timezone definition from the service.
     *
     * Results are cached for 24 hours to avoid repeated network requests.
     *
     * @param tzid The IANA timezone ID (e.g., "America/New_York")
     * @return Result containing the iCalendar VTIMEZONE data or error
     */
    fun fetchTimezone(tzid: String): Result<String> {
        // Check cache first
        val cached = cache[tzid]
        if (cached != null && !cached.isExpired()) {
            return Result.success(cached.data)
        }

        // Fetch from service
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
                        // Cache the result
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

    /**
     * Get the TZURL for a timezone ID.
     *
     * @param tzid The IANA timezone ID
     * @return Full URL to fetch the timezone definition
     */
    fun getTzurl(tzid: String): String {
        val baseUrl = serviceUrl.trimEnd('/')
        return "$baseUrl/$tzid.ics"
    }

    /**
     * Check if the timezone service is available.
     *
     * Performs a lightweight HEAD request to verify connectivity.
     *
     * @return true if service responds successfully
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

    /**
     * Clear the timezone cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Get current cache size.
     */
    fun cacheSize(): Int = cache.size

    /**
     * Cached timezone data with expiration.
     */
    private data class CachedTimezone(
        val data: String,
        val fetchedAt: Long
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS
    }

    companion object {
        /** Default timezone service URL (CalConnect tzurl.org) */
        const val DEFAULT_SERVICE_URL = "https://www.tzurl.org/zoneinfo"

        /** Cache TTL: 24 hours */
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * Get instance with default settings.
         */
        val default = TimezoneServiceClient()
    }
}
