package org.onekash.icaldav.recurrence

import net.fortuna.ical4j.model.NumberList
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.WeekDay
import net.fortuna.ical4j.model.WeekDayList
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import net.fortuna.ical4j.transform.recurrence.Frequency as ICalFrequency

/**
 * Expands recurring events into individual occurrences.
 *
 * Uses ical4j's Recur class for RFC 5545 compliant expansion,
 * implementing the full recurrence formula:
 * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Additional handling for:
 * - EXDATE exclusions (deleted occurrences)
 * - RDATE additions (extra occurrence dates)
 * - RECURRENCE-ID overrides (modified occurrences)
 * - Timezone-aware date matching
 */
class RRuleExpander {

    /**
     * Expand a recurring event into individual occurrences within a time range.
     *
     * Implements RFC 5545 recurrence expansion:
     * RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
     *
     * @param masterEvent The event with RRULE and/or RDATE
     * @param rangeStart Start of expansion range (inclusive)
     * @param rangeEnd End of expansion range (exclusive)
     * @param overrides Map of RECURRENCE-ID daycodes to modified ICalEvent
     * @return List of occurrence events with adjusted timestamps
     */
    fun expand(
        masterEvent: ICalEvent,
        rangeStart: Instant,
        rangeEnd: Instant,
        overrides: Map<String, ICalEvent> = emptyMap()
    ): List<ICalEvent> {
        val rrule = masterEvent.rrule

        // If no RRULE and no RDATE, return single event
        if (rrule == null && masterEvent.rdates.isEmpty()) {
            return listOf(masterEvent)
        }

        val occurrences = mutableListOf<ICalEvent>()

        // Get the event's timezone for calculations
        // For all-day events (isDate=true), always use UTC to preserve calendar dates.
        // DATE values are stored as UTC midnight, so expansion must use UTC consistently.
        val eventZone = if (masterEvent.isAllDay) {
            ZoneOffset.UTC
        } else {
            masterEvent.dtStart.timezone ?: ZoneId.systemDefault()
        }

        // Calculate event duration for creating occurrence end times
        val eventDuration = calculateDuration(masterEvent)

        // Build set of excluded day codes from EXDATE
        val excludedDayCodes = masterEvent.exdates.map { it.toDayCode() }.toSet()

        // Track day codes of RECURRENCE-ID overrides
        val overrideDayCodes = overrides.keys

        // Track generated day codes to avoid duplicates between RRULE and RDATE
        val generatedDayCodes = mutableSetOf<String>()

        // ========== RRULE Expansion ==========
        if (rrule != null) {
            // Build ical4j Recur from our RRule
            val recur = buildRecur(rrule)

            // Get start date for recurrence calculation
            val eventStartZdt = masterEvent.dtStart.toZonedDateTime()

            // Generate occurrence dates using ical4j
            val periodStart = ZonedDateTime.ofInstant(rangeStart, eventZone)
            val periodEnd = ZonedDateTime.ofInstant(rangeEnd, eventZone)

            // Always use LocalDateTime - for all-day events, use midnight
            val seed = if (masterEvent.isAllDay) {
                eventStartZdt.toLocalDate().atStartOfDay()
            } else {
                eventStartZdt.toLocalDateTime()
            }

            val rangeStartLdt = if (masterEvent.isAllDay) {
                periodStart.toLocalDate().atStartOfDay()
            } else {
                periodStart.toLocalDateTime()
            }

            val rangeEndLdt = if (masterEvent.isAllDay) {
                periodEnd.toLocalDate().atStartOfDay()
            } else {
                periodEnd.toLocalDateTime()
            }

            // ical4j 4.x: getDates accepts LocalDateTime, returns List<LocalDateTime>
            val dates: List<LocalDateTime> = recur.getDates(seed, rangeStartLdt, rangeEndLdt)

            for (date in dates) {
                val occurrenceZdt = date.atZone(eventZone)
                val occurrenceDayCode = "%04d%02d%02d".format(
                    occurrenceZdt.year,
                    occurrenceZdt.monthValue,
                    occurrenceZdt.dayOfMonth
                )

                // Skip if excluded by EXDATE
                if (occurrenceDayCode in excludedDayCodes) continue

                // Track this day code as generated
                generatedDayCodes.add(occurrenceDayCode)

                // If there's an override for this date, use it instead
                if (occurrenceDayCode in overrideDayCodes) {
                    overrides[occurrenceDayCode]?.let { occurrences.add(it) }
                    continue
                }

                // Create occurrence event with adjusted timestamps
                val occurrenceStart = ICalDateTime.fromZonedDateTime(occurrenceZdt, masterEvent.isAllDay)
                val occurrenceEnd = eventDuration?.let { dur ->
                    ICalDateTime.fromTimestamp(
                        occurrenceStart.timestamp + dur.toMillis(),
                        occurrenceStart.timezone,
                        masterEvent.isAllDay
                    )
                }

                val occurrence = masterEvent.copy(
                    importId = "${masterEvent.uid}:OCC:$occurrenceDayCode",
                    dtStart = occurrenceStart,
                    dtEnd = occurrenceEnd,
                    rrule = null,
                    exdates = emptyList(),
                    rdates = emptyList(),
                    recurrenceId = null
                )

                occurrences.add(occurrence)
            }
        }

        // ========== RDATE Expansion ==========
        // Add occurrences from RDATE that are within range, not excluded, and not duplicates
        for (rdate in masterEvent.rdates) {
            // Check if within range
            if (rdate.timestamp < rangeStart.toEpochMilli() ||
                rdate.timestamp >= rangeEnd.toEpochMilli()) continue

            val rdateDayCode = rdate.toDayCode()

            // Skip if excluded by EXDATE
            if (rdateDayCode in excludedDayCodes) continue

            // Skip if already generated by RRULE (avoid duplicates)
            if (rdateDayCode in generatedDayCodes) continue

            // Track this day code
            generatedDayCodes.add(rdateDayCode)

            // If there's an override for this date, use it instead
            if (rdateDayCode in overrideDayCodes) {
                overrides[rdateDayCode]?.let { occurrences.add(it) }
                continue
            }

            // Create occurrence from RDATE
            val occurrenceEnd = eventDuration?.let { dur ->
                ICalDateTime.fromTimestamp(
                    rdate.timestamp + dur.toMillis(),
                    rdate.timezone,
                    masterEvent.isAllDay
                )
            }

            val occurrence = masterEvent.copy(
                importId = "${masterEvent.uid}:OCC:$rdateDayCode",
                dtStart = rdate,
                dtEnd = occurrenceEnd,
                rrule = null,
                exdates = emptyList(),
                rdates = emptyList(),
                recurrenceId = null
            )

            occurrences.add(occurrence)
        }

        return occurrences.sortedBy { it.dtStart.timestamp }
    }

    /**
     * Expand with TimeRange convenience class.
     */
    fun expand(
        masterEvent: ICalEvent,
        range: TimeRange,
        overrides: Map<String, ICalEvent> = emptyMap()
    ): List<ICalEvent> = expand(masterEvent, range.start, range.end, overrides)

    /**
     * Build ical4j Recur from our RRule model.
     * Uses ical4j 4.x API with generics and java.time.
     */
    private fun buildRecur(rrule: RRule): Recur<LocalDateTime> {
        val freq = ICalFrequency.valueOf(rrule.freq.name)
        val builder = Recur.Builder<LocalDateTime>()
            .frequency(freq)
            .interval(rrule.interval)

        rrule.count?.let { builder.count(it) }
        rrule.until?.let {
            // ical4j 4.x: until() expects LocalDateTime
            val untilDate = it.toZonedDateTime().toLocalDateTime()
            builder.until(untilDate)
        }

        // Sub-daily BY* parts (RFC 5545 §3.3.10). Emitted in
        // BYSECOND/BYMINUTE/BYHOUR order to match ical4j's internal field
        // layout in Recur (Recur.java:298-302).
        rrule.bySecond?.let { builder.secondList(it) }
        rrule.byMinute?.let { builder.minuteList(it) }
        rrule.byHour?.let { builder.hourList(it) }

        rrule.byDay?.let { days ->
            val weekDayList = WeekDayList()
            days.forEach { weekdayNum ->
                val javaDay = weekdayNum.dayOfWeek
                // ical4j 4.x: WeekDay constructor takes (WeekDay, Int), not (DayOfWeek, Int)
                val weekDay = if (weekdayNum.ordinal != null) {
                    WeekDay(WeekDay.getWeekDay(javaDay), weekdayNum.ordinal)
                } else {
                    WeekDay.getWeekDay(javaDay)
                }
                weekDayList.add(weekDay)
            }
            builder.dayList(weekDayList)
        }

        rrule.byMonthDay?.let { days ->
            builder.monthDayList(NumberList(days.joinToString(",")))
        }

        rrule.byMonth?.let { months ->
            // ical4j 4.x: monthList accepts List<Month>
            val monthList = months.map { net.fortuna.ical4j.model.Month.valueOf(it) }
            builder.monthList(monthList)
        }

        rrule.byWeekNo?.let { weeks ->
            builder.weekNoList(NumberList(weeks.joinToString(",")))
        }

        rrule.byYearDay?.let { days ->
            builder.yearDayList(NumberList(days.joinToString(",")))
        }

        rrule.bySetPos?.let { positions ->
            builder.setPosList(NumberList(positions.joinToString(",")))
        }

        // ical4j 4.x: weekStartDay expects WeekDay
        builder.weekStartDay(WeekDay.getWeekDay(rrule.wkst))

        return builder.build()
    }

    /**
     * Calculate event duration from dtStart and dtEnd or duration property.
     */
    private fun calculateDuration(event: ICalEvent): Duration? {
        return event.duration ?: event.dtEnd?.let { dtEnd ->
            Duration.ofMillis(dtEnd.timestamp - event.dtStart.timestamp)
        }
    }

    companion object {
        /**
         * Create a map of day codes to override events from a list of modified instances.
         */
        fun buildOverrideMap(overrideEvents: List<ICalEvent>): Map<String, ICalEvent> {
            return overrideEvents
                .filter { it.recurrenceId != null }
                .associateBy { event ->
                    // Use the RECURRENCE-ID date as the key (the original occurrence date)
                    event.recurrenceId!!.toDayCode()
                }
        }
    }
}

/**
 * Time range for expansion queries.
 */
data class TimeRange(
    val start: Instant,
    val end: Instant
) {
    companion object {
        /**
         * Create a range for a specific month.
         */
        fun forMonth(year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val startOfMonth = LocalDate.of(year, month, 1).atStartOfDay(zone)
            val endOfMonth = startOfMonth.plusMonths(1)
            return TimeRange(startOfMonth.toInstant(), endOfMonth.toInstant())
        }

        /**
         * Create a range from now to N days in the future.
         */
        fun nextDays(days: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.toInstant(),
                now.plusDays(days).toInstant()
            )
        }

        /**
         * Create a range from N days ago to N days in the future.
         */
        fun aroundNow(daysBefore: Long, daysAfter: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.minusDays(daysBefore).toInstant(),
                now.plusDays(daysAfter).toInstant()
            )
        }

        /**
         * Create a 1-year range centered on now (typical sync window).
         */
        fun syncWindow(zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            return aroundNow(365, 365, zone)
        }
    }
}
