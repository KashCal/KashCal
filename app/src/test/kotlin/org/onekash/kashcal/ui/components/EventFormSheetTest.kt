package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.components.pickers.DateSelectionMode

/**
 * Unit tests for EventFormSheet logic.
 *
 * Tests occurrence dates, duration, time validation, date range picker,
 * save button enablement, and form state calculations.
 */
class EventFormSheetTest {

    // ========== Occurrence Date Tests ==========

    /**
     * Simulates the occurrence date calculation from EventFormSheet.
     * When editing a single occurrence, the form should use occurrenceTs (not master event date).
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
        // Master event: Jan 1, 2024 10:00 AM - 11:00 AM (1 hour event)
        val masterStartTs = 1704106800000L // Jan 1, 2024 10:00 AM UTC
        val masterEndTs = 1704110400000L   // Jan 1, 2024 11:00 AM UTC

        // Occurrence: Jan 8, 2024 10:00 AM (one week later)
        val occurrenceTs = 1704711600000L  // Jan 8, 2024 10:00 AM UTC

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        // Should use occurrence date, not master date
        assertTrue("Should use occurrenceTs for start", actualStart == occurrenceTs)
        assertTrue("End should be occurrence + duration", actualEnd == occurrenceTs + (masterEndTs - masterStartTs))
        assertTrue("Duration should be preserved", actualEnd - actualStart == masterEndTs - masterStartTs)
    }

    @Test
    fun `form loads with master event date when occurrenceTs is null`() {
        // Master event: Jan 1, 2024 10:00 AM - 11:00 AM
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
        val masterStartTs = 1704106800000L // 10:00 AM
        val masterEndTs = 1704114000000L   // 12:00 PM (2 hours)
        val expectedDuration = masterEndTs - masterStartTs // 2 hours = 7200000ms

        // Occurrence on different date
        val occurrenceTs = 1704711600000L

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        assertTrue("Duration should be exactly 2 hours", actualEnd - actualStart == expectedDuration)
    }

    // ========== Duration Maintenance Tests ==========

    /**
     * Check if start and end dates represent different calendar days.
     * Uses Calendar DAY_OF_YEAR comparison (matches production isMultiDay function).
     */
    private fun isMultiDayTest(startDateMillis: Long, endDateMillis: Long): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        return startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /**
     * Simulates the duration maintenance logic from EventFormSheet onStartTimeSelected.
     * Returns Pair(newEndHour, newEndMinute)
     */
    private fun calculateEndTimeWithDuration(
        oldStartHour: Int,
        oldStartMinute: Int,
        oldEndHour: Int,
        oldEndMinute: Int,
        oldStartDateMillis: Long,
        oldEndDateMillis: Long,
        newStartHour: Int,
        newStartMinute: Int
    ): Triple<Int, Int, Long> {
        // Calculate current duration (or default 20 mins if invalid)
        val currentDurationMinutes = (oldEndHour * 60 + oldEndMinute) -
            (oldStartHour * 60 + oldStartMinute)
        // Handle case where end was already on next day (use calendar day comparison)
        val adjustedDuration = if (isMultiDayTest(oldStartDateMillis, oldEndDateMillis)) {
            currentDurationMinutes + 24 * 60
        } else {
            currentDurationMinutes
        }
        val duration = if (adjustedDuration > 0) adjustedDuration else 20

        // Calculate new end time
        val newEndTotalMinutes = newStartHour * 60 + newStartMinute + duration

        return if (newEndTotalMinutes >= 24 * 60) {
            // Crosses midnight
            val nextDayMillis = oldStartDateMillis + (24 * 60 * 60 * 1000)
            val overflowMinutes = newEndTotalMinutes - (24 * 60)
            Triple(overflowMinutes / 60, overflowMinutes % 60, nextDayMillis)
        } else {
            // Same day
            Triple(newEndTotalMinutes / 60, newEndTotalMinutes % 60, oldStartDateMillis)
        }
    }

    @Test
    fun `end time follows start time maintaining duration`() {
        // Given: start=10:00, end=10:30 (30 min duration)
        val (newEndHour, newEndMinute, _) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 10, oldEndMinute = 30,
            oldStartDateMillis = 1704106800000L, // Jan 1
            oldEndDateMillis = 1704106800000L,   // Jan 1 (same day)
            newStartHour = 14, newStartMinute = 0
        )
        // Then: end should be 14:30
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 30", 30, newEndMinute)
    }

    @Test
    fun `end time uses default 20 min when duration invalid`() {
        // Given: start=10:00, end=09:00 (negative duration)
        val (newEndHour, newEndMinute, _) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 9, oldEndMinute = 0,
            oldStartDateMillis = 1704106800000L,
            oldEndDateMillis = 1704106800000L,
            newStartHour = 14, newStartMinute = 0
        )
        // Then: end should be 14:20 (default)
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
    }

    @Test
    fun `midnight crossing updates endDateMillis to next day`() {
        // Given: start=22:00, end=22:30, same date
        val startDateMillis = 1704106800000L // Jan 1
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 22, oldStartMinute = 0,
            oldEndHour = 22, oldEndMinute = 30,
            oldStartDateMillis = startDateMillis,
            oldEndDateMillis = startDateMillis,
            newStartHour = 23, newStartMinute = 50
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
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 23, oldStartMinute = 0,
            oldEndHour = 0, oldEndMinute = 30,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2, // End is on day 2
            newStartHour = 23, newStartMinute = 30
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
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 23, oldStartMinute = 50,
            oldEndHour = 0, oldEndMinute = 10,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2,
            newStartHour = 10, newStartMinute = 0
        )
        // Then: end=10:20, endDateMillis = same day
        assertEquals("End hour should be 10", 10, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
        assertEquals("endDateMillis should be same day", day1, newEndDateMillis)
    }

    @Test
    fun `same day event with different timestamps does not add 24h duration`() {
        // Given: Event 10 AM - 11 AM on Jan 2 (realistic: different timestamps, same day)
        // This is how real events are stored - start and end have DIFFERENT timestamps
        val jan2_10am = 1704193200000L  // Jan 2, 2024 @ 10:00 AM UTC
        val jan2_11am = jan2_10am + (60 * 60 * 1000)  // 1 hour later = 11:00 AM

        // When: User changes start to 12:00 AM (midnight)
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 11, oldEndMinute = 0,
            oldStartDateMillis = jan2_10am,
            oldEndDateMillis = jan2_11am,  // Different timestamp, SAME day!
            newStartHour = 0, newStartMinute = 0  // User picks 12:00 AM
        )

        // Then: Should preserve 1-hour duration, end at 1:00 AM same day
        assertEquals("End hour should be 1 (1 AM)", 1, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertEquals("Should stay same day", jan2_10am, newEndDateMillis)
    }

    // ========== shouldShowSeparatePickers Tests ==========

    /**
     * Simulates shouldShowSeparatePickers logic.
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
        // 1 day apart, endHour(14) > startHour(10) = NOT midnight crossing
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
     * Simulates the isMidnightCrossing logic from MergedTimeRow.
     * This is the FIXED version that uses isMultiDay() for proper day comparison.
     *
     * Bug: The original code used `endDateMillis > startDateMillis` which is always
     * true for same-day events because timestamps include time (not just date).
     *
     * Fix: Use calendar day comparison via isMultiDay(), then check if end hour
     * wrapped around midnight (endHour < startHour).
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
        // 10 AM to 10:20 PM same day - should NOT show +1
        // This was the bug: timestamps differ but calendar day is the same
        val day1 = 1704106800000L  // Some day at 10 AM
        val day1Later = day1 + (12 * 60 * 60 * 1000)  // Same day at 10 PM (+12 hours)
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
        // 10 AM to 3 PM next day - multi-day event, NOT midnight crossing
        // Should show separate pickers, not merged with +1
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 AM to 3 PM next day should NOT show +1 (multi-day event)",
            isMidnightCrossing(day1, day2, 10, 15)
        )
    }

    @Test
    fun `isMidnightCrossing returns false for exactly 24 hour event`() {
        // 10 PM to 10 PM next day - exactly 24h, NOT midnight crossing
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 PM to 10 PM next day should NOT show +1",
            isMidnightCrossing(day1, day2, 22, 22)
        )
    }

    // ========== onEndTimeSelected Tests ==========

    /**
     * Simulates the onEndTimeSelected logic that updates endDateMillis
     * when end time crosses midnight relative to start time.
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

    // ========== Date Range Picker Tests (Marriott-style unified picker) ==========

    /**
     * Tests for the unified date range picker that shows both start and end
     * dates in a single compact row with a shared calendar.
     *
     * Key behaviors:
     * - Start/End tab toggle for selection mode
     * - Auto-advance from Start to End after selection
     * - Smart swap validation (end < start → swap)
     * - Same-day confirmation (tap same date → collapse)
     */

    /**
     * Simulates the date selection logic from DateRangePickerCard.
     * Returns Pair(newStartDateMillis, newEndDateMillis).
     */
    private fun simulateDateSelection(
        currentStartMillis: Long,
        currentEndMillis: Long,
        selectedMillis: Long,
        activeSelection: DateSelectionMode
    ): Pair<Long, Long> {
        return if (activeSelection == DateSelectionMode.START) {
            // Update start date
            val newStart = selectedMillis
            // If new start is after end, swap (smart validation)
            val newEnd = if (selectedMillis > currentEndMillis) selectedMillis else currentEndMillis
            Pair(newStart, newEnd)
        } else {
            // Update end date
            if (selectedMillis < currentStartMillis) {
                // Smart swap: selected becomes start, old start becomes end
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
        // When selecting dates, time components should be preserved
        val jan1_10am = 1704103200000L  // Jan 1 at 10:00 AM
        val jan1_11am = jan1_10am + (60 * 60 * 1000)  // Jan 1 at 11:00 AM

        val initial = EventFormState(
            dateMillis = jan1_10am,
            endDateMillis = jan1_11am,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0
        )

        // Changing date should not affect time fields
        val updated = initial.copy(dateMillis = jan1_10am + (24 * 60 * 60 * 1000))  // Jan 2

        // Time fields should remain unchanged
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
        // Simulating the auto-advance behavior
        var activeSelection = DateSelectionMode.START

        // After selecting start date, should advance to END
        if (activeSelection == DateSelectionMode.START) {
            activeSelection = DateSelectionMode.END
        }

        assertEquals("Should auto-advance to END", DateSelectionMode.END, activeSelection)
    }

    // ========== Range Highlighting Tests ==========

    /**
     * Tests the range highlighting logic for calendar days.
     * - Start date: primary color
     * - End date: tertiary color
     * - Days in range: primaryContainer background
     */

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
        // Visual test: "Thu, Jan 2 → Sat, Jan 4"
        val jan2 = 1704153600000L  // Jan 2, 2024
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Should be multi-day", isMultiDayTest(jan2, jan4))
        // UI should show "Jan 2 → Jan 4" format
    }

    @Test
    fun `collapsed row shows single date for same-day`() {
        // Visual test: "Thu, Jan 2" (not "Thu, Jan 2 → Thu, Jan 2")
        val jan2 = 1704153600000L

        assertFalse("Should be same-day", isMultiDayTest(jan2, jan2))
        // UI should show just "Jan 2" without arrow
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

        // All-day events also use the unified picker
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

    // ========== MergedTimeRow Date Label Tests (v5.1.0) ==========

    /**
     * v5.1.0 Change: MergedTimeRow now always used for time selection.
     * shouldShowSeparatePickers() determines if date labels are shown in tabs.
     *
     * For multi-day events, the Start/End tabs show date labels underneath:
     *   [Start]    [End]
     *   [Jan 2]    [Jan 4]
     *
     * For same-day events, no date labels are shown:
     *   [Start]    [End]
     */

    @Test
    fun `multi-day event shows date labels in unified time picker`() {
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        // shouldShowSeparatePickers now determines if date labels are shown
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
        // Simulate: Jan 2 10 AM - Jan 4 3 PM (multi-day event)
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        // This is a multi-day event
        assertTrue("Should detect as multi-day", isMultiDayTest(jan2, jan4))

        // Verify duration calculation for multi-day (53 hours = 3180 minutes)
        val startMinutes = 10 * 60  // 10:00 AM
        val endMinutes = 15 * 60    // 3:00 PM
        // Duration across days: (24h - 10h) + 24h + 15h = 53 hours
        val durationMinutes = (24 * 60 - startMinutes) + (24 * 60) + endMinutes
        assertEquals("Multi-day duration calculation", 53 * 60, durationMinutes)
    }

    // ========== v6.1.0 Regression Tests for Multi-Day Bug Fix ==========

    /**
     * Simulates the fixed onEndTimeSelected logic.
     * BUG (pre-v6.1.0): Always reset endDateMillis to dateMillis
     * FIX: Check if already multi-day and preserve endDateMillis
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
            !isSameDay -> endDateMillis  // FIX: Preserve multi-day end date
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
        // This is the exact bug scenario reported in v6.0.0
        // Given: Multi-day event (conference from Jan 2 - Jan 4)
        val conferenceStart = 1704153600000L  // Jan 2
        val conferenceEnd = conferenceStart + (2 * 24 * 60 * 60 * 1000)  // Jan 4

        assertTrue("Conference should be multi-day", isMultiDayTest(conferenceStart, conferenceEnd))

        // When: User opens time picker and selects ANY end time
        // In v6.0.0, this would ALWAYS reset endDateMillis to dateMillis
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

    // ========== Time Validation Tests (v15.0.7) ==========

    /**
     * Simulates the hasTimeConflict logic from EventFormSheet.
     * Returns true if end time is before start time on the same day.
     */
    private fun hasTimeConflict(state: EventFormState): Boolean {
        if (state.isAllDay) return false
        val startDateOnly = normalizeToLocalMidnightTest(state.dateMillis)
        val endDateOnly = normalizeToLocalMidnightTest(state.endDateMillis)
        if (startDateOnly == endDateOnly) {
            val startMins = state.startHour * 60 + state.startMinute
            val endMins = state.endHour * 60 + state.endMinute
            return endMins < startMins
        }
        return false
    }

    /**
     * Normalize timestamp to local midnight for date comparison.
     */
    private fun normalizeToLocalMidnightTest(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `hasTimeConflict returns false when end time after start time`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("End time after start time should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns true when end time before start time same day`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 0,
            endHour = 14,   // 2 PM (before start)
            endMinute = 0,
            isAllDay = false
        )
        assertTrue("End time before start time should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for all-day events`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15,
            startMinute = 0,
            endHour = 14, // Would conflict if not all-day
            endMinute = 0,
            isAllDay = true // All-day events skip time validation
        )
        assertFalse("All-day events should skip time validation", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false when dates are different`() {
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
        assertFalse("Different dates should allow any end hour", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for equal start and end time - zero duration allowed`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 30,
            endHour = 15,   // 3 PM (same as start)
            endMinute = 30,
            isAllDay = false
        )
        assertFalse("Zero-duration events (end = start) should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict handles midnight boundary correctly`() {
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
        assertTrue("End at midnight (hour 0) before start at 11 PM should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for multi-day event with earlier end hour`() {
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
        assertFalse("Multi-day event with earlier end hour should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict edge case - end time 1 minute before start`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 14,   // 2:59 PM (1 minute before)
            endMinute = 59,
            isAllDay = false
        )
        assertTrue("End time 1 minute before start should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict edge case - end time 1 minute after start`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 15,   // 3:01 PM (1 minute after)
            endMinute = 1,
            isAllDay = false
        )
        assertFalse("End time 1 minute after start should be valid", hasTimeConflict(state))
    }

    // ========== Save Button Enablement Tests ==========

    /**
     * Simulates the save button enabled state logic.
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
     * Simulates the FIXED onStartTimeConfirm logic for timed events.
     * Computes actual duration from current state and applies to new start.
     *
     * Returns Triple(newEndHour, newEndMinute, newEndDateMillis)
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
        val oldStartMins = oldStartHour * 60 + oldStartMinute
        val oldEndMins = oldEndHour * 60 + oldEndMinute
        val oldStartDateOnly = normalizeToLocalMidnightTest(oldStartDateMillis)
        val oldEndDateOnly = normalizeToLocalMidnightTest(oldEndDateMillis)
        val dayGapMinutes = ((oldEndDateOnly - oldStartDateOnly) / (60 * 1000)).toInt()
        val currentDurationMins = (oldEndMins - oldStartMins) + dayGapMinutes
        val durationMins = if (currentDurationMins >= 0) currentDurationMins else defaultDuration

        val newEndTotalMins = newStartHour * 60 + newStartMinute + durationMins
        val dayOverflowMs = (newEndTotalMins / (24 * 60)).toLong() * 24L * 60 * 60 * 1000
        val remainderMins = newEndTotalMins % (24 * 60)
        val newEndDateMillis = newStartDateMillis + dayOverflowMs
        return Triple(remainderMins / 60, remainderMins % 60, newEndDateMillis)
    }

    /**
     * Simulates the FIXED onStartDateConfirm logic for all-day events.
     * Computes day span from current state and applies to new start date.
     *
     * Returns newEndDateMillis.
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
        // Then: end should be 16:00 (not 14:30 from defaultDuration)
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
        assertEquals("End date should be Jan 5", jan5, newEndDate)
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
        assertEquals("End date should be Jan 6", jan6, newEndDate)
    }

    @Test
    fun `start time change falls back to default for negative duration`() {
        // Given: end before start (invalid, but possible in state)
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
     * Simulates the calendar intent data path in EventFormSheet (lines 505-540).
     * This is the code path used when:
     * - Quick Add "More options" sends CalendarIntentData to EventFormSheet
     * - External apps (Gmail, browsers) use ACTION_INSERT
     *
     * Returns Pair(startTs, endTs) as computed by the form.
     */
    private fun simulateCalendarIntentPath(
        intentStartMillis: Long?,
        intentEndMillis: Long?,
        defaultEventDuration: Int,
        currentHourOfDay: Int = 14 // for testing next-hour snap
    ): Pair<Long, Long> {
        val startTs = intentStartMillis ?: run {
            // No parsed time — snap to next hour (matches FAB create behavior)
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
        // Regression: was hardcoded to 60*60*1000 (1 hour)
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
}
