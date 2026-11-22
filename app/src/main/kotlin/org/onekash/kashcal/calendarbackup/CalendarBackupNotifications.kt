package org.onekash.kashcal.calendarbackup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.R
import org.onekash.kashcal.SettingsActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Automatic calendar backup channel and its one notification, posted under
 * [NOTIFICATION_TAG] with [NOTIFICATION_ID]. Android tells notifications apart by tag and id
 * together, so it can't replace or cancel the app's other notifications. `KashCalApplication`
 * creates the channel at app start.
 *
 * A tap opens Settings on the backup sheet on top of whatever KashCal has open: no flag clears
 * the task, so Back returns there with an open form and its edits intact.
 */
@Singleton
class CalendarBackupNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarBackupAlerts {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_calendar_backup),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_calendar_backup_description)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun notificationState(): BackupNotificationState {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return BackupNotificationState.APP_OFF
        }
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        return if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            BackupNotificationState.CHANNEL_OFF
        } else {
            BackupNotificationState.ON
        }
    }

    override fun post(alert: BackupAlert) {
        val text = textFor(alert)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.calendar_backup_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
    }

    override fun cancel() {
        notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
    }

    private fun textFor(alert: BackupAlert): String {
        val res = context.resources
        fun folder(name: String?) = name ?: res.getString(R.string.calendar_backup_folder_unnamed)
        return when (alert) {
            is BackupAlert.FolderUnreachable ->
                res.getString(R.string.calendar_backup_alert_unreachable, folder(alert.folderName))
            is BackupAlert.FolderUnsupported ->
                res.getString(R.string.calendar_backup_alert_unsupported, folder(alert.folderName))
            is BackupAlert.CalendarsFailed -> if (alert.fileNames.size == 1) {
                res.getString(R.string.calendar_backup_alert_file_retry, alert.fileNames.single())
            } else {
                res.getQuantityString(
                    R.plurals.calendar_backup_alert_files_retry, alert.fileNames.size, alert.fileNames.size
                )
            }
        }
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_OPEN_CALENDAR_BACKUP, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "calendar_backup"
        const val NOTIFICATION_TAG = "calendar_backup"
        const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 0
    }
}
