package org.onekash.kashcal.error

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Adversarial tests for error handling and propagation.
 *
 * Tests edge cases in error transformation:
 * - Exception to CalendarError mapping
 * - HTTP code to CalendarError mapping
 * - Error presentation mapping
 * - Retryability logic
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ErrorPropagationAdversarialTest {

    // ==================== Exception Mapping Tests ====================

    @Test
    fun `map SocketTimeoutException to Network Timeout`() {
        val exception = SocketTimeoutException("Connection timed out")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.Timeout, error)
    }

    @Test
    fun `map UnknownHostException to Network UnknownHost`() {
        val exception = UnknownHostException("Unable to resolve host")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.UnknownHost, error)
    }

    @Test
    fun `map SSLHandshakeException to Network SslError`() {
        val exception = SSLHandshakeException("SSL handshake failed")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.SslError, error)
    }

    @Test
    fun `map ConnectException to Network ConnectionFailed`() {
        val exception = ConnectException("Connection refused")
        val error = ErrorMapper.fromException(exception)

        assertTrue("Should map to ConnectionFailed", error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `map IOException with timeout message to Network Timeout`() {
        val exception = IOException("Read timeout occurred")
        val error = ErrorMapper.fromException(exception)

        assertEquals(CalendarError.Network.Timeout, error)
    }

    @Test
    fun `map IOException with connection message to ConnectionFailed`() {
        val exception = IOException("Connection reset by peer")
        val error = ErrorMapper.fromException(exception)

        assertTrue("Should map to ConnectionFailed", error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `map generic Exception to Unknown error`() {
        val exception = RuntimeException("Something went wrong")
        val error = ErrorMapper.fromException(exception)

        assertTrue("Should map to Unknown error", error is CalendarError.Unknown)
    }

    @Test
    fun `exception with null message handled gracefully`() {
        val exception = RuntimeException(null as String?)
        val error = ErrorMapper.fromException(exception)

        assertNotNull("Should produce error even with null message", error)
        assertTrue("Should be Unknown error", error is CalendarError.Unknown)
    }

    // ==================== HTTP Code Mapping Tests ====================

    @Test
    fun `map 401 to Auth InvalidCredentials`() {
        val error = ErrorMapper.fromHttpCode(401)

        assertEquals(CalendarError.Auth.InvalidCredentials, error)
    }

    @Test
    fun `map 403 to Server Forbidden`() {
        val error = ErrorMapper.fromHttpCode(403, "resource")

        assertTrue("Should map to Server.Forbidden", error is CalendarError.Server.Forbidden)
    }

    @Test
    fun `map 404 to Server NotFound`() {
        val error = ErrorMapper.fromHttpCode(404, "event.ics")

        assertTrue("Should map to Server.NotFound", error is CalendarError.Server.NotFound)
    }

    @Test
    fun `map 412 to Server Conflict`() {
        val error = ErrorMapper.fromHttpCode(412, "Team Meeting")

        assertTrue("Should map to Server.Conflict", error is CalendarError.Server.Conflict)
    }

    @Test
    fun `map 429 to Server RateLimited`() {
        val error = ErrorMapper.fromHttpCode(429)

        assertEquals(CalendarError.Server.RateLimited, error)
    }

    @Test
    fun `map 500 to Server TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(500)

        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `map 502 to Server TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(502)

        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `map 503 to Server TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(503)

        assertEquals(CalendarError.Server.TemporarilyUnavailable, error)
    }

    @Test
    fun `map unknown HTTP code to Unknown error`() {
        val error = ErrorMapper.fromHttpCode(999)

        assertTrue("Unknown code should map to Unknown error", error is CalendarError.Unknown)
    }

    @Test
    fun `map negative HTTP code to Unknown error`() {
        val error = ErrorMapper.fromHttpCode(-1)

        assertTrue("Negative code should map to Unknown error", error is CalendarError.Unknown)
    }

    // ==================== Retryability Tests ====================

    @Test
    fun `Network Timeout is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Timeout))
    }

    @Test
    fun `Network Offline is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Offline))
    }

    @Test
    fun `Network SslError is not retryable`() {
        // SSL errors usually indicate configuration issues, not transient failures
        assertFalse(ErrorMapper.isRetryable(CalendarError.Network.SslError))
    }

    @Test
    fun `Auth InvalidCredentials is not retryable`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.InvalidCredentials))
    }

    @Test
    fun `Server TemporarilyUnavailable is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.TemporarilyUnavailable))
    }

    @Test
    fun `Server RateLimited is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.RateLimited))
    }

    @Test
    fun `Server NotFound is not retryable`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.NotFound("resource")))
    }

    @Test
    fun `Server Conflict is not retryable`() {
        // 412 Conflict requires user intervention (force sync or merge)
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.Conflict("event")))
    }

    // ==================== Error Presentation Tests ====================

    @Test
    fun `Network errors produce Snackbar presentation`() {
        val errors = listOf(
            CalendarError.Network.Offline,
            CalendarError.Network.Timeout,
            CalendarError.Network.SslError,
            CalendarError.Network.UnknownHost,
            CalendarError.Network.ConnectionFailed("test")
        )

        errors.forEach { error ->
            val presentation = ErrorMapper.toPresentation(error)
            assertTrue(
                "${error::class.simpleName} should produce Snackbar",
                presentation is ErrorPresentation.Snackbar
            )
        }
    }

    @Test
    fun `Auth errors produce Dialog presentation`() {
        val errors = listOf(
            CalendarError.Auth.InvalidCredentials,
            CalendarError.Auth.SessionExpired,
            CalendarError.Auth.AccountLocked,
            CalendarError.Auth.AppSpecificPasswordRequired
        )

        errors.forEach { error ->
            val presentation = ErrorMapper.toPresentation(error)
            assertTrue(
                "${error::class.simpleName} should produce Dialog",
                presentation is ErrorPresentation.Dialog
            )
        }
    }

    @Test
    fun `Storage errors produce Dialog presentation`() {
        val errors = listOf(
            CalendarError.Storage.StorageFull,
            CalendarError.Storage.DatabaseCorruption
        )

        errors.forEach { error ->
            val presentation = ErrorMapper.toPresentation(error)
            assertTrue(
                "${error::class.simpleName} should produce Dialog",
                presentation is ErrorPresentation.Dialog
            )
        }
    }

    @Test
    fun `Permission errors produce appropriate presentation`() {
        // NotificationDenied and ExactAlarmDenied -> Dialog (needs settings)
        listOf(
            CalendarError.Permission.NotificationDenied,
            CalendarError.Permission.ExactAlarmDenied
        ).forEach { error ->
            val presentation = ErrorMapper.toPresentation(error)
            assertTrue(
                "${error::class.simpleName} should produce Dialog",
                presentation is ErrorPresentation.Dialog
            )
        }

        // StorageDenied -> Snackbar (less critical)
        val storagePresentation = ErrorMapper.toPresentation(CalendarError.Permission.StorageDenied)
        assertTrue(
            "StorageDenied should produce Snackbar",
            storagePresentation is ErrorPresentation.Snackbar
        )
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `all HTTP 5xx codes map to TemporarilyUnavailable`() {
        listOf(500, 501, 502, 503, 504, 599).forEach { code ->
            val error = ErrorMapper.fromHttpCode(code)
            assertEquals(
                "HTTP $code should map to TemporarilyUnavailable",
                CalendarError.Server.TemporarilyUnavailable,
                error
            )
        }
    }

    @Test
    fun `HTTP 4xx codes with message preserve context`() {
        val error = ErrorMapper.fromHttpCode(404, "weekly-meeting.ics")
        assertTrue(error is CalendarError.Server.NotFound)
        assertEquals("weekly-meeting.ics", (error as CalendarError.Server.NotFound).resource)
    }

    @Test
    fun `Unknown error preserves original message`() {
        val error = ErrorMapper.fromHttpCode(418, "I'm a teapot")
        assertTrue(error is CalendarError.Unknown)
        assertTrue((error as CalendarError.Unknown).message.contains("418"))
    }

    @Test
    fun `fromException preserves exception in Unknown`() {
        val original = IllegalStateException("Test exception")
        val error = ErrorMapper.fromException(original)

        assertTrue(error is CalendarError.Unknown)
        assertEquals(original, (error as CalendarError.Unknown).throwable)
    }
}
