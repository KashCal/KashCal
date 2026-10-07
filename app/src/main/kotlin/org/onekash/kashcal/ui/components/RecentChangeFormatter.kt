package org.onekash.kashcal.ui.components

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.changes.isSeriesRow
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Words a Recent changes row: the day header it sits under, the event's own date and time, and
 * what changed.
 *
 * A series row (no occurrence key, recurring) shows no date: its stored start is the first
 * occurrence, possibly months back. A timed series shows its time of day, plus the weekday when
 * it moved to another day; an all-day series shows its weekday. A series moved by whole weeks
 * keeps the same slot, so its row and its "Moved from" show dates instead. A changed occurrence
 * shows its full date. All-day dates are read in UTC, where they are stored; timed ones in
 * [zone], with the user's [timePattern].
 */
class RecentChangeFormatter(
    private val resources: Resources,
    private val timePattern: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) {
    private val timeFormat = DateTimeFormatter.ofPattern(timePattern, locale)
    private val weekdayFormat = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEE", locale), locale)
    private val dateFormat = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEMMMd", locale), locale)
    private val dateWithYearFormat =
        DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yEEEMMMd", locale), locale)

    /** Returns "Today", "Yesterday", or the arrival date ("Tue, Sep 29"). */
    fun dayHeader(detectedAt: Long, now: Long): String {
        val day = Instant.ofEpochMilli(detectedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> resources.getString(R.string.label_today)
            today.minusDays(1) -> resources.getString(R.string.label_yesterday)
            else -> date(day, today)
        }
    }

    /** Returns the event's own date and time as the row shows it. */
    fun whenText(item: RecentChangeItem, now: Long): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = if (item.isSeriesRow && !item.slotUnchanged) {
            seriesSlot(item)
        } else {
            moment(item.startTs, item.isAllDay, today)
        }
        val endOnly = item.changeType == ChangeType.MODIFIED && ChangedField.TIME in item.changedFields &&
            !item.moved && !item.isAllDay
        if (!endOnly) return start
        val sameDay = localDate(item.startTs, false) == localDate(item.endTs, false)
        val end = if (sameDay || item.isSeriesRow) time(item.endTs) else moment(item.endTs, false, today)
        return resources.getString(R.string.recent_changes_time_range, start, end)
    }

    /** Returns what changed ("Moved from 2:00 PM · Location changed"), or null if nothing. */
    fun detail(item: RecentChangeItem): String? {
        if (item.changeType == ChangeType.DELETED) {
            return if (item.instanceTs != 0L) resources.getString(R.string.recent_changes_cancelled) else null
        }
        if (item.changeType == ChangeType.NEW) return null
        val parts = buildList {
            if (item.moved) {
                add(resources.getString(R.string.recent_changes_moved_from, previousText(item)))
            } else if (ChangedField.TIME in item.changedFields) {
                add(resources.getString(R.string.recent_changes_time_changed))
            }
            if (ChangedField.LOCATION in item.changedFields) add(resources.getString(R.string.recent_changes_location_changed))
            if (ChangedField.TITLE in item.changedFields) add(resources.getString(R.string.recent_changes_title_changed))
            if (ChangedField.RECURRENCE in item.changedFields) add(resources.getString(R.string.recent_changes_repeat_changed))
        }
        return parts.takeIf { it.isNotEmpty() }
            ?.joinToString(resources.getString(R.string.recent_changes_detail_separator))
    }

    private val RecentChangeItem.moved get() = previousStartTs != null && previousStartTs != startTs

    private val RecentChangeItem.previousAllDay get() = previousIsAllDay ?: isAllDay

    /**
     * A changed series that moved but reads the same slot (weekday and time): moved by whole
     * weeks. A deleted row carries the previous start of a move merged into it, but shows no
     * "Moved from", so it keeps the plain slot.
     */
    private val RecentChangeItem.slotUnchanged: Boolean
        get() {
            if (!isSeriesRow || !moved || changeType != ChangeType.MODIFIED) return false
            val previous = previousStartTs!!
            return previousAllDay == isAllDay && slot(previous, previousAllDay) == slot(startTs, isAllDay)
        }

    /** Weekday for all-day, weekday and time otherwise ("Mon", "Mon, 2:00 PM"). */
    private fun slot(ts: Long, isAllDay: Boolean): String =
        if (isAllDay) weekday(ts, true) else dateTime(weekday(ts, false), time(ts))

    /** The series' slot: weekday for all-day, time of day plus weekday if the day changed. */
    private fun seriesSlot(item: RecentChangeItem): String {
        val previous = item.previousStartTs?.takeIf { item.moved }
        val dayChanged = previous != null &&
            weekday(previous, item.previousIsAllDay ?: false) != weekday(item.startTs, false)
        return if (item.isAllDay || dayChanged) slot(item.startTs, item.isAllDay) else time(item.startTs)
    }

    private fun previousText(item: RecentChangeItem): String {
        val previous = item.previousStartTs!!
        val previousAllDay = item.previousAllDay
        if (item.isSeriesRow) {
            if (item.slotUnchanged) return moment(previous, previousAllDay, localDate(item.startTs, item.isAllDay))
            val sameWeekday = !previousAllDay && !item.isAllDay &&
                weekday(previous, false) == weekday(item.startTs, false)
            return if (sameWeekday) time(previous) else slot(previous, previousAllDay)
        }
        if (previousAllDay) return date(localDate(previous, true), today = localDate(item.startTs, item.isAllDay))
        val sameDay = !item.isAllDay && localDate(previous, false) == localDate(item.startTs, false)
        return if (sameDay) time(previous) else moment(previous, false, localDate(item.startTs, item.isAllDay))
    }

    /** Formats a timed instant as date and time, all-day as date; a year only if not [today]'s. */
    private fun moment(ts: Long, isAllDay: Boolean, today: LocalDate): String {
        val day = date(localDate(ts, isAllDay), today)
        return if (isAllDay) day else dateTime(day, time(ts))
    }

    private fun date(day: LocalDate, today: LocalDate): String =
        day.format(if (day.year == today.year) dateFormat else dateWithYearFormat)

    /** Joins a day and a time the way the locale writes them ("Tue, 2:00 PM"; no comma in ja). */
    private fun dateTime(day: String, time: String): String =
        resources.getString(R.string.notification_date_time, day, time)

    private fun time(ts: Long): String = Instant.ofEpochMilli(ts).atZone(zone).format(timeFormat)

    private fun weekday(ts: Long, isAllDay: Boolean): String = zoned(ts, isAllDay).format(weekdayFormat)

    private fun localDate(ts: Long, isAllDay: Boolean): LocalDate =
        DateTimeUtils.eventTsToLocalDate(ts, isAllDay, zone)

    private fun zoned(ts: Long, isAllDay: Boolean): ZonedDateTime =
        DateTimeUtils.eventTsToZonedDateTime(ts, isAllDay, zone)
}
