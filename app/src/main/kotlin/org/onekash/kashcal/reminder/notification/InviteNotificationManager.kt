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
 * Builds and shows the per-invite system notification fired when the
 * sync engine pulls an event with the user's PARTSTAT=NEEDS-ACTION.
 *
 * Mirrors [ReminderNotificationManager] in shape; the differences are:
 *
 * - **Lower priority.** PRIORITY_DEFAULT — invites aren't time-sensitive
 *   like reminders.
 * - **No actions.** Tapping opens the event detail surface (Respond
 *   buttons live there). Inline Accept/Decline-from-notification is
 *   deferred to v2 once the broadcast-receiver write path is paid for.
 * - **Tap intent reuses [ReminderNotificationManager.ACTION_SHOW_EVENT]**
 *   — the existing MainActivity handler already deep-links to the event
 *   quick-view sheet; no new intent route needed.
 */
@Singleton
class InviteNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: InviteNotificationChannels
) {
    /**
     * Build and show an invitation notification for [event]. The
     * notification ID is keyed on the attendee row so multiple
     * invitations for distinct events stack rather than overwrite,
     * and so the ViewModel can cancel by event ID later.
     *
     * @param attendeeRowId The Room row ID of the user's ATTENDEE row,
     *   used to key the notification ID for stacking.
     * @param organizerLabel A human-readable label for the organizer
     *   (CN if set, else the bare address).
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

    /**
     * Cancel a notification keyed on attendee row.
     */
    fun cancel(attendeeRowId: Long) {
        channels.cancel(channels.getNotificationId(attendeeRowId))
    }

    /**
     * Cancel any invite notification that may exist for [eventId]. Used
     * by `EventCoordinator.replyRsvp` so the system notification clears
     * when the user responds from any in-app surface. Cancels both the
     * per-row ID and the per-event ID since the caller doesn't know
     * which form was used.
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
        // Reuse the existing event-detail deep link path defined by
        // ReminderNotificationManager. MainActivity's onNewIntent already
        // handles ACTION_SHOW_EVENT, so the notification tap lands on
        // the quick-view sheet (where the Respond buttons live).
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ReminderNotificationManager.ACTION_SHOW_EVENT
            putExtra(ReminderNotificationManager.EXTRA_EVENT_ID, event.id)
            // Treat the event's startTs as the occurrence time — invitations
            // are series-level on every fixture-tested server; per-instance
            // is a T4 concern.
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
