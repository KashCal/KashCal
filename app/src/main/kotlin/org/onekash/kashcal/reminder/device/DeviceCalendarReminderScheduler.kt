package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.UpcomingDeviceReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.AlarmArming
import org.onekash.kashcal.util.maskEventId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Arms AlarmManager alarms for device event reminders.
 *
 * - One reminder alarm at a time: the next upcoming reminder. After it fires, the next is
 *   queried and armed. Snooze alarms are separate, one per occurrence.
 * - An occurrence is keyed by (eventId, occurrence start), not the provider's instance ID.
 * - Nothing is stored in Room; the alarm's intent extras carry the whole event.
 *
 * Alarms go through [AlarmArming.setAllowWhileIdle]: exact when exact alarms are allowed,
 * otherwise inexact.
 *
 * @see DeviceCalendarAlarmReceiver
 */
@Singleton
class DeviceCalendarReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "DeviceCalReminderSched"

        const val ACTION_DEVICE_REMINDER_ALARM = "org.onekash.kashcal.DEVICE_REMINDER_ALARM"

        // Same keys as DeviceCalendarReminderNotificationManager's extras.
        const val EXTRA_EVENT_ID = "device_event_id"
        const val EXTRA_OCCURRENCE_TS = "device_occurrence_ts"
        const val EXTRA_TITLE = "device_title"
        const val EXTRA_LOCATION = "device_location"
        const val EXTRA_IS_ALL_DAY = "device_is_all_day"
        const val EXTRA_CALENDAR_COLOR = "device_calendar_color"
        const val EXTRA_CALENDAR_ID = "device_calendar_id"
        const val EXTRA_TRIGGER_TIME = "device_trigger_time"

        /** Request code of the one reminder alarm; re-arming replaces it. */
        private const val REQUEST_CODE = 5001

        /** Base of the per-occurrence snooze request codes, so several snoozes coexist. */
        private const val SNOOZE_REQUEST_CODE_BASE = 6000

        /** Snooze request code buckets; 100K gives <0.01% collision odds at 5 events. */
        const val SNOOZE_REQUEST_CODE_RANGE = 100_000

        /** Returns the snooze alarm's request code for an occurrence. Public for tests. */
        fun computeSnoozeRequestCode(eventId: Long, occurrenceTs: Long): Int {
            return SNOOZE_REQUEST_CODE_BASE + abs((eventId xor occurrenceTs) % SNOOZE_REQUEST_CODE_RANGE).toInt()
        }
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Arms the alarm for the next upcoming reminder in the enabled device calendars.
     *
     * Cancels the pending reminder alarm instead when device reminders or device calendars are
     * off, READ_CALENDAR isn't granted, no device calendar is enabled, or no reminder is upcoming.
     * Snooze alarms are left alone.
     */
    suspend fun scheduleNextReminder() {
        if (!dataStore.getDeviceCalendarRemindersEnabled()) {
            Log.d(TAG, "Device calendar reminders disabled, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        if (!dataStore.getDeviceCalendarsEnabled()) {
            Log.d(TAG, "Device calendars disabled, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        if (!hasReadCalendarPermission()) {
            Log.w(TAG, "READ_CALENDAR permission not granted, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        val enabledCalendarIds = dataStore.getEnabledDeviceCalendarIds()
        if (enabledCalendarIds.isEmpty()) {
            Log.d(TAG, "No enabled device calendars, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        val nextReminder = calendarProviderRepository.getNextUpcomingReminder(enabledCalendarIds)
        if (nextReminder == null) {
            Log.d(TAG, "No upcoming device calendar reminders found, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        scheduleAlarm(nextReminder)
    }

    /** Arms the next reminder after one fired; same as [scheduleNextReminder]. */
    suspend fun rescheduleAfterFire() {
        scheduleNextReminder()
    }

    /**
     * Returns whether a fired alarm for [eventId] may still notify. The intent extras are fixed
     * when the alarm is armed; since then the event may have been deleted (`DELETED = 1` counts),
     * device reminders or device calendars turned off, every device calendar disabled, or
     * READ_CALENDAR revoked.
     */
    suspend fun shouldFireReminder(eventId: Long): Boolean {
        if (!dataStore.getDeviceCalendarRemindersEnabled()) return false
        if (!dataStore.getDeviceCalendarsEnabled()) return false
        if (!hasReadCalendarPermission()) return false
        if (dataStore.getEnabledDeviceCalendarIds().isEmpty()) return false
        return calendarProviderRepository.isEventActive(eventId)
    }

    /**
     * Arms a snooze alarm [snoozeDurationMinutes] from now for one occurrence, replacing an
     * earlier snooze of the same occurrence.
     *
     * @param occurrenceTs the occurrence's start, not the snooze time
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

        // A request code of its own, so the snooze doesn't replace the reminder alarm.
        val pendingIntent = createSnoozePendingIntent(snoozedReminder)

        // A SecurityException on the exact set is logged, not retried as an inexact alarm.
        AlarmArming.setAllowWhileIdle(
            alarmManager = alarmManager,
            triggerTime = triggerTime,
            pendingIntent = pendingIntent,
            tag = TAG,
            label = "Device snooze for event ${eventId.maskEventId()} in $snoozeDurationMinutes min",
            inexactFallback = false
        )
    }

    /** Cancels the pending reminder alarm; snooze alarms stay armed. */
    fun cancelPendingAlarm() {
        val pendingIntent = createAlarmPendingIntent(null)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled pending device calendar reminder alarm")
    }

    private fun scheduleAlarm(reminder: UpcomingDeviceReminder) {
        val pendingIntent = createAlarmPendingIntent(reminder)

        AlarmArming.setAllowWhileIdle(
            alarmManager = alarmManager,
            triggerTime = reminder.triggerTime,
            pendingIntent = pendingIntent,
            tag = TAG,
            label = "Device reminder for event ${reminder.eventId.maskEventId()}"
        )
    }

    /**
     * Creates the reminder alarm's PendingIntent.
     *
     * @param reminder the extras to carry, or null to build one for cancelling
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

    /** Creates a snooze alarm's PendingIntent, with a request code per occurrence. */
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

    private fun hasReadCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }
}
