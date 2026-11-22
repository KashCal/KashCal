package org.onekash.kashcal.ui.components.weekview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Space between an hour label and the day columns. */
internal val HOUR_LABEL_END_PADDING = 8.dp

/** Space on each side of the 7-day view's week label, so it doesn't touch the first day header. */
internal val WEEK_LABEL_PADDING = 2.dp

/**
 * Returns the width of the time grid's hour column: the widest of the 24 hour labels
 * ([WeekViewUtils.formatHourLabel]) plus [HOUR_LABEL_END_PADDING], or, when [weekLabelPrefix] is
 * given (the 7-day view's header corner), the widest week label it can produce if that is wider.
 * Both are measured in labelSmall at the current density and font scale and rounded up to whole
 * pixels. The week label is measured with week 53, so the width stays the same on every page.
 * The day headers and the all-day strip line up on it; the Day view's week strip uses the Week
 * view's ([rememberWeekTimeColumnWidth]) to sit over its headers.
 */
@Composable
fun rememberTimeColumnWidth(is24Hour: Boolean, weekLabelPrefix: String?): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return remember(is24Hour, weekLabelPrefix, style, density) {
        with(density) {
            val hourLabels = (0 until 24).maxOf { hour ->
                measurer.measure(WeekViewUtils.formatHourLabel(hour, is24Hour), style, softWrap = false, maxLines = 1).size.width
            } + HOUR_LABEL_END_PADDING.toPx()
            val weekLabel = weekLabelPrefix?.let { prefix ->
                measurer.measure("${prefix}53", style, softWrap = false, maxLines = 1).size.width + WEEK_LABEL_PADDING.toPx() * 2
            } ?: 0f
            ceil(maxOf(hourLabels, weekLabel)).toDp()
        }
    }
}

/**
 * Returns the Week view's hour column width for [timePattern] (24-hour when it starts with "H")
 * and its corner's [weekLabelPrefix] (blank for none). The 7-day grid and the Day view's week
 * strip both use it, so the strip's dates sit over the Week view's headers.
 */
@Composable
fun rememberWeekTimeColumnWidth(timePattern: String, weekLabelPrefix: String): Dp =
    rememberTimeColumnWidth(is24Hour = timePattern.startsWith("H"), weekLabelPrefix = weekLabelPrefix.takeIf { it.isNotEmpty() })

