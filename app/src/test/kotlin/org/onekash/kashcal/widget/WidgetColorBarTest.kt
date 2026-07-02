package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants for the shared list-widget row primitives — the calendar-color
 * pill and the leading time column shared by the agenda, week, and upcoming
 * widgets.
 *
 * The time column is a fixed width (Glance has no min/max width constraint), so
 * it must be sized per resolved time format: 24-hour times ("13:30") are
 * narrower than 12-hour times ("10:00 am"), and a single width tuned for 12h
 * leaves a visible gap for 24h users. These tests pin the format-to-width
 * mapping and the relative ordering so a future edit can't silently widen the
 * 24h column back into a gap or narrow the 12h column into clipping.
 */
class WidgetColorBarTest {

    @Test
    fun `12-hour pattern selects the wider column`() {
        assertEquals(TIME_COL_WIDTH_12H_DP, timeColumnWidthDp("h:mm a"))
    }

    @Test
    fun `24-hour pattern selects the narrower column`() {
        assertEquals(TIME_COL_WIDTH_24H_DP, timeColumnWidthDp("HH:mm"))
    }

    @Test
    fun `24-hour column is narrower than 12-hour column`() {
        // The whole point of the format-aware width: 24h has no meridiem, so it
        // must reserve less horizontal space than 12h or the gap returns.
        assertTrue(
            "24h width ($TIME_COL_WIDTH_24H_DP) must be less than 12h width ($TIME_COL_WIDTH_12H_DP)",
            TIME_COL_WIDTH_24H_DP < TIME_COL_WIDTH_12H_DP
        )
    }

    @Test
    fun `detection keys on the meridiem marker not the pattern literal`() {
        // Any pattern carrying the meridiem symbol is 12-hour regardless of
        // separator or seconds; anything without it is treated as 24-hour.
        assertEquals(TIME_COL_WIDTH_12H_DP, timeColumnWidthDp("h:mm:ss a"))
        assertEquals(TIME_COL_WIDTH_24H_DP, timeColumnWidthDp("H:mm"))
    }

    @Test
    fun `event rows share one tight vertical padding across all list widgets`() {
        // The agenda, week, and upcoming event rows historically drifted to
        // different vertical paddings (6dp / 4dp / 6dp). One shared constant
        // keeps them uniform and prevents future drift; assert it stays tight.
        assertTrue(
            "event row vertical padding ($EVENT_ROW_VERTICAL_PADDING_DP dp) should stay at or below 4dp",
            EVENT_ROW_VERTICAL_PADDING_DP <= 4
        )
    }

    @Test
    fun `time-to-title gap is tight and shared`() {
        // Historically drifted (8dp agenda / 8dp upcoming / 4dp week); one
        // shared constant keeps the time snug to the title uniformly.
        assertTrue(
            "time-to-title gap ($TIME_TO_TITLE_GAP_DP dp) should stay at or below 2dp",
            TIME_TO_TITLE_GAP_DP <= 2
        )
    }

    @Test
    fun `horizontal frame margin stays clear of the widget edge`() {
        // Shared left/right inset for rows AND day headers so they align in one
        // column. Tightened from 12dp, but kept off the rounded widget corners.
        assertEquals(8, WIDGET_HORIZONTAL_MARGIN_DP)
    }

    @Test
    fun `color bar is short enough to not drive row height`() {
        // Regression guard for issue #253 follow-up: the pill was 20dp tall and
        // forced a minimum row height; halving it lets the centered text set the
        // row height instead. Assert it stays at or below the halved value.
        assertTrue(
            "color bar height ($COLOR_BAR_HEIGHT_DP dp) should stay at or below 10dp",
            COLOR_BAR_HEIGHT_DP <= 10
        )
    }
}
