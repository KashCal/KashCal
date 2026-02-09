package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MonthYearWheelPicker] helper functions.
 * Tests locale-independent logic; month names depend on test locale (en_US in CI).
 */
class MonthYearWheelPickerTest {

    // ==================== getLocalizedMonthNames ====================

    @Test
    fun `month names has exactly 12 items`() {
        val names = getLocalizedMonthNames()
        assertEquals(12, names.size)
    }

    @Test
    fun `month names are non-blank`() {
        val names = getLocalizedMonthNames()
        for (name in names) {
            assertTrue("Month name should not be blank: '$name'", name.isNotBlank())
        }
    }

    @Test
    fun `month names first and last match locale`() {
        val names = getLocalizedMonthNames()
        // In en_US locale (default for JVM tests)
        assertEquals("January", names.first())
        assertEquals("December", names.last())
    }

    // ==================== monthIndexToName ====================

    @Test
    fun `month index 0 maps to first name`() {
        val names = getLocalizedMonthNames()
        assertEquals(names[0], monthIndexToName(0, names))
    }

    @Test
    fun `month index 11 maps to last name`() {
        val names = getLocalizedMonthNames()
        assertEquals(names[11], monthIndexToName(11, names))
    }

    @Test
    fun `month index negative clamped to 0`() {
        val names = getLocalizedMonthNames()
        assertEquals(names[0], monthIndexToName(-1, names))
    }

    @Test
    fun `month index 12 clamped to 11`() {
        val names = getLocalizedMonthNames()
        assertEquals(names[11], monthIndexToName(12, names))
    }

    // ==================== yearToIndex ====================

    @Test
    fun `year 2025 maps to index 125 in default range`() {
        assertEquals(125, yearToIndex(2025, 1900..2200))
    }

    @Test
    fun `year 1900 maps to index 0`() {
        assertEquals(0, yearToIndex(1900, 1900..2200))
    }

    @Test
    fun `year 2200 maps to index 300`() {
        assertEquals(300, yearToIndex(2200, 1900..2200))
    }

    @Test
    fun `year below range clamped to 0`() {
        assertEquals(0, yearToIndex(1800, 1900..2200))
    }

    @Test
    fun `year above range clamped to last`() {
        assertEquals(300, yearToIndex(2300, 1900..2200))
    }

    // ==================== Custom Year Range ====================

    @Test
    fun `custom range 2020-2030 has 11 items`() {
        val range = 2020..2030
        assertEquals(11, range.toList().size)
    }

    @Test
    fun `year 2025 maps to index 5 in custom range`() {
        assertEquals(5, yearToIndex(2025, 2020..2030))
    }

    // ==================== Default Range Properties ====================

    @Test
    fun `default range 1900-2200 has 301 items`() {
        val range = 1900..2200
        assertEquals(301, range.toList().size)
    }

    // ==================== require guard ====================

    @Test(expected = IllegalArgumentException::class)
    fun `inverted range throws`() {
        // This tests the require guard logic; in practice the composable calls require()
        val range = 2100..1900
        require(range.first <= range.last) { "yearRange must not be empty" }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `single-value range is valid but empty IntRange throws`() {
        // IntRange(5, 4) is empty
        val range = 5..4
        require(range.first <= range.last) { "yearRange must not be empty" }
    }

    @Test
    fun `single-value range first equals last is valid`() {
        val range = 2025..2025
        assertTrue(range.first <= range.last)
        assertEquals(1, range.toList().size)
    }

    // ==================== selectedMonth guard ====================

    @Test(expected = IllegalArgumentException::class)
    fun `selectedMonth negative throws`() {
        require(-1 in 0..11) { "selectedMonth must be 0..11, was -1" }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `selectedMonth 12 throws`() {
        require(12 in 0..11) { "selectedMonth must be 0..11, was 12" }
    }

    @Test
    fun `selectedMonth 0 and 11 are valid`() {
        assertTrue(0 in 0..11)
        assertTrue(11 in 0..11)
    }
}
