package org.onekash.kashcal.ui.components.weekview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Checks the time grid's two-finger pinch rules: which axis a pinch takes, when a sideways pinch
 * switches Week, 3-day and Day, how far the columns stretch meanwhile, and which day sits under
 * the fingers.
 */
class PinchViewSwitchTest {

    @Test
    fun `no axis until a span changes by more than the slop`() {
        assertNull(pinchAxis(startSpanX = 200f, startSpanY = 100f, spanX = 205f, spanY = 104f, slopPx = 8f))
    }

    @Test
    fun `the larger span change picks the axis`() {
        assertEquals(PinchAxis.SIDEWAYS, pinchAxis(200f, 100f, 240f, 110f, slopPx = 8f))
        assertEquals(PinchAxis.VERTICAL, pinchAxis(200f, 100f, 210f, 140f, slopPx = 8f))
    }

    @Test
    fun `a closing pinch picks its axis by the size of the change`() {
        assertEquals(PinchAxis.SIDEWAYS, pinchAxis(200f, 100f, 150f, 95f, slopPx = 8f))
    }

    @Test
    fun `a tie goes to the vertical hour zoom`() {
        assertEquals(PinchAxis.VERTICAL, pinchAxis(200f, 100f, 220f, 120f, slopPx = 8f))
    }

    @Test
    fun `the spread ratio floors a tiny starting span`() {
        assertEquals(2f, spreadRatio(startSpan = 100f, span = 200f, minSpanPx = 48f), 0.001f)
        // Fingers almost on top of each other: measured against the floor, not the 2px gap.
        assertEquals(1.25f, spreadRatio(startSpan = 2f, span = 60f, minSpanPx = 48f), 0.001f)
    }

    @Test
    fun `a pinch in from close gets small`() {
        assertEquals(20f / 56f, spreadRatio(startSpan = 56f, span = 20f, minSpanPx = 48f), 0.001f)
    }

    @Test
    fun `spreading far enough shows fewer days`() {
        assertEquals(3, nextVisibleDays(7, 1.3f))
        assertEquals(1, nextVisibleDays(3, 1.5f))
    }

    @Test
    fun `pinching in far enough shows more days`() {
        assertEquals(3, nextVisibleDays(1, 0.75f))
        assertEquals(7, nextVisibleDays(3, 0.6f))
    }

    @Test
    fun `short of the thresholds nothing switches`() {
        assertNull(nextVisibleDays(7, 1.29f))
        assertNull(nextVisibleDays(3, 0.76f))
        assertNull(nextVisibleDays(3, 1f))
    }

    @Test
    fun `nothing lies past the chain's ends`() {
        assertNull(nextVisibleDays(1, 2f))
        assertNull(nextVisibleDays(7, 0.5f))
    }

    @Test
    fun `the preview stretches with the fingers up to 10 percent`() {
        assertEquals(1f, previewScaleX(7, 1f), 0.0001f)
        assertEquals(1.035f, previewScaleX(7, 1.1f), 0.0001f)
        assertEquals(1.10f, previewScaleX(7, 1.6f), 0.0001f)
        assertEquals(0.90f, previewScaleX(3, 0.4f), 0.0001f)
    }

    @Test
    fun `at an end the preview stops at 5 percent`() {
        assertEquals(1.05f, previewScaleX(1, 1.6f), 0.0001f)
        assertEquals(0.95f, previewScaleX(7, 0.4f), 0.0001f)
    }

    @Test
    fun `the preview grows with the spread`() {
        val scales = listOf(0.5f, 0.8f, 0.95f, 1f, 1.05f, 1.2f, 1.5f).map { previewScaleX(3, it) }
        assertTrue("$scales", scales.zipWithNext().all { (a, b) -> a <= b })
    }

    private val days = (0L..2L).map { LocalDate.of(2026, 10, 15).plusDays(it) }

    @Test
    fun `the anchor is the column under the fingers`() {
        assertEquals(days[0], pinchAnchorDate(days, centroidX = 10f, columnsWidth = 300f, isRtl = false))
        assertEquals(days[1], pinchAnchorDate(days, centroidX = 150f, columnsWidth = 300f, isRtl = false))
        assertEquals(days[2], pinchAnchorDate(days, centroidX = 299f, columnsWidth = 300f, isRtl = false))
    }

    @Test
    fun `the anchor is clamped to the columns`() {
        assertEquals(days[0], pinchAnchorDate(days, centroidX = -20f, columnsWidth = 300f, isRtl = false))
        assertEquals(days[2], pinchAnchorDate(days, centroidX = 400f, columnsWidth = 300f, isRtl = false))
    }

    @Test
    fun `right to left the first date is the rightmost column`() {
        assertEquals(days[2], pinchAnchorDate(days, centroidX = 10f, columnsWidth = 300f, isRtl = true))
        assertEquals(days[0], pinchAnchorDate(days, centroidX = 290f, columnsWidth = 300f, isRtl = true))
    }
}
