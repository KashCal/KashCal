package org.onekash.kashcal.sync.serializer

import org.onekash.kashcal.data.db.entity.Event
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale
import javax.inject.Inject

/**
 * Serializes Event entities to iCal (RFC 5545) format for CalDAV upload.
 *
 * Produces a complete VCALENDAR with one or more VEVENT components.
 * Handles:
 * - Basic event properties (SUMMARY, DTSTART, DTEND, etc.)
 * - All-day events (DATE vs DATE-TIME)
 * - Timezones (TZID parameter)
 * - Recurrence (RRULE, RDATE, EXDATE)
 * - Exception events (RECURRENCE-ID)
 * - Reminders (VALARM)
 * - Extra properties (X-APPLE-*, etc.)
 */
class ICalSerializer @Inject constructor() {

    companion object {
        private const val PRODID = "-//KashCal//KashCal 2.0//EN"
        private const val VERSION = "2.0"
        private const val CALSCALE = "GREGORIAN"

        // Line folding at 75 octets per RFC 5545
        private const val MAX_LINE_LENGTH = 75
    }

    /**
     * Serialize a single event to a complete VCALENDAR.
     *
     * @param event The event to serialize
     * @return iCal string ready for CalDAV upload
     */
    fun serialize(event: Event): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:$VERSION")
            appendLine("PRODID:$PRODID")
            appendLine("CALSCALE:$CALSCALE")

            // Add VTIMEZONE if event has a timezone
            event.timezone?.let { tz ->
                appendTimezone(this, tz)
            }

            // Add VEVENT
            appendEvent(this, event)

            appendLine("END:VCALENDAR")
        }.trimEnd()
    }

    /**
     * Serialize multiple events (master + exceptions) to a single VCALENDAR.
     *
     * @param masterEvent The master recurring event
     * @param exceptions Exception events for modified occurrences
     * @return iCal string with all events
     */
    fun serializeWithExceptions(masterEvent: Event, exceptions: List<Event>): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:$VERSION")
            appendLine("PRODID:$PRODID")
            appendLine("CALSCALE:$CALSCALE")

            // Collect all timezones
            val timezones = mutableSetOf<String>()
            masterEvent.timezone?.let { timezones.add(it) }
            exceptions.forEach { it.timezone?.let { tz -> timezones.add(tz) } }

            // Add VTIMEZONE components
            timezones.forEach { tz ->
                appendTimezone(this, tz)
            }

            // Add master VEVENT
            appendEvent(this, masterEvent)

            // Add exception VEVENTs
            exceptions.forEach { exception ->
                appendEvent(this, exception)
            }

            appendLine("END:VCALENDAR")
        }.trimEnd()
    }

    /**
     * Append a VEVENT component to the builder.
     */
    private fun appendEvent(builder: StringBuilder, event: Event) {
        builder.apply {
            appendLine("BEGIN:VEVENT")

            // Required properties
            appendLine("UID:${event.uid}")
            appendLine("DTSTAMP:${formatUtcDateTime(event.dtstamp)}")

            // RFC 5545 recommended properties
            appendLine("CREATED:${formatUtcDateTime(event.createdAt)}")
            appendLine("LAST-MODIFIED:${formatUtcDateTime(event.localModifiedAt ?: event.updatedAt)}")

            // Date/time properties
            if (event.isAllDay) {
                appendLine("DTSTART;VALUE=DATE:${formatDate(event.startTs)}")
                // RFC 5545: DTEND is exclusive for all-day events (the day AFTER the last day)
                // endTs is stored as inclusive (last second of last day), so add 1 day
                val exclusiveEndTs = event.endTs + 86400000  // Add 1 day in milliseconds
                appendLine("DTEND;VALUE=DATE:${formatDate(exclusiveEndTs)}")
            } else if (event.timezone != null) {
                appendLine("DTSTART;TZID=${event.timezone}:${formatLocalDateTime(event.startTs, event.timezone)}")
                appendLine("DTEND;TZID=${event.timezone}:${formatLocalDateTime(event.endTs, event.timezone)}")
            } else {
                appendLine("DTSTART:${formatUtcDateTime(event.startTs)}")
                appendLine("DTEND:${formatUtcDateTime(event.endTs)}")
            }

            // Summary (title)
            appendFoldedLine(this, "SUMMARY:${escapeText(event.title)}")

            // Optional properties
            event.location?.let {
                appendFoldedLine(this, "LOCATION:${escapeText(it)}")
            }

            event.description?.let {
                appendFoldedLine(this, "DESCRIPTION:${escapeText(it)}")
            }

            // Status
            if (event.status != "CONFIRMED") {
                appendLine("STATUS:${event.status}")
            }

            // Transparency
            if (event.transp != "OPAQUE") {
                appendLine("TRANSP:${event.transp}")
            }

            // Classification
            if (event.classification != "PUBLIC") {
                appendLine("CLASS:${event.classification}")
            }

            // Organizer
            if (event.organizerEmail != null) {
                val organizer = if (event.organizerName != null) {
                    "ORGANIZER;CN=${escapeText(event.organizerName)}:mailto:${event.organizerEmail}"
                } else {
                    "ORGANIZER:mailto:${event.organizerEmail}"
                }
                appendFoldedLine(this, organizer)
            }

            // Recurrence (only on master events)
            event.rrule?.let {
                appendLine("RRULE:$it")
            }

            event.rdate?.let {
                appendLine("RDATE:$it")
            }

            event.exdate?.let {
                appendLine("EXDATE:$it")
            }

            event.duration?.let {
                appendLine("DURATION:$it")
            }

            // Recurrence-ID (for exception events)
            if (event.isException && event.originalInstanceTime != null) {
                if (event.isAllDay) {
                    appendLine("RECURRENCE-ID;VALUE=DATE:${formatDate(event.originalInstanceTime)}")
                } else if (event.timezone != null) {
                    appendLine("RECURRENCE-ID;TZID=${event.timezone}:${formatLocalDateTime(event.originalInstanceTime, event.timezone)}")
                } else {
                    appendLine("RECURRENCE-ID:${formatUtcDateTime(event.originalInstanceTime)}")
                }
            }

            // Sequence
            if (event.sequence > 0) {
                appendLine("SEQUENCE:${event.sequence}")
            }

            // Reminders (VALARM)
            event.reminders?.forEach { reminder ->
                appendAlarm(this, reminder)
            }

            // Extra properties (X-APPLE-*, etc.)
            event.extraProperties?.forEach { (key, value) ->
                appendFoldedLine(this, "$key:${escapeText(value)}")
            }

            appendLine("END:VEVENT")
        }
    }

    /**
     * Append a VALARM component.
     * @param trigger Trigger in ISO 8601 duration format (e.g., "-PT15M")
     *
     * Includes platform-specific properties for proper iCloud/device handling:
     * - UID: Unique identifier for this alarm
     * - X-WR-ALARMUID: iCloud alarm identifier (same as UID)
     * - X-APPLE-DEFAULT-ALARM:FALSE: Prevents iPhone from treating this as
     *   a "default" alarm that can be merged with calendar defaults
     */
    private fun appendAlarm(builder: StringBuilder, trigger: String) {
        val alarmUid = java.util.UUID.randomUUID().toString().uppercase()
        builder.apply {
            appendLine("BEGIN:VALARM")
            appendLine("ACTION:DISPLAY")
            appendLine("TRIGGER:$trigger")
            appendLine("DESCRIPTION:Reminder")
            appendLine("UID:$alarmUid")
            appendLine("X-WR-ALARMUID:$alarmUid")
            appendLine("X-APPLE-DEFAULT-ALARM:FALSE")
            appendLine("END:VALARM")
        }
    }

    /**
     * Append a VTIMEZONE component with STANDARD and DAYLIGHT rules.
     *
     * Generates proper RFC 5545 VTIMEZONE from java.time zone data.
     * Supports both fixed-offset and DST-observing timezones.
     *
     * @param builder StringBuilder to append to
     * @param tzid IANA timezone ID (e.g., "America/New_York")
     */
    private fun appendTimezone(builder: StringBuilder, tzid: String) {
        try {
            val zoneId = ZoneId.of(tzid)
            val rules = zoneId.rules

            builder.appendLine("BEGIN:VTIMEZONE")
            builder.appendLine("TZID:$tzid")

            // Get transition rules for repeating DST patterns
            val transitionRules = rules.transitionRules

            if (transitionRules.isEmpty()) {
                // No DST - single STANDARD component with fixed offset
                val offset = rules.getOffset(Instant.now())
                appendFixedTimezoneComponent(builder, offset, tzid)
            } else {
                // Has DST - generate STANDARD and DAYLIGHT components from rules
                for (rule in transitionRules) {
                    appendTimezoneComponent(builder, rule, zoneId)
                }
            }

            builder.appendLine("END:VTIMEZONE")
        } catch (e: Exception) {
            // Skip invalid timezone IDs - server will use TZID reference
        }
    }

    /**
     * Append a fixed-offset timezone component (no DST).
     */
    private fun appendFixedTimezoneComponent(builder: StringBuilder, offset: ZoneOffset, tzid: String) {
        val offsetStr = formatOffset(offset)
        val abbrev = tzid.substringAfterLast("/").take(4).uppercase()

        builder.appendLine("BEGIN:STANDARD")
        builder.appendLine("DTSTART:19700101T000000")
        builder.appendLine("TZOFFSETFROM:$offsetStr")
        builder.appendLine("TZOFFSETTO:$offsetStr")
        builder.appendLine("TZNAME:$abbrev")
        builder.appendLine("END:STANDARD")
    }

    /**
     * Append a STANDARD or DAYLIGHT component from a transition rule.
     */
    private fun appendTimezoneComponent(builder: StringBuilder, rule: ZoneOffsetTransitionRule, zoneId: ZoneId) {
        // Determine if transitioning TO daylight time (clocks spring forward)
        // Use totalSeconds because ZoneOffset comparison is non-intuitive (-05:00 < -06:00)
        val isDst = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
        val componentType = if (isDst) "DAYLIGHT" else "STANDARD"

        builder.appendLine("BEGIN:$componentType")

        // DTSTART: Use 1970 as base year per common practice
        val month = rule.month.value
        val dayOfWeek = rule.dayOfWeek
        val dayOfMonthIndicator = rule.dayOfMonthIndicator
        val time = rule.localTime

        // Format DTSTART as YYYYMMDDTHHMMSS
        val dtstart = String.format(
            "1970%02d%02dT%02d%02d%02d",
            month,
            calculateDtstartDay(rule),
            time.hour,
            time.minute,
            time.second
        )
        builder.appendLine("DTSTART:$dtstart")

        // RRULE for recurring transition
        val rrule = buildRrule(rule)
        builder.appendLine("RRULE:$rrule")

        // Offsets
        builder.appendLine("TZOFFSETFROM:${formatOffset(rule.offsetBefore)}")
        builder.appendLine("TZOFFSETTO:${formatOffset(rule.offsetAfter)}")

        // Timezone abbreviation - use standard Java API to get proper name
        val abbrev = getTimezoneAbbreviation(zoneId, rule.offsetAfter, isDst)
        builder.appendLine("TZNAME:$abbrev")

        builder.appendLine("END:$componentType")
    }

    /**
     * Get timezone abbreviation using standard Java time API.
     * Falls back to offset-based format if unavailable.
     */
    private fun getTimezoneAbbreviation(zoneId: ZoneId, offset: ZoneOffset, isDst: Boolean): String {
        return try {
            // Create a sample instant in the target offset period to get correct abbreviation
            // Use a date in the middle of summer (July) for DST, winter (January) for standard
            val sampleYear = 2024
            val sampleMonth = if (isDst) 7 else 1
            val sampleInstant = java.time.LocalDateTime.of(sampleYear, sampleMonth, 15, 12, 0)
                .toInstant(offset)
            val zdt = sampleInstant.atZone(zoneId)

            // Use DateTimeFormatter to get proper abbreviation (e.g., "CST", "CDT", "JST")
            val formatter = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US)
            zdt.format(formatter)
        } catch (e: Exception) {
            // Fallback to offset string format
            formatOffset(offset)
        }
    }

    /**
     * Calculate DTSTART day for a transition rule.
     * Returns a day in 1970 that matches the rule pattern.
     */
    private fun calculateDtstartDay(rule: ZoneOffsetTransitionRule): Int {
        val dayOfMonthIndicator = rule.dayOfMonthIndicator
        val dayOfWeek = rule.dayOfWeek

        return if (dayOfWeek == null) {
            // Fixed day of month
            if (dayOfMonthIndicator > 0) dayOfMonthIndicator else 28 + dayOfMonthIndicator
        } else {
            // Day of week in month (e.g., 2nd Sunday)
            // For DTSTART, we just need a valid date - RRULE handles the pattern
            when {
                dayOfMonthIndicator > 0 -> dayOfMonthIndicator.coerceAtMost(28)
                dayOfMonthIndicator < 0 -> 28 + dayOfMonthIndicator
                else -> 1
            }
        }
    }

    /**
     * Build RRULE string for a transition rule.
     */
    private fun buildRrule(rule: ZoneOffsetTransitionRule): String {
        val parts = mutableListOf("FREQ=YEARLY")
        parts.add("BYMONTH=${rule.month.value}")

        val dayOfWeek = rule.dayOfWeek
        val dayOfMonthIndicator = rule.dayOfMonthIndicator

        if (dayOfWeek != null) {
            val weekNum = when {
                dayOfMonthIndicator >= 8 && dayOfMonthIndicator <= 14 -> 2
                dayOfMonthIndicator >= 15 && dayOfMonthIndicator <= 21 -> 3
                dayOfMonthIndicator >= 22 && dayOfMonthIndicator <= 28 -> 4
                dayOfMonthIndicator < 0 -> -1  // Last occurrence
                else -> 1
            }
            val dayAbbrev = dayOfWeekToIcal(dayOfWeek)
            parts.add("BYDAY=$weekNum$dayAbbrev")
        } else {
            parts.add("BYMONTHDAY=$dayOfMonthIndicator")
        }

        return parts.joinToString(";")
    }

    /**
     * Convert DayOfWeek to iCal abbreviation.
     */
    private fun dayOfWeekToIcal(dow: DayOfWeek): String {
        return when (dow) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }
    }

    /**
     * Format ZoneOffset as iCal offset string (e.g., "-0500", "+0900").
     */
    private fun formatOffset(offset: ZoneOffset): String {
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return String.format("%s%02d%02d", sign, hours, minutes)
    }

    /**
     * Format timestamp as UTC DATE-TIME (e.g., "20240101T120000Z")
     * Uses java.time API per Android recommendation.
     */
    private fun formatUtcDateTime(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        return formatter.format(instant)
    }

    /**
     * Format timestamp as local DATE-TIME in timezone (e.g., "20240101T070000")
     * Uses java.time API per Android recommendation.
     * Falls back to UTC for invalid timezone IDs.
     */
    private fun formatLocalDateTime(epochMillis: Long, tzid: String): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val zoneId = try {
            ZoneId.of(tzid)
        } catch (e: Exception) {
            ZoneOffset.UTC  // Fallback for invalid zone
        }
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(zoneId)
        return formatter.format(instant)
    }

    /**
     * Format timestamp as DATE (e.g., "20240101")
     * Uses java.time API per Android recommendation.
     */
    private fun formatDate(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
        return formatter.format(instant)
    }

    /**
     * Escape special characters in text values per RFC 5545.
     * - Backslash → \\
     * - Semicolon → \;
     * - Comma → \,
     * - Newline → \n
     */
    private fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
    }

    /**
     * Append a line with RFC 5545 line folding.
     * Lines longer than 75 octets are folded with CRLF + space.
     */
    private fun appendFoldedLine(builder: StringBuilder, line: String) {
        if (line.length <= MAX_LINE_LENGTH) {
            builder.appendLine(line)
            return
        }

        var remaining = line
        var first = true

        while (remaining.isNotEmpty()) {
            val maxLen = if (first) MAX_LINE_LENGTH else MAX_LINE_LENGTH - 1
            val chunk = if (remaining.length <= maxLen) {
                remaining
            } else {
                remaining.substring(0, maxLen)
            }

            if (first) {
                builder.appendLine(chunk)
                first = false
            } else {
                builder.appendLine(" $chunk")
            }

            remaining = remaining.substring(chunk.length)
        }
    }
}
