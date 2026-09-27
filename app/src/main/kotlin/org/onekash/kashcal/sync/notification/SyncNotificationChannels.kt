package org.onekash.kashcal.sync.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the sync notification channels and owns their notification IDs.
 *
 * - [CHANNEL_SYNC_PROGRESS]: low importance, for the foreground sync notification.
 * - [CHANNEL_SYNC_STATUS]: default importance, for completion, error, parse-failure, conflict
 *   and expiry notifications.
 *
 * Once a channel exists, re-creating it updates only its name and description, and lowers its
 * importance only if the user hasn't changed the channel. A raised importance reaches only
 * devices where the channel doesn't exist yet.
 */
@Singleton
class SyncNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_SYNC_PROGRESS = "sync_progress"
        const val CHANNEL_SYNC_STATUS = "sync_status"

        const val NOTIFICATION_ID_SYNC_PROGRESS = 1001
        const val NOTIFICATION_ID_SYNC_COMPLETE = 1002
        const val NOTIFICATION_ID_SYNC_ERROR = 1003
        const val NOTIFICATION_ID_CONFLICT_ABANDONED = 1004
        const val NOTIFICATION_ID_OPERATION_EXPIRED = 1005
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Creates both channels; called from `KashCalApplication.onCreate`. */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return // No channels before Android 8.
        }

        createSyncProgressChannel()
        createSyncStatusChannel()
    }

    /** Silent and badgeless, so a running sync doesn't disturb the user. */
    private fun createSyncProgressChannel() {
        val channel = NotificationChannel(
            CHANNEL_SYNC_PROGRESS,
            context.getString(R.string.channel_sync_progress),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_sync_progress_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    /** Default importance, so sync results and errors alert the user. */
    private fun createSyncStatusChannel() {
        val channel = NotificationChannel(
            CHANNEL_SYNC_STATUS,
            context.getString(R.string.channel_sync_status),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_sync_status_desc)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    /** Returns whether the app may post notifications at all. */
    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Returns true if the channel exists and the user hasn't turned it off. */
    fun isChannelEnabled(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return areNotificationsEnabled()
        }

        val channel = notificationManager.getNotificationChannel(channelId)
            ?: return false

        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /** Cancels every sync notification ID above. */
    fun cancelAll() {
        cancel(NOTIFICATION_ID_SYNC_PROGRESS)
        cancel(NOTIFICATION_ID_SYNC_COMPLETE)
        cancel(NOTIFICATION_ID_SYNC_ERROR)
        cancel(NOTIFICATION_ID_CONFLICT_ABANDONED)
        cancel(NOTIFICATION_ID_OPERATION_EXPIRED)
    }
}
