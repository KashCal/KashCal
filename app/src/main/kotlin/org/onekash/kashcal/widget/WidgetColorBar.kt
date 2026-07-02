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
 */
@Composable
internal fun CalendarColorBar(color: Int) {
    val barColor = Color(color)
    Box(
        modifier = GlanceModifier
            .width(COLOR_BAR_WIDTH_DP.dp)
            .height(COLOR_BAR_HEIGHT_DP.dp)
            .cornerRadius((COLOR_BAR_WIDTH_DP / 2).dp)
            .background(ColorProvider(day = barColor, night = barColor))
    ) {}
}

/** Width of the leading calendar-color pill, in dp. */
internal const val COLOR_BAR_WIDTH_DP = 4

/** Height of the leading calendar-color pill, in dp. */
internal const val COLOR_BAR_HEIGHT_DP = 10

/** Gap between the leading color pill and the time column, in dp. */
internal const val BAR_TO_TIME_GAP_DP = 4

/**
 * Vertical padding on a single event row, in dp — shared by the agenda, week,
 * and upcoming list widgets so their row density stays uniform. Kept tight so
 * more events fit in a fixed widget height.
 */
internal const val EVENT_ROW_VERTICAL_PADDING_DP = 4

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
