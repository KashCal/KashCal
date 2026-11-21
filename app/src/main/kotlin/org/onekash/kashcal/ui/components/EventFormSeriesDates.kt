package org.onekash.kashcal.ui.components

import org.onekash.kashcal.util.TimezoneUtils
import org.onekash.kashcal.util.zone.ZoneCatalog
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
 * Both functions read a timed form's dates in its current timezone, as
 * [toStartEndTs] does, and an all-day one's as a UTC calendar date; the stored
 * series or occurrence is read in the zone it was stored in.
 */

/**
 * Returns true when the form's start falls on a different day from the
 * occurrence it was opened on ([occurrenceTs], all-day per [occurrenceIsAllDay]).
 * The form's day is read in its current zone and the occurrence's in the zone it
 * was stored in ([EventFormState.editingZone]), so a zone change alone, which
 * keeps the shown date, is not a date change.
 */
internal fun EventFormState.occurrenceDateChanged(occurrenceTs: Long, occurrenceIsAllDay: Boolean): Boolean {
    val formZone = TimezoneUtils.resolveZone(timezone)
    val occurrenceZone = TimezoneUtils.resolveZone(editingZone ?: timezone)
    return dateOf(toStartEndTs().first, isAllDay, formZone) != dateOf(occurrenceTs, occurrenceIsAllDay, occurrenceZone)
}

/**
 * Returns the start and end to write for the whole series when the form was
 * opened on the occurrence at [occurrenceTs] and its date wasn't changed. An
 * untouched start keeps [seriesStartTs] as is, seconds and zone quirks included,
 * so an unchanged save writes nothing new. A new clock time, a new zone or an
 * all-day toggle lands on the series' own first date, read in [seriesZone] (the
 * zone the series is stored in), at the form's clock time in the form's zone. The
 * form's length is kept either way.
 */
internal fun EventFormState.startEndAnchoredToSeries(
    occurrenceTs: Long,
    seriesStartTs: Long,
    seriesIsAllDay: Boolean,
    seriesZone: String?,
): Pair<Long, Long> {
    val (formStart, formEnd) = toStartEndTs()
    val length = formEnd - formStart
    // An all-day toggle is a change even when the instants coincide (a timed
    // midnight in a zone at UTC+0 that day equals the all-day UTC midnight).
    // Untouched only in the series' own zone: another zone can give the same instant on the opened
    // date (London and UTC in winter) and still put the first date at another clock time.
    val device = TimezoneUtils.getDeviceTimezone()
    val sameZone = isAllDay || ZoneCatalog.sameZone(timezone ?: device, seriesZone ?: device)
    if (formStart == occurrenceTs && isAllDay == seriesIsAllDay && sameZone) {
        return seriesStartTs to seriesStartTs + length
    }
    val zone = TimezoneUtils.resolveZone(timezone)
    val firstDate = dateOf(seriesStartTs, seriesIsAllDay, TimezoneUtils.resolveZone(seriesZone))
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
