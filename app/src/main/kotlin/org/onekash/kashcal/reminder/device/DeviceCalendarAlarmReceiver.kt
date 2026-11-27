package org.onekash.kashcal.reminder.device

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
import org.onekash.kashcal.data.calendar_provider.DeviceReminderCursor
import org.onekash.kashcal.util.maskEventId
import javax.inject.Inject

/**
 * Receives [DeviceCalendarReminderScheduler.ACTION_DEVICE_REMINDER_ALARM] for device events.
 *
 * Reads the event from the intent extras and shows the notification when
 * [DeviceCalendarReminderScheduler.shouldFireReminder] allows it. A reminder alarm then shows the
 * other reminders due by now that [DeviceCalendarReminderScheduler.takeDueAndArmNext] returns,
 * which also arms the next; a snooze alarm only re-arms. goAsync() allows about 10 seconds, so
 * the work is cut off at [GOASYNC_TIMEOUT_MS].
 *
 * @see DeviceCalendarReminderScheduler
 */
@AndroidEntryPoint
class DeviceCalendarAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DeviceCalAlarmReceiver"
        private const val GOASYNC_TIMEOUT_MS = 9_000L
    }

    @Inject
    lateinit var scheduler: DeviceCalendarReminderScheduler

    @Inject
    lateinit var notificationManager: DeviceCalendarReminderNotificationManager

    /**
     * Shows the reminder unless it's stale, then, for a reminder alarm, each due reminder after it
     * that may still notify, reading the clock once as [nowMs]. A failure showing one reminder is
     * logged and doesn't stop the others or the arming of the next; if
     * [DeviceCalendarReminderScheduler.takeDueAndArmNext] throws, it falls back to
     * [DeviceCalendarReminderScheduler.rescheduleAfterFire]. Takes its
     * dependencies as parameters because `@AndroidEntryPoint`'s generated `onReceive` re-runs
     * field injection on every dispatch, overwriting fields a test set.
     */
    internal suspend fun handleAlarm(
        scheduler: DeviceCalendarReminderScheduler,
        notificationManager: DeviceCalendarReminderNotificationManager,
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        triggerTime: Long,
        isSnooze: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        showIfAllowed(
            scheduler, notificationManager, eventId, occurrenceTs, title, location,
            isAllDay, calendarColor, calendarId, triggerTime,
        )
        if (isSnooze) {
            scheduler.rescheduleAfterFire()
            return
        }
        val due = try {
            scheduler.takeDueAndArmNext(DeviceReminderCursor(triggerTime, eventId, occurrenceTs), nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read the due device reminders, re-arming the next", e)
            scheduler.rescheduleAfterFire()
            return
        }
        for (reminder in due) {
            showIfAllowed(
                scheduler, notificationManager, reminder.eventId, reminder.occurrenceStartTs,
                reminder.title, reminder.location, reminder.isAllDay, reminder.calendarColor,
                reminder.calendarId, reminder.triggerTime,
            )
        }
    }

    private suspend fun showIfAllowed(
        scheduler: DeviceCalendarReminderScheduler,
        notificationManager: DeviceCalendarReminderNotificationManager,
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        triggerTime: Long,
    ) {
        try {
            if (scheduler.shouldFireReminder(eventId, calendarId, title)) {
                notificationManager.showNotification(
                    eventId = eventId,
                    occurrenceTs = occurrenceTs,
                    title = title,
                    location = location,
                    isAllDay = isAllDay,
                    calendarColor = calendarColor,
                    calendarId = calendarId,
                    triggerTime = triggerTime,
                )
            } else {
                Log.d(TAG, "Suppressed stale reminder for event ${eventId.maskEventId()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't show the reminder for event ${eventId.maskEventId()}", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DeviceCalendarReminderScheduler.ACTION_DEVICE_REMINDER_ALARM) {
            Log.d(TAG, "Ignoring intent with action: ${intent?.action}")
            return
        }

        val eventId = intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L)
        val occurrenceTs = intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_OCCURRENCE_TS, -1L)
        val title = intent.getStringExtra(DeviceCalendarReminderScheduler.EXTRA_TITLE).orEmpty()
        val location = intent.getStringExtra(DeviceCalendarReminderScheduler.EXTRA_LOCATION)
        val isAllDay = intent.getBooleanExtra(DeviceCalendarReminderScheduler.EXTRA_IS_ALL_DAY, false)
        val calendarColor = intent.getIntExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_COLOR, 0)
        val calendarId = intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_ID, -1L)
        val isSnooze = intent.getBooleanExtra(DeviceCalendarReminderScheduler.EXTRA_IS_SNOOZE, false)

        if (eventId == -1L || occurrenceTs == -1L) {
            Log.w(TAG, "Missing required extras: eventId=$eventId, occurrenceTs=$occurrenceTs")
            return
        }

        val maskedEventId = eventId.maskEventId()
        Log.d(TAG, "Received alarm for event $maskedEventId at occurrence $occurrenceTs")

        // goAsync() can return null under Robolectric.
        val pendingResult = goAsync()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                val triggerTime = intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_TRIGGER_TIME, System.currentTimeMillis())
                val completed = withTimeoutOrNull(GOASYNC_TIMEOUT_MS) {
                    handleAlarm(
                        scheduler = scheduler,
                        notificationManager = notificationManager,
                        eventId = eventId,
                        occurrenceTs = occurrenceTs,
                        title = title,
                        location = location,
                        isAllDay = isAllDay,
                        calendarColor = calendarColor,
                        calendarId = calendarId,
                        triggerTime = triggerTime,
                        isSnooze = isSnooze,
                    )
                }
                if (completed == null) {
                    Log.w(TAG, "Device calendar reminder handling timed out for event $maskedEventId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling device calendar reminder", e)
            } finally {
                // finish() must run on every path to end the broadcast.
                pendingResult?.finish()
            }
        }
    }
}
