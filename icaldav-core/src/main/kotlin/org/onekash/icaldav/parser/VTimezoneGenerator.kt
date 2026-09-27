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
 * Generates RFC 5545 VTIMEZONE components for the timezone IDs events use, for clients that
 * don't recognize IANA timezone IDs.
 *
 * An inline VTIMEZONE has one STANDARD or DAYLIGHT component per java.time transition rule,
 * each with a yearly RRULE, or a single fixed-offset STANDARD component for a zone without
 * DST. The [VTimezoneStrategy] decides whether it is inlined, replaced by a TZURL, or both.
 * UTC IDs (`UTC`, `Z`, `Etc/UTC`, `GMT`) and IDs `ZoneId.of` rejects produce an empty string.
 *
 * @param timezoneService source of TZURLs for [getTzurl]; when null, tzurl.org URLs are used
 * @param strategy defaults to INLINE, which is what `VTimezoneGenerator()` gives
 *
 * @see <a href="https://www.calconnect.org/resources/tzurl">CalConnect TZURL Service</a>
 */
class VTimezoneGenerator(
    private val timezoneService: TimezoneServiceClient? = null,
    private val strategy: VTimezoneStrategy = VTimezoneStrategy.INLINE
) {

    /** Chooses how [generate] writes each VTIMEZONE. */
    enum class VTimezoneStrategy {
        /** Writes the full component inline; every client can read it. */
        INLINE,

        /**
         * Writes only TZID and a TZURL. The receiving client must fetch the definition, so use
         * it only when the receiver is known to support TZURL.
         */
        TZURL_ONLY,

        /** Writes the full component plus a TZURL pointing at the authoritative definition. */
        BOTH
    }

    /**
     * Generates the VTIMEZONE for [tzid] (for example "America/New_York") with the configured
     * [VTimezoneStrategy].
     *
     * @return the component, or an empty string for a UTC or invalid ID
     */
    fun generate(tzid: String): String {
        // UTC needs no VTIMEZONE.
        if (tzid == "UTC" || tzid == "Z" || tzid == "Etc/UTC" || tzid == "GMT") {
            return ""
        }

        return when (strategy) {
            VTimezoneStrategy.INLINE -> generateInline(tzid)
            VTimezoneStrategy.TZURL_ONLY -> generateTzurlOnly(tzid)
            VTimezoneStrategy.BOTH -> generateWithTzurl(tzid)
        }
    }

    private fun generateInline(tzid: String): String {
        return buildString {
            appendTimezone(this, tzid, includeTzurl = false)
        }
    }

    /**
     * Generates a VTIMEZONE with only TZID and TZURL. Without a [timezoneService] the TZURL
     * is the tzurl.org one ([getTzurl]); it never falls back to an inline definition.
     */
    private fun generateTzurlOnly(tzid: String): String {
        val tzurl = getTzurl(tzid)

        return buildString {
            try {
                ZoneId.of(tzid) // Throws for an unknown ID

                crlfLine("BEGIN:VTIMEZONE")
                crlfLine("TZID:$tzid")
                crlfLine("TZURL:$tzurl")
                crlfLine("END:VTIMEZONE")
            } catch (e: Exception) {
                // An invalid ID produces nothing.
            }
        }
    }

    private fun generateWithTzurl(tzid: String): String {
        return buildString {
            appendTimezone(this, tzid, includeTzurl = true)
        }
    }

    /** Returns [timezoneService]'s TZURL for [tzid], or the tzurl.org URL when there is none. */
    fun getTzurl(tzid: String): String {
        return timezoneService?.getTzurl(tzid)
            ?: "https://www.tzurl.org/zoneinfo/$tzid.ics"
    }

    /** Generates and concatenates the VTIMEZONE of each of [tzids]. */
    fun generate(tzids: Set<String>): String {
        return buildString {
            tzids.forEach { tzid ->
                append(generate(tzid))
            }
        }
    }

    /**
     * Collects the timezone IDs that [events] use in DTSTART, DTEND, RECURRENCE-ID, EXDATE and
     * RDATE. UTC, DATE and floating values contribute nothing.
     */
    fun collectTimezones(events: List<ICalEvent>): Set<String> {
        val tzids = mutableSetOf<String>()
        events.forEach { addEventTzids(it, tzids) }
        return tzids
    }

    /**
     * Collects the timezone IDs used by every VEVENT, VTODO and VJOURNAL in [calendar],
     * deduplicated, with UTC, DATE and floating values excluded.
     */
    fun collectTimezones(calendar: org.onekash.icaldav.model.ICalCalendar): Set<String> {
        val tzids = mutableSetOf<String>()
        calendar.events.forEach { addEventTzids(it, tzids) }
        calendar.todos.forEach { addTodoTzids(it, tzids) }
        calendar.journals.forEach { addJournalTzids(it, tzids) }
        return tzids
    }

    /** Collects the TZIDs of a VTODO's DTSTART, DUE, COMPLETED and RECURRENCE-ID. */
    fun collectTimezones(todo: org.onekash.icaldav.model.ICalTodo): Set<String> {
        val tzids = mutableSetOf<String>()
        addTodoTzids(todo, tzids)
        return tzids
    }

    /** Collects the TZIDs of a VJOURNAL's DTSTART and RECURRENCE-ID. */
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

    /** Adds [dt]'s zone ID to [tzids] unless [dt] is UTC, a DATE, floating, or a UTC alias. */
    private fun collectFromDateTime(dt: ICalDateTime, tzids: MutableSet<String>) {
        if (!dt.isUtc && !dt.isDate && dt.timezone != null) {
            val tzid = dt.timezone.id
            if (tzid != "UTC" && tzid != "Z" && tzid != "Etc/UTC" && tzid != "GMT") {
                tzids.add(tzid)
            }
        }
    }

    /** Appends the inline VTIMEZONE for [tzid], with a TZURL when [includeTzurl]. */
    private fun appendTimezone(builder: StringBuilder, tzid: String, includeTzurl: Boolean = false) {
        try {
            val zoneId = ZoneId.of(tzid)
            val rules = zoneId.rules

            builder.crlfLine("BEGIN:VTIMEZONE")
            builder.crlfLine("TZID:$tzid")

            if (includeTzurl) {
                builder.crlfLine("TZURL:${getTzurl(tzid)}")
            }

            // The rules that repeat every year; empty for a zone without DST.
            val transitionRules = rules.transitionRules

            if (transitionRules.isEmpty()) {
                // No DST: one STANDARD component at today's offset.
                val offset = rules.getOffset(Instant.now())
                appendFixedTimezoneComponent(builder, offset, tzid)
            } else {
                // DST: one STANDARD or DAYLIGHT component per rule.
                for (rule in transitionRules) {
                    appendTimezoneComponent(builder, rule, zoneId)
                }
            }

            builder.crlfLine("END:VTIMEZONE")
        } catch (e: Exception) {
            // ZoneId.of throws before anything is appended, so an invalid ID produces nothing.
        }
    }

    /**
     * Appends a STANDARD component with [offset] on both sides and a TZNAME of the first four
     * letters of the ID's last segment, uppercased.
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

    /** Appends a STANDARD or DAYLIGHT component for one yearly transition [rule]. */
    private fun appendTimezoneComponent(builder: StringBuilder, rule: ZoneOffsetTransitionRule, zoneId: ZoneId) {
        // DAYLIGHT when the clocks go forward. Compare totalSeconds: ZoneOffset.compareTo
        // orders -05:00 before -06:00.
        val isDst = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
        val componentType = if (isDst) "DAYLIGHT" else "STANDARD"

        builder.crlfLine("BEGIN:$componentType")

        // DTSTART in 1970, the usual base year.
        val month = rule.month.value
        val time = rule.localTime

        val dtstart = String.format(
            "1970%02d%02dT%02d%02d%02d",
            month,
            calculateDtstartDay(rule),
            time.hour,
            time.minute,
            time.second
        )
        builder.crlfLine("DTSTART:$dtstart")

        val rrule = buildRrule(rule)
        builder.crlfLine("RRULE:$rrule")

        builder.crlfLine("TZOFFSETFROM:${formatOffset(rule.offsetBefore)}")
        builder.crlfLine("TZOFFSETTO:${formatOffset(rule.offsetAfter)}")

        val abbrev = getTimezoneAbbreviation(zoneId, rule.offsetAfter, isDst)
        builder.crlfLine("TZNAME:$abbrev")

        builder.crlfLine("END:$componentType")
    }

    /**
     * Returns the zone's short name ("CST", "CDT") for a mid-July (DST) or mid-January
     * (standard) 2024 date, or the [formatOffset] string if formatting throws.
     */
    private fun getTimezoneAbbreviation(zoneId: ZoneId, offset: ZoneOffset, isDst: Boolean): String {
        return try {
            val sampleYear = 2024
            val sampleMonth = if (isDst) 7 else 1
            val sampleInstant = LocalDateTime.of(sampleYear, sampleMonth, 15, 12, 0)
                .toInstant(offset)
            val zdt = sampleInstant.atZone(zoneId)

            val formatter = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US)
            zdt.format(formatter)
        } catch (e: Exception) {
            formatOffset(offset)
        }
    }

    /**
     * Returns the day of month for a rule's 1970 DTSTART: a positive day-of-month indicator
     * (capped at 28 for a day-of-week rule), or 28 plus a negative one. For a day-of-week rule
     * the day isn't checked to fall on that weekday.
     */
    private fun calculateDtstartDay(rule: ZoneOffsetTransitionRule): Int {
        val dayOfMonthIndicator = rule.dayOfMonthIndicator
        val dayOfWeek = rule.dayOfWeek

        return if (dayOfWeek == null) {
            if (dayOfMonthIndicator > 0) dayOfMonthIndicator else 28 + dayOfMonthIndicator
        } else {
            // Day of week in month, for example the 2nd Sunday.
            when {
                dayOfMonthIndicator > 0 -> dayOfMonthIndicator.coerceAtMost(28)
                dayOfMonthIndicator < 0 -> 28 + dayOfMonthIndicator
                else -> 1
            }
        }
    }

    /**
     * Builds the yearly RRULE for [rule]: BYMONTH plus BYDAY with a week number read from the
     * day-of-month indicator (8-14 is the 2nd, 15-21 the 3rd, 22-28 the 4th, negative the
     * last, anything else the 1st), or BYMONTHDAY for a fixed day.
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
                dayOfMonthIndicator < 0 -> -1  // Last in the month
                else -> 1
            }
            val dayAbbrev = dayOfWeekToIcal(dayOfWeek)
            parts.add("BYDAY=$weekNum$dayAbbrev")
        } else {
            parts.add("BYMONTHDAY=$dayOfMonthIndicator")
        }

        return parts.joinToString(";")
    }

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

    /** Formats [offset] as an iCalendar UTC offset ("-0500", "+0530"), dropping seconds. */
    fun formatOffset(offset: ZoneOffset): String {
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return String.format("%s%02d%02d", sign, hours, minutes)
    }
}
