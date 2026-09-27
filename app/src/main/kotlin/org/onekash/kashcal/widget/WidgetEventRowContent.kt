package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.layout.Column
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher

/**
 * Renders the inside of a list-widget event row: the leading color pill, the time and the title.
 * Shared by the Agenda, Week and Upcoming widgets so their rows look the same; each wraps it in
 * its own clickable Row, which owns the padding and tap action. Past and cancelled events are
 * grayed and struck through.
 *
 * @param detailedRows false renders one compact line (pill, start time in a fixed-width column,
 *   title); true renders a two-line stack (title at full width, then the start-end time) beside
 *   a taller pill that spans both lines.
 */
@Composable
internal fun RowScope.EventRowInner(
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String,
    detailedRows: Boolean
) {
    val context = LocalContext.current
    val displayTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)
    val allDayText = context.getString(R.string.label_all_day)
    val dimmed = event.isPast || event.isCancelled
    val textColor = if (dimmed) WidgetTheme.pastEventText else WidgetTheme.primaryText
    val decoration = if (dimmed) TextDecoration.LineThrough else TextDecoration.None

    if (detailedRows) {
        // Taller pill spans both text lines.
        CalendarColorBar(event.calendarColor, heightDp = COLOR_BAR_HEIGHT_DETAILED_DP)
        Spacer(modifier = GlanceModifier.width(BAR_TO_TIME_GAP_DP.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            // Title first, at full width (no fixed time column).
            Text(
                text = displayTitle,
                style = TextStyle(
                    color = textColor,
                    fontSize = WidgetTypography.contentTitle,
                    fontWeight = FontWeight.Medium,
                    textDecoration = decoration
                ),
                maxLines = 1
            )
            // Same color as the title so the row reads as one unit.
            Text(
                text = formatWidgetEventTimeRange(context, event, dayCode, timePattern, allDayText),
                style = TextStyle(
                    color = textColor,
                    fontSize = WidgetTypography.secondary,
                    textDecoration = decoration
                ),
                maxLines = 1
            )
        }
    } else {
        CalendarColorBar(event.calendarColor)
        Spacer(modifier = GlanceModifier.width(BAR_TO_TIME_GAP_DP.dp))
        // The time column's width follows the resolved 12h/24h format. The time shares the
        // title's color so the row reads as one unit, not a two-tone split.
        Text(
            text = formatWidgetEventTime(event, dayCode, timePattern, allDayText),
            style = TextStyle(
                color = textColor,
                fontSize = WidgetTypography.secondary,
                textDecoration = decoration
            ),
            maxLines = 1,
            modifier = GlanceModifier.width(timeColumnWidthDp(timePattern).dp)
        )
        Spacer(modifier = GlanceModifier.width(TIME_TO_TITLE_GAP_DP.dp))
        Text(
            text = displayTitle,
            style = TextStyle(
                color = textColor,
                fontSize = WidgetTypography.contentTitle,
                fontWeight = FontWeight.Medium,
                textDecoration = decoration
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

/**
 * Returns the row's vertical padding for the row style: compact rows come to ~28dp, detailed
 * two-line rows to ~48dp.
 */
internal fun eventRowVerticalPaddingDp(detailedRows: Boolean): Int =
    if (detailedRows) EVENT_ROW_VERTICAL_PADDING_DP else EVENT_ROW_VERTICAL_PADDING_COMPACT_DP

/**
 * Builds the TalkBack label for a cancelled event row, which shows only as a strikethrough.
 * Both row styles use the compact time string so the spoken label stays short.
 */
internal fun cancelledRowLabel(
    context: android.content.Context,
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    timePattern: String,
    displayTitle: String
): String = context.getString(
    R.string.cd_widget_event_cancelled,
    formatWidgetEventTime(event, dayCode, timePattern, context.getString(R.string.label_all_day)),
    displayTitle,
)
