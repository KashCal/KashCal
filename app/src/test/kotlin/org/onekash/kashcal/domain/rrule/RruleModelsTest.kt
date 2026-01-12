package org.onekash.kashcal.domain.rrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Unit tests for RRULE domain models.
 */
class RruleModelsTest {

    // ==================== RecurrenceFrequency Tests ====================

    @Test
    fun `RecurrenceFrequency has all expected values`() {
        val values = RecurrenceFrequency.values()
        assertTrue(values.contains(RecurrenceFrequency.NONE))
        assertTrue(values.contains(RecurrenceFrequency.DAILY))
        assertTrue(values.contains(RecurrenceFrequency.WEEKLY))
        assertTrue(values.contains(RecurrenceFrequency.MONTHLY))
        assertTrue(values.contains(RecurrenceFrequency.YEARLY))
        assertTrue(values.contains(RecurrenceFrequency.CUSTOM))
    }

    // ==================== MonthlyPattern Tests ====================

    @Test
    fun `MonthlyPattern SameDay stores day of month`() {
        val pattern = MonthlyPattern.SameDay(15)
        assertEquals(15, pattern.dayOfMonth)
    }

    @Test
    fun `MonthlyPattern LastDay is singleton`() {
        val pattern1 = MonthlyPattern.LastDay
        val pattern2 = MonthlyPattern.LastDay
        assertEquals(pattern1, pattern2)
    }

    @Test
    fun `MonthlyPattern NthWeekday stores ordinal and weekday`() {
        val pattern = MonthlyPattern.NthWeekday(2, DayOfWeek.TUESDAY)
        assertEquals(2, pattern.ordinal)
        assertEquals(DayOfWeek.TUESDAY, pattern.weekday)
    }

    @Test
    fun `MonthlyPattern NthWeekday supports last weekday`() {
        val pattern = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY)
        assertEquals(-1, pattern.ordinal)
        assertEquals(DayOfWeek.FRIDAY, pattern.weekday)
    }

    @Test
    fun `MonthlyPattern SameDay equality`() {
        assertEquals(MonthlyPattern.SameDay(15), MonthlyPattern.SameDay(15))
        assertNotEquals(MonthlyPattern.SameDay(15), MonthlyPattern.SameDay(20))
    }

    @Test
    fun `MonthlyPattern NthWeekday equality`() {
        assertEquals(
            MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY),
            MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY)
        )
        assertNotEquals(
            MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY),
            MonthlyPattern.NthWeekday(3, DayOfWeek.MONDAY)
        )
        assertNotEquals(
            MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY),
            MonthlyPattern.NthWeekday(2, DayOfWeek.TUESDAY)
        )
    }

    // ==================== EndCondition Tests ====================

    @Test
    fun `EndCondition Never is singleton`() {
        val cond1 = EndCondition.Never
        val cond2 = EndCondition.Never
        assertEquals(cond1, cond2)
    }

    @Test
    fun `EndCondition Count stores count`() {
        val cond = EndCondition.Count(10)
        assertEquals(10, cond.count)
    }

    @Test
    fun `EndCondition Until stores dateMillis`() {
        val millis = 1767657600000L
        val cond = EndCondition.Until(millis)
        assertEquals(millis, cond.dateMillis)
    }

    @Test
    fun `EndCondition Count equality`() {
        assertEquals(EndCondition.Count(10), EndCondition.Count(10))
        assertNotEquals(EndCondition.Count(10), EndCondition.Count(20))
    }

    @Test
    fun `EndCondition Until equality`() {
        assertEquals(EndCondition.Until(1000L), EndCondition.Until(1000L))
        assertNotEquals(EndCondition.Until(1000L), EndCondition.Until(2000L))
    }

    // ==================== ParsedRecurrence Tests ====================

    @Test
    fun `ParsedRecurrence default values`() {
        val parsed = ParsedRecurrence()
        assertEquals(RecurrenceFrequency.NONE, parsed.frequency)
        assertEquals(1, parsed.interval)
        assertTrue(parsed.weekdays.isEmpty())
        assertEquals(null, parsed.monthlyPattern)
        assertEquals(EndCondition.Never, parsed.endCondition)
    }

    @Test
    fun `ParsedRecurrence stores all fields`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val pattern = MonthlyPattern.SameDay(15)
        val endCond = EndCondition.Count(5)

        val parsed = ParsedRecurrence(
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 2,
            weekdays = weekdays,
            monthlyPattern = pattern,
            endCondition = endCond
        )

        assertEquals(RecurrenceFrequency.MONTHLY, parsed.frequency)
        assertEquals(2, parsed.interval)
        assertEquals(weekdays, parsed.weekdays)
        assertEquals(pattern, parsed.monthlyPattern)
        assertEquals(endCond, parsed.endCondition)
    }

    @Test
    fun `ParsedRecurrence copy works correctly`() {
        val original = ParsedRecurrence(
            frequency = RecurrenceFrequency.WEEKLY,
            interval = 1
        )
        val copied = original.copy(interval = 2)

        assertEquals(RecurrenceFrequency.WEEKLY, copied.frequency)
        assertEquals(2, copied.interval)
    }

    // ==================== FrequencyOption Tests ====================

    @Test
    fun `FrequencyOption has all expected values`() {
        val options = FrequencyOption.values()
        assertTrue(options.any { it == FrequencyOption.NEVER })
        assertTrue(options.any { it == FrequencyOption.DAILY })
        assertTrue(options.any { it == FrequencyOption.WEEKLY })
        assertTrue(options.any { it == FrequencyOption.BIWEEKLY })
        assertTrue(options.any { it == FrequencyOption.MONTHLY })
        assertTrue(options.any { it == FrequencyOption.QUARTERLY })
        assertTrue(options.any { it == FrequencyOption.YEARLY })
    }

    @Test
    fun `FrequencyOption BIWEEKLY has interval 2`() {
        assertEquals(2, FrequencyOption.BIWEEKLY.interval)
        assertEquals(RecurrenceFrequency.WEEKLY, FrequencyOption.BIWEEKLY.freq)
    }

    @Test
    fun `FrequencyOption QUARTERLY has interval 3`() {
        assertEquals(3, FrequencyOption.QUARTERLY.interval)
        assertEquals(RecurrenceFrequency.MONTHLY, FrequencyOption.QUARTERLY.freq)
    }

    @Test
    fun `FrequencyOption default interval is 1`() {
        assertEquals(1, FrequencyOption.DAILY.interval)
        assertEquals(1, FrequencyOption.WEEKLY.interval)
        assertEquals(1, FrequencyOption.MONTHLY.interval)
        assertEquals(1, FrequencyOption.YEARLY.interval)
    }

    @Test
    fun `FrequencyOption labels are user-friendly`() {
        assertEquals("Never", FrequencyOption.NEVER.label)
        assertEquals("Daily", FrequencyOption.DAILY.label)
        assertEquals("Biweekly", FrequencyOption.BIWEEKLY.label)
        assertEquals("Quarterly", FrequencyOption.QUARTERLY.label)
    }
}
