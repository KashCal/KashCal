package org.onekash.kashcal.ui.components.pickers

import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.FormDateFields
import org.onekash.kashcal.ui.components.allDayLengthDays
import org.onekash.kashcal.ui.components.dateFields
import org.onekash.kashcal.ui.components.deviceLocalDate
import org.onekash.kashcal.ui.components.deviceMidnight
import org.onekash.kashcal.ui.components.lengthMinutes
import org.onekash.kashcal.ui.components.withAllDayEnd
import org.onekash.kashcal.ui.components.withAllDayLength
import org.onekash.kashcal.ui.components.withAllDayStart
import org.onekash.kashcal.ui.components.withDateFields
import org.onekash.kashcal.ui.components.withTimedEnd
import org.onekash.kashcal.ui.components.withTimedLength
import org.onekash.kashcal.ui.components.withTimedStart
import java.time.LocalDate
import java.time.YearMonth

/**
 * Holds the date and time sheet's working copy of an event's start and end while the user edits
 * both ends in one sheet. Every transition returns a new state.
 *
 * The edits apply the event form's own rules as they happen, so the other end updates live:
 * moving the start keeps the event's length, and an end date before the start date swaps them. The
 * copy carries only the date fields plus the all-day flag and time zone those rules read, so
 * nothing else the form changes while the sheet is open can be overwritten by [commit].
 *
 * @property side the end the grid and wheel are editing.
 * @property displayedMonth the month the grid shows.
 * @property showEndHint whether the all-day end hint shows: on after a date tap on the Starts side
 *   flips to Ends, off after the next date tap, a chip or a flip.
 */
internal data class DateTimeSheetState(
    private val form: EventFormState,
    val side: DateSelectionMode,
    val displayedMonth: YearMonth,
    private val defaultDurationMinutes: Int,
    private val committed: Boolean = false,
    val showEndHint: Boolean = false,
) {
    val isAllDay: Boolean get() = form.isAllDay

    /** The working start and end. */
    val fields: FormDateFields get() = form.dateFields()

    /** The date of the end being edited, which the grid shows as selected. */
    val editedDate: LocalDate get() = dateOf(side)

    /** Returns the device-local date of [side]'s end. */
    fun dateOf(side: DateSelectionMode): LocalDate = form.dateOf(side)

    /** Returns [side]'s hour (0-23) and minute. */
    fun timeOf(side: DateSelectionMode): Pair<Int, Int> =
        if (side == DateSelectionMode.START) form.startHour to form.startMinute
        else form.endHour to form.endMinute

    /** Switches editing to the given end, with the grid on that end's month. */
    fun flip(newSide: DateSelectionMode): DateTimeSheetState =
        copy(side = newSide, displayedMonth = YearMonth.from(dateOf(newSide)), showEndHint = false)

    fun setDisplayedMonth(month: YearMonth): DateTimeSheetState = copy(displayedMonth = month)

    /**
     * Sets [side]'s date. The side is passed by the caller, not read from the state's current
     * side, so a wheel or grid composed for one end edits that end even when its last report
     * arrives after a flip.
     *
     * For an all-day event a start date also moves editing to the end and shows the end hint,
     * keeping the month on screen, so the next tap can set the end; the event is already complete
     * (it keeps its number of days), so that tap is optional.
     */
    fun selectDate(side: DateSelectionMode, date: LocalDate): DateTimeSheetState {
        val millis = deviceMidnight(date)
        val (hour, minute) = timeOf(side)
        val updated = when {
            form.isAllDay && side == DateSelectionMode.START -> form.withAllDayStart(millis)
            form.isAllDay -> form.withAllDayEnd(millis, hour, minute)
            side == DateSelectionMode.START ->
                form.withTimedStart(millis, hour, minute, defaultDurationMinutes)
            else -> form.withTimedEnd(millis, hour, minute)
        }
        return when {
            form.isAllDay && side == DateSelectionMode.START ->
                copy(form = updated, side = DateSelectionMode.END, showEndHint = true)
            form.isAllDay -> copy(form = updated, showEndHint = false)
            else -> copy(form = updated)
        }
    }

    /** Sets [side]'s time; see [selectDate] for why the side is passed. */
    fun selectTime(side: DateSelectionMode, hour: Int, minute: Int): DateTimeSheetState {
        val updated = if (side == DateSelectionMode.START) {
            form.withTimedStart(form.dateMillis, hour, minute, defaultDurationMinutes)
        } else {
            form.withTimedEnd(form.endDateMillis, hour, minute)
        }
        return copy(form = updated)
    }

    /**
     * Sets the length: [length] minutes for a timed event, [length] days for an all-day one. It
     * sets the end, so the end hint goes.
     */
    fun applyChip(length: Int): DateTimeSheetState =
        copy(
            form = if (form.isAllDay) form.withAllDayLength(length) else form.withTimedLength(length),
            showEndHint = false,
        )

    /** Returns the one of [chips] equal to the current length, or null when none is. */
    fun selectedChip(chips: List<Int>): Int? {
        val length = if (form.isAllDay) form.allDayLengthDays() else form.lengthMinutes()
        return chips.firstOrNull { it == length }
    }

    /**
     * Returns the committed state and the start and end to hand to the form, or null fields when
     * this state was already committed, so a swipe-close racing Done applies once.
     */
    fun commit(): Pair<DateTimeSheetState, FormDateFields?> =
        if (committed) this to null else copy(committed = true) to fields

    companion object {
        /** Opens the sheet on [side] with a copy of [form]'s start and end. */
        fun open(
            form: EventFormState,
            side: DateSelectionMode,
            defaultDurationMinutes: Int,
        ): DateTimeSheetState {
            val copy = EventFormState(isAllDay = form.isAllDay, timezone = form.timezone)
                .withDateFields(form.dateFields())
            return DateTimeSheetState(copy, side, YearMonth.from(copy.dateOf(side)), defaultDurationMinutes)
        }

        private fun EventFormState.dateOf(side: DateSelectionMode): LocalDate =
            deviceLocalDate(if (side == DateSelectionMode.START) dateMillis else endDateMillis)
    }
}
