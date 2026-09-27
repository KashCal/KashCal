package org.onekash.kashcal.reminder.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.onekash.kashcal.util.maskEventId
import javax.inject.Inject

/**
 * Receives [DeviceCalendarReminderScheduler.ACTION_DEVICE_REMINDER_ALARM] for device events.
 *
 * Reads the event from the intent extras, shows the notification when
 * [DeviceCalendarReminderScheduler.shouldFireReminder] allows it, then arms the next reminder.
 * goAsync() allows about 10 seconds, so the work is cut off at [GOASYNC_TIMEOUT_MS].
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
     * Shows the reminder unless it's stale, then reschedules. Takes its dependencies as
     * parameters because `@AndroidEntryPoint`'s generated `onReceive` re-runs field injection
     * on every dispatch, overwriting fields a test set.
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
    ) {
        if (scheduler.shouldFireReminder(eventId)) {
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
        scheduler.rescheduleAfterFire()
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
