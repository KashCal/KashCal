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
 * Manages the system notification channel used by T2's invite-arrival
 * notifications. Mirrors [ReminderNotificationChannels] in shape; the
 * key differences are:
 *
 * - **Lower importance than reminders.** Invites announce something the
 *   user can decide on later; they should not preempt time-sensitive
 *   reminder buzzes. Default-importance channel — heads-up suppressed,
 *   sound/vibration off by default (user can opt in via per-channel
 *   settings).
 * - **Distinct ID range.** Notification IDs use base 2500 to avoid
 *   collisions with reminders (2000-base) and sync notifications
 *   (1001-1003).
 *
 * Channel created once at app start by [KashCalApplication].
 */
@Singleton
class InviteNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_INVITATIONS = "event_invitations"
        // Above ReminderNotificationChannels.NOTIFICATION_ID_BASE (2000) and
        // its 10000-row modulo space, below 13000.
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
            // No vibration by default — invites aren't time-sensitive.
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Generate a stable notification ID per attendee row. Modulo guards
     * against very large IDs without colliding within typical row counts.
     */
    fun getNotificationId(attendeeRowId: Long): Int {
        return (NOTIFICATION_ID_BASE + (attendeeRowId % 10_000)).toInt()
    }

    /**
     * Generate a stable notification ID per event (used by
     * [InviteNotificationManager.cancelForEvent], which doesn't know
     * which attendee row notified). Uses event ID + 5000 offset to
     * keep distinct from per-row IDs.
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
