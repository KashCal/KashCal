package org.onekash.kashcal.sync.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification channels for sync operations.
 *
 * Creates and manages two channels:
 * 1. SYNC_PROGRESS - Low importance, for ongoing sync foreground service
 * 2. SYNC_STATUS - Default importance, for sync completion/error notifications
 *
 * Per Android best practices:
 * - Channels are created once and persisted
 * - Users can customize channel settings
 * - Channel settings cannot be changed programmatically after creation
 */
@Singleton
class SyncNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Channel IDs
        const val CHANNEL_SYNC_PROGRESS = "sync_progress"
        const val CHANNEL_SYNC_STATUS = "sync_status"

        // Notification IDs
        const val NOTIFICATION_ID_SYNC_PROGRESS = 1001
        const val NOTIFICATION_ID_SYNC_COMPLETE = 1002
        const val NOTIFICATION_ID_SYNC_ERROR = 1003
        const val NOTIFICATION_ID_CONFLICT_ABANDONED = 1004
        const val NOTIFICATION_ID_OPERATION_EXPIRED = 1005
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Create all notification channels.
     * Should be called at app startup.
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return // Channels not needed before Android 8
        }

        createSyncProgressChannel()
        createSyncStatusChannel()
    }

    /**
     * Create the sync progress channel.
     * Used for foreground service notification during sync.
     * Low importance to avoid disturbing the user.
     */
    private fun createSyncProgressChannel() {
        val channel = NotificationChannel(
            CHANNEL_SYNC_PROGRESS,
            "Sync Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while syncing calendars"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Create the sync status channel.
     * Used for sync completion and error notifications.
     * Default importance to alert user of important sync events.
     */
    private fun createSyncStatusChannel() {
        val channel = NotificationChannel(
            CHANNEL_SYNC_STATUS,
            "Sync Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about sync completion and errors"
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Check if notifications are enabled for the app.
     */
    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Check if a specific channel is enabled.
     *
     * @return true if channel exists and is not disabled, false otherwise
     */
    fun isChannelEnabled(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return areNotificationsEnabled()
        }

        val channel = notificationManager.getNotificationChannel(channelId)
            ?: return false // Non-existent channel is not enabled

        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Cancel a notification by ID.
     */
    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * Cancel all sync-related notifications.
     */
    fun cancelAll() {
        cancel(NOTIFICATION_ID_SYNC_PROGRESS)
        cancel(NOTIFICATION_ID_SYNC_COMPLETE)
        cancel(NOTIFICATION_ID_SYNC_ERROR)
        cancel(NOTIFICATION_ID_CONFLICT_ABANDONED)
        cancel(NOTIFICATION_ID_OPERATION_EXPIRED)
    }
}
