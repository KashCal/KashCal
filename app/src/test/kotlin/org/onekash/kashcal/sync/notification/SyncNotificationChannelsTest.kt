package org.onekash.kashcal.sync.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for SyncNotificationChannels.
 *
 * Tests:
 * - Channel creation
 * - Channel configuration
 * - Notification cancellation
 * - Notification status checks
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncNotificationChannelsTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var channels: SyncNotificationChannels

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
    }

    @After
    fun tearDown() {
        // Clean up notification channels
        notificationManager.deleteNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
        notificationManager.deleteNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_STATUS)
        unmockkAll()
    }

    // ==================== Channel Creation Tests ====================

    @Test
    fun `createChannels creates sync_progress channel`() {
        // When
        channels.createChannels()

        // Then
        val channel = notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
        assertNotNull(channel)
        assertEquals("Sync Progress", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `createChannels creates sync_status channel`() {
        // When
        channels.createChannels()

        // Then
        val channel = notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_STATUS)
        assertNotNull(channel)
        assertEquals("Sync Status", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `sync_progress channel has correct configuration`() {
        // When
        channels.createChannels()

        // Then
        val channel = notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
        assertNotNull(channel)
        assertFalse(channel.canShowBadge())
        // Note: Sound/vibration settings can't be verified in unit tests
    }

    @Test
    fun `sync_status channel has badge enabled`() {
        // When
        channels.createChannels()

        // Then
        val channel = notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_STATUS)
        assertNotNull(channel)
        assertTrue(channel.canShowBadge())
    }

    @Test
    fun `createChannels can be called multiple times safely`() {
        // When - call multiple times
        channels.createChannels()
        channels.createChannels()
        channels.createChannels()

        // Then - should not throw and channels should exist
        assertNotNull(notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_PROGRESS))
        assertNotNull(notificationManager.getNotificationChannel(SyncNotificationChannels.CHANNEL_SYNC_STATUS))
    }

    // ==================== Notification ID Constants Tests ====================

    @Test
    fun `notification IDs are distinct`() {
        val ids = setOf(
            SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS,
            SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE,
            SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR
        )
        assertEquals(3, ids.size)
    }

    @Test
    fun `channel IDs are distinct`() {
        assertNotEquals(
            SyncNotificationChannels.CHANNEL_SYNC_PROGRESS,
            SyncNotificationChannels.CHANNEL_SYNC_STATUS
        )
    }

    // ==================== Notification Status Tests ====================

    @Test
    fun `areNotificationsEnabled returns true when enabled`() {
        // Robolectric enables notifications by default
        assertTrue(channels.areNotificationsEnabled())
    }

    @Test
    fun `isChannelEnabled returns true for created channel`() {
        // Given
        channels.createChannels()

        // When/Then
        assertTrue(channels.isChannelEnabled(SyncNotificationChannels.CHANNEL_SYNC_PROGRESS))
        assertTrue(channels.isChannelEnabled(SyncNotificationChannels.CHANNEL_SYNC_STATUS))
    }

    @Test
    fun `isChannelEnabled returns false for non-existent channel`() {
        // When/Then
        assertFalse(channels.isChannelEnabled("non_existent_channel"))
    }

    // ==================== Cancellation Tests ====================

    @Test
    fun `cancel removes notification by ID`() {
        // This test verifies the method doesn't throw
        // Actual notification verification requires instrumentation tests
        channels.cancel(SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS)
    }

    @Test
    fun `cancelAll removes all sync notifications`() {
        // This test verifies the method doesn't throw
        channels.cancelAll()
    }

    // ==================== Channel ID Constants Tests ====================

    @Test
    fun `CHANNEL_SYNC_PROGRESS constant value`() {
        assertEquals("sync_progress", SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
    }

    @Test
    fun `CHANNEL_SYNC_STATUS constant value`() {
        assertEquals("sync_status", SyncNotificationChannels.CHANNEL_SYNC_STATUS)
    }
}
