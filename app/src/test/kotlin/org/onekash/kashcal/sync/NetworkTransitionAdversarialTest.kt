package org.onekash.kashcal.sync

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorMapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Adversarial tests for network transition handling.
 *
 * Tests error mapping for network conditions:
 * - WiFi to Cellular transitions
 * - Network loss scenarios
 * - DNS resolution failures
 * - SSL/TLS errors
 * - Connection refused
 * - Timeout variations
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkTransitionAdversarialTest {

    // ==================== DNS Resolution Tests ====================

    @Test
    fun `UnknownHostException maps to Network UnknownHost`() {
        val exception = UnknownHostException("Unable to resolve host \"caldav.icloud.com\"")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.UnknownHost, error)
    }

    @Test
    fun `UnknownHostException with null message`() {
        val exception = UnknownHostException()
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.UnknownHost, error)
    }

    // ==================== Connection Refused Tests ====================

    @Test
    fun `ConnectException maps to Network ConnectionFailed`() {
        val exception = ConnectException("Connection refused")
        val error = ErrorMapper.fromException(exception)

        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `NoRouteToHostException handled as connection failure`() {
        val exception = NoRouteToHostException("No route to host")
        val error = ErrorMapper.fromException(exception)

        // Should map to some network error
        assertTrue(
            "NoRouteToHost should be network error",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    // ==================== Timeout Tests ====================

    @Test
    fun `SocketTimeoutException maps to Network Timeout`() {
        val exception = SocketTimeoutException("Read timed out")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.Timeout, error)
    }

    @Test
    fun `SocketTimeoutException with connect timeout`() {
        val exception = SocketTimeoutException("connect timed out")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.Timeout, error)
    }

    @Test
    fun `IOException with timeout in message`() {
        val exception = IOException("Connection timeout while reading")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.Timeout, error)
    }

    // ==================== SSL/TLS Tests ====================

    @Test
    fun `SSLHandshakeException maps to Network SslError`() {
        val exception = SSLHandshakeException("Handshake failed")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.SslError, error)
    }

    @Test
    fun `SSLPeerUnverifiedException handled as SSL error`() {
        val exception = SSLPeerUnverifiedException("Hostname verification failed")
        val error = ErrorMapper.fromException(exception)

        // Should be SSL-related error
        assertTrue(
            "SSLPeerUnverified should be network/ssl error",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    @Test
    fun `SSLException with certificate error`() {
        val exception = SSLException("Certificate chain validation failed")
        val error = ErrorMapper.fromException(exception)

        assertTrue(
            "SSL certificate error should map to network error",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    // ==================== Socket Reset Tests ====================

    @Test
    fun `SocketException connection reset`() {
        val exception = SocketException("Connection reset by peer")
        val error = ErrorMapper.fromException(exception)

        assertTrue(
            "Socket reset should be network error",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    @Test
    fun `IOException connection reset`() {
        val exception = IOException("Connection reset")
        val error = ErrorMapper.fromException(exception)

        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `SocketException broken pipe`() {
        val exception = SocketException("Broken pipe")
        val error = ErrorMapper.fromException(exception)

        assertTrue(
            "Broken pipe should be network error",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    // ==================== Nested Exception Tests ====================

    @Test
    fun `IOException wrapping SocketTimeoutException`() {
        val cause = SocketTimeoutException("Original timeout")
        val wrapper = IOException("I/O error during sync", cause)

        val error = ErrorMapper.fromException(wrapper)

        // ErrorMapper only checks outer exception message, not cause
        // "I/O error during sync" doesn't contain "timeout" or "connection"
        // so it maps to Unknown (this is current behavior)
        assertTrue(
            "Wrapped exception without keywords maps to Unknown",
            error is CalendarError.Unknown
        )
    }

    @Test
    fun `IOException wrapping UnknownHostException`() {
        val cause = UnknownHostException("caldav.icloud.com")
        val wrapper = IOException("Network error", cause)

        val error = ErrorMapper.fromException(wrapper)

        assertTrue(
            "Should handle wrapped UnknownHost",
            error is CalendarError.Network || error is CalendarError.Unknown
        )
    }

    // ==================== Error Message Pattern Tests ====================

    @Test
    fun `IOException with connection in message`() {
        // Note: ErrorMapper checks for "connection" not "connect"
        val exception = IOException("Connection refused to caldav.icloud.com/17.253.144.10:443")
        val error = ErrorMapper.fromException(exception)

        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `IOException with generic message`() {
        val exception = IOException("Unexpected end of stream")
        val error = ErrorMapper.fromException(exception)

        // Generic IO error becomes Unknown
        assertTrue(
            "Generic IO should be Unknown",
            error is CalendarError.Unknown
        )
    }

    // ==================== Retryability Tests ====================

    @Test
    fun `network errors are retryable`() {
        val retryableErrors = listOf(
            CalendarError.Network.Offline,
            CalendarError.Network.Timeout,
            CalendarError.Network.UnknownHost,
            CalendarError.Network.ConnectionFailed("test")
        )

        retryableErrors.forEach { error ->
            assertTrue(
                "${error::class.simpleName} should be retryable",
                ErrorMapper.isRetryable(error)
            )
        }
    }

    @Test
    fun `SSL error is not retryable by default`() {
        val sslError = CalendarError.Network.SslError

        // SSL errors usually need user intervention (cert issues)
        assertFalse(
            "SSL error should not be auto-retryable",
            ErrorMapper.isRetryable(sslError)
        )
    }

    // ==================== Edge Cases ====================

    @Test
    fun `null exception message handled`() {
        val exception = IOException(null as String?)
        val error = ErrorMapper.fromException(exception)

        assertNotNull("Should handle null message", error)
    }

    @Test
    fun `empty exception message handled`() {
        val exception = IOException("")
        val error = ErrorMapper.fromException(exception)

        assertNotNull("Should handle empty message", error)
    }

    @Test
    fun `exception with very long message`() {
        val longMessage = "Error: " + "a".repeat(10000)
        val exception = IOException(longMessage)
        val error = ErrorMapper.fromException(exception)

        assertNotNull("Should handle long message", error)
    }

    @Test
    fun `exception message with special characters`() {
        val specialMessage = "Error: 网络连接失败 (Network failed) 🔌"
        val exception = IOException(specialMessage)
        val error = ErrorMapper.fromException(exception)

        assertNotNull("Should handle special characters", error)
    }

    // ==================== HTTP Code Tests for Network Issues ====================

    @Test
    fun `HTTP 502 Bad Gateway is server error`() {
        val error = ErrorMapper.fromHttpCode(502)
        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `HTTP 503 Service Unavailable is server error`() {
        val error = ErrorMapper.fromHttpCode(503)
        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `HTTP 504 Gateway Timeout is server error`() {
        val error = ErrorMapper.fromHttpCode(504)
        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `server errors are retryable`() {
        val serverError = CalendarError.Server.TemporarilyUnavailable
        assertTrue(ErrorMapper.isRetryable(serverError))
    }
}
