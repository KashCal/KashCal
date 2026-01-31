package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.sync.engine.SyncResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages sync-related notifications.
 *
 * Provides:
 * - Progress notifications for foreground service
 * - Completion notifications
 * - Error notifications
 * - ForegroundInfo for WorkManager expedited work
 *
 * Per Android WorkManager best practices:
 * - Uses ForegroundInfo for long-running/expedited work
 * - Specifies foreground service type for Android 14+
 * - Provides cancel action for user control
 */
@Singleton
class SyncNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: SyncNotificationChannels
) {
    companion object {
        private const val TAG = "SyncNotificationManager"

        // Request codes for pending intents
        private const val REQUEST_CODE_OPEN_APP = 100
        private const val REQUEST_CODE_CANCEL_SYNC = 101
    }

    /**
     * Create ForegroundInfo for expedited/long-running work.
     *
     * @param progress Current progress message
     * @param cancelIntent Optional intent to cancel the work
     * @return ForegroundInfo for setForeground()
     */
    fun createForegroundInfo(
        progress: String,
        cancelIntent: PendingIntent? = null
    ): ForegroundInfo {
        val notification = createProgressNotification(progress, cancelIntent)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS,
                notification
            )
        }
    }

    /**
     * Create a progress notification for ongoing sync.
     *
     * @param progress Progress message
     * @param cancelIntent Optional cancel action
     * @return Notification for foreground service
     */
    fun createProgressNotification(
        progress: String,
        cancelIntent: PendingIntent? = null
    ): Notification {
        val builder = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(progress)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Add cancel action if provided
        cancelIntent?.let {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                it
            )
        }

        return builder.build()
    }

    /**
     * Create a progress notification with indeterminate progress.
     *
     * @param title Notification title
     * @param content Content text
     * @param cancelIntent Optional cancel action
     * @return Notification with indeterminate progress bar
     */
    fun createIndeterminateProgressNotification(
        title: String,
        content: String,
        cancelIntent: PendingIntent? = null
    ): Notification {
        val builder = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        cancelIntent?.let {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                it
            )
        }

        return builder.build()
    }

    /**
     * Create a progress notification with determinate progress.
     *
     * @param title Notification title
     * @param content Content text
     * @param progress Current progress (0-100)
     * @param cancelIntent Optional cancel action
     * @return Notification with progress bar
     */
    fun createDeterminateProgressNotification(
        title: String,
        content: String,
        progress: Int,
        cancelIntent: PendingIntent? = null
    ): Notification {
        val builder = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        cancelIntent?.let {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                it
            )
        }

        return builder.build()
    }

    /**
     * Show sync completion notification.
     *
     * @param result The sync result
     * @param showOnlyOnChanges If true, only show if there were changes
     */
    fun showCompletionNotification(result: SyncResult, showOnlyOnChanges: Boolean = true) {
        when (result) {
            is SyncResult.Success -> {
                if (showOnlyOnChanges && result.totalChanges == 0) {
                    // No changes, don't show notification
                    return
                }
                showSuccessNotification(result)
            }
            is SyncResult.PartialSuccess -> {
                showPartialSuccessNotification(result)
            }
            is SyncResult.AuthError -> {
                showAuthErrorNotification(result)
            }
            is SyncResult.Error -> {
                showErrorNotification(result)
            }
        }
    }

    /**
     * Show success notification.
     */
    private fun showSuccessNotification(result: SyncResult.Success) {
        val content = buildString {
            append("Synced ${result.calendarsSynced} calendars")
            if (result.totalChanges > 0) {
                append(", ${result.totalChanges} changes")
            }
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Complete")
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE, notification)
    }

    /**
     * Show partial success notification with error count.
     */
    private fun showPartialSuccessNotification(result: SyncResult.PartialSuccess) {
        val content = buildString {
            append("Synced ${result.calendarsSynced} calendars")
            append(", ${result.errors.size} errors")
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Partially Complete")
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE, notification)
    }

    /**
     * Show authentication error notification.
     * This is high priority as user action is required.
     */
    private fun showAuthErrorNotification(result: SyncResult.AuthError) {
        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Authentication Failed")
            .setContentText("Please re-authenticate your account")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * Show general error notification.
     */
    private fun showErrorNotification(result: SyncResult.Error) {
        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync Failed")
            .setContentText(result.message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * Show a custom error notification.
     *
     * @param title Notification title
     * @param message Error message
     */
    fun showErrorNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * Show notification when parse errors were abandoned after max retries.
     * This alerts the user that some events couldn't be synced.
     *
     * @param calendarName Name of the calendar with abandoned events
     * @param abandonedCount Number of events that couldn't be parsed
     */
    fun showParseFailureNotification(calendarName: String, abandonedCount: Int) {
        if (abandonedCount <= 0) return

        val eventText = if (abandonedCount == 1) "1 event" else "$abandonedCount events"
        val content = "$eventText in $calendarName couldn't be synced. Try Force Sync in Settings."

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Some events couldn't be synced")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * Show notification when sync conflicts were abandoned after max retries.
     * Alerts user that local changes were lost due to unresolvable conflicts.
     *
     * @param eventTitle Title of the event (shown when only 1 event abandoned)
     * @param abandonedCount Number of events with abandoned conflicts
     */
    fun showConflictAbandonedNotification(eventTitle: String?, abandonedCount: Int) {
        if (abandonedCount <= 0) return

        val content = if (eventTitle != null && abandonedCount == 1) {
            "Local changes to \"$eventTitle\" were lost due to sync conflict. Server version will be used."
        } else {
            val eventText = if (abandonedCount == 1) "1 event" else "$abandonedCount events"
            "Local changes to $eventText were lost due to sync conflicts. Server versions will be used."
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync conflict resolved")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_CONFLICT_ABANDONED, notification)
    }

    /**
     * Show notification when operations were abandoned due to 30-day lifetime expiry.
     * Alerts user that some local changes couldn't be synced.
     *
     * @param expiredCount Number of operations that were abandoned
     */
    fun showOperationExpiredNotification(expiredCount: Int) {
        if (expiredCount <= 0) return

        val eventText = if (expiredCount == 1) "1 event" else "$expiredCount events"
        val content = "$eventText couldn't be synced after 30 days. " +
            "The server version will be used. Force Sync to retry."

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sync changes expired")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED, notification)
    }

    /**
     * Cancel progress notification.
     * Should be called when sync completes (success or failure).
     */
    fun cancelProgressNotification() {
        channels.cancel(SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS)
    }

    /**
     * Cancel all sync notifications.
     */
    fun cancelAllNotifications() {
        channels.cancelAll()
    }

    /**
     * Create pending intent to open the app.
     * Uses explicit intent to avoid security vulnerability (implicit PendingIntent).
     */
    private fun createOpenAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Post a notification.
     */
    private fun notify(id: Int, notification: Notification) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notificationManager.notify(id, notification)
    }
}
