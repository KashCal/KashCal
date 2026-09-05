package org.onekash.kashcal.reminder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.util.maskEventId
import javax.inject.Inject

/**
 * BroadcastReceiver for reminder alarm triggers.
 *
 * Called by AlarmManager when a scheduled reminder fires.
 * Shows the notification with Snooze/Dismiss actions.
 *
 * Per Android best practices:
 * - Uses goAsync() for work that takes > 10ms
 * - Uses Hilt for dependency injection in receiver
 * - Keeps receiver execution fast
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderAlarmReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L

        /**
         * Budget for the sibling lookup. Generous for an index-covered read of a
         * handful of ids, and small enough that losing it still leaves the
         * notification itself plenty of the enclosing timeout.
         */
        private const val SIBLING_LOOKUP_TIMEOUT_MS = 500L
    }

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var notificationManager: ReminderNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER_ALARM) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }

        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1)
        if (reminderId == -1L) {
            Log.e(TAG, "Missing reminder ID in intent")
            return
        }

        Log.d(TAG, "Alarm fired for reminder $reminderId")

        // Use goAsync() for database access
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handleAlarm(reminderScheduler, notificationManager, reminderId)
                }
                if (completed == null) {
                    Log.w(TAG, "Alarm handling timed out for reminder $reminderId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm for reminder $reminderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Extracted for test access: `@AndroidEntryPoint`'s generated `onReceive`
     * re-runs field injection on every dispatch, clobbering any values set
     * manually by a test. Callers must pass the dependencies explicitly.
     */
    internal suspend fun handleAlarm(
        reminderScheduler: ReminderScheduler,
        notificationManager: ReminderNotificationManager,
        reminderId: Long,
    ) {
        // Get the reminder from database
        val reminder = reminderScheduler.getReminder(reminderId)
        if (reminder == null) {
            Log.w(TAG, "Reminder $reminderId not found in database")
            return
        }

        // Check if already dismissed (avoid duplicate notifications)
        if (reminder.status == ReminderStatus.DISMISSED) {
            Log.d(TAG, "Reminder $reminderId already dismissed, skipping")
            return
        }

        // The reminder row denormalizes event data, so it can fire "blind"
        // after its whole event was deleted or soft-deleted (e.g. a CalDAV
        // delete not yet pushed, or a server-side event delete pulled in the
        // background). Suppress the notification and clean up the stale row +
        // sibling alarms.
        if (!reminderScheduler.shouldFireReminder(reminder.eventId)) {
            Log.d(TAG, "Suppressed stale reminder $reminderId for event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelRemindersForEvent(reminder.eventId)
            return
        }

        // The whole event is still live, but this reminder is for ONE occurrence
        // of it — and that single instance may have been cancelled (an organizer
        // skipped one meeting of a recurring series, pulled in via CalDAV as an
        // EXDATE or a cancelled exception). Suppress this slot's notification and
        // clean up only its row + alarm; the series' other live occurrences keep
        // their reminders.
        if (!reminderScheduler.hasLiveOccurrenceForReminder(reminder)) {
            Log.d(TAG, "Suppressed reminder $reminderId for cancelled occurrence of event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelReminderForOccurrence(reminder.eventId, reminder.occurrenceTime)
            return
        }

        // An occurrence can carry several reminders (1 hour before, 15 minutes
        // before), each keyed to its own notification, so without this the user
        // collects one notification per offset for the same meeting. Clear the
        // others and let this one stand alone.
        //
        // Everything that can suspend happens first, and the clear-then-post pair
        // below cannot, which is what keeps "never zero notifications" true:
        //
        // - Clear BEFORE posting, never after. If two reminders for one occurrence
        //   fire at the same instant (real after a long doze, when several offsets
        //   come due together), each only ever clears ids it does not own, so the
        //   worst case is two notifications on screen rather than none.
        // - Build BEFORE clearing. Composing the notification reads preferences and
        //   can suspend, so if this handler's timeout expires there the cost is the
        //   new notification, not the one already on screen.
        val notification = notificationManager.buildNotification(reminder)
        val siblingIds = findSiblingIds(reminderScheduler, reminder)

        for (siblingId in siblingIds) {
            notificationManager.cancelNotification(siblingId)
        }
        notificationManager.postNotification(reminder, notification)

        // Mark as fired
        reminderScheduler.markAsFired(reminderId)

        Log.d(TAG, "Showed notification for reminder $reminderId: ${reminder.eventTitle}")
    }

    /**
     * Sibling ids for [reminder], or an empty list if they can't be looked up.
     *
     * Tidying up other notifications is cosmetic, so it must never cost the user
     * the reminder itself. Two ways that could happen, both handled here:
     *
     * - The query fails. Swallow it and post anyway; the fallback is the old
     *   behaviour of one notification per offset, which beats silence.
     * - The query is slow (a long write transaction holding the database during
     *   a sync). The caller runs this whole handler under a single timeout, so a
     *   slow read here could eat the budget the notification itself needs. Its
     *   own short timeout keeps that cost local: contention loses the tidy-up,
     *   not the reminder.
     *
     * Cancellation is rethrown rather than swallowed. Once the caller's timeout
     * has fired, the job is cancelled and posting will fail at its next
     * suspension point regardless, so pretending otherwise would only hide it.
     */
    private suspend fun findSiblingIds(
        reminderScheduler: ReminderScheduler,
        reminder: ScheduledReminder,
    ): List<Long> {
        return try {
            withTimeoutOrNull(SIBLING_LOOKUP_TIMEOUT_MS) {
                reminderScheduler.getSiblingReminderIds(reminder)
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up sibling reminders for ${reminder.id}", e)
            emptyList()
        }
    }
}
