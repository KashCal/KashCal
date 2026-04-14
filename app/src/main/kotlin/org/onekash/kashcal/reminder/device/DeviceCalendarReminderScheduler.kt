package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.UpcomingDeviceReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules reminders for device calendar events using AlarmManager.
 *
 * Key design:
 * - **Single next-upcoming alarm model**: Only one alarm is active at a time.
 *   After it fires, reschedule for the next reminder.
 * - **Uses (eventId, occurrenceStartTs) as stable composite key** (not instanceId)
 * - **Ephemeral**: No Room storage; all context is in intent extras
 *
 * Per Android best practices:
 * - Uses setExactAndAllowWhileIdle() for exact timing in Doze
 * - Falls back to setAndAllowWhileIdle() if exact alarms not available
 * - Checks READ_CALENDAR permission before querying
 *
 * @see DeviceCalendarAlarmReceiver for alarm handling
 */
@Singleton
class DeviceCalendarReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "DeviceCalReminderSched"

        /** Intent action for device calendar reminder alarm */
        const val ACTION_DEVICE_REMINDER_ALARM = "org.onekash.kashcal.DEVICE_REMINDER_ALARM"

        // Intent extras - stored for notification display and event identification
        const val EXTRA_EVENT_ID = "device_event_id"
        const val EXTRA_OCCURRENCE_TS = "device_occurrence_ts"
        const val EXTRA_TITLE = "device_title"
        const val EXTRA_LOCATION = "device_location"
        const val EXTRA_IS_ALL_DAY = "device_is_all_day"
        const val EXTRA_CALENDAR_COLOR = "device_calendar_color"
        const val EXTRA_CALENDAR_ID = "device_calendar_id"
        const val EXTRA_TRIGGER_TIME = "device_trigger_time"

        /** Single request code - only one alarm is active at a time */
        private const val REQUEST_CODE = 5001

        /** Request code base for snooze alarms (supports multiple snoozed events) */
        private const val SNOOZE_REQUEST_CODE_BASE = 6000

        /** Bucket range for snooze request codes. 100K buckets gives <0.01% collision at 5 events. */
        const val SNOOZE_REQUEST_CODE_RANGE = 100_000

        /**
         * Compute snooze alarm request code from event identity.
         * Public for testability — used internally by [createSnoozePendingIntent].
         */
        fun computeSnoozeRequestCode(eventId: Long, occurrenceTs: Long): Int {
            return SNOOZE_REQUEST_CODE_BASE + abs((eventId xor occurrenceTs) % SNOOZE_REQUEST_CODE_RANGE).toInt()
        }
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Schedule the next upcoming device calendar reminder.
     *
     * Checks:
     * 1. Feature is enabled (deviceCalendarRemindersEnabled)
     * 2. Device calendars are enabled
     * 3. READ_CALENDAR permission is granted
     * 4. There are enabled device calendar IDs
     * 5. There is an upcoming reminder
     */
    suspend fun scheduleNextReminder() {
        // Check if feature is enabled
        if (!dataStore.getDeviceCalendarRemindersEnabled()) {
            Log.d(TAG, "Device calendar reminders disabled, skipping")
            return
        }

        // Check if device calendars are enabled
        if (!dataStore.getDeviceCalendarsEnabled()) {
            Log.d(TAG, "Device calendars disabled, skipping")
            return
        }

        // Check READ_CALENDAR permission
        if (!hasReadCalendarPermission()) {
            Log.w(TAG, "READ_CALENDAR permission not granted, skipping")
            return
        }

        // Get enabled calendar IDs
        val enabledCalendarIds = dataStore.getEnabledDeviceCalendarIds()
        if (enabledCalendarIds.isEmpty()) {
            Log.d(TAG, "No enabled device calendars, skipping")
            return
        }

        // Query for next upcoming reminder
        val nextReminder = calendarProviderRepository.getNextUpcomingReminder(enabledCalendarIds)
        if (nextReminder == null) {
            Log.d(TAG, "No upcoming device calendar reminders found")
            return
        }

        scheduleAlarm(nextReminder)
    }

    /**
     * Reschedule after the current alarm fires.
     * Re-queries for the next upcoming reminder.
     */
    suspend fun rescheduleAfterFire() {
        scheduleNextReminder()
    }

    /**
     * Schedule a snooze alarm for a device calendar event.
     *
     * @param eventId Device calendar event ID
     * @param occurrenceTs Original occurrence start timestamp
     * @param title Event title
     * @param location Event location (optional)
     * @param isAllDay Whether this is an all-day event
     * @param calendarColor Calendar color
     * @param calendarId Calendar ID
     * @param snoozeDurationMinutes How long to snooze (default 15 minutes)
     */
    fun scheduleSnooze(
        eventId: Long,
        occurrenceTs: Long,
        title: String,
        location: String?,
        isAllDay: Boolean,
        calendarColor: Int,
        calendarId: Long,
        snoozeDurationMinutes: Int = 15
    ) {
        val triggerTime = System.currentTimeMillis() + (snoozeDurationMinutes * 60 * 1000L)

        val snoozedReminder = UpcomingDeviceReminder(
            eventId = eventId,
            occurrenceStartTs = occurrenceTs,
            title = title,
            location = location,
            isAllDay = isAllDay,
            reminderMinutes = 0, // Not used for snooze
            triggerTime = triggerTime,
            calendarColor = calendarColor,
            calendarId = calendarId
        )

        // Use a different request code for snooze to not conflict with regular alarms
        val pendingIntent = createSnoozePendingIntent(snoozedReminder)

        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled snooze for event ${eventId.toString().take(4)}*** in $snoozeDurationMinutes minutes")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot schedule snooze alarm", e)
        }
    }

    /**
     * Cancel any pending device calendar reminder alarm.
     */
    fun cancelPendingAlarm() {
        val pendingIntent = createAlarmPendingIntent(null)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled pending device calendar reminder alarm")
    }

    /**
     * Schedule an alarm for a reminder.
     */
    private fun scheduleAlarm(reminder: UpcomingDeviceReminder) {
        val pendingIntent = createAlarmPendingIntent(reminder)

        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact device reminder for event ${reminder.eventId.toString().take(4)}*** at ${reminder.triggerTime}")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact device reminder (may drift 5-15 min)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException scheduling alarm, trying inexact", e)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
            } catch (e2: SecurityException) {
                Log.e(TAG, "Cannot schedule any alarm for device reminder", e2)
            }
        }
    }

    /**
     * Create PendingIntent for device calendar reminder alarm.
     *
     * @param reminder The reminder data to store in extras, or null for cancel operation
     */
    private fun createAlarmPendingIntent(reminder: UpcomingDeviceReminder?): PendingIntent {
        val intent = Intent(context, DeviceCalendarAlarmReceiver::class.java).apply {
            action = ACTION_DEVICE_REMINDER_ALARM
            if (reminder != null) {
                putExtra(EXTRA_EVENT_ID, reminder.eventId)
                putExtra(EXTRA_OCCURRENCE_TS, reminder.occurrenceStartTs)
                putExtra(EXTRA_TITLE, reminder.title)
                putExtra(EXTRA_LOCATION, reminder.location)
                putExtra(EXTRA_IS_ALL_DAY, reminder.isAllDay)
                putExtra(EXTRA_CALENDAR_COLOR, reminder.calendarColor)
                putExtra(EXTRA_CALENDAR_ID, reminder.calendarId)
                putExtra(EXTRA_TRIGGER_TIME, reminder.triggerTime)
            }
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create PendingIntent for snooze alarm.
     * Uses a unique request code per event to allow multiple snoozed events.
     */
    private fun createSnoozePendingIntent(reminder: UpcomingDeviceReminder): PendingIntent {
        val intent = Intent(context, DeviceCalendarAlarmReceiver::class.java).apply {
            action = ACTION_DEVICE_REMINDER_ALARM
            putExtra(EXTRA_EVENT_ID, reminder.eventId)
            putExtra(EXTRA_OCCURRENCE_TS, reminder.occurrenceStartTs)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_LOCATION, reminder.location)
            putExtra(EXTRA_IS_ALL_DAY, reminder.isAllDay)
            putExtra(EXTRA_CALENDAR_COLOR, reminder.calendarColor)
            putExtra(EXTRA_CALENDAR_ID, reminder.calendarId)
            putExtra(EXTRA_TRIGGER_TIME, reminder.triggerTime)
        }

        val requestCode = computeSnoozeRequestCode(reminder.eventId, reminder.occurrenceStartTs)

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Check if READ_CALENDAR permission is granted.
     */
    private fun hasReadCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if we can schedule exact alarms.
     * For Android 12+, USE_EXACT_ALARM is auto-granted for calendar apps.
     */
    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
