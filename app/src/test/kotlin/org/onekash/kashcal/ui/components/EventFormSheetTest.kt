package org.onekash.kashcal.ui.components

import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.components.pickers.DateSelectionMode

/**
 * Tests the event form's pure logic.
 *
 * Production functions called: [withTimedStart], [withTimedEnd], [withTimezone], [withAllDay],
 * [toStartEndTs], [endsBeforeStart], [toFormDateFields], [parseIso8601DurationToMinutes],
 * [canEditAttendees], [showSchedulingUnavailable], [eventFormHasUnsavedChanges],
 * [resolveFormDismiss] and [shouldRebaselineOnCalendarResolve].
 *
 * Other sections run inline copies and don't call production. Copies of form code: the
 * delete-confirmation predicate, the save-button gate, the all-day start-date move and the
 * calendar-intent start and end in [EventFormContent], and the occurrence start in
 * [toFormDateFields]. The separate-pickers, midnight-crossing, end-time, date-range
 * selection, range-highlight and cross-month, date-label and multi-day end-time sections
 * model picker logic with no production counterpart in the form. The DateSelectionMode
 * tests assert only enum values, the date-format tests only a multi-day check, and the
 * edit-mode and all-day date-range sections build an [EventFormState] and read it back.
 */
class EventFormSheetTest {

    // ========== Delete Button Confirmation Tests ==========

    /**
     * Copies the delete row's onToggle predicate in [EventFormContent]: the inline two-tap
     * confirmation is skipped only when the host's scope sheet will appear, which happens only
     * for a recurring master. Returns true when the inline confirmation must be shown.
     */
    private fun shouldShowInlineDeleteConfirm(
        wasRecurringAtLoad: Boolean,
        loadedIsDetachedException: Boolean
    ): Boolean {
        return !(wasRecurringAtLoad && !loadedIsDetachedException)
    }

    @Test
    fun `delete on non-recurring event shows inline confirmation`() {
        assertTrue(shouldShowInlineDeleteConfirm(
            wasRecurringAtLoad = false,
            loadedIsDetachedException = false
        ))
    }

    @Test
    fun `delete on recurring master skips inline confirmation - scope sheet confirms instead`() {
        assertFalse(shouldShowInlineDeleteConfirm(
            wasRecurringAtLoad = true,
            loadedIsDetachedException = false
        ))
    }

    @Test
    fun `delete on exception event shows inline confirmation - scope sheet does NOT appear`() {
        // An exception also has wasRecurringAtLoad=true (via originalEventId), but
        // its delete goes straight to the single-occurrence delete without a scope
        // sheet. Skipping the inline confirmation would make one tap delete it.
        assertTrue(shouldShowInlineDeleteConfirm(
            wasRecurringAtLoad = true,
            loadedIsDetachedException = true
        ))
    }

    // ========== Occurrence Date Tests ==========

    /**
     * Copies the occurrence start in [toFormDateFields], without its exception branch: an
     * occurrence edit starts at occurrenceTs, not the master's start, and keeps the duration.
     */
    private fun calculateActualStartTs(
        eventStartTs: Long,
        eventEndTs: Long,
        occurrenceTs: Long?
    ): Pair<Long, Long> {
        val eventDuration = eventEndTs - eventStartTs
        val actualStartTs = occurrenceTs ?: eventStartTs
        val actualEndTs = actualStartTs + eventDuration
        return Pair(actualStartTs, actualEndTs)
    }

    @Test
    fun `form loads with occurrenceTs date when editing single occurrence`() {
        // Master event: a 1-hour event on Jan 1, 2024.
        val masterStartTs = 1704106800000L // Jan 1, 2024 11:00 UTC
        val masterEndTs = 1704110400000L   // Jan 1, 2024 12:00 UTC

        // Occurrence one week later.
        val occurrenceTs = 1704711600000L  // Jan 8, 2024 11:00 UTC

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        // Should use occurrence date, not master date
        assertTrue("Should use occurrenceTs for start", actualStart == occurrenceTs)
        assertTrue("End should be occurrence + duration", actualEnd == occurrenceTs + (masterEndTs - masterStartTs))
        assertTrue("Duration should be preserved", actualEnd - actualStart == masterEndTs - masterStartTs)
    }

    @Test
    fun `form loads with master event date when occurrenceTs is null`() {
        // Master event: Jan 1, 2024 11:00-12:00 UTC
        val masterStartTs = 1704106800000L
        val masterEndTs = 1704110400000L

        // No occurrenceTs (editing master event or all occurrences)
        val occurrenceTs: Long? = null

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        // Should use master event date
        assertTrue("Should use master startTs", actualStart == masterStartTs)
        assertTrue("Should use master endTs", actualEnd == masterEndTs)
    }

    @Test
    fun `occurrence date preserves event duration`() {
        // Master event: 2 hour duration
        val masterStartTs = 1704106800000L // 11:00 UTC
        val masterEndTs = 1704114000000L   // 13:00 UTC (2 hours)
        val expectedDuration = masterEndTs - masterStartTs // 2 hours = 7200000ms

        // Occurrence on different date
        val occurrenceTs = 1704711600000L

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        assertTrue("Duration should be exactly 2 hours", actualEnd - actualStart == expectedDuration)
    }

    // ========== Duration Maintenance Tests ==========

    /**
     * Returns whether two instants fall on different days in the JVM default zone, comparing
     * year and DAY_OF_YEAR.
     */
    private fun isMultiDayTest(startDateMillis: Long, endDateMillis: Long): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        return startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    @Test
    fun `end time follows start time maintaining duration`() {
        // Given: start=10:00, end=10:30 (30 min duration)
        val (newEndHour, newEndMinute, _) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 10, oldEndMinute = 30,
            oldStartDateMillis = 1704106800000L, // Jan 1
            oldEndDateMillis = 1704106800000L,   // Jan 1 (same day)
            newStartHour = 14, newStartMinute = 0,
            defaultDuration = 20
        )
        // Then: end should be 14:30
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 30", 30, newEndMinute)
    }

    @Test
    fun `end time uses default 20 min when duration invalid`() {
        // Given: start=10:00, end=09:00 (negative duration)
        val (newEndHour, newEndMinute, _) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 9, oldEndMinute = 0,
            oldStartDateMillis = 1704106800000L,
            oldEndDateMillis = 1704106800000L,
            newStartHour = 14, newStartMinute = 0,
            defaultDuration = 20
        )
        // Then: end should be 14:20 (default)
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
    }

    @Test
    fun `midnight crossing updates endDateMillis to next day`() {
        // Given: start=22:00, end=22:30, same date
        val startDateMillis = 1704106800000L // Jan 1
        val (newEndHour, newEndMinute, newEndDateMillis) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 22, oldStartMinute = 0,
            oldEndHour = 22, oldEndMinute = 30,
            oldStartDateMillis = startDateMillis,
            oldEndDateMillis = startDateMillis,
            newStartHour = 23, newStartMinute = 50,
            defaultDuration = 20
        )
        // Then: end=00:20, endDateMillis = next day
        assertEquals("End hour should be 0", 0, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
        assertTrue("endDateMillis should be next day", newEndDateMillis > startDateMillis)
    }

    @Test
    fun `midnight crossing preserves duration across day boundary`() {
        // Given: start=23:00, end=00:30 (+1 day), duration=90 mins
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDateMillis) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 23, oldStartMinute = 0,
            oldEndHour = 0, oldEndMinute = 30,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2, // End is on day 2
            newStartHour = 23, newStartMinute = 30,
            defaultDuration = 20
        )
        // Then: end=01:00 (+1 day), duration still 90 mins
        assertEquals("End hour should be 1", 1, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertTrue("endDateMillis should be next day", newEndDateMillis > day1)
    }

    @Test
    fun `returning from midnight crossing resets endDateMillis`() {
        // Given: start=23:50, end=00:10 (+1 day)
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDateMillis) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 23, oldStartMinute = 50,
            oldEndHour = 0, oldEndMinute = 10,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2,
            newStartHour = 10, newStartMinute = 0,
            defaultDuration = 20
        )
        // Then: end=10:20, endDateMillis = same day
        assertEquals("End hour should be 10", 10, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
        assertFalse("End should stay on the same day", isMultiDayTest(day1, newEndDateMillis))
    }

    @Test
    fun `same day event with different timestamps does not add 24h duration`() {
        // Given: a 10 AM - 11 AM event whose start and end dates carry different
        // timestamps on the same day, as stored events do.
        val jan2_10am = 1704193200000L  // Jan 2, 2024 11:00 UTC
        val jan2_11am = jan2_10am + (60 * 60 * 1000)  // 1 hour later

        // When: User changes start to 12:00 AM (midnight)
        val (newEndHour, newEndMinute, newEndDateMillis) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 11, oldEndMinute = 0,
            oldStartDateMillis = jan2_10am,
            oldEndDateMillis = jan2_11am,  // Different timestamp, same day.
            newStartHour = 0, newStartMinute = 0, // User picks 12:00 AM
            defaultDuration = 20
        )

        // Then: Should preserve 1-hour duration, end at 1:00 AM same day
        assertEquals("End hour should be 1 (1 AM)", 1, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertFalse("Should stay same day", isMultiDayTest(jan2_10am, newEndDateMillis))
    }

    // ========== shouldShowSeparatePickers Tests ==========

    /**
     * Models separate start and end pickers: shown for a multi-day range, except a
     * one-day-apart range whose end hour is before its start hour (a midnight crossing).
     * No production function matches it.
     */
    private fun shouldShowSeparatePickers(
        startDateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        endHour: Int
    ): Boolean {
        // Check if dates are different
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        val isMultiDay = startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)

        if (!isMultiDay) return false

        // If exactly 1 day apart and end time < start time, it's a midnight crossing
        val daysDiff = (endDateMillis - startDateMillis) / (24 * 60 * 60 * 1000)
        if (daysDiff == 1L && endHour < startHour) {
            return false  // Midnight crossing - keep merged view
        }

        return true
    }

    @Test
    fun `same day event shows merged picker`() {
        val day1 = 1704106800000L
        assertFalse(
            "Same day should show merged picker",
            shouldShowSeparatePickers(day1, day1, 10, 11)
        )
    }

    @Test
    fun `midnight crossing shows merged picker with +1`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // 1 day apart, endHour(1) < startHour(23) = midnight crossing
        assertFalse(
            "Midnight crossing should show merged picker",
            shouldShowSeparatePickers(day1, day2, 23, 1)
        )
    }

    @Test
    fun `true multi-day event shows separate pickers`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // 1 day apart, endHour(14) > startHour(10) = not a midnight crossing
        assertTrue(
            "True multi-day should show separate pickers",
            shouldShowSeparatePickers(day1, day2, 10, 14)
        )
    }

    @Test
    fun `2+ day event always shows separate pickers`() {
        val day1 = 1704106800000L
        val day3 = day1 + (2 * 24 * 60 * 60 * 1000) // 2 days later
        assertTrue(
            "2+ day event should show separate pickers",
            shouldShowSeparatePickers(day1, day3, 23, 1)
        )
    }

    @Test
    fun `midnight crossing edge case - exactly at midnight`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // Start at 23:30, end at 00:00 (exactly midnight)
        assertFalse(
            "End at midnight should show merged picker",
            shouldShowSeparatePickers(day1, day2, 23, 0)
        )
    }

    // ========== isMidnightCrossing Tests ==========

    /**
     * Models a midnight crossing as a different calendar day with an end hour before the start
     * hour. Comparing `endDateMillis > startDateMillis` would flag every same-day event, since
     * the timestamps carry a time. The production
     * [org.onekash.kashcal.ui.components.pickers.isMidnightCrossing] compares only the clock
     * times, not the dates, and is tested in `DateTimePickerTest`.
     */
    private fun isMidnightCrossing(
        startDateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        endHour: Int
    ): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        val isMultiDay = startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)

        return isMultiDay && endHour < startHour
    }

    @Test
    fun `isMidnightCrossing returns false for same day event`() {
        // 10 AM to 10 PM on one day: no +1, although the timestamps differ.
        val day1 = 1704106800000L  // Jan 1, 2024 11:00 UTC
        val day1Later = day1 + (12 * 60 * 60 * 1000)  // 12 hours later, 23:00 UTC
        assertFalse(
            "Same day event should NOT show +1",
            isMidnightCrossing(day1, day1Later, 10, 22)
        )
    }

    @Test
    fun `isMidnightCrossing returns true for late night to early morning`() {
        // 10 PM to 2 AM - should show +1 (true midnight crossing)
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertTrue(
            "10 PM to 2 AM should show +1",
            isMidnightCrossing(day1, day2, 22, 2)
        )
    }

    @Test
    fun `isMidnightCrossing returns true for 11-30 PM to 12-30 AM`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertTrue(
            "11:30 PM to 12:30 AM should show +1",
            isMidnightCrossing(day1, day2, 23, 0)
        )
    }

    @Test
    fun `isMidnightCrossing returns false for true multi-day event`() {
        // 10 AM to 3 PM next day: a multi-day event, not a midnight crossing.
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 AM to 3 PM next day should NOT show +1 (multi-day event)",
            isMidnightCrossing(day1, day2, 10, 15)
        )
    }

    @Test
    fun `isMidnightCrossing returns false for exactly 24 hour event`() {
        // 10 PM to 10 PM next day: 24 hours, not a midnight crossing.
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 PM to 10 PM next day should NOT show +1",
            isMidnightCrossing(day1, day2, 22, 22)
        )
    }

    // ========== onEndTimeSelected Tests ==========

    /**
     * Models an end-time pick that moves the end date to the next day when the end hour is
     * before the start hour. The form's [withTimedEnd] takes the end date from the picker
     * instead.
     */
    private fun calculateEndDateMillisForEndTimeChange(
        startDateMillis: Long,
        startHour: Int,
        newEndHour: Int
    ): Long {
        val crossesMidnight = newEndHour < startHour
        return if (crossesMidnight) {
            startDateMillis + (24 * 60 * 60 * 1000)
        } else {
            startDateMillis
        }
    }

    @Test
    fun `changing end time to cross midnight updates endDateMillis to next day`() {
        // Given: Event starting at 10 PM same day
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end to 1 AM (crosses midnight)
        val newEndHour = 1
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should be next day
        val expectedNextDay = day1 + (24 * 60 * 60 * 1000)
        assertEquals("End should be next day", expectedNextDay, newEndDateMillis)
        assertTrue("Should detect as multi-day", isMultiDayTest(day1, newEndDateMillis))
        assertTrue("Should show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `changing end time within same day keeps endDateMillis unchanged`() {
        // Given: Event starting at 10 AM same day
        val day1 = 1704106800000L
        val startHour = 10

        // When: User changes end to 2 PM (same day, no midnight crossing)
        val newEndHour = 14
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should stay same day
        assertEquals("End should be same day", day1, newEndDateMillis)
        assertFalse("Should NOT show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `changing end time from midnight crossing back to same day`() {
        // Given: Event starting at 10 PM
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end from 1 AM back to 11 PM (same day as start)
        val newEndHour = 23
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should stay same day (not next day)
        assertEquals("End should be same day", day1, newEndDateMillis)
        assertFalse("Should NOT show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `end time at exactly midnight shows +1`() {
        // Given: Event starting at 10 PM
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end to 12:00 AM (midnight = hour 0)
        val newEndHour = 0
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should be next day, +1 should show
        val expectedNextDay = day1 + (24 * 60 * 60 * 1000)
        assertEquals("End should be next day", expectedNextDay, newEndDateMillis)
        assertTrue("Should show +1 for midnight", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    // ========== Date Range Picker Tests ==========
    //
    // Model a single date-range picker with Start and End tabs: a start after the
    // end moves the end, an end before the start swaps them, and picking the start
    // date as the end makes a same-day range. The form uses separate Start and End
    // sheets; its End sheet does the swap ([withTimedEnd] for a timed form).

    /**
     * Models a date pick for the [activeSelection] tab and returns
     * Pair(newStartDateMillis, newEndDateMillis).
     */
    private fun simulateDateSelection(
        currentStartMillis: Long,
        currentEndMillis: Long,
        selectedMillis: Long,
        activeSelection: DateSelectionMode
    ): Pair<Long, Long> {
        return if (activeSelection == DateSelectionMode.START) {
            val newStart = selectedMillis
            // A start after the end moves the end to it.
            val newEnd = if (selectedMillis > currentEndMillis) selectedMillis else currentEndMillis
            Pair(newStart, newEnd)
        } else {
            if (selectedMillis < currentStartMillis) {
                // Swap: the picked date becomes the start, the old start the end.
                Pair(selectedMillis, currentStartMillis)
            } else {
                Pair(currentStartMillis, selectedMillis)
            }
        }
    }

    @Test
    fun `selecting start date updates startDateMillis`() {
        val jan1 = 1704067200000L  // Jan 1, 2024
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)  // Jan 5, 2024

        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1,
            selectedMillis = jan5,
            activeSelection = DateSelectionMode.START
        )

        assertEquals("Start should be Jan 5", jan5, newStart)
        assertEquals("End should move to Jan 5 (was before)", jan5, newEnd)
    }

    @Test
    fun `selecting end date updates endDateMillis`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1,
            selectedMillis = jan5,
            activeSelection = DateSelectionMode.END
        )

        assertEquals("Start should stay Jan 1", jan1, newStart)
        assertEquals("End should be Jan 5", jan5, newEnd)
    }

    @Test
    fun `selecting end before start triggers smart swap`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val dec25 = jan1 - (7 * 24 * 60 * 60 * 1000)  // Dec 25, 2023

        // Start = Jan 5, End = Jan 5
        // User selects Dec 25 as end date (before start)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan5,
            currentEndMillis = jan5,
            selectedMillis = dec25,
            activeSelection = DateSelectionMode.END
        )

        // Should swap: Dec 25 becomes start, Jan 5 becomes end
        assertEquals("Start should be Dec 25 (swapped)", dec25, newStart)
        assertEquals("End should be Jan 5 (swapped)", jan5, newEnd)
    }

    @Test
    fun `selecting start after end auto-adjusts end`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val jan10 = jan1 + (9 * 24 * 60 * 60 * 1000)

        // Start = Jan 1, End = Jan 5
        // User selects Jan 10 as start date (after end)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan5,
            selectedMillis = jan10,
            activeSelection = DateSelectionMode.START
        )

        // End should move to Jan 10 to maintain valid range
        assertEquals("Start should be Jan 10", jan10, newStart)
        assertEquals("End should move to Jan 10", jan10, newEnd)
    }

    @Test
    fun `selecting same date as start in END mode confirms same-day`() {
        val jan1 = 1704067200000L

        // Start = Jan 1, End = Jan 5
        // User selects Jan 1 as end date (same as start)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1 + (4 * 24 * 60 * 60 * 1000),
            selectedMillis = jan1,
            activeSelection = DateSelectionMode.END
        )

        // Both should be Jan 1 (same-day event)
        assertEquals("Start should stay Jan 1", jan1, newStart)
        assertEquals("End should be Jan 1", jan1, newEnd)
    }

    @Test
    fun `date range selection preserves time components`() {
        // Copying a new date into the state leaves its time fields as they were.
        val jan1_10am = 1704103200000L  // Jan 1, 2024 10:00 UTC
        val jan1_11am = jan1_10am + (60 * 60 * 1000)  // Jan 1, 2024 11:00 UTC

        val initial = EventFormState(
            dateMillis = jan1_10am,
            endDateMillis = jan1_11am,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0
        )

        val updated = initial.copy(dateMillis = jan1_10am + (24 * 60 * 60 * 1000))  // Jan 2

        assertEquals("Start hour should be preserved", 10, updated.startHour)
        assertEquals("End hour should be preserved", 11, updated.endHour)
    }

    @Test
    fun `multi-day date range triggers separate time pickers`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        // 4-day event, 10 AM - 3 PM
        val shouldSeparate = shouldShowSeparatePickers(jan1, jan5, 10, 15)
        assertTrue("4-day event should show separate pickers", shouldSeparate)
    }

    @Test
    fun `same-day date range uses merged time picker`() {
        val jan1 = 1704067200000L

        val shouldSeparate = shouldShowSeparatePickers(jan1, jan1, 10, 11)
        assertFalse("Same-day event should use merged picker", shouldSeparate)
    }

    // ========== DateSelectionMode State Machine Tests ==========

    @Test
    fun `selection mode START allows start date changes`() {
        val mode = DateSelectionMode.START
        assertTrue("START mode should be for selecting start", mode == DateSelectionMode.START)
    }

    @Test
    fun `selection mode END allows end date changes`() {
        val mode = DateSelectionMode.END
        assertTrue("END mode should be for selecting end", mode == DateSelectionMode.END)
    }

    @Test
    fun `auto-advance from START to END after selection`() {
        // Models the advance from the Start tab to End after a start pick.
        var activeSelection = DateSelectionMode.START

        if (activeSelection == DateSelectionMode.START) {
            activeSelection = DateSelectionMode.END
        }

        assertEquals("Should auto-advance to END", DateSelectionMode.END, activeSelection)
    }

    // ========== Range Highlighting Tests ==========

    // Model a range picker's day highlighting: the start day, the end day, and the
    // days strictly between them. Only the predicates are tested, not colors.

    private fun isInRange(dayMillis: Long, startMillis: Long, endMillis: Long): Boolean {
        return dayMillis > startMillis && dayMillis < endMillis
    }

    private fun isStartDate(dayMillis: Long, startMillis: Long): Boolean {
        val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = dayMillis }
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
        return dayCal.get(java.util.Calendar.YEAR) == startCal.get(java.util.Calendar.YEAR) &&
            dayCal.get(java.util.Calendar.DAY_OF_YEAR) == startCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun isEndDate(dayMillis: Long, endMillis: Long): Boolean {
        val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = dayMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endMillis }
        return dayCal.get(java.util.Calendar.YEAR) == endCal.get(java.util.Calendar.YEAR) &&
            dayCal.get(java.util.Calendar.DAY_OF_YEAR) == endCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    @Test
    fun `start date gets primary highlighting`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 1 should be start date", isStartDate(jan1, jan1))
        assertFalse("Jan 1 should not be in range", isInRange(jan1, jan1, jan5))
    }

    @Test
    fun `end date gets tertiary highlighting`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 5 should be end date", isEndDate(jan5, jan5))
        assertFalse("Jan 5 should not be in range", isInRange(jan5, jan1, jan5))
    }

    @Test
    fun `days in range get container highlighting`() {
        val jan1 = 1704067200000L
        val jan3 = jan1 + (2 * 24 * 60 * 60 * 1000)
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 3 should be in range", isInRange(jan3, jan1, jan5))
        assertFalse("Jan 3 should not be start", isStartDate(jan3, jan1))
        assertFalse("Jan 3 should not be end", isEndDate(jan3, jan5))
    }

    @Test
    fun `same day shows primary only - no range`() {
        val jan1 = 1704067200000L

        assertTrue("Jan 1 should be start date", isStartDate(jan1, jan1))
        assertTrue("Jan 1 should also be end date", isEndDate(jan1, jan1))
        assertFalse("No range when same day", isInRange(jan1, jan1, jan1))
    }

    // ========== Cross-Month Range Tests ==========

    @Test
    fun `range spanning months highlights correctly`() {
        val dec30 = 1703894400000L  // Dec 30, 2023
        val jan2 = dec30 + (3 * 24 * 60 * 60 * 1000)  // Jan 2, 2024
        val dec31 = dec30 + (24 * 60 * 60 * 1000)
        val jan1 = dec30 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Dec 30 is start", isStartDate(dec30, dec30))
        assertTrue("Jan 2 is end", isEndDate(jan2, jan2))
        assertTrue("Dec 31 is in range", isInRange(dec31, dec30, jan2))
        assertTrue("Jan 1 is in range", isInRange(jan1, dec30, jan2))
    }

    @Test
    fun `range spanning years highlights correctly`() {
        val dec30_2023 = 1703894400000L
        val jan3_2024 = dec30_2023 + (4 * 24 * 60 * 60 * 1000)
        val jan1_2024 = dec30_2023 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Range crosses year boundary", isInRange(jan1_2024, dec30_2023, jan3_2024))
    }

    // ========== Date Format Display Tests ==========

    @Test
    fun `collapsed row shows both dates for multi-day`() {
        // A collapsed row would read "Tue, Jan 2 → Thu, Jan 4"; only the multi-day check
        // is asserted, not the text.
        val jan2 = 1704153600000L  // Jan 2, 2024
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Should be multi-day", isMultiDayTest(jan2, jan4))
    }

    @Test
    fun `collapsed row shows single date for same-day`() {
        // A collapsed row would read "Tue, Jan 2" with no arrow; only the same-day check
        // is asserted, not the text.
        val jan2 = 1704153600000L

        assertFalse("Should be same-day", isMultiDayTest(jan2, jan2))
    }

    // ========== Edit Mode Date Range Tests ==========

    @Test
    fun `edit mode loads multi-day event dates correctly`() {
        // Given: Existing multi-day event Jan 2 - Jan 5
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val state = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            dateMillis = jan2,
            endDateMillis = jan5
        )

        assertTrue("Should show as multi-day", isMultiDayTest(state.dateMillis, state.endDateMillis))
        assertEquals("Start should be Jan 2", jan2, state.dateMillis)
        assertEquals("End should be Jan 5", jan5, state.endDateMillis)
    }

    @Test
    fun `edit mode loads same-day event dates correctly`() {
        val jan2 = 1704153600000L

        val state = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            dateMillis = jan2,
            endDateMillis = jan2
        )

        assertFalse("Should show as same-day", isMultiDayTest(state.dateMillis, state.endDateMillis))
    }

    // ========== All-Day Event Date Range Tests ==========

    @Test
    fun `all-day event uses unified date range picker`() {
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val state = EventFormState(
            isAllDay = true,
            dateMillis = jan2,
            endDateMillis = jan5
        )

        assertTrue("All-day multi-day event detected", isMultiDayTest(state.dateMillis, state.endDateMillis))
    }

    @Test
    fun `toggling all-day preserves date range`() {
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val initial = EventFormState(
            isAllDay = false,
            dateMillis = jan2,
            endDateMillis = jan5
        )
        val state = initial.copy(isAllDay = true)

        assertEquals("Start date preserved", jan2, state.dateMillis)
        assertEquals("End date preserved", jan5, state.endDateMillis)
    }

    // ========== Time Picker Date Label Tests ==========
    //
    // Model date labels under a time picker's Start and End tabs, decided by
    // shouldShowSeparatePickers: shown for a multi-day event, hidden for a
    // same-day event and a midnight crossing.
    //   [Start]    [End]
    //   [Jan 2]    [Jan 4]

    @Test
    fun `multi-day event shows date labels in unified time picker`() {
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        val showDateLabels = shouldShowSeparatePickers(jan2, jan4, 10, 14)
        assertTrue("Multi-day event should show date labels", showDateLabels)
    }

    @Test
    fun `same-day event hides date labels in unified time picker`() {
        val jan2 = 1704153600000L

        val showDateLabels = shouldShowSeparatePickers(jan2, jan2, 10, 14)
        assertFalse("Same-day event should hide date labels", showDateLabels)
    }

    @Test
    fun `midnight crossing event hides date labels (single logical event)`() {
        val jan2 = 1704153600000L
        val jan3 = jan2 + (24 * 60 * 60 * 1000)

        // 10 PM to 2 AM = midnight crossing, not a multi-day event
        val showDateLabels = shouldShowSeparatePickers(jan2, jan3, 22, 2)
        assertFalse("Midnight crossing should hide date labels", showDateLabels)
    }

    @Test
    fun `multi-day duration calculation works with unified time picker`() {
        // Jan 2 10 AM - Jan 4 3 PM (multi-day event)
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        // This is a multi-day event
        assertTrue("Should detect as multi-day", isMultiDayTest(jan2, jan4))

        // The duration across days is 53 hours (3180 minutes), computed inline.
        val startMinutes = 10 * 60  // 10:00 AM
        val endMinutes = 15 * 60    // 3:00 PM
        // Duration across days: (24h - 10h) + 24h + 15h = 53 hours
        val durationMinutes = (24 * 60 - startMinutes) + (24 * 60) + endMinutes
        assertEquals("Multi-day duration calculation", 53 * 60, durationMinutes)
    }

    // ========== Multi-Day End-Time Tests ==========

    /**
     * Models an end-time pick that keeps a multi-day event's end date. A same-day event moves
     * its end to the next day when the end hour is before the start hour; resetting the end
     * date to the start date would collapse a multi-day event to one day.
     */
    private fun calculateEndDateMillisForEndTimeChangeFix(
        dateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        newEndHour: Int
    ): Long {
        val isSameDay = !isMultiDayTest(dateMillis, endDateMillis)
        val crossesMidnight = newEndHour < startHour
        return when {
            !isSameDay -> endDateMillis  // A multi-day end date stays.
            crossesMidnight -> dateMillis + (24 * 60 * 60 * 1000)
            else -> dateMillis
        }
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected preserves endDateMillis for multi-day events`() {
        // Given: Multi-day event Jan 2 - Jan 5
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        // Verify it's multi-day
        assertTrue("Should be multi-day", isMultiDayTest(jan2, jan5))

        // When: User changes end time from 3 PM to 4 PM
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan5,
            startHour = 10,
            newEndHour = 16
        )

        // Then: endDateMillis should stay Jan 5 (not reset to Jan 2)
        assertEquals("Should preserve multi-day end date", jan5, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected still detects midnight crossing for same-day`() {
        // Given: Same-day event starting at 10 PM
        val jan2 = 1704153600000L

        // Verify it's same-day
        assertFalse("Should be same-day", isMultiDayTest(jan2, jan2))

        // When: User changes end time to 1 AM (crosses midnight)
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan2,  // Same day
            startHour = 22,        // 10 PM
            newEndHour = 1         // 1 AM (crosses midnight)
        )

        // Then: endDateMillis should move to next day
        val expectedNextDay = jan2 + (24 * 60 * 60 * 1000)
        assertEquals("Should move to next day on midnight crossing", expectedNextDay, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected stays same day for normal same-day event`() {
        // Given: Same-day event
        val jan2 = 1704153600000L

        // When: User changes end time within same day (no midnight crossing)
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan2,
            startHour = 10,
            newEndHour = 15  // 3 PM (no midnight crossing)
        )

        // Then: endDateMillis should stay same day
        assertEquals("Should stay same day", jan2, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - multi-day event time change does not collapse to single day`() {
        // Given: Multi-day event (conference from Jan 2 - Jan 4)
        val conferenceStart = 1704153600000L  // Jan 2
        val conferenceEnd = conferenceStart + (2 * 24 * 60 * 60 * 1000)  // Jan 4

        assertTrue("Conference should be multi-day", isMultiDayTest(conferenceStart, conferenceEnd))

        // When: the user picks any end time, the end date must not reset to the start date.
        val scenarios = listOf(
            Pair(15, "3 PM - normal time"),
            Pair(23, "11 PM - late time"),
            Pair(9, "9 AM - early time"),
            Pair(1, "1 AM - would be midnight crossing if same-day")
        )

        for ((newEndHour, description) in scenarios) {
            val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
                dateMillis = conferenceStart,
                endDateMillis = conferenceEnd,
                startHour = 10,
                newEndHour = newEndHour
            )

            // All scenarios should preserve Jan 4 end date for multi-day event
            assertEquals(
                "Multi-day should preserve end date: $description",
                conferenceEnd,
                newEndDateMillis
            )
        }
    }

    // ========== Form dates, event timezone and the end-before-start / duration checks ==========
    //
    // Timed forms keep the event's date in the form's own timezone, stored as that
    // date's midnight on the phone, and the hours in the form's timezone. Every
    // check measures the instants toStartEndTs will save.

    private val oneHourMs = 3_600_000L
    private val newYork = "America/New_York"

    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d)

    /** A timed form as the edit loaders build it for an event at [startTs]..[endTs]. */
    private fun loadedForm(startTs: Long, endTs: Long, timezone: String? = newYork) =
        EventFormState(timezone = timezone).withDateFields(timedFormDateFields(startTs, endTs, timezone))

    @Test
    fun `event crossing midnight in its own zone but not on the phone is not flagged as ending before it starts`() =
        withDeviceTimeZone("America/Los_Angeles") {
            // New York 2024-03-05 23:30 to 2024-03-06 00:30 is 20:30-21:30 Mar 5 in Los Angeles.
            val state = EventFormState(
                dateMillis = phoneMidnight(day(2024, 3, 5)),
                endDateMillis = phoneMidnight(day(2024, 3, 6)),
                startHour = 23, startMinute = 30,
                endHour = 0, endMinute = 30,
                timezone = newYork,
            )
            assertFalse(state.endsBeforeStart())
        }

    @Test
    fun `moving the start keeps the duration when the event spans the phone's midnight but not its own`() =
        withDeviceTimeZone("America/Los_Angeles") {
            // New York 2024-03-06 02:00-04:00, which is 23:00 Mar 5 to 01:00 Mar 6 in Los Angeles.
            val state = loadedForm(1_709_708_400_000L, 1_709_715_600_000L)

            val moved = state.withTimedStart(state.dateMillis, 3, 0, defaultDurationMinutes = 30)

            val (start, end) = moved.toStartEndTs()
            assertEquals(1_709_712_000_000L, start) // 03:00 New York
            assertEquals(1_709_719_200_000L, end) // 05:00 New York, still 2 hours
            assertEquals(5, moved.endHour)
            assertEquals(day(2024, 3, 6), phoneLocalDate(moved.endDateMillis))
        }

    @Test
    fun `moving the start across a daylight saving change keeps the real elapsed duration`() =
        withDeviceTimeZone("America/Los_Angeles") {
            // New York 2024-03-10 00:00-04:00 spans the spring-forward change: 3 real hours.
            val state = loadedForm(1_710_046_800_000L, 1_710_057_600_000L)

            val moved = state.withTimedStart(state.dateMillis, 9, 0, defaultDurationMinutes = 30)

            val (start, end) = moved.toStartEndTs()
            assertEquals(3 * oneHourMs, end - start)
            assertEquals(12, moved.endHour)
        }

    @Test
    fun `a repeated clock time resolves to its first occurrence`() = withDeviceTimeZone("America/Los_Angeles") {
        // RFC 5545 section 3.3.5: 01:30 on the fall-back day is 01:30 EDT, the first of the two.
        val state = EventFormState(
            dateMillis = phoneMidnight(day(2024, 11, 3)),
            endDateMillis = phoneMidnight(day(2024, 11, 3)),
            startHour = 1, startMinute = 30,
            endHour = 3, endMinute = 0,
            timezone = newYork,
        )
        assertEquals(1_730_611_800_000L, state.toStartEndTs().first) // 05:30Z
    }

    @Test
    fun `a skipped clock time resolves with the offset before the gap`() = withDeviceTimeZone("America/Los_Angeles") {
        // RFC 5545 section 3.3.5: 02:30 on the spring-forward day is read as 03:30 EDT.
        val state = EventFormState(
            dateMillis = phoneMidnight(day(2024, 3, 10)),
            endDateMillis = phoneMidnight(day(2024, 3, 10)),
            startHour = 2, startMinute = 30,
            endHour = 4, endMinute = 0,
            timezone = newYork,
        )
        assertEquals(1_710_055_800_000L, state.toStartEndTs().first) // 07:30Z
    }

    @Test
    fun `an unchanged form keeps the loaded time in the second of two repeated hours`() =
        withDeviceTimeZone("America/Los_Angeles") {
            val secondOneThirty = 1_730_615_400_000L // 2024-11-03 01:30 EST (06:30Z)
            val state = loadedForm(secondOneThirty, secondOneThirty + oneHourMs)
            assertEquals(secondOneThirty to secondOneThirty + oneHourMs, state.toStartEndTs())
        }

    @Test
    fun `a winter event moved into a repeated hour follows the first-occurrence rule`() =
        withDeviceTimeZone("America/Los_Angeles") {
            val state = loadedForm(1_704_898_800_000L, 1_704_902_400_000L) // 2024-01-10 10:00 EST
            val moved = state.withTimedStart(phoneMidnight(day(2024, 11, 3)), 1, 30, defaultDurationMinutes = 30)
            assertEquals(1_730_611_800_000L, moved.toStartEndTs().first) // 01:30 EDT
        }

    @Test
    fun `picking a new timezone keeps the event at the same moment inside a repeated hour`() =
        withDeviceTimeZone("America/Los_Angeles") {
            val start = 1_730_619_000_000L // 02:30 EST Nov 3 = 01:30 CST, Chicago's second 01:30
            val moved = loadedForm(start, start + oneHourMs).withTimezone("America/Chicago")
            assertEquals(start to start + oneHourMs, moved.toStartEndTs())
            assertEquals(1, moved.startHour)
            assertEquals(30, moved.startMinute)
        }

    @Test
    fun `picking a new timezone keeps the event at the same moment when the phone is ahead of the event`() =
        withDeviceTimeZone("Asia/Tokyo") {
            val start = 1_709_647_200_000L // 2024-03-05 09:00 New York (23:00 in Tokyo)
            val moved = loadedForm(start, start + oneHourMs).withTimezone("America/Chicago")
            assertEquals(start to start + oneHourMs, moved.toStartEndTs())
            assertEquals(8, moved.startHour)
            assertEquals(day(2024, 3, 5), phoneLocalDate(moved.dateMillis))
        }

    @Test
    fun `picking a timezone replaces a preserved unrecognised one`() = withDeviceTimeZone("America/Los_Angeles") {
        val state = EventFormState(sourceTimezoneId = "Eastern Standard Time")
        assertNull(state.withTimezone(null).sourceTimezoneId)
        assertNull(state.withTimezone(newYork).sourceTimezoneId)
    }

    @Test
    fun `turning all-day on and off keeps the event's date and time`() = withDeviceTimeZone("America/Los_Angeles") {
        val start = 1_709_704_800_000L // 2024-03-06 01:00 New York (22:00 Mar 5 in Los Angeles)
        val state = loadedForm(start, start + oneHourMs)

        val toggled = state.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)

        assertEquals(start to start + oneHourMs, toggled.toStartEndTs())
    }

    @Test
    fun `turning all-day on swaps the default reminder`() = withDeviceTimeZone("America/Los_Angeles") {
        val state = EventFormState(reminders = listOf(15, 60), isAllDay = false)
        val allDay = state.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertTrue(allDay.isAllDay)
        assertEquals(listOf(60, 1440), allDay.reminders)
    }

    @Test
    fun `re-picking the end on a phone ahead of the event keeps a one-hour event`() = withDeviceTimeZone("Asia/Tokyo") {
        // New York 2024-03-05 09:00-10:00 is 23:00 Mar 5 to 00:00 Mar 6 in Tokyo.
        val start = 1_709_647_200_000L
        val state = loadedForm(start, start + oneHourMs)

        // The user picks Mar 5, 10:00 in the End sheet.
        val picked = state.withTimedEnd(phoneMidnight(day(2024, 3, 5)), 10, 0)

        // Same day as the start, so no swap: the start date is untouched.
        assertEquals(state.dateMillis, picked.dateMillis)
        val (newStart, newEnd) = picked.toStartEndTs()
        assertEquals(start, newStart)
        assertEquals(oneHourMs, newEnd - newStart)
    }

    @Test
    fun `picking an end date before the start date swaps them`() = withDeviceTimeZone("America/Los_Angeles") {
        val state = EventFormState(
            dateMillis = phoneMidnight(day(2024, 3, 5)),
            endDateMillis = phoneMidnight(day(2024, 3, 5)),
            startHour = 10, startMinute = 0,
            endHour = 11, endMinute = 0,
        )
        val swapped = state.withTimedEnd(phoneMidnight(day(2024, 3, 3)), 12, 0)
        assertEquals(day(2024, 3, 3), phoneLocalDate(swapped.dateMillis))
        assertEquals(day(2024, 3, 5), phoneLocalDate(swapped.endDateMillis))
        assertEquals(12, swapped.endHour)
    }

    @Test
    fun `a Room event loads with its own date and clock time and the loaded instants`() =
        withDeviceTimeZone("America/Los_Angeles") {
            val start = 1_709_704_800_000L // 2024-03-06 01:00 New York
            val event = org.onekash.kashcal.data.db.entity.Event(
                uid = "u", calendarId = 1L, title = "Late call",
                startTs = start, endTs = start + oneHourMs, dtstamp = start,
                timezone = newYork,
            )
            val fields = event.toFormDateFields(occurrenceTs = null)
            assertEquals(day(2024, 3, 6), phoneLocalDate(fields.dateMillis))
            assertEquals(1, fields.startHour)
            assertEquals(2, fields.endHour)
            assertEquals(start, fields.startOffsetHintTs)
            assertEquals(start + oneHourMs, fields.endOffsetHintTs)
        }

    @Test
    fun `a Room event with an offset timezone loads and saves in that offset`() = withDeviceTimeZone("America/Los_Angeles") {
        val start = 1_709_650_800_000L // 15:00Z, 20:00 at UTC+05:00
        val event = org.onekash.kashcal.data.db.entity.Event(
            uid = "u", calendarId = 1L, title = "Offset",
            startTs = start, endTs = start + oneHourMs, dtstamp = start,
            timezone = "UTC+05:00",
        )
        val fields = event.toFormDateFields(occurrenceTs = null)
        assertEquals(20, fields.startHour)

        val form = EventFormState(timezone = "UTC+05:00").withDateFields(fields)
        assertEquals(start to start + oneHourMs, form.toStartEndTs())
    }

    @Test
    fun `a blank or unrecognised timezone saves in the phone's timezone`() = withDeviceTimeZone("America/Los_Angeles") {
        val tenAmLosAngeles = 1_709_661_600_000L // 2024-03-05 10:00 PST
        for (zone in listOf("", "Eastern Standard Time")) {
            val state = EventFormState(
                dateMillis = phoneMidnight(day(2024, 3, 5)),
                endDateMillis = phoneMidnight(day(2024, 3, 5)),
                startHour = 10, startMinute = 0,
                endHour = 11, endMinute = 0,
                timezone = zone,
            )
            assertEquals("zone '$zone'", tenAmLosAngeles, state.toStartEndTs().first)
        }
    }

    @Test
    fun `a legacy three-letter timezone still resolves`() = withDeviceTimeZone("America/Los_Angeles") {
        val state = EventFormState(
            dateMillis = phoneMidnight(day(2024, 3, 5)),
            endDateMillis = phoneMidnight(day(2024, 3, 5)),
            startHour = 10, startMinute = 0,
            endHour = 11, endMinute = 0,
            timezone = "EST",
        )
        assertEquals(1_709_650_800_000L, state.toStartEndTs().first) // 10:00 at -05:00
    }

    @Test
    fun `an ambiguous three-letter timezone is not guessed`() = withDeviceTimeZone("America/Los_Angeles") {
        // "BST" could be British Summer Time or Bangladesh; only the unambiguous
        // fixed-offset names are accepted.
        val state = EventFormState(
            dateMillis = phoneMidnight(day(2024, 3, 5)),
            endDateMillis = phoneMidnight(day(2024, 3, 5)),
            startHour = 10, startMinute = 0,
            endHour = 11, endMinute = 0,
            timezone = "BST",
        )
        assertEquals(1_709_661_600_000L, state.toStartEndTs().first) // 10:00 in Los Angeles
    }

    @Test
    fun `re-picking the same end day on a new-event form does not swap`() = withDeviceTimeZone("America/Los_Angeles") {
        // New-event forms carry raw instants in their date fields.
        val tenAm = 1_709_661_600_000L // 2024-03-05 10:00 PST
        val state = EventFormState(
            dateMillis = tenAm, endDateMillis = tenAm,
            startHour = 10, startMinute = 0, endHour = 11, endMinute = 0,
        )
        val picked = state.withTimedEnd(phoneMidnight(day(2024, 3, 5)), 12, 0)
        assertEquals(tenAm, picked.dateMillis)
        assertEquals(2 * oneHourMs, picked.toStartEndTs().let { it.second - it.first })
    }

    @Test
    fun `moving the start so the end lands in the second repeated hour keeps that end`() =
        withDeviceTimeZone("America/Los_Angeles") {
            // 00:30 EDT to 01:30 EST on 2024-11-03 is two real hours.
            val state = loadedForm(1_730_608_200_000L, 1_730_615_400_000L)
            val moved = state.withTimedStart(state.dateMillis, 0, 30, defaultDurationMinutes = 30)
            assertEquals(1_730_615_400_000L, moved.toStartEndTs().second)
        }

    @Test
    fun `picking the same day again does not count as an edit`() = withDeviceTimeZone("America/Los_Angeles") {
        val tenAm = 1_709_661_600_000L
        val baseline = EventFormState(dateMillis = tenAm, endDateMillis = tenAm)
        val sameDay = baseline.copy(dateMillis = phoneMidnight(day(2024, 3, 5)), endDateMillis = phoneMidnight(day(2024, 3, 5)))
        val nextDay = baseline.copy(dateMillis = phoneMidnight(day(2024, 3, 6)))
        assertFalse(eventFormHasUnsavedChanges(baseline, sameDay))
        assertTrue(eventFormHasUnsavedChanges(baseline, nextDay))
    }

    @Test
    fun `choosing the device zone for an event with an unrecognised timezone counts as an edit`() {
        val loaded = EventFormState(timezone = null, sourceTimezoneId = "Eastern Standard Time")
        assertTrue(eventFormHasUnsavedChanges(loaded, loaded.withTimezone(null)))
    }

    @Test
    fun `turning all-day on and off keeps a repeat end date`() = withDeviceTimeZone("America/Los_Angeles") {
        val timed = EventFormState(
            isAllDay = false,
            timezone = "America/Los_Angeles",
            // The UNTIL is the end of Dec 31 in Los Angeles.
            rrule = "FREQ=WEEKLY;BYDAY=MO;UNTIL=20270101T075959Z;WKST=MO",
        )

        val allDay = timed.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231;WKST=MO", allDay.rrule)

        val backToTimed = allDay.withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals(timed.rrule, backToTimed.rrule)
    }

    @Test
    fun `turning all-day off ends the repeat on the same day in the event's zone`() = withDeviceTimeZone("Asia/Tokyo") {
        val allDay = EventFormState(isAllDay = true, timezone = null, rrule = "FREQ=DAILY;UNTIL=20261231")
        val timed = allDay.withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals("FREQ=DAILY;UNTIL=20261231T145959Z", timed.rrule) // Dec 31 23:59:59 in Tokyo
    }

    @Test
    fun `turning all-day on leaves a repeat without an end date alone`() = withDeviceTimeZone("America/Los_Angeles") {
        for (rule in listOf("FREQ=DAILY;COUNT=5", "FREQ=DAILY")) {
            val toggled = EventFormState(isAllDay = false, rrule = rule)
                .withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            assertEquals(rule, toggled.rrule)
        }
    }

    @Test
    fun `turning all-day on keeps a date-only repeat end as written`() = withDeviceTimeZone("Asia/Tokyo") {
        val timed = EventFormState(isAllDay = false, timezone = "Asia/Tokyo", rrule = "FREQ=DAILY;UNTIL=20261231")
        assertEquals(
            "FREQ=DAILY;UNTIL=20261231",
            timed.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440).rrule,
        )
    }

    @Test
    fun `turning all-day on and back off restores a repeat rule exactly`() = withDeviceTimeZone("America/Los_Angeles") {
        // A server-written end that isn't the end of a day; the round trip must not rewrite it.
        val loaded = EventFormState(isAllDay = false, timezone = "America/Los_Angeles", rrule = "FREQ=DAILY;UNTIL=20261231T000000Z")

        val roundTrip = loaded.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)

        assertEquals(loaded.rrule, roundTrip.rrule)
        assertFalse(eventFormHasUnsavedChanges(loaded, roundTrip))
    }

    @Test
    fun `a repeat rule edited between all-day toggles is re-expressed, not restored`() = withDeviceTimeZone("America/Los_Angeles") {
        val loaded = EventFormState(isAllDay = false, timezone = "America/Los_Angeles", rrule = "FREQ=DAILY;UNTIL=20270101T075959Z")
        val allDay = loaded.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            .copy(rrule = "FREQ=WEEKLY;UNTIL=20261231")
        val timed = allDay.withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals("FREQ=WEEKLY;UNTIL=20270101T075959Z", timed.rrule)
    }

    @Test
    fun `a malformed repeat end is left alone on an all-day toggle`() = withDeviceTimeZone("America/Los_Angeles") {
        val rule = "FREQ=DAILY;UNTIL=20261231T1459Z"
        val toggled = EventFormState(isAllDay = true, rrule = rule)
            .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals(rule, toggled.rrule)
    }

    @Test
    fun `a round trip restores a repeat rule the first toggle left unchanged`() = withDeviceTimeZone("America/Los_Angeles") {
        val timed = EventFormState(isAllDay = false, timezone = "America/Los_Angeles", rrule = "FREQ=DAILY;UNTIL=20261231")
        val roundTrip = timed.withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            .withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals(timed.rrule, roundTrip.rrule)

        val allDay = EventFormState(isAllDay = true, timezone = "UTC", rrule = "FREQ=DAILY;UNTIL=20261231T235959Z")
        val back = allDay.withAllDay(false, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
            .withAllDay(true, defaultReminderTimed = 15, defaultReminderAllDay = 1440)
        assertEquals(allDay.rrule, back.rrule)
    }

    // ========== End-Before-Start Tests ==========


    @Test
    fun `end after start is valid`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("End time after start time should be valid", state.endsBeforeStart())
    }

    @Test
    fun `end before start on the same day is invalid`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 0,
            endHour = 14,   // 2 PM (before start)
            endMinute = 0,
            isAllDay = false
        )
        assertTrue("End time before start time should be invalid", state.endsBeforeStart())
    }

    @Test
    fun `all-day events skip the end-before-start check`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15,
            startMinute = 0,
            endHour = 14, // Would conflict if not all-day
            endMinute = 0,
            isAllDay = true // All-day events skip time validation
        )
        assertFalse("All-day events should skip time validation", state.endsBeforeStart())
    }

    @Test
    fun `an end hour earlier than the start hour on a later date is valid`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000) // Next day
        val state = EventFormState(
            dateMillis = day1,
            endDateMillis = day2, // Different day
            startHour = 22, // 10 PM
            startMinute = 0,
            endHour = 2,    // 2 AM next day - hour is "before" but date is different
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("Different dates should allow any end hour", state.endsBeforeStart())
    }

    @Test
    fun `an end equal to the start is valid`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 30,
            endHour = 15,   // 3 PM (same as start)
            endMinute = 30,
            isAllDay = false
        )
        assertFalse("Zero-duration events (end = start) should be valid", state.endsBeforeStart())
    }

    @Test
    fun `an end just after midnight on the start date is before a late-evening start`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 23, // 11 PM
            startMinute = 30,
            endHour = 0,    // 12 AM (midnight)
            endMinute = 30,
            isAllDay = false
        )
        // Same date with end hour 0 < start hour 23 = conflict
        assertTrue("End at midnight (hour 0) before start at 11 PM should be invalid", state.endsBeforeStart())
    }

    @Test
    fun `a multi-day event with an earlier end hour is valid`() {
        val day1 = 1704106800000L
        val day2 = day1 + (2 * 24 * 60 * 60 * 1000) // 2 days later
        val state = EventFormState(
            dateMillis = day1,
            endDateMillis = day2,
            startHour = 14, // 2 PM on day 1
            startMinute = 0,
            endHour = 10,   // 10 AM on day 3 (earlier hour, but different day)
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("Multi-day event with earlier end hour should be valid", state.endsBeforeStart())
    }

    @Test
    fun `an end one minute before the start is invalid`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 14,   // 2:59 PM (1 minute before)
            endMinute = 59,
            isAllDay = false
        )
        assertTrue("End time 1 minute before start should be invalid", state.endsBeforeStart())
    }

    @Test
    fun `an end one minute after the start is valid`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 15,   // 3:01 PM (1 minute after)
            endMinute = 1,
            isAllDay = false
        )
        assertFalse("End time 1 minute after start should be valid", state.endsBeforeStart())
    }

    // ========== Save Button Enablement Tests ==========

    /**
     * Copies the Save button's enabled gate in [EventFormContent] for a form that isn't
     * read-only: a non-blank title, not saving, and no end-before-start conflict.
     */
    private fun isSaveButtonEnabled(
        title: String,
        isSaving: Boolean,
        hasTimeConflict: Boolean
    ): Boolean {
        return title.isNotBlank() && !isSaving && !hasTimeConflict
    }

    @Test
    fun `save button disabled when time conflict exists`() {
        assertFalse(
            "Save should be disabled on time conflict",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = false,
                hasTimeConflict = true
            )
        )
    }

    @Test
    fun `save button enabled when no time conflict`() {
        assertTrue(
            "Save should be enabled when no conflict",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = false,
                hasTimeConflict = false
            )
        )
    }

    @Test
    fun `save button disabled when title empty even without time conflict`() {
        assertFalse(
            "Save should be disabled with empty title",
            isSaveButtonEnabled(
                title = "",
                isSaving = false,
                hasTimeConflict = false
            )
        )
    }

    @Test
    fun `save button disabled when saving even without time conflict`() {
        assertFalse(
            "Save should be disabled while saving",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = true,
                hasTimeConflict = false
            )
        )
    }

    // ========== Duration Preservation Tests (start time change) ==========

    /**
     * Moves the start of a timed form through the production [withTimedStart]
     * and returns Triple(newEndHour, newEndMinute, newEndDateMillis). The end
     * date is a device-local midnight, so compare days with [isMultiDayTest].
     */
    private fun simulateStartTimeChangePreservingDuration(
        oldStartHour: Int,
        oldStartMinute: Int,
        oldEndHour: Int,
        oldEndMinute: Int,
        oldStartDateMillis: Long,
        oldEndDateMillis: Long,
        newStartHour: Int,
        newStartMinute: Int,
        newStartDateMillis: Long = oldStartDateMillis,
        defaultDuration: Int = 30
    ): Triple<Int, Int, Long> {
        val moved = EventFormState(
            dateMillis = oldStartDateMillis,
            endDateMillis = oldEndDateMillis,
            startHour = oldStartHour,
            startMinute = oldStartMinute,
            endHour = oldEndHour,
            endMinute = oldEndMinute,
        ).withTimedStart(newStartDateMillis, newStartHour, newStartMinute, defaultDuration)
        return Triple(moved.endHour, moved.endMinute, moved.endDateMillis)
    }

    /** Returns the midnight of [millis]'s day in the JVM default zone. */
    private fun normalizeToLocalMidnightTest(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Copies the all-day branch of the Start sheet's onConfirm in [EventFormContent]: the old
     * day span is applied to the new start date. Returns newEndDateMillis.
     */
    private fun simulateAllDayStartChangePreservingDaySpan(
        oldStartDateMillis: Long,
        oldEndDateMillis: Long,
        newStartDateMillis: Long
    ): Long {
        val normalizedOldStart = normalizeToLocalMidnightTest(oldStartDateMillis)
        val normalizedOldEnd = normalizeToLocalMidnightTest(oldEndDateMillis)
        val daySpanMs = (normalizedOldEnd - normalizedOldStart).coerceAtLeast(0)
        return normalizeToLocalMidnightTest(newStartDateMillis) + daySpanMs
    }

    @Test
    fun `start time change preserves actual 2h duration`() {
        // Given: 10:00-12:00 (2h event)
        // When: start moves to 14:00
        // Then: end should be 16:00, not 14:30 from defaultDuration
        val (newEndHour, newEndMinute, _) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 12, oldEndMinute = 0,
            oldStartDateMillis = 1704106800000L,
            oldEndDateMillis = 1704106800000L,
            newStartHour = 14, newStartMinute = 0
        )
        assertEquals("End hour should be 16", 16, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
    }

    @Test
    fun `start date change preserves duration across day boundaries`() {
        // Given: Jan 1 10:00-12:00 (2h)
        // When: start moves to Jan 5 14:00
        // Then: end should be Jan 5 16:00
        val jan1 = 1704106800000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDate) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 12, oldEndMinute = 0,
            oldStartDateMillis = jan1,
            oldEndDateMillis = jan1,
            newStartHour = 14, newStartMinute = 0,
            newStartDateMillis = jan5
        )
        assertEquals("End hour should be 16", 16, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertFalse("End date should be Jan 5", isMultiDayTest(jan5, newEndDate))
    }

    @Test
    fun `all-day multi-day preserves day span on start change`() {
        // Given: 3-day event Jan 1 - Jan 3
        // When: start moves to Jan 10
        // Then: end should be Jan 12
        val jan1 = normalizeToLocalMidnightTest(1704106800000L)
        val jan3 = jan1 + (2 * 24 * 60 * 60 * 1000)
        val jan10 = jan1 + (9 * 24 * 60 * 60 * 1000)
        val jan12 = jan1 + (11 * 24 * 60 * 60 * 1000)

        val newEndDate = simulateAllDayStartChangePreservingDaySpan(
            oldStartDateMillis = jan1,
            oldEndDateMillis = jan3,
            newStartDateMillis = jan10
        )
        assertEquals("End should be Jan 12 (2-day span preserved)", jan12, newEndDate)
    }

    @Test
    fun `all-day single-day stays single-day on start change`() {
        // Given: 1-day event Jan 1
        // When: start moves to Jan 10
        // Then: end should be Jan 10
        val jan1 = normalizeToLocalMidnightTest(1704106800000L)
        val jan10 = jan1 + (9 * 24 * 60 * 60 * 1000)

        val newEndDate = simulateAllDayStartChangePreservingDaySpan(
            oldStartDateMillis = jan1,
            oldEndDateMillis = jan1,
            newStartDateMillis = jan10
        )
        assertEquals("End should be Jan 10 (same day)", jan10, newEndDate)
    }

    @Test
    fun `start time change preserves 90min duration with midnight overflow`() {
        // Given: 22:00-23:30 (90 min)
        // When: start moves to 23:00
        // Then: end should be 00:30 next day
        val day1 = 1704106800000L
        val (newEndHour, newEndMinute, newEndDate) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 22, oldStartMinute = 0,
            oldEndHour = 23, oldEndMinute = 30,
            oldStartDateMillis = day1,
            oldEndDateMillis = day1,
            newStartHour = 23, newStartMinute = 0,
            newStartDateMillis = day1
        )
        assertEquals("End hour should be 0 (midnight overflow)", 0, newEndHour)
        assertEquals("End minute should be 30", 30, newEndMinute)
        assertTrue("End date should be next day", newEndDate > day1)
    }

    @Test
    fun `multi-day timed event preserves 4h duration spanning midnight`() {
        // Given: Jan 1 22:00 to Jan 2 02:00 (4h)
        // When: start moves to Jan 5 20:00
        // Then: end should be Jan 6 00:00
        val jan1 = 1704106800000L
        val jan2 = jan1 + (24 * 60 * 60 * 1000)
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val jan6 = jan5 + (24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDate) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 22, oldStartMinute = 0,
            oldEndHour = 2, oldEndMinute = 0,
            oldStartDateMillis = jan1,
            oldEndDateMillis = jan2,
            newStartHour = 20, newStartMinute = 0,
            newStartDateMillis = jan5
        )
        assertEquals("End hour should be 0 (midnight)", 0, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertFalse("End date should be Jan 6", isMultiDayTest(jan6, newEndDate))
    }

    @Test
    fun `start time change falls back to default for negative duration`() {
        // Given: end before start (invalid, but the state can hold it)
        // When: start changes
        // Then: should use defaultDuration (30 min)
        val (newEndHour, newEndMinute, _) = simulateStartTimeChangePreservingDuration(
            oldStartHour = 15, oldStartMinute = 0,
            oldEndHour = 14, oldEndMinute = 0,
            oldStartDateMillis = 1704106800000L,
            oldEndDateMillis = 1704106800000L,
            newStartHour = 10, newStartMinute = 0,
            defaultDuration = 30
        )
        assertEquals("End hour should be 10:30 (default 30 min)", 10, newEndHour)
        assertEquals("End minute should be 30", 30, newEndMinute)
    }

    // ========== Calendar Intent / Quick Add Expand Duration Tests ==========

    /**
     * Copies the calendar-intent start and end in [EventFormContent]: a missing start is the
     * next full hour, a missing end is the start plus [defaultEventDuration] minutes. Quick
     * Add's "More options", another app's ACTION_INSERT or ACTION_EDIT, and a long shared text
     * take this path. Returns Pair(startTs, endTs); [currentHourOfDay] stands in for the clock.
     */
    private fun simulateCalendarIntentPath(
        intentStartMillis: Long?,
        intentEndMillis: Long?,
        defaultEventDuration: Int,
        currentHourOfDay: Int = 14 // for testing next-hour snap
    ): Pair<Long, Long> {
        val startTs = intentStartMillis ?: run {
            // No parsed time: the next full hour.
            val nextHour = (currentHourOfDay + 1) % 24
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, nextHour)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }
        val endTs = intentEndMillis
            ?: (startTs + defaultEventDuration * 60 * 1000L)
        return Pair(startTs, endTs)
    }

    @Test
    fun `intent data with null times uses default duration not hardcoded 1 hour`() {
        // The default duration setting applies, not a fixed hour.
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 30
        )
        val durationMinutes = (endTs - startTs) / (60 * 1000)
        assertEquals("Duration should be 30 min (user setting), not 60", 30, durationMinutes)
    }

    @Test
    fun `intent data with null times and 15 min default uses 15 min`() {
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 15
        )
        val durationMinutes = (endTs - startTs) / (60 * 1000)
        assertEquals(15, durationMinutes)
    }

    @Test
    fun `intent data with null times and 2 hour default uses 2 hours`() {
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 120
        )
        val durationMinutes = (endTs - startTs) / (60 * 1000)
        assertEquals(120, durationMinutes)
    }

    @Test
    fun `intent data with start time but null end uses default duration`() {
        val startMs = 1704110400000L // some fixed timestamp
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = startMs,
            intentEndMillis = null,
            defaultEventDuration = 30
        )
        assertEquals("Start should be passed through", startMs, startTs)
        assertEquals("End should be start + 30 min", startMs + 30 * 60 * 1000L, endTs)
    }

    @Test
    fun `intent data with start time but null end uses 60 min default`() {
        val startMs = 1704110400000L
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = startMs,
            intentEndMillis = null,
            defaultEventDuration = 60
        )
        assertEquals(startMs, startTs)
        assertEquals(startMs + 60 * 60 * 1000L, endTs)
    }

    @Test
    fun `intent data with both start and end preserves them`() {
        val startMs = 1704110400000L
        val endMs = 1704117600000L // 2 hours later
        val (startTs, endTs) = simulateCalendarIntentPath(
            intentStartMillis = startMs,
            intentEndMillis = endMs,
            defaultEventDuration = 30 // should be ignored
        )
        assertEquals("Start preserved", startMs, startTs)
        assertEquals("End preserved (not overwritten by default)", endMs, endTs)
    }

    @Test
    fun `intent data null start snaps to next hour`() {
        val (startTs, _) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 30,
            currentHourOfDay = 14 // 2 PM
        )
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startTs }
        assertEquals("Should snap to next hour (3 PM)", 15, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, cal.get(java.util.Calendar.MINUTE))
        assertEquals("Second should be 0", 0, cal.get(java.util.Calendar.SECOND))
    }

    @Test
    fun `intent data null start at 23h wraps to 0h`() {
        val (startTs, _) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 30,
            currentHourOfDay = 23
        )
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startTs }
        assertEquals("Should wrap to midnight", 0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `intent data null start at 0h snaps to 1h`() {
        val (startTs, _) = simulateCalendarIntentPath(
            intentStartMillis = null,
            intentEndMillis = null,
            defaultEventDuration = 30,
            currentHourOfDay = 0
        )
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startTs }
        assertEquals("Should snap to 1 AM", 1, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    // ========== Signed reminder offset parsing (ISO -> minutes-before) ==========

    @Test
    fun `parseIso8601DurationToMinutes preserves sign for before and after triggers`() {
        // Negative iCal trigger (before start) -> positive minutes-before.
        assertEquals(900, parseIso8601DurationToMinutes("-PT15H")) // 1d chip
        assertEquals(2340, parseIso8601DurationToMinutes("-PT39H")) // 2d chip
        assertEquals(15, parseIso8601DurationToMinutes("-PT15M"))
        // Positive iCal trigger (after start) -> negative minutes-before.
        assertEquals(-540, parseIso8601DurationToMinutes("PT9H")) // 9 AM day-of
        assertEquals(-720, parseIso8601DurationToMinutes("PT12H"))
        // At start.
        assertEquals(0, parseIso8601DurationToMinutes("PT0M"))
    }

    @Test
    fun `parseIso8601DurationToMinutes returns null on unparseable (distinct from REMINDER_OFF)`() {
        assertNull(parseIso8601DurationToMinutes(null))
        assertNull(parseIso8601DurationToMinutes(""))
        assertNull(parseIso8601DurationToMinutes("garbage"))
    }

    @Test
    fun `parseIso8601DurationToMinutes parses legacy period forms`() {
        assertEquals(1440, parseIso8601DurationToMinutes("-P1D"))
        assertEquals(10080, parseIso8601DurationToMinutes("-P1W"))
        assertEquals(1560, parseIso8601DurationToMinutes("-P1DT2H"))
    }

    @Test
    fun `reminder offset round-trips Int - ISO - Int including after-start values`() {
        for (minutes in listOf(-540, 0, 15, 30, 900, 2340, 9540, -720)) {
            val iso = org.onekash.kashcal.data.contacts.ContactEventUtils.minutesToIsoDuration(minutes)
            assertEquals("round-trip failed for $minutes (iso=$iso)", minutes, parseIso8601DurationToMinutes(iso))
        }
    }

    // ========== Attendee row gate ==========

    @Test
    fun `attendee row editable for a detached exception on a schedulable organizer account`() {
        // The per-occurrence ("just this one") edit carries the edited guest set,
        // so a detached exception's attendee row is editable. canEditAttendees
        // takes no recurrence input, so this test and the next pass the same
        // arguments.
        assertTrue(canEditAttendees(isReadOnly = false, isSchedulable = true, hasContactQuery = true))
    }

    @Test
    fun `attendee row editable for a recurring series master`() {
        assertTrue(canEditAttendees(isReadOnly = false, isSchedulable = true, hasContactQuery = true))
    }

    @Test
    fun `attendee row not editable on a non-schedulable account`() {
        assertFalse(canEditAttendees(isReadOnly = false, isSchedulable = false, hasContactQuery = true))
    }

    @Test
    fun `attendee row not editable for a read-only invitee`() {
        assertFalse(canEditAttendees(isReadOnly = true, isSchedulable = true, hasContactQuery = true))
    }

    @Test
    fun `attendee row not editable without a contact-query callback`() {
        assertFalse(canEditAttendees(isReadOnly = false, isSchedulable = true, hasContactQuery = false))
    }

    @Test
    fun `scheduling-unavailable text stays suppressed for a recurring occurrence edit`() {
        // A non-schedulable recurring occurrence edit, a detached exception
        // included, shows the read-only guest chips (or nothing), not the "inviting
        // unavailable" text: that branch is off when isEditMode && wasRecurringAtLoad,
        // both true here.
        assertFalse(
            showSchedulingUnavailable(
                isReadOnly = false,
                isSchedulable = false,
                hasContactQuery = true,
                isEditMode = true,
                wasRecurringAtLoad = true,
            )
        )
    }

    @Test
    fun `scheduling-unavailable text shows for a new non-schedulable event`() {
        assertTrue(
            showSchedulingUnavailable(
                isReadOnly = false,
                isSchedulable = false,
                hasContactQuery = true,
                isEditMode = false,
                wasRecurringAtLoad = false,
            )
        )
    }

    // ========== Unsaved-Changes Detection Tests ==========

    private val baselineFormState = EventFormState(
        title = "Standup",
        selectedCalendarId = 7L,
        location = "Room 1",
        description = "notes",
        reminders = listOf(10),
        rrule = "FREQ=DAILY",
        eventColor = 0xFF0000,
        transp = "OPAQUE",
        categories = listOf("work"),
    )

    @Test
    fun `no change when states are identical`() {
        assertFalse(eventFormHasUnsavedChanges(baselineFormState, baselineFormState.copy()))
    }

    @Test
    fun `a change in each tracked field is detected`() {
        val b = baselineFormState
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(title = "Retro")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(dateMillis = b.dateMillis + 86_400_000L)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(endDateMillis = b.endDateMillis + 86_400_000L)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(startHour = b.startHour + 1)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(startMinute = b.startMinute + 1)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(endHour = (b.endHour + 1) % 24)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(endMinute = b.endMinute + 1)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(selectedCalendarId = 99L)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(isAllDay = !b.isAllDay)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(location = "Room 2")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(description = "changed")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(reminders = listOf(10, 30))))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(rrule = "FREQ=WEEKLY")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(timezone = "America/New_York")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(eventColor = 0x00FF00)))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(transp = "TRANSPARENT")))
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(categories = listOf("home"))))
    }

    @Test
    fun `editing the guest list counts as an unsaved change`() {
        // The attendee picker commits every add and remove into form state with
        // attendeesEdited=true; adding a guest then dismissing must trip the
        // two-tap discard guard, or the edit is silently dropped.
        val b = baselineFormState
        assertTrue(eventFormHasUnsavedChanges(b, b.copy(attendeesEdited = true)))
    }

    @Test
    fun `untracked UI-only fields do not count as changes`() {
        val b = baselineFormState
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(selectedCalendarName = "Personal")))
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(selectedCalendarColor = 0x123456)))
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(isLoading = !b.isLoading)))
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(isSaving = !b.isSaving)))
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(error = "boom")))
        assertFalse(eventFormHasUnsavedChanges(b, b.copy(startOffsetHintTs = 1L, endOffsetHintTs = 2L)))
        assertFalse(
            eventFormHasUnsavedChanges(b, b.copy(repeatRuleBeforeAllDayToggle = "FREQ=DAILY", repeatRuleAfterAllDayToggle = "FREQ=WEEKLY"))
        )
        assertFalse(
            eventFormHasUnsavedChanges(
                b,
                b.copy(calendarGroups = b.calendarGroups + org.onekash.kashcal.ui.model.CalendarGroup("g", 1L))
            )
        )
    }

    // ========== Dismiss State-Machine Tests ==========

    @Test
    fun `dismiss is blocked while saving regardless of other state`() {
        assertEquals(FormDismissAction.BLOCKED, resolveFormDismiss(true, false, false))
        assertEquals(FormDismissAction.BLOCKED, resolveFormDismiss(true, true, false))
        assertEquals(FormDismissAction.BLOCKED, resolveFormDismiss(true, false, true))
        assertEquals(FormDismissAction.BLOCKED, resolveFormDismiss(true, true, true))
    }

    @Test
    fun `no unsaved changes dismisses immediately`() {
        assertEquals(FormDismissAction.DISMISS, resolveFormDismiss(false, false, false))
    }

    @Test
    fun `first dismiss attempt with changes shows the discard confirmation`() {
        assertEquals(FormDismissAction.SHOW_DISCARD_CONFIRM, resolveFormDismiss(false, true, false))
    }

    @Test
    fun `second dismiss attempt with the confirmation showing dismisses`() {
        assertEquals(FormDismissAction.DISMISS, resolveFormDismiss(false, true, true))
    }

    // ========== Calendar Re-baseline Tests ==========

    @Test
    fun `re-baseline fires for a null baseline or an unresolved calendar`() {
        assertTrue(shouldRebaselineOnCalendarResolve(null))
        assertTrue(shouldRebaselineOnCalendarResolve(baselineFormState.copy(selectedCalendarId = null)))
    }

    @Test
    fun `re-baseline does not fire once the calendar is resolved`() {
        assertFalse(shouldRebaselineOnCalendarResolve(baselineFormState.copy(selectedCalendarId = 7L)))
    }
}
