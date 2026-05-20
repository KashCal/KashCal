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
    fun `ViewMode has 8 entries`() {
        assertEquals(8, ViewMode.entries.size)
    }

    @Test
    fun `fromKey day returns DAY`() {
        assertEquals(ViewMode.DAY, ViewMode.fromKey("day"))
    }

    @Test
    fun `DAY key is day`() {
        assertEquals("day", ViewMode.DAY.key)
    }

    @Test
    fun `DAY visibleDays is 1`() {
        assertEquals(1, ViewMode.DAY.visibleDays)
    }

    @Test
    fun `DAY pagerNextStep is 1`() {
        assertEquals(1, ViewMode.DAY.pagerNextStep)
    }

    @Test
    fun `DAY isTimeGrid is true`() {
        assertEquals(true, ViewMode.DAY.isTimeGrid)
    }

    @Test
    fun `visibleDays is 3 for THREE_DAYS`() {
        assertEquals(3, ViewMode.THREE_DAYS.visibleDays)
    }

    @Test
    fun `visibleDays is 7 for WEEK`() {
        assertEquals(7, ViewMode.WEEK.visibleDays)
    }

    @Test
    fun `visibleDays is null for non-time-grid modes`() {
        assertEquals(null, ViewMode.MONTH.visibleDays)
        assertEquals(null, ViewMode.AGENDA.visibleDays)
        assertEquals(null, ViewMode.MONTH_FULL.visibleDays)
        assertEquals(null, ViewMode.YEAR.visibleDays)
        assertEquals(null, ViewMode.INSIGHTS.visibleDays)
    }

    @Test
    fun `pagerNextStep is 3 for THREE_DAYS`() {
        assertEquals(3, ViewMode.THREE_DAYS.pagerNextStep)
    }

    @Test
    fun `pagerNextStep is 1 for WEEK`() {
        assertEquals(1, ViewMode.WEEK.pagerNextStep)
    }

    @Test
    fun `pagerNextStep is null for non-time-grid modes`() {
        assertEquals(null, ViewMode.MONTH.pagerNextStep)
        assertEquals(null, ViewMode.AGENDA.pagerNextStep)
        assertEquals(null, ViewMode.MONTH_FULL.pagerNextStep)
        assertEquals(null, ViewMode.YEAR.pagerNextStep)
        assertEquals(null, ViewMode.INSIGHTS.pagerNextStep)
    }
}
