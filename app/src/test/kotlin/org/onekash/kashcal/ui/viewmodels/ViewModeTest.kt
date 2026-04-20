package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModeTest {

    @Test
    fun `fromKey year returns YEAR`() {
        assertEquals(ViewMode.YEAR, ViewMode.fromKey("year"))
    }

    @Test
    fun `fromKey month returns MONTH`() {
        assertEquals(ViewMode.MONTH, ViewMode.fromKey("month"))
    }

    @Test
    fun `fromKey agenda returns AGENDA`() {
        assertEquals(ViewMode.AGENDA, ViewMode.fromKey("agenda"))
    }

    @Test
    fun `fromKey three_days returns THREE_DAYS`() {
        assertEquals(ViewMode.THREE_DAYS, ViewMode.fromKey("three_days"))
    }

    @Test
    fun `fromKey month_full returns MONTH_FULL`() {
        assertEquals(ViewMode.MONTH_FULL, ViewMode.fromKey("month_full"))
    }

    @Test
    fun `fromKey unknown falls back to MONTH`() {
        assertEquals(ViewMode.MONTH, ViewMode.fromKey("unknown"))
    }

    @Test
    fun `fromKey empty falls back to MONTH`() {
        assertEquals(ViewMode.MONTH, ViewMode.fromKey(""))
    }

    @Test
    fun `YEAR key is year`() {
        assertEquals("year", ViewMode.YEAR.key)
    }

    @Test
    fun `fromKey week returns WEEK`() {
        assertEquals(ViewMode.WEEK, ViewMode.fromKey("week"))
    }

    @Test
    fun `WEEK key is week`() {
        assertEquals("week", ViewMode.WEEK.key)
    }

    @Test
    fun `all entries have unique keys`() {
        val keys = ViewMode.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `ViewMode has 7 entries`() {
        assertEquals(7, ViewMode.entries.size)
    }
}
