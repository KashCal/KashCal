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
    fun `detailed row padding clears the Material tap target`() {
        // The agenda, week, and upcoming event rows historically drifted to
        // different vertical paddings (6dp / 4dp / 6dp). One shared constant per
        // row style keeps each uniform across the three widgets. The DETAILED row
        // is the accessible option: its padding plus a two-line ~32dp text stack
        // must land the row at the 48dp Material minimum. 8dp x 2 + ~32dp ~= 48dp,
        // so guard against a future edit dropping below the floor. (Extra padding
        // beyond this only adds whitespace — it does not enlarge the text — so the
        // constant is kept snug to 8dp rather than larger.)
        assertTrue(
            "detailed row vertical padding ($EVENT_ROW_VERTICAL_PADDING_DP dp) must be at least 8dp so the two-line row clears the 48dp tap target",
            EVENT_ROW_VERTICAL_PADDING_DP >= 8
        )
    }

    @Test
    fun `compact row padding stays denser than the detailed row`() {
        // The default (compact) style intentionally reverts to a denser row so
        // more events fit; it sits below the detailed style's comfortable target
        // by design. Pin the relationship so a future edit can't accidentally
        // make compact as tall as detailed (erasing the density difference) or
        // negative.
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
        // The style flag routes to the right padding: detailed -> the comfortable
        // constant, compact -> the dense one. A swap here would silently give the
        // compact row detailed density (or vice versa) with no visual test.
        assertEquals(EVENT_ROW_VERTICAL_PADDING_DP, eventRowVerticalPaddingDp(detailedRows = true))
        assertEquals(EVENT_ROW_VERTICAL_PADDING_COMPACT_DP, eventRowVerticalPaddingDp(detailedRows = false))
    }

    @Test
    fun `detailed color pill spans both text lines`() {
        // The two-line detailed row uses a taller pill than the compact single
        // line so the color indicator runs the full height of the stack.
        assertTrue(
            "detailed pill ($COLOR_BAR_HEIGHT_DETAILED_DP dp) must be taller than the compact pill ($COLOR_BAR_HEIGHT_DP dp)",
            COLOR_BAR_HEIGHT_DETAILED_DP > COLOR_BAR_HEIGHT_DP
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
