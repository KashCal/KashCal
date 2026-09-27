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
 * Posts a reminder's notification, with Snooze and Dismiss actions, when its alarm fires.
 *
 * It then arms the reminders now due within [ReminderScheduler.SCHEDULE_WINDOW_DAYS]. The work
 * runs under goAsync() and a 9-second timeout; [handleAlarm] holds the steps.
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

        /**
         * Budget for refilling the window after the notification is posted. It
         * leaves the rest of the enclosing timeout for finishing the broadcast.
         */
        private const val REFILL_TIMEOUT_MS = 6_000L
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
     * Handles one fired alarm; internal so tests can call it.
     *
     * `@AndroidEntryPoint`'s generated `onReceive` re-runs field injection on every dispatch,
     * overwriting fields a test set, so callers must pass the dependencies explicitly.
     */
    internal suspend fun handleAlarm(
        reminderScheduler: ReminderScheduler,
        notificationManager: ReminderNotificationManager,
        reminderId: Long,
    ) {
        val reminder = reminderScheduler.getReminder(reminderId)
        if (reminder == null) {
            Log.w(TAG, "Reminder $reminderId not found in database")
            return
        }

        // A dismissed reminder doesn't post again
        if (reminder.status == ReminderStatus.DISMISSED) {
            Log.d(TAG, "Reminder $reminderId already dismissed, skipping")
            return
        }

        // The row carries its own copy of the event's display data, so its alarm can fire
        // after the whole event was deleted or soft-deleted (e.g. a CalDAV delete not yet
        // pushed, or a server delete pulled in the background). Post nothing and remove all
        // of the event's reminders and their alarms.
        if (!reminderScheduler.shouldFireReminder(reminder.eventId)) {
            Log.d(TAG, "Suppressed stale reminder $reminderId for event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelRemindersForEvent(reminder.eventId)
            return
        }

        // The event is live, but this reminder's occurrence may be cancelled (an organizer
        // skipped one meeting of a series, pulled in via CalDAV as an EXDATE or a cancelled
        // exception). Post nothing and remove only this occurrence's reminders; the series'
        // other occurrences keep theirs.
        if (!reminderScheduler.hasLiveOccurrenceForReminder(reminder)) {
            Log.d(TAG, "Suppressed reminder $reminderId for cancelled occurrence of event ${reminder.eventId.maskEventId()}")
            reminderScheduler.cancelReminderForOccurrence(reminder.eventId, reminder.occurrenceTime)
            return
        }

        // An occurrence can carry several reminders (1 hour before, 15 minutes before),
        // each with its own notification, so without this the user collects one
        // notification per offset for the same meeting. Clear the others and post this one.
        //
        // Everything that can suspend runs first, and the clear-then-post pair below can't
        // suspend, which keeps the user from ever being left with zero notifications:
        //
        // - Clear before posting, never after. If two reminders for one occurrence fire at
        //   the same instant (after a long doze, when several offsets come due together),
        //   each clears only ids it doesn't own, so the worst case is two notifications on
        //   screen, never none.
        // - Build before clearing. Building the notification reads preferences and can
        //   suspend, so a timeout there costs the new notification, not the one already on
        //   screen.
        val notification = notificationManager.buildNotification(reminder)
        val siblingIds = findSiblingIds(reminderScheduler, reminder)

        for (siblingId in siblingIds) {
            notificationManager.cancelNotification(siblingId)
        }
        notificationManager.postNotification(reminder, notification)

        reminderScheduler.markAsFired(reminderId)

        Log.d(TAG, "Showed notification for reminder $reminderId: ${reminder.eventTitle}")

        refillReminderWindow(reminderScheduler)
    }

    /**
     * Arms the reminders now due within the scheduler's window.
     *
     * Only reminders due within the window hold an alarm, so each fire arms the next ones.
     * It runs inside this broadcast, not as a background job, which the system can defer or a
     * later request can replace. It runs last, after the notification is posted and the row
     * marked fired, so a slow or failed refill costs only the refill; the daily refresh arms
     * anything it missed.
     */
    private suspend fun refillReminderWindow(reminderScheduler: ReminderScheduler) {
        try {
            val refilled = withTimeoutOrNull(REFILL_TIMEOUT_MS) {
                reminderScheduler.scheduleUpcomingReminders()
            }
            if (refilled == null) {
                Log.w(TAG, "Refill after a fired reminder ran out of time; the daily refresh arms the rest")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not refill reminders after a fired reminder", e)
        }
    }

    /**
     * Returns the sibling ids of [reminder], or an empty list if they can't be looked up.
     *
     * Clearing other notifications is cosmetic, so it must never cost the user the reminder
     * itself. Two ways it could, both handled here:
     *
     * - The query fails. Swallow it and post anyway; the fallback is one notification per
     *   offset, which beats silence.
     * - The query is slow (a long write transaction holding the database during a sync). The
     *   caller runs the whole handler under one timeout, so a slow read here could eat the
     *   budget the notification needs. Its own short timeout keeps the cost local: contention
     *   loses the tidy-up, not the reminder.
     *
     * Cancellation is rethrown. Once the caller's timeout has fired the job is cancelled, and
     * posting fails at its next suspension point anyway, so swallowing it would only hide that.
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
