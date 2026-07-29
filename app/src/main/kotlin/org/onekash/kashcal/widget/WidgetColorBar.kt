package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Box
import androidx.glance.layout.height
import androidx.glance.layout.width

/**
 * Slim vertical pill marking an event's calendar color.
 *
 * Placed on the leading (left) edge of every list-widget event row so the
 * agenda, week, and upcoming widgets share one consistent indicator, matching
 * the common calendar-app convention (issue #253). Replaces the earlier mix of
 * a left dot (week) and trailing-right dots (agenda, upcoming).
 *
 * @param color the resolved ARGB calendar/event color
 * @param heightDp the pill height, in dp. Defaults to the compact single-line
 *   height; a detailed two-line row passes the taller [COLOR_BAR_HEIGHT_DETAILED_DP]
 *   so the pill spans both text lines.
 */
@Composable
internal fun CalendarColorBar(color: Int, heightDp: Int = COLOR_BAR_HEIGHT_DP) {
    val barColor = Color(color)
    Box(
        modifier = GlanceModifier
            .width(COLOR_BAR_WIDTH_DP.dp)
            .height(heightDp.dp)
            .cornerRadius((COLOR_BAR_WIDTH_DP / 2).dp)
            .background(ColorProvider(day = barColor, night = barColor))
    ) {}
}

/** Width of the leading calendar-color pill, in dp. */
internal const val COLOR_BAR_WIDTH_DP = 4

/** Height of the leading calendar-color pill on a compact single-line row, in dp. */
internal const val COLOR_BAR_HEIGHT_DP = 10

/**
 * Height of the leading calendar-color pill on a detailed two-line row, in dp —
 * taller so the pill spans both the title line and the time line.
 */
internal const val COLOR_BAR_HEIGHT_DETAILED_DP = 32

/** Gap between the leading color pill and the time column, in dp. */
internal const val BAR_TO_TIME_GAP_DP = 4

/**
 * Vertical padding on a detailed two-line event row, in dp — shared by the
 * agenda, week, and upcoming list widgets so their row density stays uniform.
 * Combined with a two-line stack (14sp title + 12sp time, ~32dp) this lands the
 * row right at the 48dp Material minimum tap target. Extra padding beyond this
 * only adds whitespace — it does not enlarge the text — so it is kept snug to the
 * floor to fit more events before the list scrolls. Applied as padding rather
 * than a fixed row height so the row still grows with the system font-scale
 * instead of clipping the title.
 */
internal const val EVENT_ROW_VERTICAL_PADDING_DP = 8

/**
 * Vertical padding on a compact single-line event row, in dp. Denser than the
 * detailed padding so a single-line row clears roughly 28dp, fitting the most
 * events. The detailed row style offers the larger ~48dp tap target for users
 * who prefer it. Applied as padding, not a fixed height, so the row still grows
 * with the system font-scale.
 */
internal const val EVENT_ROW_VERTICAL_PADDING_COMPACT_DP = 4

/** Gap between the time column and the event title, in dp. */
internal const val TIME_TO_TITLE_GAP_DP = 2

/**
 * Left/right inset for list-widget rows and day headers, in dp. Shared so rows
 * and their headers align in a single column; kept off the rounded widget edge.
 */
internal const val WIDGET_HORIZONTAL_MARGIN_DP = 8

/**
 * Width of the leading time column for 12-hour times, in dp.
 *
 * Sized for the widest 12-hour string ("10:00 am" / "12:30 pm") at the
 * secondary font. Glance offers no min/max width constraint, so this is a fixed
 * width with no font-scale cushion — at large accessibility font sizes the
 * trailing "m" may clip, which is an acceptable degradation for the space saved.
 */
internal const val TIME_COL_WIDTH_12H_DP = 66

/**
 * Width of the leading time column for 24-hour times, in dp.
 *
 * 24-hour times ("13:30") carry no meridiem, so they reserve less space than
 * [TIME_COL_WIDTH_12H_DP]; using the wider 12-hour column for 24-hour users
 * leaves a visible gap before the title.
 */
internal const val TIME_COL_WIDTH_24H_DP = 50

/**
 * Selects the leading time-column width for a resolved [DateTimeFormatter]
 * pattern. 12-hour patterns carry the meridiem symbol `a`; 24-hour patterns
 * (`HH:mm` / `H:mm`) do not.
 */
internal fun timeColumnWidthDp(timePattern: String): Int =
    if (timePattern.contains('a')) TIME_COL_WIDTH_12H_DP else TIME_COL_WIDTH_24H_DP
