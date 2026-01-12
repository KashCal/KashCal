package org.onekash.kashcal.sync.parser

import android.util.Log
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.Related
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Clazz
import net.fortuna.ical4j.model.property.Transp
import net.fortuna.ical4j.model.Recur
import org.onekash.kashcal.util.TimezoneUtils
import java.io.StringReader
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * RFC 5545 iCal parser using ical4j library.
 *
 * This is the production parser for KashCal. It handles:
 * - All datetime formats (UTC, TZID, floating, DATE)
 * - RRULE parsing with all frequency types
 * - EXDATE/RDATE handling
 * - RECURRENCE-ID for modified instances
 * - VALARM with various trigger formats
 * - Line folding/unfolding (automatic via ical4j)
 * - Escaped characters (automatic via ical4j)
 */
class Ical4jParser @Inject constructor() : ICalParser {

    companion object {
        private const val TAG = "Ical4jParser"

        // Day code format for EXDATE/RDATE
        private val DAY_CODE_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun parse(icalData: String): ParseResult {
        if (icalData.isBlank()) {
            return ParseResult.empty()
        }

        return try {
            val builder = CalendarBuilder()
            val calendar = builder.build(StringReader(icalData))

            val events = calendar.getComponents<VEvent>(Component.VEVENT)
                .mapNotNull { vevent ->
                    try {
                        parseVEvent(vevent, icalData)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse VEVENT: ${e.message}")
                        null
                    }
                }

            ParseResult.success(events)
        } catch (e: ParserException) {
            Log.e(TAG, "iCal parse error: ${e.message}")
            ParseResult.failure("Parse error at line ${e.lineNo}: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected parse error: ${e.message}", e)
            ParseResult.failure("Unexpected error: ${e.message}")
        }
    }

    private fun parseVEvent(vevent: VEvent, rawIcal: String): ParsedEvent? {
        // UID is required
        val uid = vevent.uid?.value
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "Skipping VEVENT without UID")
            return null
        }

        // DTSTART is required
        val dtStart = vevent.startDate ?: run {
            Log.w(TAG, "Skipping VEVENT without DTSTART: $uid")
            return null
        }

        // Parse RECURRENCE-ID for exceptions
        val recurrenceId = vevent.recurrenceId?.let { formatRecurrenceId(it) }

        // Parse datetime
        val isAllDay = dtStart.date !is DateTime
        // RFC 5545: VALUE=DATE represents a calendar date, not a moment in time.
        // ical4j's Date.time uses JVM default timezone, which can shift the date.
        // Re-interpret as UTC for all-day events to preserve the calendar date.
        val startTs = if (isAllDay) {
            dateToUtcMidnight(dtStart.date)
        } else {
            dtStart.date.time / 1000
        }
        // Canonicalize timezone to standard IANA form (e.g., US/Pacific → America/Los_Angeles)
        val rawTimezone = dtStart.getParameter<TzId>(Parameter.TZID)?.value
        val timezone = TimezoneUtils.canonicalizeTimezone(rawTimezone)

        // Calculate end time
        val endTs = calculateEndTs(vevent, startTs, isAllDay)

        // Parse RRULE
        val rrule = vevent.getProperty<RRule>(Property.RRULE)
        val rruleString = rrule?.value

        // Parse EXDATE
        val exdates = vevent.getProperties<ExDate>(Property.EXDATE)
            .flatMap { exdate -> exdate.dates.map { formatDayCode(it) } }
            .distinct()

        // Parse RDATE
        val rdates = vevent.getProperties<RDate>(Property.RDATE)
            .flatMap { rdate -> rdate.dates.map { formatDayCode(it) } }
            .distinct()

        // Parse VALARMs
        val reminders = vevent.alarms
            .mapNotNull { parseValarm(it) }
            .distinct()
            .sorted()
            .take(3)

        // Parse DTSTAMP
        val dtstamp = vevent.dateStamp?.date?.time?.div(1000) ?: (System.currentTimeMillis() / 1000)

        // Parse ORGANIZER
        val organizer = vevent.organizer
        val organizerEmail = organizer?.calAddress?.toString()
            ?.removePrefix("mailto:")
            ?.removePrefix("MAILTO:")
        val organizerName = organizer?.getParameter<net.fortuna.ical4j.model.parameter.Cn>(Parameter.CN)?.value

        // Parse TRANSP (time transparency: OPAQUE/TRANSPARENT)
        val transp = vevent.getProperty<Transp>(Property.TRANSP)?.value

        // Parse CLASS (access classification: PUBLIC/PRIVATE/CONFIDENTIAL)
        val classification = vevent.getProperty<Clazz>(Property.CLASS)?.value

        // Parse X-* properties for round-trip preservation
        val extraProperties = extractExtraProperties(vevent)

        return ParsedEvent(
            uid = uid,
            recurrenceId = recurrenceId,
            summary = vevent.summary?.value ?: "Untitled",
            description = vevent.description?.value ?: "",
            location = vevent.location?.value ?: "",
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            timezone = timezone,
            rrule = rruleString,
            exdates = exdates,
            rdates = rdates,
            reminderMinutes = reminders,
            sequence = vevent.sequence?.sequenceNo ?: 0,
            status = vevent.status?.value,
            dtstamp = dtstamp,
            organizerEmail = organizerEmail,
            organizerName = organizerName,
            transp = transp,
            classification = classification,
            extraProperties = extraProperties,
            rawIcal = rawIcal
        )
    }

    private fun calculateEndTs(vevent: VEvent, startTs: Long, isAllDay: Boolean): Long {
        // Try DTEND first
        vevent.endDate?.let { dtEnd ->
            // Use UTC for all-day dates to preserve calendar date
            val rawEndTs = if (isAllDay) {
                dateToUtcMidnight(dtEnd.date)
            } else {
                dtEnd.date.time / 1000
            }
            // RFC 5545: DTEND is exclusive for all-day events (day AFTER event)
            // Subtract 1 second to get inclusive end (matches RfcIcsParser behavior)
            return if (isAllDay && rawEndTs > startTs) {
                rawEndTs - 1
            } else {
                rawEndTs
            }
        }

        // Try DURATION
        vevent.duration?.duration?.let { duration ->
            val durationSeconds = durationToSeconds(duration)
            return startTs + durationSeconds
        }

        // Default: 1 hour for timed events, same day for all-day
        return if (isAllDay) {
            startTs + 86400 - 1  // End of same day (86400 - 1 second)
        } else {
            startTs + 3600   // 1 hour default
        }
    }

    /**
     * Convert ical4j Date to UTC midnight epoch seconds.
     *
     * RFC 5545: VALUE=DATE represents a calendar date, not an instant in time.
     * We parse the date string directly to avoid timezone pollution from ical4j's
     * internal Date.getTime() which can shift dates based on device timezone.
     *
     * Uses java.time.LocalDate per Android recommendation (preferred over java.util.Date).
     *
     * Example: "20251225" should always be Dec 25 00:00:00 UTC, regardless of device TZ.
     */
    private fun dateToUtcMidnight(date: net.fortuna.ical4j.model.Date): Long {
        // Parse date string directly - ical4j Date.toString() returns "yyyyMMdd" format
        val dateStr = date.toString()
        val localDate = LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE)
        return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().epochSecond
    }

    private fun durationToSeconds(duration: java.time.temporal.TemporalAmount): Long {
        return try {
            when (duration) {
                is java.time.Duration -> duration.seconds
                is java.time.Period -> {
                    val days = duration.days.toLong() + (duration.months * 30L) + (duration.years * 365L)
                    days * 86400
                }
                else -> 3600L // 1 hour default
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse duration: $duration")
            3600L
        }
    }

    private fun formatRecurrenceId(recId: RecurrenceId): String {
        val date = recId.date
        return if (date is DateTime) {
            // Format as ISO datetime with Z
            val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(date)
        } else {
            // All-day: parse date string directly to avoid timezone pollution
            // ical4j Date.toString() returns "yyyyMMdd" format
            date.toString().substring(0, 8)
        }
    }

    private fun formatDayCode(date: net.fortuna.ical4j.model.Date): String {
        // Parse date string directly to avoid timezone pollution
        // ical4j Date.toString() returns "yyyyMMdd" format for VALUE=DATE
        val dateStr = date.toString()
        return if (dateStr.length >= 8) {
            dateStr.substring(0, 8)  // Extract yyyyMMdd portion
        } else {
            // Fallback for DateTime - use UTC formatter
            synchronized(DAY_CODE_FORMAT) {
                DAY_CODE_FORMAT.format(date)
            }
        }
    }

    private fun parseValarm(alarm: VAlarm): Int? {
        val trigger = alarm.trigger ?: return null

        // Skip RELATED=END triggers
        val related = trigger.getParameter<Related>(Parameter.RELATED)
        if (related?.value == "END") {
            return null
        }

        val duration = trigger.duration ?: return null

        return try {
            // ical4j 3.x uses net.fortuna.ical4j.model.Dur or java.time.temporal.TemporalAmount
            val totalMinutes = when (duration) {
                is java.time.Duration -> {
                    val minutes = duration.toMinutes()
                    // Negative means "before" which is what we want
                    if (minutes >= 0) return null
                    (-minutes).toInt()
                }
                is java.time.Period -> {
                    // Periods are typically negative for "before"
                    val days = -(duration.days + (duration.months * 30) + (duration.years * 365))
                    if (days < 0) return null
                    days * 24 * 60
                }
                else -> {
                    // Try to parse as string: -PT15M, -P1D, etc.
                    parseDurationString(duration.toString())
                }
            }
            totalMinutes?.coerceIn(0, 40320)  // Max 4 weeks
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse VALARM trigger: ${e.message}")
            null
        }
    }

    private fun parseDurationString(durationStr: String): Int? {
        // Parse RFC 5545 duration strings like -PT15M, -P1D, -PT1H30M
        if (!durationStr.startsWith("-P")) {
            return null  // Only negative (before) durations
        }

        val str = durationStr.substring(2)  // Remove "-P"
        var totalMinutes = 0

        // Check for T (time component)
        val timePart = if (str.contains("T")) {
            str.substringAfter("T")
        } else null

        val datePart = if (str.contains("T")) {
            str.substringBefore("T")
        } else str

        // Parse date part: 1D, 2W, etc.
        if (datePart.isNotEmpty()) {
            val weeksMatch = Regex("(\\d+)W").find(datePart)
            val daysMatch = Regex("(\\d+)D").find(datePart)

            weeksMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalMinutes += it * 7 * 24 * 60
            }
            daysMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalMinutes += it * 24 * 60
            }
        }

        // Parse time part: 1H, 30M, 15S
        if (!timePart.isNullOrEmpty()) {
            val hoursMatch = Regex("(\\d+)H").find(timePart)
            val minutesMatch = Regex("(\\d+)M").find(timePart)
            val secondsMatch = Regex("(\\d+)S").find(timePart)

            hoursMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalMinutes += it * 60
            }
            minutesMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalMinutes += it
            }
            secondsMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalMinutes += it / 60
            }
        }

        return if (totalMinutes > 0) totalMinutes else null
    }

    /**
     * Extract X-* properties for round-trip preservation.
     * Includes property parameters in key for perfect fidelity.
     * Example: "X-APPLE-STRUCTURED-LOCATION;VALUE=URI" -> "geo:..."
     */
    private fun extractExtraProperties(vevent: VEvent): Map<String, String>? {
        val extras = mutableMapOf<String, String>()

        for (property in vevent.properties) {
            val name = property.name ?: continue
            // Capture X-* properties (vendor extensions)
            if (name.startsWith("X-", ignoreCase = true)) {
                // Include parameters in key for round-trip fidelity
                val params = property.parameters
                    ?.filterNotNull()
                    ?.filter { it.name != null }
                    ?.joinToString(";") { "${it.name}=${it.value}" }
                val fullKey = if (params.isNullOrEmpty()) name else "$name;$params"
                extras[fullKey] = property.value ?: ""
            }
        }

        return extras.takeIf { it.isNotEmpty() }
    }

    override fun generate(events: List<ParsedEvent>): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//KashCal//Android//EN")
            events.forEach { event ->
                appendLine(generateVEvent(event))
            }
            appendLine("END:VCALENDAR")
        }
    }

    private fun generateVEvent(event: ParsedEvent): String {
        return buildString {
            appendLine("BEGIN:VEVENT")
            appendLine("UID:${event.uid}")
            appendLine("DTSTAMP:${formatUtcDateTime(event.dtstamp)}")

            // RECURRENCE-ID for exceptions
            event.recurrenceId?.let {
                appendLine("RECURRENCE-ID:$it")
            }

            // DTSTART
            if (event.isAllDay) {
                appendLine("DTSTART;VALUE=DATE:${formatDate(event.startTs)}")
            } else if (event.timezone != null) {
                appendLine("DTSTART;TZID=${event.timezone}:${formatLocalDateTime(event.startTs, event.timezone)}")
            } else {
                appendLine("DTSTART:${formatUtcDateTime(event.startTs)}")
            }

            // DTEND
            // RFC 5545: DTEND is exclusive for all-day events (the day AFTER the last day)
            // Since we stored endTs as inclusive (subtracted 1 sec during parse), add 1 day back
            if (event.isAllDay) {
                val exclusiveEndTs = event.endTs + 86400  // Add 1 day to convert inclusive → exclusive
                appendLine("DTEND;VALUE=DATE:${formatDate(exclusiveEndTs)}")
            } else if (event.timezone != null) {
                appendLine("DTEND;TZID=${event.timezone}:${formatLocalDateTime(event.endTs, event.timezone)}")
            } else {
                appendLine("DTEND:${formatUtcDateTime(event.endTs)}")
            }

            appendLine("SUMMARY:${escapeText(event.summary)}")

            if (event.description.isNotEmpty()) {
                appendLine("DESCRIPTION:${escapeText(event.description)}")
            }
            if (event.location.isNotEmpty()) {
                appendLine("LOCATION:${escapeText(event.location)}")
            }

            event.rrule?.let {
                appendLine("RRULE:$it")
            }

            event.exdates.forEach { exdate ->
                if (event.isAllDay) {
                    appendLine("EXDATE;VALUE=DATE:$exdate")
                } else {
                    appendLine("EXDATE:${exdate}T${formatTimeFromTs(event.startTs)}Z")
                }
            }

            event.reminderMinutes.forEach { minutes ->
                appendLine("BEGIN:VALARM")
                appendLine("ACTION:DISPLAY")
                appendLine("DESCRIPTION:Reminder")
                appendLine("TRIGGER:${formatTrigger(minutes)}")
                appendLine("END:VALARM")
            }

            if (event.sequence > 0) {
                appendLine("SEQUENCE:${event.sequence}")
            }
            event.status?.let {
                appendLine("STATUS:$it")
            }

            // TRANSP (only output non-default value)
            event.transp?.let {
                if (it != "OPAQUE") {
                    appendLine("TRANSP:$it")
                }
            }

            // CLASS (only output non-default value)
            event.classification?.let {
                if (it != "PUBLIC") {
                    appendLine("CLASS:$it")
                }
            }

            // Extra properties (X-APPLE-*, X-GOOGLE-*, etc.)
            event.extraProperties?.forEach { (key, value) ->
                appendLine("$key:${escapeText(value)}")
            }

            appendLine("END:VEVENT")
        }.trimEnd()
    }

    private fun formatUtcDateTime(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(epochSeconds * 1000)
    }

    private fun formatLocalDateTime(epochSeconds: Long, tzid: String): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone(tzid)
        return sdf.format(epochSeconds * 1000)
    }

    private fun formatDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(epochSeconds * 1000)
    }

    private fun formatTimeFromTs(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("HHmmss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(epochSeconds * 1000)
    }

    private fun formatTrigger(minutes: Int): String {
        return when {
            minutes >= 1440 && minutes % 1440 == 0 -> "-P${minutes / 1440}D"
            minutes >= 60 && minutes % 60 == 0 -> "-PT${minutes / 60}H"
            else -> "-PT${minutes}M"
        }
    }

    private fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    override fun mergeException(masterIcal: String, exception: ParsedEvent): String {
        // Parse existing calendar
        val builder = CalendarBuilder()
        val calendar = builder.build(StringReader(masterIcal))

        // Generate exception VEVENT
        val exceptionIcal = generateVEvent(exception)

        // Find and remove existing exception with same RECURRENCE-ID
        val existingExceptions = calendar.getComponents<VEvent>(Component.VEVENT)
            .filter { it.recurrenceId?.value == exception.recurrenceId }
        existingExceptions.forEach { calendar.components.remove(it) }

        // Re-output with new exception
        val sw = StringWriter()
        sw.append("BEGIN:VCALENDAR\n")
        sw.append("VERSION:2.0\n")
        sw.append("PRODID:-//KashCal//Android//EN\n")

        // Add all existing VEVENTs
        calendar.getComponents<VEvent>(Component.VEVENT).forEach { vevent ->
            val veventStr = vevent.toString()
            sw.append(veventStr)
            if (!veventStr.endsWith("\n")) sw.append("\n")
        }

        // Add new exception
        sw.append(exceptionIcal)
        sw.append("\n")
        sw.append("END:VCALENDAR\n")

        return sw.toString()
    }

    override fun removeException(masterIcal: String, recurrenceId: String): String {
        val builder = CalendarBuilder()
        val calendar = builder.build(StringReader(masterIcal))

        // Remove exception with matching RECURRENCE-ID
        val toRemove = calendar.getComponents<VEvent>(Component.VEVENT)
            .filter { vevent ->
                vevent.recurrenceId?.let { formatRecurrenceId(it) } == recurrenceId
            }
        toRemove.forEach { calendar.components.remove(it) }

        // Re-output
        val sw = StringWriter()
        sw.append("BEGIN:VCALENDAR\n")
        sw.append("VERSION:2.0\n")
        sw.append("PRODID:-//KashCal//Android//EN\n")

        calendar.getComponents<VEvent>(Component.VEVENT).forEach { vevent ->
            val veventStr = vevent.toString()
            sw.append(veventStr)
            if (!veventStr.endsWith("\n")) sw.append("\n")
        }

        sw.append("END:VCALENDAR\n")
        return sw.toString()
    }
}
