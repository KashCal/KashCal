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
 * Renders the slim vertical pill marking an event's color.
 *
 * Sits on the leading edge of every Agenda, Week and Upcoming event row, so the three widgets
 * share one indicator, as calendar apps commonly do (#253).
 *
 * @param color the resolved ARGB event or calendar color
 * @param heightDp the pill height in dp; a detailed two-line row passes
 *   [COLOR_BAR_HEIGHT_DETAILED_DP] so the pill spans both lines.
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

/** Height of the leading calendar-color pill on a detailed row, in dp: spans both lines. */
internal const val COLOR_BAR_HEIGHT_DETAILED_DP = 32

/** Gap between the leading color pill and the time column, in dp. */
internal const val BAR_TO_TIME_GAP_DP = 4

/**
 * Vertical padding on a detailed two-line event row, in dp, shared by the Agenda, Week and
 * Upcoming widgets. With the two-line stack (14sp title and 12sp time, ~32dp) the row lands at
 * the 48dp Material minimum tap target; more padding only adds whitespace, so it stays at that
 * floor to fit more events before the list scrolls. Padding, not a fixed row height, so the
 * row grows with the system font scale instead of clipping the title.
 */
internal const val EVENT_ROW_VERTICAL_PADDING_DP = 8

/**
 * Vertical padding on a compact single-line event row, in dp, so the row comes to about 28dp
 * and fits the most events; the detailed style offers the ~48dp tap target. Padding, not a
 * fixed height, so the row grows with the system font scale.
 */
internal const val EVENT_ROW_VERTICAL_PADDING_COMPACT_DP = 4

/** Gap between the time column and the event title, in dp. */
internal const val TIME_TO_TITLE_GAP_DP = 2

/**
 * Left and right inset for list-widget rows, day headers and widget headers, in dp, so they
 * align in one column clear of the rounded widget edge.
 */
internal const val WIDGET_HORIZONTAL_MARGIN_DP = 8

/**
 * Width of the leading time column for 12-hour times, in dp.
 *
 * Sized for the widest 12-hour string ("10:00 am", "12:30 pm") at the secondary font. Glance
 * has no min or max width constraint, so the width is fixed with no font-scale cushion: at
 * large accessibility font sizes the trailing "m" may clip, accepted for the space saved.
 */
internal const val TIME_COL_WIDTH_12H_DP = 66

/**
 * Width of the leading time column for 24-hour times ("13:30"), in dp. With no meridiem they
 * need less than [TIME_COL_WIDTH_12H_DP], whose extra width would leave a visible gap before
 * the title.
 */
internal const val TIME_COL_WIDTH_24H_DP = 50

/**
 * Returns the time-column width for a resolved `DateTimeFormatter` pattern: 12-hour patterns
 * carry the meridiem symbol `a`, 24-hour patterns (`HH:mm`, `H:mm`) don't.
 */
internal fun timeColumnWidthDp(timePattern: String): Int =
    if (timePattern.contains('a')) TIME_COL_WIDTH_12H_DP else TIME_COL_WIDTH_24H_DP
