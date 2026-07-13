package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AgendaTitleMonthTest {

    private val fallback = LocalDate.of(2026, 1, 15) // Jan 2026 -> (2026, 0)

    @Test
    fun `day-header key yields its month`() {
        // "header_20260815" -> August 2026 (month is 0-indexed: 7)
        assertEquals(2026 to 7, AgendaTitleMonth.monthYearFromItemKey("header_20260815", fallback))
    }

    @Test
    fun `room event card key yields its trailing displayDay month`() {
        // "room_5_1700000000000_20260901" -> September 2026 (8)
        assertEquals(2026 to 8, AgendaTitleMonth.monthYearFromItemKey("room_5_1700000000000_20260901", fallback))
    }

    @Test
    fun `device event card key yields its trailing displayDay month`() {
        // "device_9_20261101" -> November 2026 (10)
        assertEquals(2026 to 10, AgendaTitleMonth.monthYearFromItemKey("device_9_20261101", fallback))
    }

    @Test
    fun `null key falls back to today`() {
        assertEquals(2026 to 0, AgendaTitleMonth.monthYearFromItemKey(null, fallback))
    }

    @Test
    fun `key with no numeric day code falls back`() {
        assertEquals(2026 to 0, AgendaTitleMonth.monthYearFromItemKey("header_", fallback))
        assertEquals(2026 to 0, AgendaTitleMonth.monthYearFromItemKey("header_notanumber", fallback))
    }

    @Test
    fun `out-of-range month in key falls back`() {
        // month component 13 is invalid -> fallback rather than crash
        assertEquals(2026 to 0, AgendaTitleMonth.monthYearFromItemKey("header_20261301", fallback))
    }

    @Test
    fun `January and December decode to correct 0-indexed months`() {
        assertEquals(2027 to 0, AgendaTitleMonth.monthYearFromItemKey("header_20270101", fallback))
        assertEquals(2025 to 11, AgendaTitleMonth.monthYearFromItemKey("header_20251231", fallback))
    }
}
