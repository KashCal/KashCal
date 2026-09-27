package org.onekash.kashcal.reminder.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows the notification for an invitation: a pulled event where the user's attendee row is
 * PARTSTAT=NEEDS-ACTION (the caller, `InviteNotifier`, picks the rows).
 *
 * Parallels [ReminderNotificationManager], except:
 * - PRIORITY_DEFAULT: invites aren't time-sensitive like reminders.
 * - No actions. Tapping opens the event, where the Respond buttons live.
 * - The tap intent uses [ReminderNotificationManager.ACTION_SHOW_EVENT], which MainActivity
 *   already routes to the event quick view.
 */
@Singleton
class InviteNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: InviteNotificationChannels
) {
    /**
     * Shows an invitation notification for [event], unless notifications or the channel are off.
     * The ID is keyed on the attendee row, so invitations for distinct events stack.
     *
     * @param attendeeRowId the Room ID of the user's attendee row
     * @param organizerLabel the organizer's name, or the bare address when it has none
     */
    fun showInvite(event: Event, attendeeRowId: Long, organizerLabel: String) {
        if (!areNotificationsEnabled()) return

        val notificationId = channels.getNotificationId(attendeeRowId)
        val body = context.getString(R.string.invite_notification_body, organizerLabel)

        val notification = NotificationCompat.Builder(context, InviteNotificationChannels.CHANNEL_INVITATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(createOpenIntent(event))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        nm.notify(notificationId, notification)
    }

    /** Cancels the notification of an attendee row. */
    fun cancel(attendeeRowId: Long) {
        channels.cancel(channels.getNotificationId(attendeeRowId))
    }

    /**
     * Cancels [eventId]'s invite notification: the one for [attendeeRowId] when given, and the
     * per-event ID. `InviteNotifier.cancelForEvent` calls it for each of the event's attendee
     * rows once an RSVP is recorded. Notifications are only ever posted under per-row IDs.
     */
    fun cancelForEvent(eventId: Long, attendeeRowId: Long? = null) {
        if (attendeeRowId != null) {
            cancel(attendeeRowId)
        }
        channels.cancel(channels.getNotificationIdForEvent(eventId))
    }

    fun areNotificationsEnabled(): Boolean =
        channels.areNotificationsEnabled() && channels.isChannelEnabled()

    private fun createOpenIntent(event: Event): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ReminderNotificationManager.ACTION_SHOW_EVENT
            putExtra(ReminderNotificationManager.EXTRA_EVENT_ID, event.id)
            // The event's start stands in for the occurrence: invitations are for the whole
            // series on every fixture-tested server.
            putExtra(ReminderNotificationManager.EXTRA_OCCURRENCE_TS, event.startTs)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            channels.getNotificationIdForEvent(event.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
