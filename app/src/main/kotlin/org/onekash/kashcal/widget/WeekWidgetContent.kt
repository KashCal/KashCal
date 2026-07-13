package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as JavaTextStyle

internal sealed class WeekWidgetItem(val itemId: Long) {
    data class Header(val dayCode: Int, val eventCount: Int) :
        WeekWidgetItem(dayCode.toLong())

    data class Event(val dayCode: Int, val event: WidgetDataRepository.WidgetEvent) :
        WeekWidgetItem(dayCode.toLong() * 100_000 + event.eventId + ITEM_ID_EVENT_OFFSET)

    data class Empty(val dayCode: Int) :
        WeekWidgetItem(dayCode.toLong() + ITEM_ID_EMPTY_OFFSET)

    data class Overflow(val dayCode: Int, val count: Int) :
        WeekWidgetItem(dayCode.toLong() + ITEM_ID_OVERFLOW_OFFSET)

    companion object {
        const val ITEM_ID_EVENT_OFFSET = 100_000_000L
        const val ITEM_ID_EMPTY_OFFSET = 200_000_000L
        const val ITEM_ID_OVERFLOW_OFFSET = 300_000_000L
    }
}

internal fun buildFlatWeekItems(
    weekEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    maxEventsPerDay: Int
): List<WeekWidgetItem> {
    val items = mutableListOf<WeekWidgetItem>()
    weekEvents.forEach { (dayCode, events) ->
        items.add(WeekWidgetItem.Header(dayCode, events.size))
        if (events.isEmpty()) {
            items.add(WeekWidgetItem.Empty(dayCode))
        } else {
            val visible = events.take(maxEventsPerDay)
            visible.forEach { event ->
                items.add(WeekWidgetItem.Event(dayCode, event))
            }
            if (events.size > maxEventsPerDay) {
                items.add(WeekWidgetItem.Overflow(dayCode, events.size - maxEventsPerDay))
            }
        }
    }
    return items
}

@Composable
fun WeekWidgetContent(
    weekEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    showEventEmojis: Boolean,
    timePattern: String = "h:mma",
    maxEventsPerDay: Int = 5
) {
    val flatItems = buildFlatWeekItems(weekEvents, maxEventsPerDay)
    val today = LocalDate.now()
    val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        WeekWidgetHeader(weekEvents.keys.toList())

        LazyColumn(
            modifier = GlanceModifier.fillMaxSize()
        ) {
            flatItems.forEach { widgetItem ->
                when (widgetItem) {
                    is WeekWidgetItem.Header -> item(itemId = widgetItem.itemId) {
                        DayHeader(widgetItem.dayCode, widgetItem.eventCount, widgetItem.dayCode == todayCode)
                    }
                    is WeekWidgetItem.Event -> item(itemId = widgetItem.itemId) {
                        CompactEventRow(widgetItem.event, widgetItem.dayCode, showEventEmojis, timePattern)
                    }
                    is WeekWidgetItem.Empty -> item(itemId = widgetItem.itemId) {
                        EmptyDayRow(widgetItem.dayCode)
                    }
                    is WeekWidgetItem.Overflow -> item(itemId = widgetItem.itemId) {
                        OverflowRow(widgetItem.dayCode, widgetItem.count)
                    }
                }
            }
        }
    }
}

/**
 * Widget header showing the week date range.
 * Tapping opens the app at today's view.
 */
@Composable
private fun WeekWidgetHeader(dayCodes: List<Int>) {
    val firstDay = dayCodes.firstOrNull() ?: return
    val lastDay = dayCodes.lastOrNull() ?: return

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            .padding(start = WIDGET_HORIZONTAL_MARGIN_DP.dp, top = 10.dp, bottom = 10.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                        )
                    )
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatWeekHeaderRange(firstDay, lastDay),
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.headerTitle,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        WidgetAddButton()
    }
}

@Composable
private fun DayHeader(dayCode: Int, eventCount: Int, isToday: Boolean) {
    val colors = dayHeaderColors(isToday)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(colors.background.provider())
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = 6.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
                        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatDayHeaderText(dayCode),
            style = TextStyle(
                color = colors.text.provider(),
                fontSize = WidgetTypography.contentTitle,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
            )
        )
        if (isToday) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = LocalContext.current.getString(R.string.label_today),
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.label,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (eventCount > 0) {
            Text(
                text = LocalContext.current.resources.getQuantityString(R.plurals.widget_event_count_plural, eventCount, eventCount),
                style = TextStyle(
                    color = if (isToday) WidgetTheme.onHeaderBackground else WidgetTheme.secondaryText,
                    fontSize = WidgetTypography.label
                )
            )
        }
    }
}

@Composable
private fun EmptyDayRow(dayCode: Int) {
    val dayStartTs = DayPagerUtils.dayCodeToMs(dayCode)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = 8.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT,
                        ActionParameters.Key<Long>(EXTRA_CREATE_EVENT_START_TS) to dayStartTs
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = LocalContext.current.getString(R.string.widget_no_events),
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = WidgetTypography.secondary
            )
        )
    }
}

@Composable
private fun CompactEventRow(
    event: WidgetDataRepository.WidgetEvent,
    dayCode: Int,
    showEventEmojis: Boolean,
    timePattern: String
) {
    val displayTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)

    val rowContext = LocalContext.current
    // A cancelled event only reads as a strikethrough visually; name that state
    // for TalkBack by labelling the whole row (time, title, cancelled).
    val cancelledLabel = if (event.isCancelled) {
        rowContext.getString(
            R.string.cd_widget_event_cancelled,
            formatWidgetEventTime(event, dayCode, timePattern, rowContext.getString(R.string.label_all_day)),
            displayTitle,
        )
    } else {
        null
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = EVENT_ROW_VERTICAL_PADDING_DP.dp)
            .let { m -> if (cancelledLabel != null) m.semantics { contentDescription = cancelledLabel } else m }
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_SHOW_EVENT,
                        ActionParameters.Key<Long>(EXTRA_EVENT_ID) to event.eventId,
                        ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS) to event.occurrenceStartTs,
                        ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT) to event.isDeviceEvent
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarColorBar(event.calendarColor)

        Spacer(modifier = GlanceModifier.width(BAR_TO_TIME_GAP_DP.dp))

        val allDayText = LocalContext.current.getString(R.string.label_all_day)
        Text(
            text = formatWidgetEventTime(event, dayCode, timePattern, allDayText),
            style = TextStyle(
                color = if (event.isPast || event.isCancelled) WidgetTheme.pastEventText else WidgetTheme.secondaryText,
                fontSize = WidgetTypography.secondary,
                textDecoration = if (event.isPast || event.isCancelled) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            modifier = GlanceModifier.width(timeColumnWidthDp(timePattern).dp)
        )

        Spacer(modifier = GlanceModifier.width(TIME_TO_TITLE_GAP_DP.dp))

        Text(
            text = displayTitle,
            style = TextStyle(
                color = if (event.isPast || event.isCancelled) WidgetTheme.pastEventText else WidgetTheme.primaryText,
                fontSize = WidgetTypography.contentTitle,
                fontWeight = FontWeight.Medium,
                textDecoration = if (event.isPast || event.isCancelled) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

@Composable
private fun OverflowRow(dayCode: Int, count: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp, vertical = 4.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
                        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = GlanceModifier.width(14.dp))
        Text(
            text = LocalContext.current.getString(R.string.status_more_events, count),
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = WidgetTypography.secondary
            )
        )
    }
}

/**
 * Format week header date range with full month names.
 * Same month: "March 7 – 13"
 * Cross-month: "March 28 – April 3"
 * Cross-year: "December 28 – January 3" (year omitted — always current/next week)
 */
internal fun formatWeekHeaderRange(firstDay: Int, lastDay: Int): String {
    val firstDate = DayPagerUtils.dayCodeToLocalDate(firstDay)
    val lastDate = DayPagerUtils.dayCodeToLocalDate(lastDay)
    val monthDay = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMMd"), Locale.getDefault())
    return if (firstDate.month == lastDate.month) {
        "${firstDate.format(monthDay)} \u2013 ${lastDate.dayOfMonth}"
    } else {
        "${firstDate.format(monthDay)} \u2013 ${lastDate.format(monthDay)}"
    }
}

internal fun formatDayHeaderText(dayCode: Int): String {
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    val dayName = date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    return "$dayName ${date.dayOfMonth}"
}
