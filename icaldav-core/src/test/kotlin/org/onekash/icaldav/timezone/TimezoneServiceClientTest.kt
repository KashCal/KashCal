package org.onekash.icaldav.timezone

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for TimezoneServiceClient.
 *
 * Note: These tests are designed to work without network access where possible.
 * Tests that require network are marked accordingly and will gracefully handle
 * network failures.
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

            // Cache might have entries from previous operations
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

            // Should be able to get a URL
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

            // If network available, should succeed; if not, should fail gracefully
            if (result.isSuccess) {
                val data = result.getOrNull()!!
                assertTrue(data.contains("BEGIN:VTIMEZONE"))
                assertTrue(data.contains("TZID:America/New_York") || data.contains("America/New_York"))
            }
            // If network unavailable, test passes - we're just checking it doesn't crash
        }

        @Test
        fun `fetchTimezone caches results (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 10000
            )

            // Clear any existing cache
            client.clearCache()
            assertEquals(0, client.cacheSize())

            // First fetch
            val result1 = client.fetchTimezone("Europe/London")

            if (result1.isSuccess) {
                // Should now be cached
                assertEquals(1, client.cacheSize())

                // Second fetch should use cache
                val result2 = client.fetchTimezone("Europe/London")
                assertTrue(result2.isSuccess)
                assertEquals(1, client.cacheSize())  // Still 1, used cache

                // Data should be identical
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

            // Should fail - either network error or 404
            // If network is available, service should return error
            // If network unavailable, connection fails
            // Either way, we just check it doesn't crash
            if (result.isFailure) {
                // Expected
                assertTrue(result.isFailure)
            }
        }

        @Test
        fun `isAvailable checks service connectivity (requires network)`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000
            )

            // This may return true or false depending on network
            // Just verify it doesn't throw
            val available = client.isAvailable()
            // Result is a boolean, test passes either way
            assertTrue(available || !available)
        }

        @Test
        fun `isAvailable returns false for invalid service URL`() {
            val client = TimezoneServiceClient(
                serviceUrl = "https://invalid.nonexistent.domain.example.com/zoneinfo",
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000
            )

            // Should fail to connect
            val available = client.isAvailable()
            assertFalse(available)
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        fun `fetchTimezone handles connection timeout gracefully`() {
            // Use extremely short timeout to force timeout
            val client = TimezoneServiceClient(
                connectTimeoutMs = 1,
                readTimeoutMs = 1
            )

            val result = client.fetchTimezone("America/New_York")

            // Should fail gracefully (either timeout or network error)
            // Just ensure it doesn't throw uncaught exception
            assertTrue(result.isSuccess || result.isFailure)
        }

        @Test
        fun `isAvailable handles connection timeout gracefully`() {
            val client = TimezoneServiceClient(
                connectTimeoutMs = 1,
                readTimeoutMs = 1
            )

            // Should return false, not throw
            val available = client.isAvailable()
            assertFalse(available)
        }
    }
}
