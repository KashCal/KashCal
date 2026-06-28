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
internal const val COLOR_BAR_HEIGHT_DP = 20
