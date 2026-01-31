package org.onekash.kashcal.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Comprehensive tests for ErrorMapper.
 *
 * Tests all mappings:
 * - CalendarError → ErrorPresentation
 * - HTTP codes → CalendarError
 * - Exceptions → CalendarError
 * - Retryable detection
 */
class ErrorMapperTest {

    // ==================== HTTP Code Mapping ====================

    @Test
    fun `fromHttpCode 401 maps to InvalidCredentials`() {
        val error = ErrorMapper.fromHttpCode(401)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `fromHttpCode 403 maps to Forbidden`() {
        val error = ErrorMapper.fromHttpCode(403, "Calendar")
        assertTrue(error is CalendarError.Server.Forbidden)
        assertEquals("Calendar", (error as CalendarError.Server.Forbidden).resource)
    }

    @Test
    fun `fromHttpCode 404 maps to NotFound`() {
        val error = ErrorMapper.fromHttpCode(404, "Event")
        assertTrue(error is CalendarError.Server.NotFound)
        assertEquals("Event", (error as CalendarError.Server.NotFound).resource)
    }

    @Test
    fun `fromHttpCode 412 maps to Conflict`() {
        val error = ErrorMapper.fromHttpCode(412, "Meeting")
        assertTrue(error is CalendarError.Server.Conflict)
        assertEquals("Meeting", (error as CalendarError.Server.Conflict).eventTitle)
    }

    @Test
    fun `fromHttpCode 429 maps to RateLimited`() {
        val error = ErrorMapper.fromHttpCode(429)
        assertTrue(error is CalendarError.Server.RateLimited)
    }

    @Test
    fun `fromHttpCode 500 maps to TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(500)
        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `fromHttpCode 502 maps to TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(502)
        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `fromHttpCode 503 maps to TemporarilyUnavailable`() {
        val error = ErrorMapper.fromHttpCode(503)
        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `fromHttpCode unknown maps to Unknown`() {
        val error = ErrorMapper.fromHttpCode(418, "I'm a teapot")
        assertTrue(error is CalendarError.Unknown)
        assertTrue((error as CalendarError.Unknown).message.contains("418"))
    }

    // ==================== Exception Mapping ====================

    @Test
    fun `fromException SocketTimeoutException maps to Timeout`() {
        val error = ErrorMapper.fromException(SocketTimeoutException("Connection timed out"))
        assertTrue(error is CalendarError.Network.Timeout)
    }

    @Test
    fun `fromException UnknownHostException maps to UnknownHost`() {
        val error = ErrorMapper.fromException(UnknownHostException("caldav.icloud.com"))
        assertTrue(error is CalendarError.Network.UnknownHost)
    }

    @Test
    fun `fromException SSLHandshakeException maps to SslError`() {
        val error = ErrorMapper.fromException(SSLHandshakeException("Certificate not trusted"))
        assertTrue(error is CalendarError.Network.SslError)
    }

    @Test
    fun `fromException ConnectException maps to ConnectionFailed`() {
        val error = ErrorMapper.fromException(ConnectException("Connection refused"))
        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `fromException IOException with timeout maps to Timeout`() {
        val error = ErrorMapper.fromException(java.io.IOException("Read timeout"))
        assertTrue(error is CalendarError.Network.Timeout)
    }

    @Test
    fun `fromException IOException with connection maps to ConnectionFailed`() {
        val error = ErrorMapper.fromException(java.io.IOException("Connection reset"))
        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `fromException generic maps to Unknown`() {
        val error = ErrorMapper.fromException(RuntimeException("Something broke"))
        assertTrue(error is CalendarError.Unknown)
        assertEquals("Something broke", (error as CalendarError.Unknown).message)
    }

    // ==================== Presentation Mapping ====================

    @Test
    fun `Auth InvalidCredentials maps to Dialog`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Auth.InvalidCredentials)
        assertTrue(presentation is ErrorPresentation.Dialog)
    }

    @Test
    fun `Auth SessionExpired maps to non-dismissible Dialog`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Auth.SessionExpired)
        assertTrue(presentation is ErrorPresentation.Dialog)
        assertFalse((presentation as ErrorPresentation.Dialog).dismissible)
    }

    @Test
    fun `Network Offline maps to Snackbar with retry`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Network.Offline)
        assertTrue(presentation is ErrorPresentation.Snackbar)
        val snackbar = presentation as ErrorPresentation.Snackbar
        assertNotNull(snackbar.action)
        assertEquals(ErrorActionCallback.Retry, snackbar.action?.callback)
    }

    @Test
    fun `Network Timeout maps to Snackbar with retry`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Network.Timeout)
        assertTrue(presentation is ErrorPresentation.Snackbar)
        assertNotNull((presentation as ErrorPresentation.Snackbar).action)
    }

    @Test
    fun `Server Conflict maps to Dialog with ForceSync`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Server.Conflict("Meeting"))
        assertTrue(presentation is ErrorPresentation.Dialog)
        val dialog = presentation as ErrorPresentation.Dialog
        assertEquals(ErrorActionCallback.ForceFullSync, dialog.primaryAction.callback)
    }

    @Test
    fun `Storage Full maps to Dialog with OpenSettings`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Storage.StorageFull)
        assertTrue(presentation is ErrorPresentation.Dialog)
        val dialog = presentation as ErrorPresentation.Dialog
        assertEquals(ErrorActionCallback.OpenAppSettings, dialog.primaryAction.callback)
    }

    @Test
    fun `Sync AlreadySyncing maps to Silent`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Sync.AlreadySyncing)
        assertTrue(presentation is ErrorPresentation.Silent)
    }

    @Test
    fun `Sync NoAccountsConfigured maps to Banner`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Sync.NoAccountsConfigured)
        assertTrue(presentation is ErrorPresentation.Banner)
        val banner = presentation as ErrorPresentation.Banner
        assertEquals(ErrorPresentation.Banner.BannerType.Info, banner.type)
    }

    @Test
    fun `Unknown error maps to Snackbar`() {
        val presentation = ErrorMapper.toPresentation(CalendarError.Unknown("Test error"))
        assertTrue(presentation is ErrorPresentation.Snackbar)
    }

    // ==================== Retryable Detection ====================

    @Test
    fun `Network Offline is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Offline))
    }

    @Test
    fun `Network Timeout is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Timeout))
    }

    @Test
    fun `Network ConnectionFailed is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.ConnectionFailed()))
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
    fun `Server SyncTokenExpired is retryable`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.SyncTokenExpired))
    }

    @Test
    fun `Auth InvalidCredentials is NOT retryable`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.InvalidCredentials))
    }

    @Test
    fun `Server Forbidden is NOT retryable`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.Forbidden()))
    }

    @Test
    fun `Storage StorageFull is NOT retryable`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Storage.StorageFull))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `Conflict with eventTitle includes title in messageArgs`() {
        val error = CalendarError.Server.Conflict("Team Meeting")
        val presentation = ErrorMapper.toPresentation(error) as ErrorPresentation.Dialog
        assertTrue(presentation.messageArgs.contains("Team Meeting"))
    }

    @Test
    fun `Conflict without eventTitle uses generic message`() {
        val error = CalendarError.Server.Conflict(null)
        val presentation = ErrorMapper.toPresentation(error) as ErrorPresentation.Dialog
        assertTrue(presentation.messageArgs.isEmpty())
    }

    @Test
    fun `ReadOnlyCalendar includes calendar name in messageArgs`() {
        val error = CalendarError.Event.ReadOnlyCalendar("Work Calendar")
        val presentation = ErrorMapper.toPresentation(error) as ErrorPresentation.Snackbar
        assertTrue(presentation.messageArgs.contains("Work Calendar"))
    }

    @Test
    fun `PartialImport includes counts in messageArgs`() {
        val error = CalendarError.ImportExport.PartialImport(5, 2)
        val presentation = ErrorMapper.toPresentation(error) as ErrorPresentation.Snackbar
        assertTrue(presentation.messageArgs.contains(5))
        assertTrue(presentation.messageArgs.contains(2))
    }

    @Test
    fun `PartialFailure includes counts in messageArgs`() {
        val error = CalendarError.Sync.PartialFailure(3, 1)
        val presentation = ErrorMapper.toPresentation(error) as ErrorPresentation.Snackbar
        assertTrue(presentation.messageArgs.contains(3))
        assertTrue(presentation.messageArgs.contains(1))
    }

    // ==================== Database Corruption ====================

    @Test
    fun `DatabaseCorruption maps to Dialog with OpenUrl to GitHub issues`() {
        val error = CalendarError.Storage.DatabaseCorruption
        val presentation = ErrorMapper.toPresentation(error)

        assertTrue(presentation is ErrorPresentation.Dialog)
        val dialog = presentation as ErrorPresentation.Dialog

        // Should have OpenUrl callback pointing to GitHub issues
        assertTrue(dialog.primaryAction.callback is ErrorActionCallback.OpenUrl)
        val openUrl = dialog.primaryAction.callback as ErrorActionCallback.OpenUrl
        assertEquals("https://github.com/KashCal/KashCal/issues", openUrl.url)

        // Should be dismissible (user can close and reinstall/resync)
        assertTrue(dialog.dismissible)

        // Should have secondary Close button
        assertNotNull(dialog.secondaryAction)
        assertEquals(ErrorActionCallback.Dismiss, dialog.secondaryAction?.callback)
    }
}
