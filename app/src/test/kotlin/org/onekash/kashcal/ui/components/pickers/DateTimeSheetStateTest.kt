package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.FormDateFields
import java.time.LocalDate
import java.time.YearMonth

/** Tests the transitions of [DateTimeSheetState], the date and time sheet's working state. */
class DateTimeSheetStateTest {

    private val zone = "America/New_York"

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    private fun timedForm(
        start: LocalDate = day(2026, 10, 27), startHour: Int = 9, startMinute: Int = 0,
        end: LocalDate = day(2026, 10, 27), endHour: Int = 9, endMinute: Int = 30,
    ) = EventFormState(
        title = "Standup",
        isAllDay = false,
        dateMillis = phoneMidnight(start), startHour = startHour, startMinute = startMinute,
        endDateMillis = phoneMidnight(end), endHour = endHour, endMinute = endMinute,
    )

    private fun allDayForm(start: LocalDate, end: LocalDate) = EventFormState(
        isAllDay = true,
        dateMillis = phoneMidnight(start),
        endDateMillis = phoneMidnight(end),
    )

    private fun open(form: EventFormState, side: DateSelectionMode = DateSelectionMode.START) =
        DateTimeSheetState.open(form, side, defaultDurationMinutes = 30)

    private fun FormDateFields.startDate() = phoneLocalDate(dateMillis)
    private fun FormDateFields.endDate() = phoneLocalDate(endDateMillis)

    @Test
    fun `opening from the Ends row starts on the Ends side`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm(), DateSelectionMode.END)

        assertEquals(DateSelectionMode.END, sheet.side)
    }

    @Test
    fun `the displayed month starts at the opening side's date`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm(end = day(2026, 11, 2)), DateSelectionMode.END)

        assertEquals(YearMonth.of(2026, 11), sheet.displayedMonth)
        assertEquals(day(2026, 11, 2), sheet.editedDate)
    }

    @Test
    fun `a new start time keeps the length and updates the end`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm()).selectTime(DateSelectionMode.START, 14, 0)

        assertEquals(14, sheet.fields.startHour)
        assertEquals(14, sheet.fields.endHour)
        assertEquals(30, sheet.fields.endMinute)
    }

    @Test
    fun `a new start date keeps the length and moves the end with it`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm()).selectDate(DateSelectionMode.START, day(2026, 11, 5))

        assertEquals(day(2026, 11, 5), sheet.fields.startDate())
        assertEquals(day(2026, 11, 5), sheet.fields.endDate())
        assertEquals(9, sheet.fields.startHour)
        assertEquals(30, sheet.fields.endMinute)
    }

    @Test
    fun `a new end time changes only the end`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm(), DateSelectionMode.END).selectTime(DateSelectionMode.END, 11, 15)

        assertEquals(9, sheet.fields.startHour)
        assertEquals(11, sheet.fields.endHour)
        assertEquals(15, sheet.fields.endMinute)
    }

    // A wheel fling can report once more after the user flipped to the other side.
    @Test
    fun `a time for the Starts side changes the start while the Ends side is showing`() =
        withDeviceTimeZone(zone) {
            val sheet = open(timedForm())
                .flip(DateSelectionMode.END)
                .selectTime(DateSelectionMode.START, 10, 0)

            assertEquals(DateSelectionMode.END, sheet.side)
            assertEquals(10, sheet.fields.startHour)
            assertEquals(10, sheet.fields.endHour)
            assertEquals(30, sheet.fields.endMinute)
        }

    @Test
    fun `an end date before the start swaps them and stays on the Ends side`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm(), DateSelectionMode.END).selectDate(DateSelectionMode.END, day(2026, 10, 20))

        assertEquals(DateSelectionMode.END, sheet.side)
        assertEquals(day(2026, 10, 20), sheet.fields.startDate())
        assertEquals(day(2026, 10, 27), sheet.fields.endDate())
        assertEquals(day(2026, 10, 27), sheet.editedDate)
    }

    @Test
    fun `an all-day end date before the start swaps them`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 27), day(2026, 10, 29)), DateSelectionMode.END)
            .selectDate(DateSelectionMode.END, day(2026, 10, 25))

        assertEquals(day(2026, 10, 25), sheet.fields.startDate())
        assertEquals(day(2026, 10, 27), sheet.fields.endDate())
    }

    @Test
    fun `an all-day start move keeps the number of days`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 27), day(2026, 10, 29)))
            .selectDate(DateSelectionMode.START, day(2026, 11, 3))

        assertEquals(day(2026, 11, 3), sheet.fields.startDate())
        assertEquals(day(2026, 11, 5), sheet.fields.endDate())
    }

    @Test
    fun `flipping sides shows the other side's month`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm(start = day(2026, 10, 31), end = day(2026, 11, 2)))
            .flip(DateSelectionMode.END)

        assertEquals(DateSelectionMode.END, sheet.side)
        assertEquals(YearMonth.of(2026, 11), sheet.displayedMonth)
        assertEquals(day(2026, 11, 2), sheet.editedDate)
    }

    @Test
    fun `browsing months changes only the displayed month`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm()).setDisplayedMonth(YearMonth.of(2027, 2))

        assertEquals(YearMonth.of(2027, 2), sheet.displayedMonth)
        assertEquals(day(2026, 10, 27), sheet.fields.startDate())
    }

    @Test
    fun `a timed chip sets the length from either side`() = withDeviceTimeZone(zone) {
        val fromStart = open(timedForm()).applyChip(60)
        val fromEnd = open(timedForm(), DateSelectionMode.END).applyChip(120)

        assertEquals(10, fromStart.fields.endHour)
        assertEquals(0, fromStart.fields.endMinute)
        assertEquals(11, fromEnd.fields.endHour)
        assertEquals(9, fromEnd.fields.startHour)
    }

    @Test
    fun `an all-day chip sets the inclusive end`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 27), day(2026, 10, 27))).applyChip(5)

        assertEquals(day(2026, 10, 31), sheet.fields.endDate())
    }

    @Test
    fun `the chip matching the current length is marked`() = withDeviceTimeZone(zone) {
        val timedChips = listOf(30, 60, 120)
        val dayChips = listOf(2, 5, 7)

        assertEquals(30, open(timedForm()).selectedChip(timedChips))
        assertNull(open(timedForm(endMinute = 45)).selectedChip(timedChips))
        assertEquals(5, open(allDayForm(day(2026, 10, 27), day(2026, 10, 31))).selectedChip(dayChips))
        assertNull(open(allDayForm(day(2026, 10, 27), day(2026, 10, 27))).selectedChip(dayChips))
    }

    // ---- all-day: a Starts tap flips to Ends with the end hint ----

    @Test
    fun `an all-day Starts tap moves the start, flips to Ends and shows the end hint`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 27), day(2026, 10, 31)))
            .selectDate(DateSelectionMode.START, day(2026, 10, 20))

        assertEquals(DateSelectionMode.END, sheet.side)
        assertTrue(sheet.showEndHint)
        assertEquals(day(2026, 10, 20), sheet.fields.startDate())
        assertEquals(day(2026, 10, 24), sheet.fields.endDate())
    }

    @Test
    fun `the auto-flip keeps the month on screen`() = withDeviceTimeZone(zone) {
        // A 5-day event moved to start Oct 30 ends in November; the grid stays on October.
        val sheet = open(allDayForm(day(2026, 10, 27), day(2026, 10, 31)))
            .selectDate(DateSelectionMode.START, day(2026, 10, 30))

        assertEquals(day(2026, 11, 3), sheet.fields.endDate())
        assertEquals(YearMonth.of(2026, 10), sheet.displayedMonth)
    }

    @Test
    fun `the next tap sets the end and hides the hint`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 28), day(2026, 10, 28)))
            .selectDate(DateSelectionMode.START, day(2026, 10, 20))
            .selectDate(DateSelectionMode.END, day(2026, 10, 23))

        assertFalse(sheet.showEndHint)
        assertEquals(day(2026, 10, 20), sheet.fields.startDate())
        assertEquals(day(2026, 10, 23), sheet.fields.endDate())
    }

    @Test
    fun `a flip hides the hint`() = withDeviceTimeZone(zone) {
        val tapped = open(allDayForm(day(2026, 10, 28), day(2026, 10, 28))).selectDate(DateSelectionMode.START, day(2026, 10, 20))

        assertFalse(tapped.flip(DateSelectionMode.START).showEndHint)
        assertFalse(tapped.flip(DateSelectionMode.END).showEndHint)
    }

    @Test
    fun `a chip after the first tap sets the end and hides the hint`() = withDeviceTimeZone(zone) {
        val sheet = open(allDayForm(day(2026, 10, 28), day(2026, 10, 28)))
            .selectDate(DateSelectionMode.START, day(2026, 10, 20))
            .applyChip(5)

        assertFalse(sheet.showEndHint)
        assertEquals(day(2026, 10, 24), sheet.fields.endDate())
    }

    @Test
    fun `a timed Starts tap neither flips nor shows the hint`() = withDeviceTimeZone(zone) {
        val sheet = open(timedForm()).selectDate(DateSelectionMode.START, day(2026, 10, 29))

        assertEquals(DateSelectionMode.START, sheet.side)
        assertFalse(sheet.showEndHint)
    }

    @Test
    fun `the sheet opens without the hint`() = withDeviceTimeZone(zone) {
        assertFalse(open(allDayForm(day(2026, 10, 27), day(2026, 10, 31))).showEndHint)
        assertFalse(open(timedForm(), DateSelectionMode.END).showEndHint)
    }

    @Test
    fun `commit returns the date fields once`() = withDeviceTimeZone(zone) {
        val (committed, fields) = open(timedForm()).selectTime(DateSelectionMode.START, 14, 0).commit()
        val (_, again) = committed.commit()

        assertNotNull(fields)
        assertEquals(14, fields!!.startHour)
        assertNull(again)
    }

    @Test
    fun `commit carries the date fields as the form would store them`() = withDeviceTimeZone(zone) {
        val form = timedForm()
        val (_, fields) = open(form).commit()

        assertEquals(
            FormDateFields(
                dateMillis = form.dateMillis, endDateMillis = form.endDateMillis,
                startHour = 9, startMinute = 0, endHour = 9, endMinute = 30,
                startOffsetHintTs = null, endOffsetHintTs = null,
            ),
            fields,
        )
    }
}
