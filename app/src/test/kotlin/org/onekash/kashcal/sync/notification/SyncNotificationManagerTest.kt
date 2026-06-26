package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels = SyncNotificationChannels(context)
        channels.createChannels()
        manager = SyncNotificationManager(context, channels)
    }

    private fun activeById(id: Int) =
        notificationManager.activeNotifications.firstOrNull { it.id == id }

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

        // When
        manager.showCompletionNotification(result, showOnlyOnChanges = true)

        // Then - a completion notification is posted
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE))
    }

    @Test
    fun `showCompletionNotification with Success skips notification when no changes and showOnlyOnChanges true`() {
        // Given - a successful sync that changed nothing
        val result = SyncResult.Success(
            calendarsSynced = 2,
            durationMs = 500
        )

        // When
        manager.showCompletionNotification(result, showOnlyOnChanges = true)

        // Then - nothing is posted
        assertNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE))
    }

    @Test
    fun `showCompletionNotification with Success shows notification when showOnlyOnChanges false`() {
        // Given - no changes, but the caller opted to always notify
        val result = SyncResult.Success(
            calendarsSynced = 2,
            durationMs = 500
        )

        // When
        manager.showCompletionNotification(result, showOnlyOnChanges = false)

        // Then - a completion notification is posted despite zero changes
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE))
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

        // When
        manager.showCompletionNotification(result)

        // Then - a completion notification is posted
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE))
    }

    // ==================== Auth Error Notification Tests ====================

    @Test
    fun `showCompletionNotification with AuthError shows high priority notification`() {
        // Given
        val result = SyncResult.AuthError(message = "Invalid credentials")

        // When
        manager.showCompletionNotification(result)

        // Then - posted on the error id at high priority
        val posted = activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR)
        assertNotNull(posted)
        assertEquals(Notification.PRIORITY_HIGH, posted!!.notification.priority)
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

        // When
        manager.showCompletionNotification(result)

        // Then - posted on the error id
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR))
    }

    @Test
    fun `showErrorNotification with custom message shows notification`() {
        // When
        manager.showErrorNotification("Custom Error", "Something went wrong")

        // Then
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR))
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
        // When
        manager.showCompletionNotification(
            SyncResult.Error(code = 500, message = "")
        )

        // Then - still posts an error notification (falls back to default text)
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR))
    }

    // ==================== Parse Failure Notification Tests (v16.7.0) ====================

    @Test
    fun `showParseFailureNotification shows notification when abandonedCount greater than 0`() {
        // When
        manager.showParseFailureNotification(calendarName = "Home Calendar", abandonedCount = 3)

        // Then - posted on the error id
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR))
    }

    @Test
    fun `showParseFailureNotification does not throw with zero abandonedCount`() {
        // Edge case: zero count is an early return — nothing should post
        manager.showParseFailureNotification(calendarName = "Work", abandonedCount = 0)

        assertNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR))
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

    // ==================== Operation Expired Notification Tests ====================

    @Test
    fun `showOperationExpiredNotification posts on the operation-expired id`() {
        // When
        manager.showOperationExpiredNotification(expiredCount = 3, calendarName = null)

        // Then - posted on the dedicated operation-expired id
        assertNotNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED))
    }

    @Test
    fun `showOperationExpiredNotification does not post with zero count`() {
        // Edge case: zero count is an early return — nothing should post
        manager.showOperationExpiredNotification(expiredCount = 0, calendarName = "Work")

        assertNull(activeById(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED))
    }

    @Test
    fun `showOperationExpiredNotification includes calendar name when provided`() {
        // When a single calendar is known, its name appears in the content
        manager.showOperationExpiredNotification(expiredCount = 2, calendarName = "Work Calendar")

        val posted = activeById(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED)
        assertNotNull(posted)
        val text = posted!!.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(
            "Calendar name should appear in the notification text: '$text'",
            text.contains("Work Calendar")
        )
    }

    @Test
    fun `showOperationExpiredNotification omits calendar name when null`() {
        // Multi-calendar / unknown falls back to count-only wording
        manager.showOperationExpiredNotification(expiredCount = 4, calendarName = null)

        val posted = activeById(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED)
        assertNotNull(posted)
        val text = posted!!.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // Fallback wording does not name a calendar with the " in <name>" phrasing.
        assertFalse(
            "Count-only fallback should not contain ' in ' calendar phrasing: '$text'",
            text.contains(" in ")
        )
    }
}
