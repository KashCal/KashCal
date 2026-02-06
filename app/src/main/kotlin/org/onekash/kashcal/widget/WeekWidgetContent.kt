package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Maximum events shown per day */
private const val MAX_EVENTS_PER_DAY = 5

/**
 * Main content composable for the week widget.
 * Shows 7 days (today + 6) in a scrollable list with events for each day.
 *
 * @param weekEvents Map of day code to events for that day
 * @param showEventEmojis Whether to show auto-detected emojis in event titles
 * @param timePattern Time format pattern (e.g., "h:mma" for 12h, "HH:mm" for 24h)
 */
@Composable
fun WeekWidgetContent(
    weekEvents: Map<Int, List<WidgetDataRepository.WidgetEvent>>,
    showEventEmojis: Boolean,
    timePattern: String = "h:mma"
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        // Header: "This Week" with date range
        WeekWidgetHeader(weekEvents.keys.toList())

        // Scrollable day list (LazyColumn for 7 days)
        LazyColumn(
            modifier = GlanceModifier.fillMaxSize()
        ) {
            items(
                items = weekEvents.toList(),
                itemId = { (dayCode, _) -> dayCode.toLong() }
            ) { (dayCode, events) ->
                DaySection(
                    dayCode = dayCode,
                    events = events,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
                    isToday = isToday(dayCode)
                )
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

    val firstDate = DayPagerUtils.dayCodeToLocalDate(firstDay)
    val lastDate = DayPagerUtils.dayCodeToLocalDate(lastDay)
    val formatter = DateTimeFormatter.ofPattern("MMM d")

    val headerText = "${firstDate.format(formatter)} - ${lastDate.format(formatter)}"

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
            text = "This Week",
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(
            text = headerText,
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 12.sp
            )
        )
    }
}

/**
 * Single day section with day header and events list.
 */
@Composable
private fun DaySection(
    dayCode: Int,
    events: List<WidgetDataRepository.WidgetEvent>,
    showEventEmojis: Boolean,
    timePattern: String,
    isToday: Boolean
) {
    Column(
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        // Day header (e.g., "Sun 19" or "Mon 20")
        DayHeader(dayCode, events.size, isToday)

        if (events.isEmpty()) {
            EmptyDayRow(dayCode)
        } else {
            val visibleEvents = events.take(MAX_EVENTS_PER_DAY)
            visibleEvents.forEach { event ->
                CompactEventRow(event, showEventEmojis, timePattern)
            }
            if (events.size > MAX_EVENTS_PER_DAY) {
                OverflowRow(dayCode, events.size - MAX_EVENTS_PER_DAY)
            }
        }
    }
}

/**
 * Day header showing day name and date.
 * Tapping navigates to that day in the app.
 */
@Composable
private fun DayHeader(dayCode: Int, eventCount: Int, isToday: Boolean) {
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    val dayName = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
    val dayNum = date.dayOfMonth

    val backgroundColor = if (isToday) {
        WidgetTheme.headerBackground
    } else {
        WidgetTheme.dividerColor
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
            text = "$dayName $dayNum",
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 13.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
            )
        )
        if (isToday) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "Today",
                style = TextStyle(
                    color = WidgetTheme.accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (eventCount > 0) {
            Text(
                text = "$eventCount event${if (eventCount != 1) "s" else ""}",
                style = TextStyle(
                    color = WidgetTheme.secondaryText,
                    fontSize = 11.sp
                )
            )
        }
    }
}

/**
 * Empty day row with "No events" text.
 * Tapping creates a new event on that day.
 */
@Composable
private fun EmptyDayRow(dayCode: Int) {
    val dayStartTs = DayPagerUtils.dayCodeToMs(dayCode)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
            text = "No events",
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 12.sp
            )
        )
    }
}

/**
 * Compact event row showing time and title.
 * Tapping opens the event quick view.
 */
@Composable
private fun CompactEventRow(
    event: WidgetDataRepository.WidgetEvent,
    showEventEmojis: Boolean,
    timePattern: String
) {
    val displayTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)
    val calendarColor = Color(event.calendarColor)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_SHOW_EVENT,
                        ActionParameters.Key<Long>(EXTRA_EVENT_ID) to event.eventId,
                        ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS) to event.occurrenceStartTs
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Calendar color dot
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .cornerRadius(3.dp)
                .background(ColorProvider(day = calendarColor, night = calendarColor))
        ) {}

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Time
        Text(
            text = formatCompactTime(event, timePattern),
            style = TextStyle(
                color = if (event.isPast) WidgetTheme.pastEventText else WidgetTheme.secondaryText,
                fontSize = 11.sp,
                textDecoration = if (event.isPast) TextDecoration.LineThrough else TextDecoration.None
            ),
            modifier = GlanceModifier.width(48.dp)
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Title
        Text(
            text = displayTitle,
            style = TextStyle(
                color = if (event.isPast) WidgetTheme.pastEventText else WidgetTheme.primaryText,
                fontSize = 12.sp,
                textDecoration = if (event.isPast) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
    }
}

/**
 * Overflow indicator showing how many more events exist.
 * Tapping navigates to that day in the app.
 */
@Composable
private fun OverflowRow(dayCode: Int, count: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
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
        Spacer(modifier = GlanceModifier.width(14.dp))  // Align with event text
        Text(
            text = "+$count more",
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = 11.sp
            )
        )
    }
}

/** Format event time compactly (e.g., "9:00a" or "All day") */
private fun formatCompactTime(event: WidgetDataRepository.WidgetEvent, timePattern: String): String {
    return if (event.isAllDay) {
        "All day"
    } else {
        val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
        Instant.ofEpochMilli(event.startTs)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
            .lowercase(Locale.getDefault())
    }
}

/** Check if dayCode is today */
private fun isToday(dayCode: Int): Boolean {
    val today = LocalDate.now()
    val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
    return dayCode == todayCode
}
