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
import org.onekash.kashcal.data.calendar_provider.DeviceReminderCursor
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
 * - One reminder alarm at a time: the next upcoming reminder, in [DeviceReminderCursor] order.
 *   When it fires, [takeDueAndArmNext] returns the other reminders due by then, up to
 *   [MAX_BATCH], so reminders at the same instant or overtaken by a late delivery are shown with
 *   it, and arms the first one left: past the cap the first due one left out (a past time, so
 *   delivered as soon as the system allows), else the first still ahead. Snooze alarms are
 *   separate, one per occurrence.
 * - An occurrence is keyed by (eventId, occurrence start), not the provider's instance ID.
 * - Nothing is stored in Room; the alarm's intent extras carry the whole event.
 * - [scheduleNextReminder] re-picks after a device-calendar change: through the provider observer
 *   while the process runs, and through the job [DeviceCalendarChangeWatcher] keeps scheduled
 *   when it doesn't. App start, boot, app update, time-zone changes, the daily refresh, the
 *   Device calendars and Device reminders switches, a settings restore while device calendars
 *   are on, showing or hiding declined events, and ticking, unticking or muting a calendar
 *   re-pick too.
 * - Muted calendars ([org.onekash.kashcal.data.db.entity.Calendar.isNotificationMuted], kept for
 *   device calendars in [KashCalDataStore.mutedDeviceCalendarIds]) and calendars that aren't
 *   enabled are never picked, and an alarm armed before the mute or the untick is dropped when
 *   it fires ([shouldFireReminder]).
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

        /** Set on snooze alarms, which show only themselves and don't take the due batch. */
        const val EXTRA_IS_SNOOZE = "device_is_snooze"

        /**
         * Most due reminders one delivery shows, so it stays inside the receiver's time limit; the
         * rest follow on the next delivery, which is armed for the first of them.
         */
        const val MAX_BATCH = 20

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
     * off, READ_CALENDAR isn't granted, every enabled device calendar is muted (or none is
     * enabled), or no reminder is upcoming. Snooze alarms are left alone.
     */
    suspend fun scheduleNextReminder() {
        val notifyingCalendarIds = notifyingCalendarIdsOrCancel() ?: return

        val nextReminder = calendarProviderRepository.getNextUpcomingReminder(notifyingCalendarIds)
        if (nextReminder == null) {
            Log.d(TAG, "No upcoming device calendar reminders found, cancelling any pending alarm")
            cancelPendingAlarm()
            return
        }

        scheduleAlarm(nextReminder)
    }

    /**
     * Returns the reminders due by [nowMs] that come after [fired] in [DeviceReminderCursor] order,
     * at most [MAX_BATCH], and arms the batch's `next` (or cancels the alarm when there is none)
     * before returning, so a failure showing them can't leave the next unarmed. A provider error
     * reads as an empty batch with no next, which cancels the alarm.
     *
     * Under the same conditions as [scheduleNextReminder] it cancels the alarm and returns nothing.
     * The caller still checks each returned reminder with [shouldFireReminder].
     */
    suspend fun takeDueAndArmNext(fired: DeviceReminderCursor, nowMs: Long): List<UpcomingDeviceReminder> {
        val notifyingCalendarIds = notifyingCalendarIdsOrCancel() ?: return emptyList()

        val batch = calendarProviderRepository.getDueAndNextReminders(notifyingCalendarIds, fired, nowMs, MAX_BATCH)
        if (batch.next != null) {
            scheduleAlarm(batch.next)
        } else {
            Log.d(TAG, "No upcoming device calendar reminders found, cancelling any pending alarm")
            cancelPendingAlarm()
        }
        if (batch.overflowed) {
            Log.w(TAG, "More than $MAX_BATCH device reminders due at once; the rest follow on the next delivery")
        }
        return batch.due
    }

    /**
     * Returns the enabled device calendars that aren't muted, or cancels the pending reminder
     * alarm and returns null when device reminders or device calendars are off, READ_CALENDAR
     * isn't granted, or no enabled calendar is left to notify.
     */
    private suspend fun notifyingCalendarIdsOrCancel(): Set<Long>? {
        if (!dataStore.getDeviceCalendarRemindersEnabled()) {
            Log.d(TAG, "Device calendar reminders disabled, cancelling any pending alarm")
            cancelPendingAlarm()
            return null
        }

        if (!dataStore.getDeviceCalendarsEnabled()) {
            Log.d(TAG, "Device calendars disabled, cancelling any pending alarm")
            cancelPendingAlarm()
            return null
        }

        if (!hasReadCalendarPermission()) {
            Log.w(TAG, "READ_CALENDAR permission not granted, cancelling any pending alarm")
            cancelPendingAlarm()
            return null
        }

        val enabledCalendarIds = dataStore.getEnabledDeviceCalendarIds()
        if (enabledCalendarIds.isEmpty()) {
            Log.d(TAG, "No enabled device calendars, cancelling any pending alarm")
            cancelPendingAlarm()
            return null
        }

        val notifyingCalendarIds = enabledCalendarIds - dataStore.getMutedDeviceCalendarIds()
        if (notifyingCalendarIds.isEmpty()) {
            Log.d(TAG, "Every enabled device calendar is muted, cancelling any pending alarm")
            cancelPendingAlarm()
            return null
        }
        return notifyingCalendarIds
    }

    /**
     * Arms the next reminder after a snooze fired, or after [takeDueAndArmNext] failed; same as
     * [scheduleNextReminder].
     */
    suspend fun rescheduleAfterFire() {
        scheduleNextReminder()
    }

    /**
     * Returns whether a fired alarm for [eventId] in [calendarId] may still notify. The intent
     * extras are fixed when the alarm is armed; since then the event may have been deleted
     * (`DELETED = 1` counts), device reminders or device calendars turned off, every device
     * calendar disabled, READ_CALENDAR revoked, or [calendarId] unticked or muted. A [calendarId]
     * of -1 (the alarm carried none) passes both calendar checks.
     */
    suspend fun shouldFireReminder(eventId: Long, calendarId: Long): Boolean {
        if (!dataStore.getDeviceCalendarRemindersEnabled()) return false
        if (!dataStore.getDeviceCalendarsEnabled()) return false
        if (!hasReadCalendarPermission()) return false
        val enabledIds = dataStore.getEnabledDeviceCalendarIds()
        if (enabledIds.isEmpty()) return false
        if (calendarId != -1L && calendarId !in enabledIds) return false
        if (calendarId in dataStore.getMutedDeviceCalendarIds()) return false
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
            putExtra(EXTRA_IS_SNOOZE, true)
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
