package org.onekash.kashcal.reminder.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

        /** [SCHEDULE_WINDOW_DAYS] in milliseconds. */
        const val SCHEDULE_WINDOW_MS = SCHEDULE_WINDOW_DAYS * 24 * 60 * 60 * 1000L

        /**
         * How long before its occurrence a reminder can be set and still be armed.
         * A reminder a week before its event comes into the window once the event
         * is fourteen days out, so occurrences are looked up this far beyond it.
         */
        const val MAX_REMINDER_LEAD_DAYS = 30

        /** How far ahead occurrences are read when looking for reminders to arm. */
        const val OCCURRENCE_LOOKAHEAD_DAYS = SCHEDULE_WINDOW_DAYS + MAX_REMINDER_LEAD_DAYS

        /**
         * How long ago an occurrence may have started and still have its reminders armed; each
         * reminder is then armed only if its own time is ahead. An all-day occurrence is stored
         * at UTC midnight while its alarms count from local midnight (RFC 5545 §3.8.6.3), at
         * most 12 hours later, so 36 hours covers any day-of offset under 24 hours in any zone,
         * and a timed reminder up to 36 hours after its start. An alarm later than that after
         * its occurrence's start (a later day of a multi-day event) is armed only if its row
         * was made before then: occurrences that started earlier are left out so the scan
         * stays on the start_ts index.
         */
        const val STARTED_LOOKBACK_MS = 36 * 60 * 60 * 1000L

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        // A trigger closer to now than this is skipped as past
        private const val MIN_TRIGGER_FUTURE_MS = 5_000L
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * Held while an existing row's alarm is re-armed from its stored time, while
     * [rescheduleAllPending] moves stored times, and while a mute's alarms are cancelled
     * ([silenceRemindersForCalendar], [sweepMutedCalendars]) or an unmute's restored
     * ([restoreRemindersForCalendar]), so a scan never re-arms
     * an existing row at a time a time-zone change is replacing. Creating rows
     * does not take it. Never held around a call that can run inside a
     * database transaction.
     */
    private val rearmMutex = Mutex()

    private fun windowEnd(now: Long): Long = now + SCHEDULE_WINDOW_MS

    /** Returns whether a reminder at [triggerTime] is armed now: not past, and in the window. */
    private fun isArmable(triggerTime: Long, now: Long): Boolean =
        triggerTime >= now + MIN_TRIGGER_FUTURE_MS && triggerTime <= windowEnd(now)

    /**
     * Returns the event's alarm offsets: every VALARM in its stored copy when it has over 3
     * alarms, since [Event.reminders] holds at most 5; [Event.reminders] for a file's copy
     * ([Event.holdsFileCopy]), whose alarms are the file's, not the user's.
     */
    private fun reminderOffsetsFor(event: Event): List<String> =
        if (event.alarmCount > 3 && event.rawIcal != null && !event.holdsFileCopy) {
            RawIcsParser.getAllAlarmTriggers(event.rawIcal)
                .takeIf { it.isNotEmpty() }
                ?: event.reminders.orEmpty()
        } else {
            event.reminders.orEmpty()
        }

    private fun triggerTimeFor(occurrenceStartTs: Long, offsetMs: Long, isAllDay: Boolean): Long =
        if (isAllDay) calculateAllDayTriggerTime(occurrenceStartTs, offsetMs) else occurrenceStartTs + offsetMs

    /**
     * Schedules [event]'s reminders for its [occurrences] within [STARTED_LOOKBACK_MS], arming
     * each reminder whose own time falls due within the window.
     *
     * A reminder that already has a row is left alone. Nothing is scheduled when the event's
     * calendar is muted ([org.onekash.kashcal.data.db.entity.Calendar.isNotificationMuted]); the
     * check lives here so every save and sync caller gets it.
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

        val reminderOffsets = reminderOffsetsFor(event)

        if (reminderOffsets.isEmpty()) {
            Log.d(TAG, "No reminders after processing for event ${event.id}")
            return
        }

        // An exception carries its master's calendarId, so this covers it too
        if (calendarsDao.getById(event.calendarId)?.isNotificationMuted == true) {
            Log.d(TAG, "Calendar of event ${event.id} is muted, scheduling nothing")
            return
        }

        val now = System.currentTimeMillis()

        for (occurrence in occurrences) {
            // An exception's occurrence arrives unfiltered; each reminder is then checked by its
            // own time
            if (occurrence.startTs < now - STARTED_LOOKBACK_MS) continue

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
     * Occurrences are read from [STARTED_LOOKBACK_MS] ago.
     *
     * Muted calendars get nothing: the scan first sweeps any alarm still armed for one
     * ([sweepMutedCalendars]), and the occurrence query leaves them out.
     *
     * Called by ReminderRefreshWorker (daily, and once after boot, app update or a time or
     * time-zone change) and by ReminderAlarmReceiver after each reminder fires.
     *
     * @return the number of new reminders scheduled
     */
    suspend fun scheduleUpcomingReminders(): Int {
        sweepMutedCalendars()

        val now = System.currentTimeMillis()

        val offsetsByEventId = mutableMapOf<Long, List<String>>()
        fun offsetsOf(event: Event) = offsetsByEventId.getOrPut(event.id) { reminderOffsetsFor(event) }

        // Most occurrences read are beyond the window. Drop them before any
        // further query; either all-day flag is tried since a changed
        // occurrence may differ from its series.
        val eventsWithReminders = eventReader.getEventsWithRemindersInRange(
            now - STARTED_LOOKBACK_MS, now + OCCURRENCE_LOOKAHEAD_DAYS * DAY_MS
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
     * Cancels the alarms still armed for muted calendars' pending and snoozed reminders, left by a
     * scan or feed refresh that read the calendar before a mute, by a boot re-arm, or by a toggle
     * that failed. The rows are kept for the unmute ([restoreRemindersForCalendar]); those whose
     * time has passed are dismissed, so they can't post late after an unmute and are cleaned up
     * with the other dismissed rows.
     *
     * Runs before this scan re-arms any existing row, so the scan never arms a muted row only for
     * it to be cancelled (each such alarm costs a wake-up). Each calendar is checked and cancelled
     * under [rearmMutex], so an unmute's restore can't land between the check and the cancel: it
     * runs before the check (the calendar reads as unmuted and is skipped) or after the cancel
     * (and arms again). A failure is logged and the scan goes on: it must never cost the other
     * calendars their alarms.
     */
    private suspend fun sweepMutedCalendars() {
        try {
            val calendarIds = scheduledRemindersDao.getMutedCalendarIdsWithPendingReminders()
            for (calendarId in calendarIds) {
                rearmMutex.withLock {
                    val rows = scheduledRemindersDao.getPendingForMutedCalendar(calendarId)
                    if (rows.isEmpty()) return@withLock
                    if (calendarsDao.getById(calendarId)?.isNotificationMuted != true) return@withLock
                    val now = System.currentTimeMillis()
                    val (passed, ahead) = rows.partition { it.triggerTime <= now }
                    if (passed.isNotEmpty()) {
                        scheduledRemindersDao.dismissIfCalendarMuted(passed.map { it.id })
                    }
                    ahead.forEach { cancelAlarm(it.id) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not sweep muted calendars' reminders", e)
        }
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

    /**
     * Reads [calendarId]'s pending and snoozed reminders, runs [delete], and then cancels those
     * reminders' alarms; returns what [delete] returned. If [delete] throws, no alarm is cancelled.
     *
     * For deleting the calendar row, whose cascade removes the reminder rows: the alarms are
     * cancelled only once the rows are gone, so a failed delete keeps every reminder. An alarm is
     * keyed by its reminder id alone ([createAlarmPendingIntent]), so a row re-armed in between
     * is cancelled too. A row created in between, or re-armed after the cancel by a caller that
     * read it before the delete, keeps a stray alarm; ReminderAlarmReceiver finds no row when it
     * fires and posts nothing. Takes no lock.
     */
    suspend fun <T> cancelCalendarAlarmsAfter(calendarId: Long, delete: suspend () -> T): T {
        val reminderIds = scheduledRemindersDao.getPendingForCalendar(calendarId).map { it.id }
        val result = delete()
        reminderIds.forEach { cancelAlarm(it) }
        Log.d(TAG, "Cancelled ${reminderIds.size} alarms of deleted calendar $calendarId")
        return result
    }

    /**
     * Deletes all of [eventId]'s reminder rows, cancelling the alarms of the pending and snoozed
     * ones and the notifications of the fired ones. For an event that is gone, declined or
     * cancelled; an edit keeps the rows still valid ([reconcileRemindersForEvent]).
     */
    suspend fun cancelRemindersForEvent(eventId: Long) {
        val reminders = scheduledRemindersDao.getAllForEvent(eventId)
        reminders.forEach { cancelAlarmAndNotification(it) }
        scheduledRemindersDao.deleteForEvent(eventId)
        Log.d(TAG, "Cancelled ${reminders.size} reminders for event $eventId")
    }

    /**
     * Brings [event]'s reminder rows in line with the event as an edit stored it, keeping each
     * row that still applies with its id, so a notification on screen keeps a working Snooze
     * (its actions carry the row id) and a snoozed reminder, which no scheduling path can create
     * again, still fires. Callers run it after the event and its occurrences are stored, then
     * [scheduleRemindersForEvent] for the reminders with no row.
     *
     * A row's occurrence must still exist, uncancelled, at exactly its occurrence_time (rows are
     * keyed by the exact start, and linking an exception moves its occurrence's start). A
     * snoozed row needs only that: a snooze is the user's own alarm (RFC 9074 §7) and outlives
     * the offset it came from. Any other row also needs its offset among the event's, compared
     * by value and respelled to the event's string so [scheduleRemindersForEvent] finds it; an
     * exception's rows are written with its own offsets and with its master's (the master's
     * step covers every occurrence, and the window scan uses the master's when the exception
     * has none), so both count. Every other row is deleted with its alarm and, if it fired, its
     * notification.
     *
     * A kept pending row takes its time recomputed from occurrence and offset (an all-day
     * reminder moves with a time-zone change). Kept pending and snoozed rows are re-read before
     * arming, so a time-zone reschedule or a snooze that changed the row since is not undone;
     * one landing between the re-read and the arm still can be. Muting is read once, so an
     * unmute landing mid-run can leave a kept row unarmed until the next window scan. Kept rows
     * get the event's display copy, which the notification shows, unless the calendar is gone.
     *
     * Takes no lock: a feed refresh calls it inside a database transaction. If that transaction
     * rolls back, the deleted rows come back but their alarms and notifications don't; the next
     * window scan re-arms pending ones.
     */
    suspend fun reconcileRemindersForEvent(event: Event) {
        val rows = scheduledRemindersDao.getAllForEvent(event.id)
        if (rows.isEmpty()) return

        val liveStarts = if (event.isException) {
            listOfNotNull(occurrencesDao.getByExceptionEventId(event.id))
        } else {
            occurrencesDao.getForEvent(event.id).filter { it.exceptionEventId == null }
        }.filterNot { it.isCancelled }.map { it.startTs }.toSet()
        val master = event.originalEventId?.let { eventReader.getEventById(it) }
        val offsets = reminderOffsetsFor(event).toSet() +
            master?.let { reminderOffsetsFor(it) }.orEmpty()
        // The event form and the pull spell some offsets differently (-PT24H and -P1D, PT0M
        // and PT0S), so a row matches by value; the event's own spelling wins over its master's.
        val offsetByValue = offsets.reversed().mapNotNull { offset ->
            parseReminderOffset(offset)?.let { it to offset }
        }.toMap()
        val calendar = calendarsDao.getById(event.calendarId)
        val muted = calendar?.isNotificationMuted == true
        val now = System.currentTimeMillis()

        var dropped = 0
        for (row in rows) {
            val spelling = if (row.reminderOffset in offsets) {
                row.reminderOffset
            } else {
                parseReminderOffset(row.reminderOffset)?.let { offsetByValue[it] }
            }
            val keep = row.occurrenceTime in liveStarts &&
                (row.status == ReminderStatus.SNOOZED || spelling != null)
            // A respelled row that collides with a row already under the new spelling is a
            // second copy of the same reminder.
            val duplicate = keep && spelling != null && spelling != row.reminderOffset &&
                scheduledRemindersDao.updateOffset(row.id, spelling) == 0
            if (!keep || duplicate) {
                cancelAlarmAndNotification(row)
                scheduledRemindersDao.deleteById(row.id)
                dropped++
                continue
            }
            if (row.status != ReminderStatus.PENDING && row.status != ReminderStatus.SNOOZED) continue

            var trigger = row.triggerTime
            var moved = false
            if (row.status == ReminderStatus.PENDING) {
                val offsetMs = parseReminderOffset(row.reminderOffset)
                val recomputed = offsetMs?.let { triggerTimeFor(row.occurrenceTime, it, event.isAllDay) }
                if (recomputed != null && recomputed != trigger) {
                    scheduledRemindersDao.updateTriggerTime(row.id, recomputed)
                    trigger = recomputed
                    moved = true
                }
            }
            when {
                muted -> cancelAlarm(row.id)
                isArmable(trigger, now) -> {
                    val current = scheduledRemindersDao.getById(row.id)
                    if (current?.status == row.status && current.triggerTime == trigger) {
                        scheduleAlarm(row.id, trigger)
                    }
                }
                moved -> cancelAlarm(row.id)
            }
        }

        if (calendar != null) {
            scheduledRemindersDao.updateDisplayForEvent(
                eventId = event.id,
                title = event.title,
                location = event.location,
                isAllDay = event.isAllDay,
                calendarColor = calendar.color
            )
        }
        Log.d(TAG, "Kept ${rows.size - dropped} and dropped $dropped reminders for event ${event.id}")
    }

    /**
     * Cancels [reminder]'s alarm if it may have one (pending or snoozed) and its notification if
     * it posted one that may still show (fired); its row is left alone.
     */
    private fun cancelAlarmAndNotification(reminder: ScheduledReminder) {
        when (reminder.status) {
            ReminderStatus.PENDING, ReminderStatus.SNOOZED -> cancelAlarm(reminder.id)
            ReminderStatus.FIRED -> channels.cancelForReminder(reminder.id)
            else -> Unit
        }
    }

    /**
     * Cancels the alarms of [calendarId]'s pending and snoozed reminders and keeps the rows, so
     * unmuting can arm them again ([restoreRemindersForCalendar]): the window scan alone can't
     * rebuild a reminder whose original time has passed (a snoozed one) or one of an occurrence
     * that started more than [STARTED_LOOKBACK_MS] ago. Called when the calendar is muted.
     */
    suspend fun silenceRemindersForCalendar(calendarId: Long) {
        rearmMutex.withLock {
            val reminders = scheduledRemindersDao.getPendingForCalendar(calendarId)
            reminders.forEach { cancelAlarm(it.id) }
            Log.d(TAG, "Silenced ${reminders.size} reminders for calendar $calendarId")
        }
    }

    /**
     * Re-arms [calendarId]'s kept pending and snoozed reminders whose time is still ahead and in
     * the window. Called when the calendar is unmuted, before the window scan creates the rows it
     * never had. A row whose time has passed is left alone, so nothing posts late: it may still
     * have an alarm on its way, if a quick mute and unmute meant the calendar was never silenced,
     * and its time passing while muted is the sweep's to dismiss ([sweepMutedCalendars]).
     */
    suspend fun restoreRemindersForCalendar(calendarId: Long) {
        rearmMutex.withLock {
            val now = System.currentTimeMillis()
            for (reminder in scheduledRemindersDao.getPendingForCalendar(calendarId)) {
                if (reminder.triggerTime <= now || reminder.triggerTime > windowEnd(now)) continue
                if (!scheduleAlarm(reminder.id, reminder.triggerTime)) {
                    Log.w(TAG, "Could not re-arm kept reminder ${reminder.id} on unmute")
                }
            }
        }
    }

    /**
     * Cancels the alarms of every pending and snoozed reminder in [calendarId] and deletes all
     * the calendar's reminder rows, in one select and one delete. Called when the calendar's
     * account is removed; muting keeps the rows ([silenceRemindersForCalendar]).
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
     * Deletes all of one occurrence's reminder rows, cancelling the alarms of the pending and
     * snoozed ones and the notifications of the fired ones.
     *
     * @param occurrenceTime the occurrence start time
     */
    suspend fun cancelReminderForOccurrence(eventId: Long, occurrenceTime: Long) {
        scheduledRemindersDao.getAllForEvent(eventId)
            .filter { it.occurrenceTime == occurrenceTime }
            .forEach { cancelAlarmAndNotification(it) }
        scheduledRemindersDao.deleteForOccurrence(eventId, occurrenceTime)
        Log.d(TAG, "Cancelled reminders for occurrence at $occurrenceTime")
    }

    /**
     * Deletes the reminders of [eventId]'s occurrences at or after [fromTimeMs], cancelling their
     * alarms and fired notifications, for a this-and-future edit or delete that ends the series
     * there.
     */
    suspend fun cancelRemindersForOccurrencesAfter(eventId: Long, fromTimeMs: Long) {
        val reminders = scheduledRemindersDao.getAllForEvent(eventId)
            .filter { it.occurrenceTime >= fromTimeMs }
        reminders.forEach { cancelAlarmAndNotification(it) }
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
     * exists, is awaiting deletion, or is CANCELLED and was created or cancelled by an ICS
     * import ([Event.importSource]); a cancelled event no import touched still fires.
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
        if (event.isPendingDelete) return false
        // An ICS import created or cancelled it: its CANCEL stops the reminders.
        return !(event.status == "CANCELLED" && event.importSource != null)
    }

    /**
     * Returns whether a non-cancelled occurrence backs [reminder]: false if the occurrence was
     * cancelled, its row is gone, the event is missing, or it is a CANCELLED changed occurrence
     * that an ICS import created or cancelled, or whose series one created.
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
        if (event.isException && event.status == "CANCELLED") {
            // A changed occurrence an ICS import created or cancelled, or under an imported series.
            val masterImported = event.originalEventId?.let { eventReader.getEventById(it) }?.importSource != null
            if (event.importSource != null || masterImported) return false
        }
        val occurrence = if (event.isException) {
            occurrencesDao.getByExceptionEventId(reminder.eventId)
        } else {
            occurrencesDao.getOccurrenceNearTime(reminder.eventId, reminder.occurrenceTime)
        }
        return occurrence != null && !occurrence.isCancelled
    }

    /**
     * Returns whether [reminder]'s calendar is muted
     * ([org.onekash.kashcal.data.db.entity.Calendar.isNotificationMuted]), read fresh; false when
     * its event or calendar is gone, a case [shouldFireReminder] owns.
     *
     * The fire-time authority for mute: arming skips muted calendars, but an alarm armed by a scan
     * or feed refresh that read the calendar before the toggle, a snooze, or a kept row re-armed
     * at boot or after a time or time-zone change can still fire. The receiver then marks the row
     * FIRED rather than deleting it, so the scan that runs next can't create it again
     * (findExisting ignores status).
     */
    suspend fun isCalendarMutedForReminder(reminder: ScheduledReminder): Boolean {
        val event = eventReader.getEventById(reminder.eventId) ?: return false
        return calendarsDao.getById(event.calendarId)?.isNotificationMuted == true
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
