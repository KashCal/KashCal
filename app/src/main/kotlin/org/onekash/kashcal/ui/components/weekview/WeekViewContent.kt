package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Main container for the week view.
 *
 * Combines:
 * - WeekHeader (date range + arrows)
 * - DayHeadersRow (Mon, Tue, Wed headers)
 * - AllDayEventsRow (expandable all-day events)
 * - TimeGrid (scrollable time grid with events)
 * - OverlapListSheet (shown when overflow badge tapped)
 *
 * @param weekStartMs Start of the current week in milliseconds
 * @param timedOccurrences List of timed occurrences for the week
 * @param timedEvents Events corresponding to timed occurrences
 * @param allDayOccurrences List of all-day occurrences for the week
 * @param allDayEvents Events corresponding to all-day occurrences
 * @param calendars List of calendars (for colors)
 * @param isLoading True while loading events
 * @param error Error message if load failed
 * @param scrollPosition Saved scroll position for state preservation
 * @param onPreviousWeek Called when previous week arrow tapped
 * @param onNextWeek Called when next week arrow tapped
 * @param onDatePickerRequest Called when date range tapped
 * @param onEventClick Called when an event is tapped
 * @param onScrollPositionChange Called when scroll position changes
 * @param modifier Modifier for the container
 */
@Composable
fun WeekViewContent(
    weekStartMs: Long,
    timedOccurrences: ImmutableList<Occurrence>,
    timedEvents: ImmutableList<Event>,
    allDayOccurrences: ImmutableList<Occurrence>,
    allDayEvents: ImmutableList<Event>,
    calendars: ImmutableList<Calendar>,
    isLoading: Boolean,
    error: String?,
    scrollPosition: Int,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onDatePickerRequest: () -> Unit,
    onEventClick: (Event, Occurrence) -> Unit,
    onScrollPositionChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Pager state: 7 pages, one per day, starting at 0 (today)
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 7 }
    )

    // Scroll state for time grid
    val scrollState = rememberScrollState(initial = scrollPosition)

    // Calendar colors map
    val calendarColors = remember(calendars) {
        calendars.associate { it.id to it.color }
    }

    // Group events by day
    val timedEventsByDay = remember(timedOccurrences, timedEvents, weekStartMs) {
        groupEventsByDay(timedOccurrences.toList(), timedEvents.toList(), weekStartMs)
    }

    val allDayEventsByDay = remember(allDayOccurrences, allDayEvents, weekStartMs) {
        groupEventsByDay(allDayOccurrences.toList(), allDayEvents.toList(), weekStartMs)
    }

    // State for overflow sheet
    var overflowEvents by remember { mutableStateOf<List<Pair<Event, Occurrence>>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Header with navigation
        WeekHeader(
            weekStartMs = weekStartMs,
            pagerState = pagerState,
            onPreviousWeek = onPreviousWeek,
            onNextWeek = onNextWeek,
            onDatePickerRequest = onDatePickerRequest
        )

        // Day headers row (Mon, Tue, Wed...)
        DayHeadersRow(
            pagerState = pagerState,
            weekStartMs = weekStartMs
        )

        // All-day events row
        AllDayEventsRow(
            pagerState = pagerState,
            weekStartMs = weekStartMs,
            allDayEvents = allDayEventsByDay,
            calendarColors = calendarColors,
            onEventClick = onEventClick
        )

        // Main content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> {
                    // Loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    // Error state
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    // Time grid with events
                    TimeGrid(
                        pagerState = pagerState,
                        weekStartMs = weekStartMs,
                        events = timedEventsByDay,
                        calendarColors = calendarColors,
                        scrollState = scrollState,
                        onEventClick = onEventClick,
                        onOverflowClick = { events -> overflowEvents = events },
                        onScrollPositionChange = onScrollPositionChange,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Overflow sheet
    overflowEvents?.let { events ->
        OverlapListSheet(
            events = events,
            calendarColors = calendarColors,
            onDismiss = { overflowEvents = null },
            onEventClick = onEventClick
        )
    }
}

/**
 * Preview/placeholder version of week view for empty state.
 */
@Composable
fun EmptyWeekView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No events this week",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
