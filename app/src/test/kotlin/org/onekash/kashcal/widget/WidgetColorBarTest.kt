package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the row sizing constants shared by the Agenda, Week and Upcoming widgets: the time column
 * width per time format, the row paddings per style, the color pill heights, the time-to-title gap
 * and the horizontal margin.
 *
 * The time column has a fixed width (Glance has no min or max width constraint), so it is sized
 * per resolved time format: 24-hour times ("13:30") are narrower than 12-hour times ("10:00 am"),
 * and one width tuned for 12-hour leaves a visible gap for 24-hour users. The format-to-width
 * tests stop an edit from widening the 24-hour column back into a gap or narrowing the 12-hour
 * column into clipping.
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
        // 24-hour times have no meridiem, so the column must reserve less space than 12-hour
        // or the gap returns.
        assertTrue(
            "24h width ($TIME_COL_WIDTH_24H_DP) must be less than 12h width ($TIME_COL_WIDTH_12H_DP)",
            TIME_COL_WIDTH_24H_DP < TIME_COL_WIDTH_12H_DP
        )
    }

    @Test
    fun `detection keys on the meridiem marker not the pattern literal`() {
        // Any pattern carrying the meridiem symbol is 12-hour regardless of seconds; anything
        // without it is treated as 24-hour.
        assertEquals(TIME_COL_WIDTH_12H_DP, timeColumnWidthDp("h:mm:ss a"))
        assertEquals(TIME_COL_WIDTH_24H_DP, timeColumnWidthDp("H:mm"))
    }

    @Test
    fun `detailed row padding clears the Material tap target`() {
        // One constant per row style keeps the three widgets' rows uniform. The detailed row is
        // the accessible option: its padding plus the ~32dp two-line text stack must reach the
        // 48dp Material minimum (8dp x 2 + ~32dp), so this guards the floor. More padding only
        // adds whitespace, not larger text, so the constant stays at the floor.
        assertTrue(
            "detailed row vertical padding ($EVENT_ROW_VERTICAL_PADDING_DP dp) must be at least 8dp so the two-line row clears the 48dp tap target",
            EVENT_ROW_VERTICAL_PADDING_DP >= 8
        )
    }

    @Test
    fun `compact row padding stays denser than the detailed row`() {
        // The default compact style is denser than detailed so more events fit, and sits below
        // the 48dp target by design. An edit mustn't make it as tall as detailed or non-positive.
        assertTrue(
            "compact padding ($EVENT_ROW_VERTICAL_PADDING_COMPACT_DP dp) must be positive",
            EVENT_ROW_VERTICAL_PADDING_COMPACT_DP > 0
        )
        assertTrue(
            "compact padding ($EVENT_ROW_VERTICAL_PADDING_COMPACT_DP dp) must be denser than detailed ($EVENT_ROW_VERTICAL_PADDING_DP dp)",
            EVENT_ROW_VERTICAL_PADDING_COMPACT_DP < EVENT_ROW_VERTICAL_PADDING_DP
        )
    }

    @Test
    fun `row padding selector maps each style to its constant`() {
        // Detailed maps to the detailed constant, compact to the compact one. A swap would
        // silently give each row the other's density, and no visual test would catch it.
        assertEquals(EVENT_ROW_VERTICAL_PADDING_DP, eventRowVerticalPaddingDp(detailedRows = true))
        assertEquals(EVENT_ROW_VERTICAL_PADDING_COMPACT_DP, eventRowVerticalPaddingDp(detailedRows = false))
    }

    @Test
    fun `detailed color pill spans both text lines`() {
        // The two-line detailed row uses a taller pill than the compact row so it spans the
        // text stack.
        assertTrue(
            "detailed pill ($COLOR_BAR_HEIGHT_DETAILED_DP dp) must be taller than the compact pill ($COLOR_BAR_HEIGHT_DP dp)",
            COLOR_BAR_HEIGHT_DETAILED_DP > COLOR_BAR_HEIGHT_DP
        )
    }

    @Test
    fun `time-to-title gap is tight and shared`() {
        // One shared constant keeps the time snug to the title in all three widgets.
        assertTrue(
            "time-to-title gap ($TIME_TO_TITLE_GAP_DP dp) should stay at or below 2dp",
            TIME_TO_TITLE_GAP_DP <= 2
        )
    }

    @Test
    fun `horizontal frame margin stays clear of the widget edge`() {
        // One left and right inset for rows and day headers so they align in one column, clear
        // of the rounded widget corners.
        assertEquals(8, WIDGET_HORIZONTAL_MARGIN_DP)
    }

    @Test
    fun `color bar is short enough to not drive row height`() {
        // #253: a 20dp pill forced a minimum row height; at 10dp or less the centered text sets
        // the row height.
        assertTrue(
            "color bar height ($COLOR_BAR_HEIGHT_DP dp) should stay at or below 10dp",
            COLOR_BAR_HEIGHT_DP <= 10
        )
    }
}
