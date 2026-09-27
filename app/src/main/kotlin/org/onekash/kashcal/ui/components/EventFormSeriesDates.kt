package org.onekash.kashcal.ui.components

import org.onekash.kashcal.util.TimezoneUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Dates for an "All events" save made from a form opened on one occurrence of
 * a series. The form shows that occurrence's date and time; the series starts
 * earlier. Per RFC 5545 section 3.8.5.3 DTSTART defines the first instance of
 * the set, so writing the occurrence's date as the series start would cut the
 * occurrences before it.
 *
 * Both functions read dates the way [toStartEndTs] does: in the form's current
 * timezone for a timed form, as a UTC calendar date for an all-day one.
 */

/**
 * Returns true when the form's start falls on a different day from the
 * occurrence it was opened on ([occurrenceTs], all-day per [occurrenceIsAllDay]).
 */
internal fun EventFormState.occurrenceDateChanged(occurrenceTs: Long, occurrenceIsAllDay: Boolean): Boolean {
    val zone = TimezoneUtils.resolveZone(timezone)
    return dateOf(toStartEndTs().first, isAllDay, zone) != dateOf(occurrenceTs, occurrenceIsAllDay, zone)
}

/**
 * Returns the start and end to write for the whole series when the form was
 * opened on the occurrence at [occurrenceTs] and its date wasn't changed. An
 * untouched start keeps [seriesStartTs] as is, seconds and zone quirks included,
 * so an unchanged save writes nothing new. A timezone-only change keeps it too,
 * so a series whose first occurrence is across a DST change from the opened one
 * keeps the first occurrence's instant, not its clock time. A new clock time or
 * an all-day toggle lands on the series' own first date. The form's length is
 * kept either way.
 */
internal fun EventFormState.startEndAnchoredToSeries(
    occurrenceTs: Long,
    seriesStartTs: Long,
    seriesIsAllDay: Boolean,
): Pair<Long, Long> {
    val (formStart, formEnd) = toStartEndTs()
    val length = formEnd - formStart
    // An all-day toggle is a change even when the instants coincide (a timed
    // midnight in a zone at UTC+0 that day equals the all-day UTC midnight).
    if (formStart == occurrenceTs && isAllDay == seriesIsAllDay) return seriesStartTs to seriesStartTs + length
    val zone = TimezoneUtils.resolveZone(timezone)
    val firstDate = dateOf(seriesStartTs, seriesIsAllDay, zone)
    val start = if (isAllDay) {
        firstDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } else {
        ZonedDateTime.of(firstDate, LocalTime.of(startHour, startMinute), zone).toInstant().toEpochMilli()
    }
    return start to start + length
}

/** The calendar date of [ts]: its UTC date when all-day, else its date in [zone]. */
private fun dateOf(ts: Long, allDay: Boolean, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(ts).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
