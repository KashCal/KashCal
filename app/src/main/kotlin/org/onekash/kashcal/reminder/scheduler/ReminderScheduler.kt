package org.onekash.kashcal.reminder.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.data.db.dao.EventWithOccurrenceAndColor
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import javax.inject.Inject
import javax.inject.Singleton

// ========== Package-level parsing functions (internal for testing) ==========

private const val TAG_PARSE = "ReminderParse"

/**
 * Parse iCal reminder offset to milliseconds.
 *
 * Supports ISO 8601 duration format used in VALARM:
 * - "-PT15M" = 15 minutes before (time-based)
 * - "-PT1H" = 1 hour before
 * - "-P1D" = 1 day before (day-based)
 * - "-P1W" = 1 week before
 * - "-P1DT2H30M" = 1 day 2 hours 30 minutes before (combined)
 *
 * @param offset The offset string (e.g., "-PT15M", "-P1D")
 * @return Milliseconds offset (negative for before), or null if unparseable
 */
internal fun parseReminderOffset(offset: String): Long? {
    if (offset.isBlank()) return null

    return try {
        val isNegative = offset.startsWith("-")
        val durationStr = if (isNegative) offset.substring(1) else offset

        val millis = parseIsoDuration(durationStr)
        if (millis == null) {
            Log.w(TAG_PARSE, "Could not parse duration: $durationStr")
            return null
        }

        if (isNegative) -millis else millis
    } catch (e: Exception) {
        Log.w(TAG_PARSE, "Failed to parse reminder offset: $offset", e)
        null
    }
}

/**
 * Parse ISO 8601 duration to milliseconds.
 *
 * Format: P[n]W or P[n]D[T[n]H[n]M[n]S]
 * Examples: P1D, P2W, PT15M, PT1H30M, P1DT2H30M
 *
 * This replaces java.time.Duration.parse() which only supports time-based
 * durations (PT...) and fails for day-based (P1D) or week-based (P1W).
 *
 * @param duration Duration string (e.g., "P1D", "PT15M")
 * @return Duration in milliseconds, or null if unparseable
 */
internal fun parseIsoDuration(duration: String): Long? {
    if (duration.isBlank() || !duration.startsWith("P")) return null

    val str = duration.substring(1) // Remove "P"

    // Handle PT0M and PT0S specially (0 = at time of event)
    if (str == "T0M" || str == "T0S") return 0L

    var totalMillis = 0L

    val hasTime = str.contains("T")
    val datePart = if (hasTime) str.substringBefore("T") else str
    val timePart = if (hasTime) str.substringAfter("T") else ""

    // Parse date part: weeks (W), days (D)
    if (datePart.isNotEmpty()) {
        Regex("(\\d+)W").find(datePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
            totalMillis += it * 7 * 24 * 60 * 60 * 1000
        }
        Regex("(\\d+)D").find(datePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
            totalMillis += it * 24 * 60 * 60 * 1000
        }
    }

    // Parse time part: hours (H), minutes (M), seconds (S)
    if (timePart.isNotEmpty()) {
        Regex("(\\d+)H").find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
            totalMillis += it * 60 * 60 * 1000
        }
        Regex("(\\d+)M").find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
            totalMillis += it * 60 * 1000
        }
        Regex("(\\d+)S").find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
            totalMillis += it * 1000
        }
    }

    // Return null if nothing was parsed (invalid format like "PXYZ")
    // But return 0L for explicit "0" values like PT0M
    return if (totalMillis > 0 || duration == "PT0M" || duration == "PT0S") totalMillis else null
}

/**
 * Schedules and manages event reminders using Android AlarmManager.
 *
 * Responsibilities:
 * - Parse reminder offsets from iCal format (e.g., "-PT15M", "-PT1H")
 * - Schedule exact alarms for reminder triggers
 * - Cancel alarms when events are deleted/updated
 * - Handle snooze functionality
 * - Reschedule after device boot
 *
 * Per Android best practices for calendar apps:
 * - Uses AlarmManager.setExactAndAllowWhileIdle() for exact timing in Doze
 * - Uses USE_EXACT_ALARM permission (auto-granted for calendar apps)
 * - Stores reminders in DB for boot recovery
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduledRemindersDao: ScheduledRemindersDao,
    private val eventReader: EventReader,
    private val channels: ReminderNotificationChannels
) {
    companion object {
        private const val TAG = "ReminderScheduler"

        // Intent action for alarm trigger
        const val ACTION_REMINDER_ALARM = "org.onekash.kashcal.REMINDER_ALARM"

        // Intent extra
        const val EXTRA_REMINDER_ID = "reminder_id"

        // Request code base for pending intents
        private const val REQUEST_CODE_BASE = 4000

        // Scheduling window: how far ahead to schedule reminders
        // Extended to 30 days (v16.5.6) - catches more far-future events
        const val SCHEDULE_WINDOW_DAYS = 30

        // Minimum trigger time in the future (avoid immediate triggers)
        private const val MIN_TRIGGER_FUTURE_MS = 5_000L
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Schedule reminders for an event's occurrences.
     *
     * @param event The event with reminders
     * @param occurrences The materialized occurrences to schedule reminders for
     * @param calendarColor The calendar color for notification display
     */
    suspend fun scheduleRemindersForEvent(
        event: Event,
        occurrences: List<Occurrence>,
        calendarColor: Int
    ) {
        val reminders = event.reminders
        if (reminders.isNullOrEmpty()) {
            Log.d(TAG, "No reminders for event ${event.id}")
            return
        }

        val now = System.currentTimeMillis()
        val scheduleWindow = now + (SCHEDULE_WINDOW_DAYS * 24 * 60 * 60 * 1000L)

        for (occurrence in occurrences) {
            // Only schedule for future occurrences within window
            if (occurrence.startTs < now || occurrence.startTs > scheduleWindow) {
                continue
            }

            for (reminderOffset in reminders) {
                scheduleReminderForOccurrence(
                    event = event,
                    occurrence = occurrence,
                    reminderOffset = reminderOffset,
                    calendarColor = calendarColor
                )
            }
        }
    }

    /**
     * Scan upcoming events and schedule any missing reminders.
     * Called by ReminderRefreshWorker to catch events that entered the window.
     *
     * @param windowDays How many days ahead to scan (default: 30, matches SCHEDULE_WINDOW_DAYS)
     * @return Number of new reminders scheduled
     */
    suspend fun scheduleUpcomingReminders(windowDays: Int = SCHEDULE_WINDOW_DAYS): Int {
        val now = System.currentTimeMillis()
        val windowEnd = now + (windowDays * 24 * 60 * 60 * 1000L)

        // Get all events with reminders that have occurrences in window
        val eventsWithReminders = eventReader.getEventsWithRemindersInRange(now, windowEnd)

        var scheduled = 0
        for (eventData in eventsWithReminders) {
            for (reminderOffset in eventData.event.reminders.orEmpty()) {
                val wasScheduled = scheduleReminderForOccurrenceIfMissing(
                    event = eventData.event,
                    occurrenceStartTs = eventData.occurrenceStartTs,
                    reminderOffset = reminderOffset,
                    calendarColor = eventData.calendarColor
                )
                if (wasScheduled) scheduled++
            }
        }

        Log.i(TAG, "Scheduled $scheduled missing reminders in ${windowDays}-day window")
        return scheduled
    }

    /**
     * Schedule reminder only if it doesn't already exist.
     * @return true if new reminder was scheduled, false if already exists
     */
    private suspend fun scheduleReminderForOccurrenceIfMissing(
        event: Event,
        occurrenceStartTs: Long,
        reminderOffset: String,
        calendarColor: Int
    ): Boolean {
        val offsetMs = parseReminderOffset(reminderOffset) ?: return false
        val triggerTime = occurrenceStartTs + offsetMs
        val now = System.currentTimeMillis()

        // Skip past/immediate triggers
        if (triggerTime < now + MIN_TRIGGER_FUTURE_MS) return false

        // Check if already scheduled
        val existing = scheduledRemindersDao.findExisting(
            eventId = event.id,
            occurrenceTime = occurrenceStartTs,
            reminderOffset = reminderOffset
        )
        if (existing != null) return false

        // Schedule new reminder
        val scheduledReminder = ScheduledReminder(
            eventId = event.id,
            occurrenceTime = occurrenceStartTs,
            triggerTime = triggerTime,
            reminderOffset = reminderOffset,
            status = ReminderStatus.PENDING,
            eventTitle = event.title,
            eventLocation = event.location,
            isAllDay = event.isAllDay,
            calendarColor = calendarColor
        )

        val reminderId = scheduledRemindersDao.insert(scheduledReminder)
        scheduleAlarm(reminderId, triggerTime)

        Log.d(TAG, "Scheduled missing reminder $reminderId for event ${event.id}")
        return true
    }

    /**
     * Schedule a single reminder for an occurrence.
     *
     * @param event The event
     * @param occurrence The occurrence
     * @param reminderOffset The reminder offset (e.g., "-PT15M")
     * @param calendarColor The calendar color
     */
    private suspend fun scheduleReminderForOccurrence(
        event: Event,
        occurrence: Occurrence,
        reminderOffset: String,
        calendarColor: Int
    ) {
        val offsetMs = parseReminderOffset(reminderOffset)
        if (offsetMs == null) {
            Log.w(TAG, "Could not parse reminder offset: $reminderOffset")
            return
        }

        val triggerTime = occurrence.startTs + offsetMs
        val now = System.currentTimeMillis()

        // Skip if trigger time is in the past or too soon
        if (triggerTime < now + MIN_TRIGGER_FUTURE_MS) {
            Log.d(TAG, "Skipping past/immediate reminder for event ${event.id}")
            return
        }

        // Check if reminder already exists
        val existing = scheduledRemindersDao.findExisting(
            eventId = event.id,
            occurrenceTime = occurrence.startTs,
            reminderOffset = reminderOffset
        )
        if (existing != null) {
            Log.d(TAG, "Reminder already scheduled: ${existing.id}")
            return
        }

        // Create and save the scheduled reminder
        val scheduledReminder = ScheduledReminder(
            eventId = event.id,
            occurrenceTime = occurrence.startTs,
            triggerTime = triggerTime,
            reminderOffset = reminderOffset,
            status = ReminderStatus.PENDING,
            eventTitle = event.title,
            eventLocation = event.location,
            isAllDay = event.isAllDay,
            calendarColor = calendarColor
        )

        val reminderId = scheduledRemindersDao.insert(scheduledReminder)
        Log.d(TAG, "Created scheduled reminder $reminderId for event ${event.id}")

        // Schedule the alarm
        scheduleAlarm(reminderId, triggerTime)
    }

    /**
     * Schedule an alarm via AlarmManager.
     *
     * @param reminderId The reminder ID
     * @param triggerTime When to trigger (millis since epoch)
     */
    fun scheduleAlarm(reminderId: Long, triggerTime: Long) {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted")
            return
        }

        val pendingIntent = createAlarmPendingIntent(reminderId)

        // Use setExactAndAllowWhileIdle for calendar reminders
        // This works in Doze mode and triggers at exact time
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )

        Log.d(TAG, "Scheduled alarm for reminder $reminderId at $triggerTime")
    }

    /**
     * Cancel a scheduled alarm.
     *
     * @param reminderId The reminder ID
     */
    fun cancelAlarm(reminderId: Long) {
        val pendingIntent = createAlarmPendingIntent(reminderId)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm for reminder $reminderId")
    }

    /**
     * Cancel all reminders for an event.
     *
     * @param eventId The event ID
     */
    suspend fun cancelRemindersForEvent(eventId: Long) {
        val reminders = scheduledRemindersDao.getPendingForEvent(eventId)
        for (reminder in reminders) {
            cancelAlarm(reminder.id)
        }
        scheduledRemindersDao.deleteForEvent(eventId)
        Log.d(TAG, "Cancelled ${reminders.size} reminders for event $eventId")
    }

    /**
     * Cancel reminder for a specific occurrence.
     *
     * @param eventId The event ID
     * @param occurrenceTime The occurrence start time
     */
    suspend fun cancelReminderForOccurrence(eventId: Long, occurrenceTime: Long) {
        val reminders = scheduledRemindersDao.getPendingForEvent(eventId)
            .filter { it.occurrenceTime == occurrenceTime }

        for (reminder in reminders) {
            cancelAlarm(reminder.id)
        }
        scheduledRemindersDao.deleteForOccurrence(eventId, occurrenceTime)
        Log.d(TAG, "Cancelled reminders for occurrence at $occurrenceTime")
    }

    /**
     * Cancel reminders for occurrences at or after a certain time.
     * Used when truncating a recurring series (deleteThisAndFuture).
     *
     * @param eventId The event ID
     * @param fromTimeMs Cancel reminders for occurrences at or after this time
     */
    suspend fun cancelRemindersForOccurrencesAfter(eventId: Long, fromTimeMs: Long) {
        val reminders = scheduledRemindersDao.getPendingForEvent(eventId)
            .filter { it.occurrenceTime >= fromTimeMs }

        for (reminder in reminders) {
            cancelAlarm(reminder.id)
        }
        scheduledRemindersDao.deleteForOccurrencesAfter(eventId, fromTimeMs)
        Log.d(TAG, "Cancelled ${reminders.size} reminders for occurrences after $fromTimeMs")
    }

    /**
     * Snooze a reminder.
     *
     * @param reminderId The reminder ID
     * @param snoozeDurationMinutes How long to snooze (default 15 minutes)
     */
    suspend fun snoozeReminder(reminderId: Long, snoozeDurationMinutes: Int = 15) {
        val newTriggerTime = System.currentTimeMillis() + (snoozeDurationMinutes * 60 * 1000L)

        // Update in database
        scheduledRemindersDao.snooze(reminderId, newTriggerTime)

        // Cancel old alarm and schedule new one
        cancelAlarm(reminderId)
        scheduleAlarm(reminderId, newTriggerTime)

        Log.d(TAG, "Snoozed reminder $reminderId for $snoozeDurationMinutes minutes")
    }

    /**
     * Mark reminder as fired.
     *
     * @param reminderId The reminder ID
     */
    suspend fun markAsFired(reminderId: Long) {
        scheduledRemindersDao.updateStatus(reminderId, ReminderStatus.FIRED)
    }

    /**
     * Mark reminder as dismissed.
     *
     * @param reminderId The reminder ID
     */
    suspend fun markAsDismissed(reminderId: Long) {
        scheduledRemindersDao.updateStatus(reminderId, ReminderStatus.DISMISSED)
        channels.cancelForReminder(reminderId)
    }

    /**
     * Reschedule all pending reminders.
     * Called after device boot or app update.
     */
    suspend fun rescheduleAllPending() {
        val now = System.currentTimeMillis()
        val pendingReminders = scheduledRemindersDao.getAllPendingAfter(now)

        Log.d(TAG, "Rescheduling ${pendingReminders.size} pending reminders")

        for (reminder in pendingReminders) {
            scheduleAlarm(reminder.id, reminder.triggerTime)
        }
    }

    /**
     * Get a scheduled reminder by ID.
     *
     * @param reminderId The reminder ID
     * @return The reminder or null
     */
    suspend fun getReminder(reminderId: Long): ScheduledReminder? {
        return scheduledRemindersDao.getById(reminderId)
    }

    /**
     * Clean up old reminders.
     * Removes fired/dismissed reminders older than specified time.
     *
     * @param olderThanDays Delete reminders older than this many days
     */
    suspend fun cleanupOldReminders(olderThanDays: Int = 7) {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        scheduledRemindersDao.deleteOldReminders(cutoffTime)
        Log.d(TAG, "Cleaned up old reminders before $cutoffTime")
    }

    /**
     * Parse iCal reminder offset to milliseconds.
     *
     * Delegates to package-level parseReminderOffset() which supports
     * all ISO 8601 duration formats including day-based (P1D) and week-based (P1W).
     *
     * @param offset The offset string (e.g., "-PT15M", "-P1D")
     * @return Milliseconds offset (negative for before), or null if unparseable
     */
    fun parseReminderOffset(offset: String): Long? {
        // Delegate to package-level function (uses fully qualified name to avoid recursion)
        return org.onekash.kashcal.reminder.scheduler.parseReminderOffset(offset)
    }

    /**
     * Create pending intent for alarm.
     */
    private fun createAlarmPendingIntent(reminderId: Long): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER_ALARM
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }

        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_BASE + reminderId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Check if we can schedule exact alarms.
     * For Android 12+, USE_EXACT_ALARM is auto-granted for calendar apps.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
