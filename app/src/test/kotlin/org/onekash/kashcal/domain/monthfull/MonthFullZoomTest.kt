package org.onekash.kashcal.domain.monthfull

import org.junit.Assert.assertEquals
import org.junit.Test

class MonthFullZoomTest {

    @Test
    fun `zoom is clamped and a missing or broken value means no zoom`() {
        assertEquals(1.6f, clampMonthFullZoom(2.0f))
        assertEquals(0.85f, clampMonthFullZoom(0.5f))
        assertEquals(1.2f, clampMonthFullZoom(1.2f))
        assertEquals(1.0f, clampMonthFullZoom(0f))
        assertEquals(1.0f, clampMonthFullZoom(-1f))
        assertEquals(1.0f, clampMonthFullZoom(Float.NaN))
        assertEquals(1.0f, clampMonthFullZoom(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `the limits are the Planner's range`() {
        assertEquals(0.85f, MIN_MONTH_FULL_ZOOM)
        assertEquals(1.6f, MAX_MONTH_FULL_ZOOM)
    }
}
