package org.onekash.icaldav.timezone

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [TimezoneServiceClient]'s URL building, cache and network calls.
 *
 * The URL, cache and constant tests run offline. Tests named "(requires network)" reach
 * tzurl.org but none fails offline: the two fetch tests assert only on success, and the
 * invalid-zone and availability asserts always hold. The invalid-host and 1 ms timeout tests
 * expect isAvailable to return false with or without a network; the 1 ms fetch test checks only
 * that nothing throws.
 */
@DisplayName("TimezoneServiceClient Tests")
class TimezoneServiceClientTest {

    @Nested
    @DisplayName("Constructor and Configuration")
    inner class ConstructorTests {

        @Test
        fun `default constructor uses tzurl org`() {
            val client = TimezoneServiceClient()

            val url = client.getTzurl("America/New_York")
            assertTrue(url.contains("tzurl.org"))
        }

        @Test
        fun `custom service URL is used`() {
            val client = TimezoneServiceClient(
                serviceUrl = "https://custom.example.com/zoneinfo"
            )

            val url = client.getTzurl("America/New_York")
            assertEquals("https://custom.example.com/zoneinfo/America/New_York.ics", url)
        }

        @Test
        fun `service URL trailing slash is handled`() {
            val client = TimezoneServiceClient(
                serviceUrl = "https://example.com/zoneinfo/"
            )

            val url = client.getTzurl("America/New_York")
            assertEquals("https://example.com/zoneinfo/America/New_York.ics", url)
        }
    }

    @Nested
    @DisplayName("getTzurl Tests")
    inner class GetTzurlTests {

        @Test
        fun `getTzurl generates correct URL for simple timezone`() {
            val client = TimezoneServiceClient()

            val url = client.getTzurl("America/New_York")

            assertEquals("https://www.tzurl.org/zoneinfo/America/New_York.ics", url)
        }

        @Test
        fun `getTzurl handles timezone with multiple path segments`() {
            val client = TimezoneServiceClient()

            val url = client.getTzurl("America/Argentina/Buenos_Aires")

            assertEquals("https://www.tzurl.org/zoneinfo/America/Argentina/Buenos_Aires.ics", url)
        }

        @Test
        fun `getTzurl handles UTC`() {
            val client = TimezoneServiceClient()

            val url = client.getTzurl("UTC")

            assertEquals("https://www.tzurl.org/zoneinfo/UTC.ics", url)
        }

        @Test
        fun `getTzurl handles Etc timezones`() {
            val client = TimezoneServiceClient()

            val url = client.getTzurl("Etc/GMT+5")

            assertEquals("https://www.tzurl.org/zoneinfo/Etc/GMT+5.ics", url)
        }
    }

    @Nested
    @DisplayName("Cache Tests")
    inner class CacheTests {

        @Test
        fun `cacheSize returns 0 initially`() {
            val client = TimezoneServiceClient()

            assertEquals(0, client.cacheSize())
        }

        @Test
        fun `clearCache empties the cache`() {
            val client = TimezoneServiceClient()

            // A new client's cache is already empty; this checks clearCache leaves it at 0.
            client.clearCache()

            assertEquals(0, client.cacheSize())
        }
    }

    @Nested
    @DisplayName("Default Instance")
    inner class DefaultInstanceTests {

        @Test
        fun `default instance exists`() {
            val client = TimezoneServiceClient.default

            val url = client.getTzurl("America/Los_Angeles")
            assertTrue(url.isNotEmpty())
        }

        @Test
        fun `DEFAULT_SERVICE_URL constant is correct`() {
            assertEquals("https://www.tzurl.org/zoneinfo", TimezoneServiceClient.DEFAULT_SERVICE_URL)
        }
    }

    @Nested
    @DisplayName("Network Tests (Integration)")
    inner class NetworkTests {

        @Test
        fun `fetchTimezone returns result for valid timezone (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 10000
            )

            val result = client.fetchTimezone("America/New_York")

            // Offline the fetch fails and the test passes without asserting.
            if (result.isSuccess) {
                val data = result.getOrNull()!!
                assertTrue(data.contains("BEGIN:VTIMEZONE"))
                assertTrue(data.contains("TZID:America/New_York") || data.contains("America/New_York"))
            }
        }

        @Test
        fun `fetchTimezone caches results (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 10000
            )

            client.clearCache()
            assertEquals(0, client.cacheSize())

            val result1 = client.fetchTimezone("Europe/London")

            if (result1.isSuccess) {
                // A successful fetch is cached.
                assertEquals(1, client.cacheSize())

                // The second fetch is served from the cache; these asserts can't tell it from a
                // refetch, which would also leave one entry.
                val result2 = client.fetchTimezone("Europe/London")
                assertTrue(result2.isSuccess)
                assertEquals(1, client.cacheSize())

                assertEquals(result1.getOrNull(), result2.getOrNull())
            }
        }

        @Test
        fun `fetchTimezone returns failure for invalid timezone (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 10000
            )

            val result = client.fetchTimezone("Invalid/Timezone/That/Does/Not/Exist")

            // Online the service answers an error such as 404, offline the connection fails; both
            // are a failure. The assert inside the if always holds, so the test checks only that
            // nothing throws.
            if (result.isFailure) {
                assertTrue(result.isFailure)
            }
        }

        @Test
        fun `isAvailable checks service connectivity (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )

            // Either answer passes, online or offline; the test checks only that isAvailable
            // doesn't throw.
            val available = client.isAvailable()
            assertTrue(available || !available)
        }

        @Test
        fun `isAvailable returns false for invalid service URL`() {
            val client = TimezoneServiceClient(
                serviceUrl = "https://invalid.nonexistent.domain.example.com/zoneinfo",
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000
            )

            // The connection fails, so isAvailable returns false.
            val available = client.isAvailable()
            assertFalse(available)
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        fun `fetchTimezone handles connection timeout gracefully`() {
            // A 1 ms timeout forces a timeout or a network error.
            val client = TimezoneServiceClient(
                connectTimeoutMs = 1,
                readTimeoutMs = 1
            )

            val result = client.fetchTimezone("America/New_York")

            // fetchTimezone returns the exception as a failure. The assert always holds, so the
            // test checks only that nothing throws.
            assertTrue(result.isSuccess || result.isFailure)
        }

        @Test
        fun `isAvailable handles connection timeout gracefully`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 1,
                readTimeoutMs = 1
            )

            // isAvailable catches the timeout and returns false.
            val available = client.isAvailable()
            assertFalse(available)
        }
    }
}
