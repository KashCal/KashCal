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
 * Expands recurring events into occurrences with ical4j's [Recur]: the RRULE's dates plus RDATEs,
 * minus EXDATEs, with each occurrence a RECURRENCE-ID exception anchors to replaced by that
 * exception. DTSTART isn't added on its own, so an event with RDATEs and no RRULE yields only
 * its RDATEs.
 */
class RRuleExpander {

    /**
     * Expands [masterEvent] into its occurrences from [rangeStart] to [rangeEnd], sorted by
     * start.
     *
     * Both bounds are inclusive for RRULE occurrences (ical4j's `getDates` keeps one at the
     * period end), and for an all-day series both are moved to the start of their UTC day. An
     * RDATE counts when it is at or after [rangeStart] and before [rangeEnd].
     *
     * EXDATE and RDATE match occurrences by calendar day, so an EXDATE removes every
     * occurrence on its day and an RDATE on a day already produced, by the RRULE or an earlier
     * RDATE, is dropped.
     *
     * @param masterEvent the event with an RRULE, RDATEs or both; one with neither is returned
     *   alone, whatever the range
     * @param overrides exceptions, each carrying a RECURRENCE-ID, that replace the occurrence
     *   they anchor to
     * @return each occurrence as a copy of the master with its own start and end and no
     *   recurrence properties, or the matching override itself
     */
    fun expand(
        masterEvent: ICalEvent,
        rangeStart: Instant,
        rangeEnd: Instant,
        overrides: List<ICalEvent> = emptyList()
    ): List<ICalEvent> {
        val rrule = masterEvent.rrule

        if (rrule == null && masterEvent.rdates.isEmpty()) {
            return listOf(masterEvent)
        }

        val occurrences = mutableListOf<ICalEvent>()

        // DATE values are stored as UTC midnight, so an all-day series expands in UTC to keep
        // its calendar dates. A timed series without a zone (UTC or floating) expands in the
        // JVM default zone.
        val eventZone = if (masterEvent.isAllDay) {
            ZoneOffset.UTC
        } else {
            masterEvent.dtStart.timezone ?: ZoneId.systemDefault()
        }

        val eventDuration = calculateDuration(masterEvent)

        val excludedDayCodes = masterEvent.exdates.map { it.toDayCode() }.toSet()

        // Index overrides by the instant of their RECURRENCE-ID, normalized to the master's
        // value type. RECURRENCE-ID identifies the original occurrence's start (RFC 5545
        // §3.8.4.4), not a calendar day. A day code would read Z-form and floating values in
        // the JVM default zone and collapse two overrides on the same date into one.
        val overrideInstants: List<Long> = overrides.map { ovr ->
            ovr.recurrenceId?.let { recId ->
                normalizeToMasterValueType(recId, masterEvent.dtStart).timestamp
            } ?: Long.MIN_VALUE  // no RECURRENCE-ID: matches no occurrence except one at the epoch
        }
        // Parallel to overrideInstants; set once an override is consumed so it replaces at
        // most one occurrence.
        val overrideUsed = BooleanArray(overrides.size)

        // Days already produced; an RDATE on one of them is dropped as a duplicate.
        val generatedDayCodes = mutableSetOf<String>()

        // Returns and consumes the first unused override whose RECURRENCE-ID instant is within
        // OVERRIDE_MATCH_TOLERANCE_MS of occurrenceInstantMs.
        fun matchOverride(occurrenceInstantMs: Long): ICalEvent? {
            if (overrides.isEmpty()) return null
            for (i in overrides.indices) {
                if (!overrideUsed[i] &&
                    kotlin.math.abs(overrideInstants[i] - occurrenceInstantMs) <= OVERRIDE_MATCH_TOLERANCE_MS
                ) {
                    overrideUsed[i] = true
                    return overrides[i]
                }
            }
            return null
        }

        // ========== RRULE Expansion ==========
        if (rrule != null) {
            val recur = buildRecur(rrule, eventZone, masterEvent.isAllDay)

            val eventStartZdt = masterEvent.dtStart.toZonedDateTime()

            val periodStart = ZonedDateTime.ofInstant(rangeStart, eventZone)
            val periodEnd = ZonedDateTime.ofInstant(rangeEnd, eventZone)

            // Recur works on LocalDateTime; an all-day series uses midnight.
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

                if (occurrenceDayCode in excludedDayCodes) continue

                generatedDayCodes.add(occurrenceDayCode)

                val override = matchOverride(occurrenceZdt.toInstant().toEpochMilli())
                if (override != null) {
                    occurrences.add(override)
                    continue
                }

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
        // RDATEs in range, not on an EXDATE day, and not on a day already produced.
        for (rdate in masterEvent.rdates) {
            if (rdate.timestamp < rangeStart.toEpochMilli() ||
                rdate.timestamp >= rangeEnd.toEpochMilli()) continue

            val rdateDayCode = rdate.toDayCode()

            if (rdateDayCode in excludedDayCodes) continue

            if (rdateDayCode in generatedDayCodes) continue

            generatedDayCodes.add(rdateDayCode)

            val override = matchOverride(rdate.timestamp)
            if (override != null) {
                occurrences.add(override)
                continue
            }

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

    /** Expands [masterEvent] over [range]; see the other overload. */
    fun expand(
        masterEvent: ICalEvent,
        range: TimeRange,
        overrides: List<ICalEvent> = emptyList()
    ): List<ICalEvent> = expand(masterEvent, range.start, range.end, overrides)

    /**
     * Builds the ical4j 4.x [Recur] for [rrule].
     *
     * ical4j compares UNTIL with occurrences as wall clocks in [eventZone].
     * A UTC UNTIL (the `Z` form, RFC 5545 section 3.3.10) is an instant, so on
     * a timed series it is moved into [eventZone] first; for a series anchored
     * to a TZID, reading it on the device clock would end the series at a
     * different occurrence depending on where the device is. Floating and DATE
     * values, and every UNTIL on an all-day series, keep the reading
     * [ICalDateTime.toZonedDateTime] gives them.
     */
    private fun buildRecur(rrule: RRule, eventZone: ZoneId, isAllDay: Boolean): Recur<LocalDateTime> {
        val freq = ICalFrequency.valueOf(rrule.freq.name)
        val builder = Recur.Builder<LocalDateTime>()
            .frequency(freq)
            .interval(rrule.interval)

        rrule.count?.let { builder.count(it) }
        rrule.until?.let {
            val untilDate = if (!isAllDay && it.isUtc && !it.isDate) {
                it.toInstant().atZone(eventZone).toLocalDateTime()
            } else {
                it.toZonedDateTime().toLocalDateTime()
            }
            builder.until(untilDate)
        }

        // Sub-daily BY* parts (RFC 5545 §3.3.10), set in BYSECOND/BYMINUTE/BYHOUR order to
        // match the declaration order of Recur's secondList, minuteList and hourList fields.
        rrule.bySecond?.let { builder.secondList(it) }
        rrule.byMinute?.let { builder.minuteList(it) }
        rrule.byHour?.let { builder.hourList(it) }

        rrule.byDay?.let { days ->
            val weekDayList = WeekDayList()
            days.forEach { weekdayNum ->
                val javaDay = weekdayNum.dayOfWeek
                // ical4j 4.x's WeekDay constructor takes (WeekDay, Int), not (DayOfWeek, Int).
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

        builder.weekStartDay(WeekDay.getWeekDay(rrule.wkst))

        return builder.build()
    }

    /** Returns the DURATION, else DTEND minus DTSTART, or null when the event has neither. */
    private fun calculateDuration(event: ICalEvent): Duration? {
        return event.duration ?: event.dtEnd?.let { dtEnd ->
            Duration.ofMillis(dtEnd.timestamp - event.dtStart.timestamp)
        }
    }

    companion object {
        /**
         * Tolerance, inclusive, for matching an occurrence's instant to a RECURRENCE-ID's.
         * It absorbs sub-second rounding and the odd off-by-a-minute a peer client emits. It
         * spans two occurrences only in a series with occurrences two minutes or less apart;
         * there the first one expanded takes the override.
         */
        private const val OVERRIDE_MATCH_TOLERANCE_MS = 60_000L

        /**
         * Converts a RECURRENCE-ID or other recurrence date to the value type of the master's
         * DTSTART, returning [value] unchanged when the types already match.
         *
         * Peer clients sometimes emit a RECURRENCE-ID whose value type differs from the
         * master's DTSTART (a bare DATE against a timed master, or a DATE-TIME against an
         * all-day master), and most CalDAV servers preserve it verbatim. Left as is, the two
         * describe different instants and the override silently fails to match its occurrence.
         *
         * - Timed master, DATE [value]: promoted to the master's time of day in the master's
         *   zone, UTC when the master has none. For a master with a TZID that is the instant
         *   its RRULE expansion produces that day.
         * - All-day master, DATE-TIME [value]: demoted to a DATE, taking the calendar date in
         *   [value]'s own zone, or UTC when floating or Z-form (how DATE values are stored),
         *   so the result doesn't depend on the JVM default zone.
         */
        fun normalizeToMasterValueType(value: ICalDateTime, masterDtStart: ICalDateTime): ICalDateTime {
            if (value.isDate == masterDtStart.isDate) return value

            return if (masterDtStart.isDate) {
                val zone = value.timezone ?: ZoneOffset.UTC
                val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(value.timestamp), zone).toLocalDate()
                ICalDateTime.fromLocalDate(date)
            } else {
                val masterZone = masterDtStart.timezone ?: ZoneOffset.UTC
                val masterLocalTime = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(masterDtStart.timestamp), masterZone)
                    .toLocalTime()
                // DATE values are stored as UTC midnight, so read the calendar date in UTC.
                val valueDate = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(value.timestamp), ZoneOffset.UTC)
                    .toLocalDate()
                val zoned = ZonedDateTime.of(valueDate, masterLocalTime, masterZone)
                ICalDateTime.fromZonedDateTime(zoned, isDate = false)
            }
        }
    }
}

/** Range for [RRuleExpander.expand], which documents how each bound is applied. */
data class TimeRange(
    val start: Instant,
    val end: Instant
) {
    companion object {
        /** Returns [month] (1-12) of [year] in [zone], midnight to midnight. */
        fun forMonth(year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val startOfMonth = LocalDate.of(year, month, 1).atStartOfDay(zone)
            val endOfMonth = startOfMonth.plusMonths(1)
            return TimeRange(startOfMonth.toInstant(), endOfMonth.toInstant())
        }

        /** Returns the range from now to [days] days from now. */
        fun nextDays(days: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.toInstant(),
                now.plusDays(days).toInstant()
            )
        }

        /** Returns the range from [daysBefore] days ago to [daysAfter] days from now. */
        fun aroundNow(daysBefore: Long, daysAfter: Long, zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            val now = ZonedDateTime.now(zone)
            return TimeRange(
                now.minusDays(daysBefore).toInstant(),
                now.plusDays(daysAfter).toInstant()
            )
        }

        /** Returns the range from 365 days ago to 365 days from now. */
        fun syncWindow(zone: ZoneId = ZoneId.systemDefault()): TimeRange {
            return aroundNow(365, 365, zone)
        }
    }
}
