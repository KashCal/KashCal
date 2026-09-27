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
 * Which calendars a batch of expired sync operations belongs to; picks the wording of the
 * "sync expired" notification. The three cases are mutually exclusive: one named calendar,
 * several calendars, or none that could be resolved.
 */
sealed interface ExpiredCalendarScope {
    /** All expired ops share one calendar with this display name. */
    data class Single(val name: String) : ExpiredCalendarScope

    /** Expired ops span [count] distinct calendars. */
    data class Multiple(val count: Int) : ExpiredCalendarScope

    /** No calendar could be resolved: the event or calendar rows are gone. */
    data object Unknown : ExpiredCalendarScope
}

/**
 * Builds and posts the sync notifications: the foreground progress notification, and the
 * completion, error, parse-failure, conflict-abandoned and expired-operation notifications.
 * Channels and IDs are in [SyncNotificationChannels].
 *
 * The progress notification goes out as WorkManager ForegroundInfo with the dataSync service
 * type, which Android 14 requires, and an optional cancel action.
 */
@Singleton
class SyncNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: SyncNotificationChannels
) {
    companion object {
        private const val REQUEST_CODE_OPEN_APP = 100
    }

    /**
     * Returns the ForegroundInfo for `setForeground()` and `getForegroundInfo()`, showing
     * [progress] and a cancel action when [cancelIntent] is given.
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

    /** Builds the ongoing, low-priority sync notification with [progress] as its text. */
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

        cancelIntent?.let {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                it
            )
        }

        return builder.build()
    }

    /** Builds a sync notification with an indeterminate progress bar. Nothing calls it today. */
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
                context.getString(R.string.action_cancel),
                it
            )
        }

        return builder.build()
    }

    /**
     * Builds a sync notification with a progress bar at [progress], clamped to 0-100. Nothing
     * calls it today.
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
                context.getString(R.string.action_cancel),
                it
            )
        }

        return builder.build()
    }

    /**
     * Posts the notification for [result].
     *
     * @param showOnlyOnChanges skips a [SyncResult.Success] with no changes.
     */
    fun showCompletionNotification(result: SyncResult, showOnlyOnChanges: Boolean = true) {
        when (result) {
            is SyncResult.Success -> {
                if (showOnlyOnChanges && result.totalChanges == 0) {
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

    private fun showSuccessNotification(result: SyncResult.Success) {
        val res = context.resources
        val content = buildString {
            append(res.getQuantityString(R.plurals.sync_notification_calendars, result.calendarsSynced, result.calendarsSynced))
            if (result.totalChanges > 0) {
                append(res.getQuantityString(R.plurals.sync_notification_changes, result.totalChanges, result.totalChanges))
            }
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_complete))
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE, notification)
    }

    /** Shows the calendar count and the error count. */
    private fun showPartialSuccessNotification(result: SyncResult.PartialSuccess) {
        val res = context.resources
        val content = buildString {
            append(res.getQuantityString(R.plurals.sync_notification_calendars, result.calendarsSynced, result.calendarsSynced))
            append(res.getQuantityString(R.plurals.sync_notification_errors, result.errors.size, result.errors.size))
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_partial))
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_COMPLETE, notification)
    }

    /** High priority: the user must sign in again. */
    private fun showAuthErrorNotification(result: SyncResult.AuthError) {
        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_auth_failed))
            .setContentText(context.getString(R.string.sync_notification_auth_reauth))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    private fun showErrorNotification(result: SyncResult.Error) {
        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_failed))
            .setContentText(result.message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /** Posts an error notification with the caller's [title] and [message]. */
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
     * Tells the user that [abandonedCount] events in [calendarName] stayed unparseable after the
     * maximum retries and weren't synced. A no-op for a count of 0.
     */
    fun showParseFailureNotification(calendarName: String, abandonedCount: Int) {
        if (abandonedCount <= 0) return

        val eventText = context.resources.getQuantityString(R.plurals.event_count, abandonedCount, abandonedCount)
        val content = context.getString(R.string.sync_notification_parse_content, eventText, calendarName)

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_parse_title))
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
     * Tells the user that local changes to [abandonedCount] events were lost to conflicts that
     * stayed unresolved after the maximum sync cycles. A no-op for a count of 0.
     *
     * @param eventTitle named in the text only when one event was abandoned.
     */
    fun showConflictAbandonedNotification(eventTitle: String?, abandonedCount: Int) {
        if (abandonedCount <= 0) return

        val content = if (eventTitle != null && abandonedCount == 1) {
            context.getString(R.string.sync_notification_conflict_single, eventTitle)
        } else {
            val eventText = context.resources.getQuantityString(R.plurals.event_count, abandonedCount, abandonedCount)
            context.getString(R.string.sync_notification_conflict_multi, eventText)
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_conflict_title))
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
     * Tells the user that [expiredCount] local changes were abandoned at the 30-day operation
     * lifetime and never synced. A no-op for a count of 0.
     *
     * @param scope the calendars the operations belong to, which picks the wording.
     */
    fun showOperationExpiredNotification(expiredCount: Int, scope: ExpiredCalendarScope) {
        if (expiredCount <= 0) return

        val eventText = context.resources.getQuantityString(R.plurals.event_count, expiredCount, expiredCount)
        val content = when (scope) {
            is ExpiredCalendarScope.Single ->
                context.getString(R.string.sync_notification_expired_content_calendar, eventText, scope.name)
            is ExpiredCalendarScope.Multiple -> {
                val calendarText = context.resources.getQuantityString(
                    R.plurals.calendar_count, scope.count, scope.count
                )
                context.getString(R.string.sync_notification_expired_content_calendars, eventText, calendarText)
            }
            ExpiredCalendarScope.Unknown ->
                context.getString(R.string.sync_notification_expired_content, eventText)
        }

        val notification = NotificationCompat.Builder(context, SyncNotificationChannels.CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notification_expired_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            // Re-posting this fixed-id notification (a later sync abandons more operations)
            // must update silently, with no sound or heads-up.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(createOpenAppIntent())
            .build()

        notify(SyncNotificationChannels.NOTIFICATION_ID_OPERATION_EXPIRED, notification)
    }

    /** Cancels the progress notification; call when a sync ends, whether it succeeded or not. */
    fun cancelProgressNotification() {
        channels.cancel(SyncNotificationChannels.NOTIFICATION_ID_SYNC_PROGRESS)
    }

    /** Cancels every sync notification. Nothing calls it today. */
    fun cancelAllNotifications() {
        channels.cancelAll()
    }

    /**
     * Opens the app. The intent names [MainActivity], since an implicit PendingIntent can be
     * hijacked (CWE-927).
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

    private fun notify(id: Int, notification: Notification) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notificationManager.notify(id, notification)
    }
}
