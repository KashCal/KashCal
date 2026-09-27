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
 * Owns the notification channel and IDs for invitation notifications.
 *
 * Parallels [ReminderNotificationChannels], with two differences:
 * - Default importance, below reminders: an invite can wait and shouldn't preempt a
 *   time-sensitive reminder. No heads-up, and vibration and lights off; the user can turn them
 *   on in the channel settings.
 * - IDs start at [NOTIFICATION_ID_BASE] (12000), above reminders (2000-11999) and sync
 *   notifications (1001-1005).
 *
 * `KashCalApplication` creates the channel at app start.
 */
@Singleton
class InviteNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_INVITATIONS = "event_invitations"
        const val NOTIFICATION_ID_BASE = 12000
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_INVITATIONS,
            context.getString(R.string.channel_event_invitations),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_event_invitations_desc)
            setShowBadge(true)
            // No vibration by default: invites aren't time-sensitive.
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** Returns an attendee row's notification ID, in 12000-21999; rows 10000 apart share it. */
    fun getNotificationId(attendeeRowId: Long): Int {
        return (NOTIFICATION_ID_BASE + (attendeeRowId % 10_000)).toInt()
    }

    /**
     * Returns a per-event ID in 17000-21999, used as the tap intent's request code and cancelled
     * by [InviteNotificationManager.cancelForEvent]. It lies inside the per-row range, so it
     * can equal a row's notification ID.
     */
    fun getNotificationIdForEvent(eventId: Long): Int {
        return (NOTIFICATION_ID_BASE + 5_000 + (eventId % 5_000)).toInt()
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return areNotificationsEnabled()
        }
        val channel = notificationManager.getNotificationChannel(CHANNEL_INVITATIONS)
            ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}
