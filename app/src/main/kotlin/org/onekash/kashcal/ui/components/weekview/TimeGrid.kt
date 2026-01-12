package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Time grid with HorizontalPager for week view.
 *
 * Shows:
 * - Time labels on the left (6am - 11pm)
 * - 7 day columns (one per page, 3 visible at a time)
 * - Hour grid lines
 * - Current time indicator (red line)
 *
 * @param pagerState Pager state for controlling scroll position
 * @param weekStartMs Start of the week in milliseconds
 * @param events Map of day index (0-6) to list of events for that day
 * @param calendarColors Map of calendar ID to color
 * @param hourHeight Height of one hour in the grid
 * @param scrollState Scroll state for time grid vertical scroll
 * @param onEventClick Called when an event is tapped
 * @param onOverflowClick Called when "+N more" badge is tapped
 * @param onLongPress Called when user long-presses on empty space (date, hour, minute)
 * @param onScrollPositionChange Called when scroll position changes (for state preservation)
 * @param modifier Modifier for the container
 */
@Composable
fun TimeGrid(
    pagerState: PagerState,
    weekStartMs: Long,
    events: Map<Int, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    onLongPress: (java.time.LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalHeight = hourHeight * WeekViewUtils.TOTAL_HOURS
    val timeColumnWidth = 48.dp

    // Track scroll position changes
    LaunchedEffect(scrollState.value) {
        onScrollPositionChange(scrollState.value)
    }

    // Calculate today's index in this week
    val todayIndex by remember(weekStartMs) {
        derivedStateOf {
            val today = LocalDate.now()
            val weekStart = Instant.ofEpochMilli(weekStartMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(weekStart, today).toInt()
            if (daysDiff in 0..6) daysDiff else -1
        }
    }

    Row(modifier = modifier.fillMaxWidth()) {
        // Time labels column (fixed)
        Column(
            modifier = Modifier
                .width(timeColumnWidth)
                .verticalScroll(scrollState)
                .height(totalHeight)
        ) {
            for (hour in WeekViewUtils.START_HOUR until WeekViewUtils.END_HOUR) {
                TimeLabel(
                    hour = hour,
                    height = hourHeight
                )
            }
        }

        // Day columns pager
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            // Calculate column width for 3-day view
            val columnWidth = maxWidth / 3

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight)
                ) {
                    // Grid lines
                    GridLines(
                        hourHeight = hourHeight,
                        totalHours = WeekViewUtils.TOTAL_HOURS
                    )

                    // Pager with 7 pages (one per day), 3 visible at a time
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSize = PageSize.Fixed(columnWidth),  // CRITICAL: Show 3 days
                        beyondViewportPageCount = 2  // Keep adjacent pages ready
                    ) { dayIndex ->
                        val date = WeekViewUtils.getDateForDayIndex(weekStartMs, dayIndex)
                        val dayEvents = events[dayIndex] ?: emptyList()

                        DayColumn(
                            date = date,
                            events = dayEvents,
                            calendarColors = calendarColors,
                            hourHeight = hourHeight,
                            isToday = dayIndex == todayIndex,
                            onEventClick = onEventClick,
                            onOverflowClick = onOverflowClick,
                            onLongPress = onLongPress,
                            modifier = Modifier.width(columnWidth)
                        )
                    }

                    // Current time indicator (only if today is visible)
                    if (todayIndex >= 0) {
                        CurrentTimeIndicator(
                            hourHeight = hourHeight,
                            todayIndex = todayIndex,
                            pagerState = pagerState,
                            columnWidth = columnWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single time label (e.g., "9 AM").
 */
@Composable
private fun TimeLabel(
    hour: Int,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth(),
        contentAlignment = Alignment.TopEnd
    ) {
        Text(
            text = formatHour(hour),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp, top = 0.dp)
        )
    }
}

/**
 * Format hour for display (e.g., "9a", "12p").
 * Uses compact format to fit in narrow time column.
 */
private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
    }
}

/**
 * Grid lines for hour boundaries.
 */
@Composable
private fun GridLines(
    hourHeight: Dp,
    totalHours: Int,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxSize()) {
        repeat(totalHours) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hourHeight)
            ) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 0.5.dp,
                    color = lineColor
                )
            }
        }
    }
}

/**
 * Current time indicator (red line).
 * Only visible when today's column is in view.
 * Positioned on today's column only.
 * Updates every minute to reflect current time.
 */
@Composable
private fun CurrentTimeIndicator(
    hourHeight: Dp,
    todayIndex: Int,
    pagerState: PagerState,
    columnWidth: Dp,
    modifier: Modifier = Modifier
) {
    // Update current time every minute
    var currentMinutes by remember { mutableStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }

    LaunchedEffect(Unit) {
        while (true) {
            // Calculate delay until next minute boundary
            val now = LocalTime.now()
            val secondsUntilNextMinute = 60 - now.second
            kotlinx.coroutines.delay(secondsUntilNextMinute * 1000L)

            // Update the current minutes
            val newTime = LocalTime.now()
            currentMinutes = newTime.hour * 60 + newTime.minute
        }
    }

    // Calculate position relative to 6am start
    val startMinutes = WeekViewUtils.START_HOUR * 60
    val endMinutes = WeekViewUtils.END_HOUR * 60

    // Only show if current time is in visible range (6am - 11pm)
    if (currentMinutes < startMinutes || currentMinutes >= endMinutes) return

    val minutesFromStart = currentMinutes - startMinutes
    val density = LocalDensity.current
    val yOffset = with(density) { (minutesFromStart.toFloat() / 60f * hourHeight.toPx()).toDp() }

    // Calculate today's visible position (0, 1, or 2 for the 3 visible columns)
    val currentPage = pagerState.currentPage
    val todayVisibleOffset = todayIndex - currentPage

    // Only show if today is in visible range (0, 1, or 2)
    if (todayVisibleOffset !in 0..2) return

    val xOffset = with(density) { (columnWidth * todayVisibleOffset) }
    val indicatorColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier
            .offset(x = xOffset, y = yOffset)
            .width(columnWidth)
            .height(2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw horizontal line across today's column
            drawLine(
                color = indicatorColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f
            )
            // Draw circle at start
            drawCircle(
                color = indicatorColor,
                radius = 4f,
                center = Offset(4f, size.height / 2)
            )
        }
    }
}

/**
 * Calculates which events belong to which day.
 *
 * @param occurrences All occurrences for the week
 * @param events Events corresponding to occurrences (by exceptionEventId ?: eventId)
 * @param weekStartMs Start of the week in milliseconds
 * @return Map of day index (0-6) to list of (Event, Occurrence) pairs
 */
fun groupEventsByDay(
    occurrences: List<Occurrence>,
    events: List<Event>,
    weekStartMs: Long
): Map<Int, List<Pair<Event, Occurrence>>> {
    // Create event lookup map using exceptionEventId ?: eventId pattern
    val eventMap = events.associateBy { it.id }

    val result = mutableMapOf<Int, MutableList<Pair<Event, Occurrence>>>()

    for (occurrence in occurrences) {
        // Get the event for this occurrence (use exception if present)
        val eventId = occurrence.exceptionEventId ?: occurrence.eventId
        val event = eventMap[eventId] ?: continue

        // Calculate which day this occurrence belongs to
        val dayIndex = WeekViewUtils.getDayIndex(occurrence.startTs, weekStartMs)

        result.getOrPut(dayIndex) { mutableListOf() }.add(event to occurrence)
    }

    return result
}
