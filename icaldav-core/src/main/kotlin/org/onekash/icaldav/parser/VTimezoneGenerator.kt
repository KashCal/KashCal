package org.onekash.icaldav.parser

import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.timezone.TimezoneServiceClient
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale

/**
 * Generates RFC 5545 compliant VTIMEZONE components.
 *
 * This generator creates VTIMEZONE definitions for timezone IDs found in events,
 * enabling interoperability with calendar clients that don't recognize IANA timezone IDs.
 *
 * The generated VTIMEZONEs include:
 * - STANDARD component for non-DST periods (or fixed-offset timezones)
 * - DAYLIGHT component for DST periods (if applicable)
 * - RRULE for recurring transitions
 *
 * Supports three generation strategies via [VTimezoneStrategy]:
 * - INLINE: Full VTIMEZONE component (default, existing behavior)
 * - TZURL_ONLY: Just TZURL reference to timezone service
 * - BOTH: Full VTIMEZONE with TZURL property for authoritative source
 *
 * Implementation notes:
 * - UTC timezones are skipped (no VTIMEZONE needed)
 * - Invalid timezone IDs return empty string
 * - Uses RRULE-based recurring transitions for DST rules
 * - Constructor defaults preserve backward compatibility: `VTimezoneGenerator()` works as before
 *
 * @param timezoneService Optional service for fetching timezones (used for TZURL_ONLY strategy)
 * @param strategy Generation strategy (defaults to INLINE for backward compatibility)
 *
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
class VTimezoneGenerator(
    private val timezoneService: TimezoneServiceClient? = null,
    private val strategy: VTimezoneStrategy = VTimezoneStrategy.INLINE
) {

    /**
     * Strategy for VTIMEZONE generation.
     */
    enum class VTimezoneStrategy {
        /**
         * Generate full inline VTIMEZONE component (default, existing behavior).
         * Most compatible with all calendar clients.
         */
        INLINE,

        /**
         * Generate only TZURL reference to timezone service.
         * Smallest output but requires client to fetch timezone.
         * Use only when you know the receiving client supports TZURL.
         */
        TZURL_ONLY,

        /**
         * Generate full VTIMEZONE with TZURL property for authoritative source.
         * Best of both worlds: immediate compatibility + authoritative reference.
         */
        BOTH
    }

    /**
     * Generate VTIMEZONE component for a single timezone ID.
     *
     * Uses the strategy configured in the constructor:
     * - INLINE: Full VTIMEZONE component (default)
     * - TZURL_ONLY: Just TZURL reference
     * - BOTH: Full VTIMEZONE with TZURL property
     *
     * @param tzid The timezone ID (e.g., "America/New_York")
     * @return VTIMEZONE component string, or empty if invalid/UTC
     */
    fun generate(tzid: String): String {
        // Skip UTC - no VTIMEZONE needed
        if (tzid == "UTC" || tzid == "Z" || tzid == "Etc/UTC" || tzid == "GMT") {
            return ""
        }

        return when (strategy) {
            VTimezoneStrategy.INLINE -> generateInline(tzid)
            VTimezoneStrategy.TZURL_ONLY -> generateTzurlOnly(tzid)
            VTimezoneStrategy.BOTH -> generateWithTzurl(tzid)
        }
    }

    /**
     * Generate full inline VTIMEZONE component (existing behavior).
     */
    private fun generateInline(tzid: String): String {
        return buildString {
            appendTimezone(this, tzid, includeTzurl = false)
        }
    }

    /**
     * Generate VTIMEZONE with just TZURL reference.
     *
     * Smallest output but requires client to fetch timezone definition.
     * Falls back to inline generation if timezone service is unavailable.
     */
    private fun generateTzurlOnly(tzid: String): String {
        val tzurl = getTzurl(tzid)

        return buildString {
            try {
                ZoneId.of(tzid) // Validate tzid exists

                crlfLine("BEGIN:VTIMEZONE")
                crlfLine("TZID:$tzid")
                crlfLine("TZURL:$tzurl")
                crlfLine("END:VTIMEZONE")
            } catch (e: Exception) {
                // Skip invalid timezone IDs
            }
        }
    }

    /**
     * Generate full VTIMEZONE with TZURL property.
     *
     * Best of both worlds: immediate compatibility + authoritative reference.
     */
    private fun generateWithTzurl(tzid: String): String {
        return buildString {
            appendTimezone(this, tzid, includeTzurl = true)
        }
    }

    /**
     * Get the TZURL for a timezone ID.
     *
     * Uses the configured timezone service, or falls back to tzurl.org.
     */
    fun getTzurl(tzid: String): String {
        return timezoneService?.getTzurl(tzid)
            ?: "https://www.tzurl.org/zoneinfo/$tzid.ics"
    }

    /**
     * Generate VTIMEZONE components for multiple timezone IDs.
     *
     * @param tzids Set of timezone IDs to generate
     * @return Concatenated VTIMEZONE components
     */
    fun generate(tzids: Set<String>): String {
        return buildString {
            tzids.forEach { tzid ->
                append(generate(tzid))
            }
        }
    }

    /**
     * Collect unique timezone IDs from a list of events.
     *
     * Extracts TZIDs from DTSTART, DTEND, and other datetime properties.
     * Excludes UTC timezones as they don't require VTIMEZONE.
     *
     * @param events List of events to scan
     * @return Set of unique non-UTC timezone IDs
     */
    fun collectTimezones(events: List<ICalEvent>): Set<String> {
        val tzids = mutableSetOf<String>()
        events.forEach { addEventTzids(it, tzids) }
        return tzids
    }

    /**
     * Collect unique timezone IDs from every component in a calendar:
     * VEVENTs, VTODOs, and VJOURNALs. Deduplicated across component types,
     * UTC-equivalent zones excluded.
     */
    fun collectTimezones(calendar: org.onekash.icaldav.model.ICalCalendar): Set<String> {
        val tzids = mutableSetOf<String>()
        calendar.events.forEach { addEventTzids(it, tzids) }
        calendar.todos.forEach { addTodoTzids(it, tzids) }
        calendar.journals.forEach { addJournalTzids(it, tzids) }
        return tzids
    }

    /** Collect TZIDs referenced by a VTODO's datetime properties. */
    fun collectTimezones(todo: org.onekash.icaldav.model.ICalTodo): Set<String> {
        val tzids = mutableSetOf<String>()
        addTodoTzids(todo, tzids)
        return tzids
    }

    /** Collect TZIDs referenced by a VJOURNAL's datetime properties. */
    fun collectTimezones(journal: org.onekash.icaldav.model.ICalJournal): Set<String> {
        val tzids = mutableSetOf<String>()
        addJournalTzids(journal, tzids)
        return tzids
    }

    private fun addEventTzids(event: ICalEvent, tzids: MutableSet<String>) {
        collectFromDateTime(event.dtStart, tzids)
        event.dtEnd?.let { collectFromDateTime(it, tzids) }
        event.recurrenceId?.let { collectFromDateTime(it, tzids) }
        event.exdates.forEach { collectFromDateTime(it, tzids) }
        event.rdates.forEach { collectFromDateTime(it, tzids) }
    }

    private fun addTodoTzids(todo: org.onekash.icaldav.model.ICalTodo, tzids: MutableSet<String>) {
        todo.dtStart?.let { collectFromDateTime(it, tzids) }
        todo.due?.let { collectFromDateTime(it, tzids) }
        todo.completed?.let { collectFromDateTime(it, tzids) }
        todo.recurrenceId?.let { collectFromDateTime(it, tzids) }
    }

    private fun addJournalTzids(journal: org.onekash.icaldav.model.ICalJournal, tzids: MutableSet<String>) {
        journal.dtStart?.let { collectFromDateTime(it, tzids) }
        journal.recurrenceId?.let { collectFromDateTime(it, tzids) }
    }

    /**
     * Extract timezone ID from a datetime and add to set if not UTC.
     */
    private fun collectFromDateTime(dt: ICalDateTime, tzids: MutableSet<String>) {
        if (!dt.isUtc && !dt.isDate && dt.timezone != null) {
            val tzid = dt.timezone.id
            if (tzid != "UTC" && tzid != "Z" && tzid != "Etc/UTC" && tzid != "GMT") {
                tzids.add(tzid)
            }
        }
    }

    /**
     * Append VTIMEZONE component for a timezone ID.
     *
     * @param builder StringBuilder to append to
     * @param tzid Timezone ID
     * @param includeTzurl Whether to include TZURL property
     */
    private fun appendTimezone(builder: StringBuilder, tzid: String, includeTzurl: Boolean = false) {
        try {
            val zoneId = ZoneId.of(tzid)
            val rules = zoneId.rules

            builder.crlfLine("BEGIN:VTIMEZONE")
            builder.crlfLine("TZID:$tzid")

            // Add TZURL if requested
            if (includeTzurl) {
                builder.crlfLine("TZURL:${getTzurl(tzid)}")
            }

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

            builder.crlfLine("END:VTIMEZONE")
        } catch (e: Exception) {
            // Skip invalid timezone IDs - return empty content
        }
    }

    /**
     * Append a fixed-offset timezone component (no DST).
     */
    private fun appendFixedTimezoneComponent(builder: StringBuilder, offset: ZoneOffset, tzid: String) {
        val offsetStr = formatOffset(offset)
        val abbrev = tzid.substringAfterLast("/").take(4).uppercase()

        builder.crlfLine("BEGIN:STANDARD")
        builder.crlfLine("DTSTART:19700101T000000")
        builder.crlfLine("TZOFFSETFROM:$offsetStr")
        builder.crlfLine("TZOFFSETTO:$offsetStr")
        builder.crlfLine("TZNAME:$abbrev")
        builder.crlfLine("END:STANDARD")
    }

    /**
     * Append a STANDARD or DAYLIGHT component from a transition rule.
     */
    private fun appendTimezoneComponent(builder: StringBuilder, rule: ZoneOffsetTransitionRule, zoneId: ZoneId) {
        // Determine if transitioning TO daylight time (clocks spring forward)
        // Use totalSeconds because ZoneOffset comparison is non-intuitive (-05:00 < -06:00)
        val isDst = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
        val componentType = if (isDst) "DAYLIGHT" else "STANDARD"

        builder.crlfLine("BEGIN:$componentType")

        // DTSTART: Use 1970 as base year per common practice
        val month = rule.month.value
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
        builder.crlfLine("DTSTART:$dtstart")

        // RRULE for recurring transition
        val rrule = buildRrule(rule)
        builder.crlfLine("RRULE:$rrule")

        // Offsets
        builder.crlfLine("TZOFFSETFROM:${formatOffset(rule.offsetBefore)}")
        builder.crlfLine("TZOFFSETTO:${formatOffset(rule.offsetAfter)}")

        // Timezone abbreviation - use standard Java API to get proper name
        val abbrev = getTimezoneAbbreviation(zoneId, rule.offsetAfter, isDst)
        builder.crlfLine("TZNAME:$abbrev")

        builder.crlfLine("END:$componentType")
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
            val sampleInstant = LocalDateTime.of(sampleYear, sampleMonth, 15, 12, 0)
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
     * Format ZoneOffset as iCal offset string (e.g., "-0500", "+0900", "+0530").
     */
    fun formatOffset(offset: ZoneOffset): String {
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return String.format("%s%02d%02d", sign, hours, minutes)
    }
}
