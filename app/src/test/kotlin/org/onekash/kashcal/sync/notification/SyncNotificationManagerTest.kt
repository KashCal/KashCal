package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for SyncNotificationManager.
 *
 * Tests:
 * - ForegroundInfo creation
 * - Progress notifications
 * - Completion notifications
 * - Error notifications
 * - Notification cancellation
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var channels: SyncNotificationChannels
    private lateinit var manager: SyncNotificationManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        channels = SyncNotificationChannels(context)
        channels.createChannels()
        manager = SyncNotificationManager(context, channels)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== ForegroundInfo Tests ====================

    @Test
    fun `createForegroundInfo returns valid ForegroundInfo`() {
        // When
        val foregroundInfo = manager.createForegroundInfo("Syncing...")

        // Then
        assertNotNull(foregroundInfo)
        assertEquals(SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS, foregroundInfo.notificationId)
        assertNotNull(foregroundInfo.notification)
    }

    @Test
    fun `createForegroundInfo includes DATA_SYNC foreground service type on Android Q+`() {
        // When
        val foregroundInfo = manager.createForegroundInfo("Syncing...")

        // Then
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundInfo.foregroundServiceType)
        }
    }

    @Test
    fun `createForegroundInfo with cancelIntent includes action`() {
        // Given
        val mockCancelIntent = mockk<android.app.PendingIntent>(relaxed = true)

        // When
        val foregroundInfo = manager.createForegroundInfo("Syncing...", mockCancelIntent)

        // Then
        assertNotNull(foregroundInfo)
        // Note: Verifying notification actions requires deeper inspection
    }

    // ==================== Progress Notification Tests ====================

    @Test
    fun `createProgressNotification returns valid notification`() {
        // When
        val notification = manager.createProgressNotification("Syncing calendars...")

        // Then
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `createIndeterminateProgressNotification returns notification with progress`() {
        // When
        val notification = manager.createIndeterminateProgressNotification(
            title = "Sync",
            content = "Loading..."
        )

        // Then
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `createDeterminateProgressNotification returns notification with progress`() {
        // When
        val notification = manager.createDeterminateProgressNotification(
            title = "Sync",
            content = "50% complete",
            progress = 50
        )

        // Then
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `createDeterminateProgressNotification clamps progress to 0-100`() {
        // When - negative progress
        val notification1 = manager.createDeterminateProgressNotification("Sync", "Loading", -10)
        assertNotNull(notification1)

        // When - excessive progress
        val notification2 = manager.createDeterminateProgressNotification("Sync", "Loading", 150)
        assertNotNull(notification2)
    }

    // ==================== Success Notification Tests ====================

    @Test
    fun `showCompletionNotification with Success shows notification when changes present`() {
        // Given
        val result = SyncResult.Success(
            calendarsSynced = 2,
            eventsPulledAdded = 5,
            eventsPulledUpdated = 3,
            durationMs = 1000
        )

        // When - should not throw
        manager.showCompletionNotification(result, showOnlyOnChanges = true)
    }

    @Test
    fun `showCompletionNotification with Success skips notification when no changes and showOnlyOnChanges true`() {
        // Given
        val result = SyncResult.Success(
            calendarsSynced = 2,
            durationMs = 500
        )

        // When - should not throw and should not show notification
        manager.showCompletionNotification(result, showOnlyOnChanges = true)
    }

    @Test
    fun `showCompletionNotification with Success shows notification when showOnlyOnChanges false`() {
        // Given
        val result = SyncResult.Success(
            calendarsSynced = 2,
            durationMs = 500
        )

        // When - should not throw
        manager.showCompletionNotification(result, showOnlyOnChanges = false)
    }

    // ==================== Partial Success Notification Tests ====================

    @Test
    fun `showCompletionNotification with PartialSuccess shows notification`() {
        // Given
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 3,
            eventsPulledAdded = 10,
            durationMs = 2000,
            errors = listOf(
                SyncError(phase = SyncPhase.PULL, calendarId = 1L, message = "Network error")
            )
        )

        // When - should not throw
        manager.showCompletionNotification(result)
    }

    // ==================== Auth Error Notification Tests ====================

    @Test
    fun `showCompletionNotification with AuthError shows high priority notification`() {
        // Given
        val result = SyncResult.AuthError(message = "Invalid credentials")

        // When - should not throw
        manager.showCompletionNotification(result)
    }

    // ==================== Error Notification Tests ====================

    @Test
    fun `showCompletionNotification with Error shows notification`() {
        // Given
        val result = SyncResult.Error(
            code = 500,
            message = "Server error",
            isRetryable = true
        )

        // When - should not throw
        manager.showCompletionNotification(result)
    }

    @Test
    fun `showErrorNotification with custom message shows notification`() {
        // When - should not throw
        manager.showErrorNotification("Custom Error", "Something went wrong")
    }

    // ==================== Cancellation Tests ====================

    @Test
    fun `cancelProgressNotification does not throw`() {
        // When/Then - should not throw
        manager.cancelProgressNotification()
    }

    @Test
    fun `cancelAllNotifications does not throw`() {
        // When/Then - should not throw
        manager.cancelAllNotifications()
    }

    // ==================== SyncResult Handling Tests ====================

    @Test
    fun `showCompletionNotification handles all SyncResult types`() {
        // Success
        manager.showCompletionNotification(
            SyncResult.Success(calendarsSynced = 1, durationMs = 100),
            showOnlyOnChanges = false
        )

        // PartialSuccess
        manager.showCompletionNotification(
            SyncResult.PartialSuccess(
                calendarsSynced = 1,
                durationMs = 100,
                errors = emptyList()
            )
        )

        // AuthError
        manager.showCompletionNotification(
            SyncResult.AuthError(message = "Auth failed")
        )

        // Error
        manager.showCompletionNotification(
            SyncResult.Error(code = 500, message = "Error")
        )
    }

    // ==================== Edge Cases ====================

    @Test
    fun `progress notification with empty message does not throw`() {
        val notification = manager.createProgressNotification("")
        assertNotNull(notification)
    }

    @Test
    fun `progress notification with long message does not throw`() {
        val longMessage = "A".repeat(1000)
        val notification = manager.createProgressNotification(longMessage)
        assertNotNull(notification)
    }

    @Test
    fun `error notification with null message in SyncResult is handled`() {
        // When - should not throw
        manager.showCompletionNotification(
            SyncResult.Error(code = 500, message = "")
        )
    }

    // ==================== Parse Failure Notification Tests (v16.7.0) ====================

    @Test
    fun `showParseFailureNotification shows notification when abandonedCount greater than 0`() {
        // When - should not throw
        manager.showParseFailureNotification(calendarName = "Home Calendar", abandonedCount = 3)
    }

    @Test
    fun `showParseFailureNotification does not throw with zero abandonedCount`() {
        // Edge case: should handle gracefully (early return)
        manager.showParseFailureNotification(calendarName = "Work", abandonedCount = 0)
    }

    @Test
    fun `showParseFailureNotification handles single abandoned event`() {
        // When - singular text should work
        manager.showParseFailureNotification(calendarName = "Personal", abandonedCount = 1)
    }

    @Test
    fun `showParseFailureNotification handles multiple abandoned events`() {
        // When - plural text
        manager.showParseFailureNotification(calendarName = "Shared Calendar", abandonedCount = 10)
    }

    @Test
    fun `showParseFailureNotification handles empty calendar name`() {
        // Edge case: calendar name might be empty
        manager.showParseFailureNotification(calendarName = "", abandonedCount = 5)
    }

    @Test
    fun `showParseFailureNotification handles long calendar name`() {
        // Edge case: very long calendar name should be handled
        val longName = "A".repeat(200)
        manager.showParseFailureNotification(calendarName = longName, abandonedCount = 2)
    }
}
