package org.onekash.kashcal.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Main content composable for the agenda widget.
 *
 * Displays today's date header and a list of events.
 * Supports tap actions to open the app.
 *
 * @param events List of events to display
 * @param currentDate Formatted current date for header
 * @param showEventEmojis Whether to show auto-detected emojis in event titles
 */
@Composable
fun AgendaWidgetContent(
    events: List<WidgetDataRepository.WidgetEvent>,
    currentDate: String,
    showEventEmojis: Boolean = true
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        // Header with date
        WidgetHeader(currentDate)

        // Event list or empty state
        if (events.isEmpty()) {
            EmptyState()
        } else {
            EventList(events, showEventEmojis)
        }
    }
}

/**
 * Widget header showing the current date.
 * Tapping opens the app at today's view.
 */
@Composable
private fun WidgetHeader(date: String) {
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
        Image(
            provider = ImageProvider(R.drawable.ic_widget_calendar),
            contentDescription = "Calendar",
            modifier = GlanceModifier.size(20.dp)
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = date,
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/**
 * List of today's events.
 * Limited to MAX_VISIBLE_EVENTS with overflow indicator.
 */
@Composable
private fun EventList(
    events: List<WidgetDataRepository.WidgetEvent>,
    showEventEmojis: Boolean
) {
    val visibleEvents = events.take(MAX_VISIBLE_EVENTS)
    val overflowCount = events.size - MAX_VISIBLE_EVENTS

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        visibleEvents.forEach { event ->
            EventRow(event, showEventEmojis)
        }

        if (overflowCount > 0) {
            OverflowIndicator(overflowCount)
        }
    }
}

/**
 * Single event row showing time, title, and calendar color.
 * Tapping opens the event quick view.
 */
@Composable
private fun EventRow(
    event: WidgetDataRepository.WidgetEvent,
    showEventEmojis: Boolean
) {
    // Format title with optional emoji prefix
    val displayTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
        // Time column
        Text(
            text = formatEventTime(event),
            style = TextStyle(
                color = if (event.isPast) WidgetTheme.pastEventText else WidgetTheme.secondaryText,
                fontSize = 12.sp,
                textDecoration = if (event.isPast) TextDecoration.LineThrough else TextDecoration.None
            ),
            modifier = GlanceModifier.width(58.dp)
        )

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Title (fills remaining space)
        Text(
            text = displayTitle,
            style = TextStyle(
                color = if (event.isPast) WidgetTheme.pastEventText else WidgetTheme.primaryText,
                fontSize = 14.sp,
                textDecoration = if (event.isPast) TextDecoration.LineThrough else TextDecoration.None
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Calendar color dot
        val calendarColor = Color(event.calendarColor)
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(ColorProvider(day = calendarColor, night = calendarColor)),
            contentAlignment = Alignment.Center
        ) {
            // Empty box, just showing the color
        }
    }
}

/**
 * Empty state shown when there are no events today.
 * Tapping creates a new event.
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_CREATE_EVENT
                    )
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "No events today",
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = 14.sp
            )
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = "+ Add event",
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = 14.sp
            )
        )
    }
}

/**
 * Overflow indicator showing how many more events exist.
 * Tapping opens the app at today's view.
 */
@Composable
private fun OverflowIndicator(count: Int) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
            text = "+ $count more",
            style = TextStyle(
                color = WidgetTheme.accentColor,
                fontSize = 12.sp
            )
        )
    }
}

/**
 * Format event time for display.
 */
private fun formatEventTime(event: WidgetDataRepository.WidgetEvent): String {
    return if (event.isAllDay) {
        "All day"
    } else {
        val formatter = DateTimeFormatter.ofPattern("h:mm a")
        Instant.ofEpochMilli(event.startTs)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }
}

// Intent extra keys and action values
const val EXTRA_ACTION = "widget_action"
const val EXTRA_EVENT_ID = "widget_event_id"
const val EXTRA_OCCURRENCE_TS = "widget_occurrence_ts"

const val ACTION_SHOW_EVENT = "show_event"
const val ACTION_CREATE_EVENT = "create_event"
const val ACTION_GO_TO_TODAY = "go_to_today"
const val ACTION_OPEN_SEARCH = "open_search"
const val ACTION_GO_TO_DATE = "go_to_date"

// Extra keys for week widget
const val EXTRA_DAY_CODE = "widget_day_code"
const val EXTRA_CREATE_EVENT_START_TS = "widget_create_event_start_ts"

/** Maximum number of events to show in widget */
private const val MAX_VISIBLE_EVENTS = 5
