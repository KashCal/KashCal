package org.onekash.kashcal.reminder.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.reader.selfDeclinedEventIds
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.sync.parser.icaldav.RawIcsParser
import org.onekash.kashcal.util.DateTimeUtils
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// ========== Package-level parsing functions (internal for testing) ==========

private const val TAG_PARSE = "ReminderParse"

// ISO 8601 duration parts, compiled once
private val WEEKS_REGEX = Regex("(\\d+)W")
private val DAYS_REGEX = Regex("(\\d+)D")
private val HOURS_REGEX = Regex("(\\d+)H")
private val MINUTES_REGEX = Regex("(\\d+)M")
private val SECONDS_REGEX = Regex("(\\d+)S")

/**
 * Parses a VALARM trigger offset (an ISO 8601 duration) to milliseconds.
 *
 * Examples:
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
 * Parses an unsigned ISO 8601 duration to milliseconds.
 *
 * Format: P[n]W or P[n]D[T[n]H[n]M[n]S]
 * Examples: P1D, P2W, PT15M, PT1H30M, P1DT2H30M
 *
 * java.time.Duration.parse() is not used: it rejects week durations (P1W).
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

    // Exact arithmetic: an absurd count must return null, never a wrapped value. The
    // `> 0` guard below catches a wrap to negative, but a wrap to positive (e.g.
    // P100000000000000D) would pass as a garbage offset. This callee must not throw:
    // ReminderConverter.isoRemindersToMinutes doesn't catch.
    try {
        // Parse date part: weeks (W), days (D)
        if (datePart.isNotEmpty()) {
            WEEKS_REGEX.find(datePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(it, 7L * 24 * 60 * 60 * 1000))
            }
            DAYS_REGEX.find(datePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(it, 24L * 60 * 60 * 1000))
            }
        }

        // Parse time part: hours (H), minutes (M), seconds (S)
        if (timePart.isNotEmpty()) {
            HOURS_REGEX.find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(it, 60L * 60 * 1000))
            }
            MINUTES_REGEX.find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(it, 60L * 1000))
            }
            SECONDS_REGEX.find(timePart)?.groupValues?.get(1)?.toLongOrNull()?.let {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(it, 1000L))
            }
        }
    } catch (_: ArithmeticException) {
        return null
    }

    // Null if nothing was parsed (an invalid format like "PXYZ"); 0 only for PT0M and PT0S
    return if (totalMillis > 0 || duration == "PT0M" || duration == "PT0S") totalMillis else null
}

/**
 * Returns the trigger time of an all-day event reminder: a signed offset from local midnight.
 *
 * All-day events store startTs as UTC midnight but begin at the user's local midnight.
 * The trigger is the event's local-midnight instant plus the signed RFC 5545 offset
 * (negative = before the start, positive = after), applied as an exact duration so that
 * what KashCal fires equals what is stored and synced (see
 * [DateTimeUtils.allDayReminderTriggerTime]).
 *
 * Offset semantics (chip values): "PT9H" = 9 AM day of event; "-PT15H" = 9 AM day before;
 * "-PT39H" = 9 AM two days before; "-PT0M" = local midnight (start of the event day).
 *
 * @param occurrenceStartTs UTC midnight of the all-day event
 * @param offsetMs The reminder offset in milliseconds (signed: negative = before, positive = after)
 * @param localZone The user's timezone (default: device timezone)
 */
internal fun calculateAllDayTriggerTime(
    occurrenceStartTs: Long,
    offsetMs: Long,
    localZone: ZoneId = ZoneId.systemDefault()
): Long = DateTimeUtils.allDayReminderTriggerTime(occurrenceStartTs, offsetMs, localZone)

/**
 * Arms, re-arms, snoozes and cancels Room event reminders through AlarmManager.
 *
 * A reminder of an occurrence gets a [ScheduledReminder] row and an alarm once it is due within
 * [SCHEDULE_WINDOW_DAYS]. The rows survive in the database, so boot, app update and time changes
 * re-arm them ([rescheduleAllPending]). Alarms use
 * AlarmManager.setExactAndAllowWhileIdle() so they fire on time in Doze ([scheduleAlarm]), with
 * USE_EXACT_ALARM on Android 13+ (auto-granted for calendar apps) and SCHEDULE_EXACT_ALARM on
 * Android 12/12L.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduledRemindersDao: ScheduledRemindersDao,
    private val eventReader: EventReader,
    private val channels: ReminderNotificationChannels,
    private val attendeesDao: AttendeesDao,
    private val accountsDao: AccountsDao,
    private val calendarsDao: CalendarsDao,
    private val occurrencesDao: OccurrencesDao
) {
    companion object {
        private const val TAG = "ReminderScheduler"

        const val ACTION_REMINDER_ALARM = "org.onekash.kashcal.REMINDER_ALARM"

        const val EXTRA_REMINDER_ID = "reminder_id"

        private const val REQUEST_CODE_BASE = 4000

        /**
         * How far ahead reminders are armed, counted on each reminder's trigger
         * time. Android caps an app at 500 pending alarms and there is one alarm
         * per reminder per occurrence, so arming further ahead lets a busy
         * calendar reach the cap. The window is refilled after each reminder
         * fires and by the daily refresh.
         */
        const val SCHEDULE_WINDOW_DAYS = 7

        /**
         * How long before its occurrence a reminder can be set and still be armed.
         * A reminder a week before its event comes into the window once the event
         * is fourteen days out, so occurrences are looked up this far beyond it.
         */
        const val MAX_REMINDER_LEAD_DAYS = 30

        /** How far ahead occurrences are read when looking for reminders to arm. */
        const val OCCURRENCE_LOOKAHEAD_DAYS = SCHEDULE_WINDOW_DAYS + MAX_REMINDER_LEAD_DAYS

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        // A trigger closer to now than this is skipped as past
        private const val MIN_TRIGGER_FUTURE_MS = 5_000L
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Held while an existing row's alarm is re-armed from its stored time, and
     * while [rescheduleAllPending] moves stored times, so a scan never re-arms
     * an existing row at a time a time-zone change is replacing. Creating rows
     * does not take it. Never held around a call that can run inside a
     * database transaction.
     */
    private val rearmMutex = Mutex()

    private fun windowEnd(now: Long): Long = now + SCHEDULE_WINDOW_DAYS * DAY_MS

    /** Returns whether a reminder at [triggerTime] is armed now: not past, and in the window. */
    private fun isArmable(triggerTime: Long, now: Long): Boolean =
        triggerTime >= now + MIN_TRIGGER_FUTURE_MS && triggerTime <= windowEnd(now)

    /** Returns the event's alarm offsets: every VALARM in rawIcal when it has over 3 alarms. */
    private fun reminderOffsetsFor(event: Event): List<String> =
        if (event.alarmCount > 3 && event.rawIcal != null) {
            RawIcsParser.getAllAlarmTriggers(event.rawIcal)
                .takeIf { it.isNotEmpty() }
                ?: event.reminders.orEmpty()
        } else {
            event.reminders.orEmpty()
        }

    private fun triggerTimeFor(occurrenceStartTs: Long, offsetMs: Long, isAllDay: Boolean): Long =
        if (isAllDay) calculateAllDayTriggerTime(occurrenceStartTs, offsetMs) else occurrenceStartTs + offsetMs

    /**
     * Schedules [event]'s reminders for its future [occurrences] that fall due within the window.
     *
     * A reminder that already has a row is left alone.
     *
     * @param calendarColor the calendar color shown on the notification
     */
    suspend fun scheduleRemindersForEvent(
        event: Event,
        occurrences: List<Occurrence>,
        calendarColor: Int
    ) {
        // No stored offsets and no VALARMs: nothing to schedule
        val storedReminders = event.reminders
        if (storedReminders.isNullOrEmpty() && event.alarmCount == 0) {
            Log.d(TAG, "No reminders for event ${event.id}")
            return
        }

        // Determine which alarms to schedule
        val reminderOffsets: List<String> = if (event.alarmCount > 3 && event.rawIcal != null) {
            // More than 3 alarms: read every VALARM from rawIcal
            Log.d(TAG, "Event ${event.id} has ${event.alarmCount} alarms, parsing rawIcal")
            RawIcsParser.getAllAlarmTriggers(event.rawIcal)
                .takeIf { it.isNotEmpty() }
                ?: storedReminders.orEmpty()
        } else {
            storedReminders.orEmpty()
        }

        if (reminderOffsets.isEmpty()) {
            Log.d(TAG, "No reminders after processing for event ${event.id}")
            return
        }

        val now = System.currentTimeMillis()

        for (occurrence in occurrences) {
            // Only future occurrences; each reminder is checked against the window
            if (occurrence.startTs < now) continue

            for (reminderOffset in reminderOffsets) {
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
     * Arms every reminder due within [SCHEDULE_WINDOW_DAYS]: creates the missing ones, and
     * re-arms pending ones whose row already exists (a reboot re-arms only rows inside the
     * window, so a row can be left without an alarm until it comes into range). Re-arming
     * replaces the alarm already set for that row, so running this again adds nothing.
     *
     * Called by ReminderRefreshWorker (daily, and once after boot, app update or a time or
     * time-zone change) and by ReminderAlarmReceiver after each reminder fires.
     *
     * @return the number of new reminders scheduled
     */
    suspend fun scheduleUpcomingReminders(): Int {
        val now = System.currentTimeMillis()

        val offsetsByEventId = mutableMapOf<Long, List<String>>()
        fun offsetsOf(event: Event) = offsetsByEventId.getOrPut(event.id) { reminderOffsetsFor(event) }

        // Most occurrences read are beyond the window. Drop them before any
        // further query; either all-day flag is tried since a changed
        // occurrence may differ from its series.
        val eventsWithReminders = eventReader.getEventsWithRemindersInRange(
            now, now + OCCURRENCE_LOOKAHEAD_DAYS * DAY_MS
        ).filter { eventData ->
            offsetsOf(eventData.event).any { offset ->
                val offsetMs = parseReminderOffset(offset) ?: return@any false
                listOf(false, true).any { allDay ->
                    isArmable(triggerTimeFor(eventData.occurrenceStartTs, offsetMs, allDay), now)
                }
            }
        }

        if (eventsWithReminders.isEmpty()) {
            Log.i(TAG, "No reminders due in the $SCHEDULE_WINDOW_DAYS-day window")
            return 0
        }

        // Self-decline suppresses alarms regardless of the display-side
        // "Show declined" toggle.
        val eventIdToCalendarId = eventsWithReminders
            .associate { it.event.id to it.event.calendarId }
        val declinedAttendees = attendeesDao
            .getDeclinedAttendeesForEvents(eventIdToCalendarId.keys.toList())
        val activeRows = if (declinedAttendees.isEmpty()) {
            eventsWithReminders
        } else {
            val accountsById = accountsDao.getAllOnce().associateBy { it.id }
            val calendarsById = calendarsDao.getAllOnce().associateBy { it.id }
            val declinedIds = selfDeclinedEventIds(
                declinedAttendees = declinedAttendees,
                accountsById = accountsById,
                eventIdToCalendarId = eventIdToCalendarId,
                calendarsById = calendarsById
            )
            if (declinedIds.isEmpty()) eventsWithReminders
            else eventsWithReminders.filterNot { it.event.id in declinedIds }
        }

        var scheduled = 0
        for (eventData in activeRows) {
            // The exception's id for a changed occurrence; event.id when the row has no target
            val targetEventId = eventData.targetEventId ?: eventData.event.id

            // An exception that inherits its master's reminders comes back as the master's row;
            // the notification needs the exception's title, location and isAllDay.
            val displayEvent = if (targetEventId != eventData.event.id) {
                eventReader.getEventById(targetEventId) ?: eventData.event
            } else {
                eventData.event
            }

            for (reminderOffset in offsetsOf(eventData.event)) {
                val wasScheduled = scheduleReminderForOccurrenceIfMissing(
                    displayEvent = displayEvent,
                    targetEventId = targetEventId,
                    occurrenceStartTs = eventData.occurrenceStartTs,
                    reminderOffset = reminderOffset,
                    calendarColor = eventData.calendarColor
                )
                if (wasScheduled) scheduled++
            }
        }

        Log.i(TAG, "Scheduled $scheduled missing reminders in the $SCHEDULE_WINDOW_DAYS-day window")
        return scheduled
    }

    /**
     * Schedules a reminder due within the window if it has no row yet, or re-arms its row if
     * that is still pending.
     *
     * @param displayEvent the event whose title, location and isAllDay the notification shows;
     *   the exception for an exception that inherits its master's reminders.
     * @param targetEventId the event id stored on the row, which a notification tap opens; the
     *   exception's id for a changed occurrence.
     * @return true if a new reminder was scheduled; false if its offset doesn't parse, it is
     *   outside the window, it already had a row, or its alarm was refused
     */
    private suspend fun scheduleReminderForOccurrenceIfMissing(
        displayEvent: Event,
        targetEventId: Long,
        occurrenceStartTs: Long,
        reminderOffset: String,
        calendarColor: Int
    ): Boolean {
        val offsetMs = parseReminderOffset(reminderOffset) ?: return false
        val triggerTime = triggerTimeFor(occurrenceStartTs, offsetMs, displayEvent.isAllDay)
        val now = System.currentTimeMillis()

        // Skip past/immediate triggers, and ones not yet due within the window
        if (!isArmable(triggerTime, now)) return false

        // Already has a row under targetEventId?
        val hadRow = rearmMutex.withLock {
            val existing = scheduledRemindersDao.findExisting(
                eventId = targetEventId,
                occurrenceTime = occurrenceStartTs,
                reminderOffset = reminderOffset
            ) ?: return@withLock false
            // The row may have no alarm (after a reboot only rows inside the window
            // are re-armed). Arming again replaces any alarm it already has. A
            // refused alarm keeps the row, and the next scan tries again.
            val isPending = existing.status == ReminderStatus.PENDING ||
                existing.status == ReminderStatus.SNOOZED
            if (isPending && isArmable(existing.triggerTime, now)) {
                scheduleAlarm(existing.id, existing.triggerTime)
            }
            true
        }
        if (hadRow) return false

        // Stored under targetEventId so a notification tap opens the right event
        val scheduledReminder = ScheduledReminder(
            eventId = targetEventId,
            occurrenceTime = occurrenceStartTs,
            triggerTime = triggerTime,
            reminderOffset = reminderOffset,
            status = ReminderStatus.PENDING,
            eventTitle = displayEvent.title,
            eventLocation = displayEvent.location,
            isAllDay = displayEvent.isAllDay,
            calendarColor = calendarColor
        )

        // A save or another scan may have written it since the check above
        val reminderId = scheduledRemindersDao.insertIfAbsent(scheduledReminder)
        if (reminderId == -1L) return false
        if (!scheduleAlarm(reminderId, triggerTime)) {
            // No alarm behind the row: drop it so a later scan retries it
            scheduledRemindersDao.deleteById(reminderId)
            return false
        }

        Log.d(TAG, "Scheduled missing reminder $reminderId for event $targetEventId (display: ${displayEvent.id})")
        return true
    }

    /**
     * Schedules one reminder for [occurrence] if it is due within the window and has no row.
     *
     * An occurrence linked to an exception stores the exception's id and shows its display
     * data, so a notification tap opens the exception.
     *
     * @param reminderOffset the reminder offset, e.g. "-PT15M"
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

        val triggerTime = triggerTimeFor(occurrence.startTs, offsetMs, event.isAllDay)
        val now = System.currentTimeMillis()

        // Skip a past or immediate trigger
        if (triggerTime < now + MIN_TRIGGER_FUTURE_MS) {
            Log.d(TAG, "Skipping past/immediate reminder for event ${event.id}")
            return
        }

        // Not due within the window yet: the refresh arms it once it is
        if (triggerTime > windowEnd(now)) return

        val targetEventId = occurrence.exceptionEventId ?: event.id

        // The notification shows the exception's title and location, not the master's
        val displayEvent = if (occurrence.exceptionEventId != null) {
            eventReader.getEventById(occurrence.exceptionEventId) ?: event
        } else {
            event
        }

        val existing = scheduledRemindersDao.findExisting(
            eventId = targetEventId,
            occurrenceTime = occurrence.startTs,
            reminderOffset = reminderOffset
        )
        if (existing != null) {
            Log.d(TAG, "Reminder already scheduled: ${existing.id}")
            return
        }

        val scheduledReminder = ScheduledReminder(
            eventId = targetEventId,
            occurrenceTime = occurrence.startTs,
            triggerTime = triggerTime,
            reminderOffset = reminderOffset,
            status = ReminderStatus.PENDING,
            eventTitle = displayEvent.title,
            eventLocation = displayEvent.location,
            isAllDay = displayEvent.isAllDay,
            calendarColor = calendarColor
        )

        // A scan may have written it since the check above
        val reminderId = scheduledRemindersDao.insertIfAbsent(scheduledReminder)
        if (reminderId == -1L) return
        Log.d(TAG, "Created scheduled reminder $reminderId for event $targetEventId (from ${event.id})")

        // Schedule the alarm. If it can't be set, drop the row, so the refresh
        // scan writes it again from the event once alarms have freed up.
        if (!scheduleAlarm(reminderId, triggerTime)) {
            scheduledRemindersDao.deleteById(reminderId)
        }
    }

    /**
     * Sets the alarm for [reminderId] at [triggerTime] (epoch millis), replacing any it has.
     *
     * Uses an exact alarm if allowed, and falls back to an inexact one if exact alarms aren't
     * allowed or throw SecurityException. Inexact alarms may drift 5-15 minutes on some devices.
     *
     * @return true if an exact or inexact alarm was set, false if the platform refused it
     */
    fun scheduleAlarm(reminderId: Long, triggerTime: Long): Boolean {
        val pendingIntent = createAlarmPendingIntent(reminderId)

        return try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact alarm for reminder $reminderId at $triggerTime")
            } else {
                scheduleInexactAlarm(reminderId, triggerTime, pendingIntent)
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm failed, trying inexact", e)
            try {
                scheduleInexactAlarm(reminderId, triggerTime, pendingIntent)
                true
            } catch (e2: SecurityException) {
                Log.e(TAG, "Cannot schedule any alarm for reminder $reminderId", e2)
                // No alarm, and no crash; the caller sees false
                false
            } catch (e2: IllegalStateException) {
                logAlarmLimitRefusal(reminderId, triggerTime, e2)
                false
            }
        } catch (e: IllegalStateException) {
            // The platform caps each app's pending alarms and refuses every
            // further one. The cap counts inexact alarms too, so no fallback.
            logAlarmLimitRefusal(reminderId, triggerTime, e)
            false
        }
    }

    /**
     * One line, no stack trace: once the app is at the alarm limit every later
     * request is refused, so a single sync or refresh can hit this many times.
     */
    private fun logAlarmLimitRefusal(reminderId: Long, triggerTime: Long, e: IllegalStateException) {
        Log.e(TAG, "Reminder $reminderId at $triggerTime not armed: ${e.message}")
    }

    /**
     * Sets an inexact fallback alarm with setAndAllowWhileIdle, which fires in Doze but may
     * drift 5-15 minutes due to alarm batching.
     */
    private fun scheduleInexactAlarm(
        reminderId: Long,
        triggerTime: Long,
        pendingIntent: PendingIntent
    ) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )
        Log.d(TAG, "Scheduled inexact alarm for reminder $reminderId (may drift 5-15 min)")
    }

    /** Cancels [reminderId]'s alarm; its row is left alone. */
    fun cancelAlarm(reminderId: Long) {
        val pendingIntent = createAlarmPendingIntent(reminderId)
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm for reminder $reminderId")
    }

    /** Cancels the alarms of [eventId]'s pending and snoozed reminders and deletes all its rows. */
    suspend fun cancelRemindersForEvent(eventId: Long) {
        val reminders = scheduledRemindersDao.getPendingForEvent(eventId)
        for (reminder in reminders) {
            cancelAlarm(reminder.id)
        }
        scheduledRemindersDao.deleteForEvent(eventId)
        Log.d(TAG, "Cancelled ${reminders.size} reminders for event $eventId")
    }

    /**
     * Cancels the alarms of every pending and snoozed reminder in [calendarId] and deletes all
     * the calendar's reminder rows, in one select and one delete.
     */
    suspend fun cancelRemindersForCalendar(calendarId: Long) {
        val reminders = scheduledRemindersDao.getPendingForCalendar(calendarId)
        for (reminder in reminders) {
            cancelAlarm(reminder.id)
        }
        scheduledRemindersDao.deleteForCalendar(calendarId)
        Log.d(TAG, "Cancelled ${reminders.size} reminders for calendar $calendarId")
    }

    /**
     * Cancels the alarms of one occurrence's pending and snoozed reminders and deletes all its
     * rows.
     *
     * @param occurrenceTime the occurrence start time
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
     * Cancels the reminders of [eventId]'s occurrences at or after [fromTimeMs], for a
     * this-and-future edit or delete that ends the series there.
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

    /** Snoozes [reminderId] for [snoozeDurationMinutes] from now and re-arms its alarm. */
    suspend fun snoozeReminder(reminderId: Long, snoozeDurationMinutes: Int = 15) {
        val newTriggerTime = System.currentTimeMillis() + (snoozeDurationMinutes.toLong() * 60 * 1000)

        scheduledRemindersDao.snooze(reminderId, newTriggerTime)

        cancelAlarm(reminderId)
        scheduleAlarm(reminderId, newTriggerTime)

        Log.d(TAG, "Snoozed reminder $reminderId for $snoozeDurationMinutes minutes")
    }

    /** Marks [reminderId] FIRED. */
    suspend fun markAsFired(reminderId: Long) {
        scheduledRemindersDao.updateStatus(reminderId, ReminderStatus.FIRED)
    }

    /** Marks [reminderId] DISMISSED and cancels its notification. */
    suspend fun markAsDismissed(reminderId: Long) {
        scheduledRemindersDao.updateStatus(reminderId, ReminderStatus.DISMISSED)
        channels.cancelForReminder(reminderId)
    }

    /**
     * Re-arms the pending and snoozed reminders due within [SCHEDULE_WINDOW_DAYS].
     *
     * Called after device boot or app update, after a time or time-zone change, and once by
     * the refresh worker's all-day time-zone migration. An all-day reminder's trigger time is
     * recomputed in the current zone for every pending row, so rows beyond the window are right
     * when they come into range. A row beyond the window whose time moved has its alarm
     * cancelled (an older install armed further ahead); the refresh re-arms it at the new time.
     */
    suspend fun rescheduleAllPending() = rearmMutex.withLock {
        val now = System.currentTimeMillis()
        val windowEnd = windowEnd(now)
        val pendingReminders = scheduledRemindersDao.getAllPendingAfter(now)
        val localZone = ZoneId.systemDefault()

        Log.d(TAG, "Rescheduling ${pendingReminders.size} pending reminders (tz: ${localZone.id})")

        for (reminder in pendingReminders) {
            val effectiveTriggerTime = if (reminder.isAllDay) {
                // Recompute in the current zone
                val offsetMs = parseReminderOffset(reminder.reminderOffset)
                if (offsetMs != null) {
                    calculateAllDayTriggerTime(reminder.occurrenceTime, offsetMs, localZone)
                } else {
                    reminder.triggerTime
                }
            } else {
                reminder.triggerTime  // Timed events: same UTC instant
            }

            // Store the moved time
            val moved = effectiveTriggerTime != reminder.triggerTime
            if (moved) {
                scheduledRemindersDao.updateTriggerTime(reminder.id, effectiveTriggerTime)
                Log.d(TAG, "Updated trigger time for reminder ${reminder.id}: ${reminder.triggerTime} -> $effectiveTriggerTime")
            }

            if (effectiveTriggerTime > windowEnd) {
                if (moved) cancelAlarm(reminder.id)
            } else if (effectiveTriggerTime > now) {
                scheduleAlarm(reminder.id, effectiveTriggerTime)
            }
        }
    }

    /** Returns the reminder with [reminderId], or null. */
    suspend fun getReminder(reminderId: Long): ScheduledReminder? {
        return scheduledRemindersDao.getById(reminderId)
    }

    /**
     * Returns the ids of the other reminders on the same occurrence as [reminder], so a firing
     * reminder can clear their notifications. See the query for the predicate.
     */
    suspend fun getSiblingReminderIds(reminder: ScheduledReminder): List<Long> {
        return scheduledRemindersDao.getSiblingIdsForOccurrence(
            eventId = reminder.eventId,
            occurrenceTime = reminder.occurrenceTime,
            excludeId = reminder.id
        )
    }

    /**
     * Returns whether a reminder for [eventId] should still fire: false if the event no longer
     * exists or is awaiting deletion.
     *
     * Reminder rows carry their own copy of the event's display data, so the notification is
     * built without reading the event. An armed alarm therefore fires even after its whole
     * event is deleted (row gone) or soft-deleted (awaiting server deletion); this re-checks
     * the event at fire time so a stale alarm is suppressed.
     *
     * Covers every Room-backed event (local, iCloud, CalDAV, ICS, contact birthdays,
     * anniversaries), since they all live in the events table.
     *
     * This is a whole-event check. A cancelled occurrence of a live series (e.g. an EXDATE
     * added by a server pull) leaves the master live, so this returns true for it;
     * [hasLiveOccurrenceForReminder] checks the occurrence.
     */
    suspend fun shouldFireReminder(eventId: Long): Boolean {
        val event = eventReader.getEventById(eventId) ?: return false
        return !event.isPendingDelete
    }

    /**
     * Returns whether a non-cancelled occurrence backs [reminder]: false if the occurrence was
     * cancelled, its row is gone, or the event is missing.
     *
     * The occurrence-level companion to [shouldFireReminder]. When an organizer cancels one
     * occurrence of a series through a background CalDAV pull, the master stays live, so
     * [shouldFireReminder] passes, yet that occurrence's reminder must not fire. Two forms
     * reach here:
     *  - EXDATE on the master: [org.onekash.kashcal.domain.generator.OccurrenceGenerator]
     *    regenerates the series' rows without the excluded instant, so no row exists at the
     *    slot.
     *  - A cancelled exception, or an occurrence cancelled locally: the row remains with
     *    `is_cancelled = 1`.
     *
     * The lookup key differs by event kind, which is the trap. A reminder for a changed
     * occurrence is stored under the exception's id ([ScheduledReminder.eventId]), but the
     * occurrence row stores `event_id` = master and `exception_event_id` = exception. So an
     * exception's row is found by its FK ([OccurrencesDao.getByExceptionEventId]); otherwise by
     * `(event_id, occurrenceTime)` within 60 seconds. `getOccurrenceNearTime(reminder.eventId,
     * ...)` would return null for every exception and suppress valid reminders.
     */
    suspend fun hasLiveOccurrenceForReminder(reminder: ScheduledReminder): Boolean {
        val event = eventReader.getEventById(reminder.eventId) ?: return false
        val occurrence = if (event.isException) {
            occurrencesDao.getByExceptionEventId(reminder.eventId)
        } else {
            occurrencesDao.getOccurrenceNearTime(reminder.eventId, reminder.occurrenceTime)
        }
        return occurrence != null && !occurrence.isCancelled
    }

    /** Deletes fired and dismissed reminders that triggered more than [olderThanDays] days ago. */
    suspend fun cleanupOldReminders(olderThanDays: Int = 7) {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000)
        scheduledRemindersDao.deleteOldReminders(cutoffTime)
        Log.d(TAG, "Cleaned up old reminders before $cutoffTime")
    }

    /**
     * Parses a reminder offset such as "-PT15M" or "-P1D" to milliseconds (negative for
     * before), or null if unparseable.
     *
     * Delegates to the package-level `parseReminderOffset`, which also takes day (P1D) and
     * week (P1W) durations.
     */
    fun parseReminderOffset(offset: String): Long? {
        // Delegate to package-level function (uses fully qualified name to avoid recursion)
        return org.onekash.kashcal.reminder.scheduler.parseReminderOffset(offset)
    }

    /**
     * Creates the explicit broadcast PendingIntent for [reminderId]'s alarm. The request code
     * is derived from the id, so arming a row again replaces its alarm.
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
     * Returns whether exact alarms are allowed. USE_EXACT_ALARM (auto-granted for calendar
     * apps) covers Android 13+; SCHEDULE_EXACT_ALARM, granted at install, covers Android 12/12L.
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
