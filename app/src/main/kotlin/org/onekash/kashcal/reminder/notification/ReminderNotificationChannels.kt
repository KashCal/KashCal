package org.onekash.kashcal.reminder.notification

import android.app.Notification
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
 * Owns the reminder notification channel and the Room reminders' notification IDs.
 *
 * The channel is high importance (heads-up and sound), with vibration, lights and an app-icon
 * badge. It is shared by Room and device event reminders. `KashCalApplication` creates it at app
 * start; once created, the user's channel settings persist.
 */
@Singleton
class ReminderNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_REMINDERS = "event_reminders"

        // Above the sync notifications (1001-1005).
        const val NOTIFICATION_ID_BASE = 2000
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Creates the reminder channel; call at app start. */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return // No channels before Android 8.
        }

        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.channel_event_reminders),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_event_reminders_desc)
            setShowBadge(true)
            enableVibration(true)
            enableLights(true)
            lightColor = android.graphics.Color.BLUE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(channel)
    }

    /** Returns the notification ID for a reminder row, in 2000-11999; rows 10000 apart share it. */
    fun getNotificationId(reminderId: Long): Int {
        return (NOTIFICATION_ID_BASE + (reminderId % 10000)).toInt()
    }

    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Returns false when the reminders channel is blocked or doesn't exist yet. */
    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return areNotificationsEnabled()
        }

        val channel = notificationManager.getNotificationChannel(CHANNEL_REMINDERS)
            ?: return false

        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelForReminder(reminderId: Long) {
        cancel(getNotificationId(reminderId))
    }
}
