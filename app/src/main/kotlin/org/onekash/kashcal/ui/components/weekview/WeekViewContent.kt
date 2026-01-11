package org.onekash.kashcal.ui.components.weekview

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "WeekViewContent"

/**
 * Main container for the week view with infinite day pager.
 *
 * Architecture:
 * - Uses pseudo-infinite pager (Int.MAX_VALUE pages) where each page = 1 day
 * - CENTER_DAY_PAGE corresponds to today
 * - Smooth scrolling across any date range without boundary issues
 * - Debounced event loading in ViewModel
 *
 * Combines:
 * - DayHeadersRow (Mon, Tue, Wed headers with dates)
 * - AllDayEventsRow (expandable all-day events)
 * - TimeGrid (scrollable time grid with events)
 * - OverflowEventsRow (events before 6am / after 11pm)
 * - OverlapListSheet (shown when overflow badge tapped)
 *
 * @param timedOccurrences List of timed occurrences for visible range + buffer
 * @param timedEvents Events corresponding to timed occurrences
 * @param allDayOccurrences List of all-day occurrences for visible range
 * @param allDayEvents Events corresponding to all-day occurrences
 * @param calendars List of calendars (for colors)
 * @param isLoading True while loading events
 * @param error Error message if load failed
 * @param scrollPosition Saved scroll position for state preservation
 * @param onDatePickerRequest Called when date range tapped
 * @param onEventClick Called when an event is tapped
 * @param onLongPress Called when user long-presses empty space (date, hour, minute)
 * @param onScrollPositionChange Called when scroll position changes
 * @param onPageChanged Called when pager page changes (for debounced loading)
 * @param pendingNavigateToPage Target page for programmatic navigation
 * @param onNavigationConsumed Called after navigation is handled
 * @param modifier Modifier for the container
 */
@Composable
fun WeekViewContent(
    timedOccurrences: ImmutableList<Occurrence>,
    timedEvents: ImmutableList<Event>,
    allDayOccurrences: ImmutableList<Occurrence>,
    allDayEvents: ImmutableList<Event>,
    calendars: ImmutableList<Calendar>,
    isLoading: Boolean,
    error: String?,
    scrollPosition: Int,
    onDatePickerRequest: () -> Unit,
    onEventClick: (Event, Occurrence) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit,
    onPageChanged: (Int) -> Unit = {},
    pendingNavigateToPage: Int? = null,
    onNavigationConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Infinite day pager: CENTER_DAY_PAGE = today
    val pagerState = rememberPagerState(
        initialPage = WeekViewUtils.CENTER_DAY_PAGE,
        pageCount = { WeekViewUtils.TOTAL_DAY_PAGES }
    )

    // Track pager position changes - use settledPage for debounced loading
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                Log.d(TAG, "Pager settled on page $page (date: ${WeekViewUtils.pageToDate(page)})")
                onPageChanged(page)
            }
    }

    // Handle programmatic navigation (from Today button, date picker)
    LaunchedEffect(pendingNavigateToPage) {
        pendingNavigateToPage?.let { targetPage ->
            Log.d(TAG, "Navigating to page $targetPage (date: ${WeekViewUtils.pageToDate(targetPage)})")
            pagerState.animateScrollToPage(targetPage)
            onNavigationConsumed()
        }
    }

    // Scroll state for time grid
    val scrollState = rememberScrollState(initial = scrollPosition)

    // Calendar colors map
    val calendarColors = remember(calendars) {
        calendars.associate { it.id to it.color }
    }

    // Group events by date (LocalDate key instead of day index)
    val timedEventsByDate = remember(timedOccurrences, timedEvents) {
        groupEventsByDate(timedOccurrences.toList(), timedEvents.toList())
    }

    val allDayEventsByDate = remember(allDayOccurrences, allDayEvents) {
        groupEventsByDate(allDayOccurrences.toList(), allDayEvents.toList())
    }

    // Separate timed events into early (before 6am), normal (6am-11pm), and late (after 11pm)
    val (earlyEventsByDate, normalEventsByDate, lateEventsByDate) = remember(timedEventsByDate) {
        separateEventsByTimeSlotByDate(timedEventsByDate)
    }

    // State for overflow sheet
    var overflowEvents by remember { mutableStateOf<List<Pair<Event, Occurrence>>?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Day headers row (Mon, Tue, Wed... with dates)
        // Note: Month/Year row with Today button is now in HomeScreen's AgendaMonthYearRow
        // Uses page-based dates (no weekStartMs)
        InfiniteDayHeadersRow(
            currentPage = pagerState.currentPage,
            visibleDays = WeekViewUtils.VISIBLE_DAYS
        )

        // All-day events row (static for reliable sync)
        InfiniteAllDayEventsRow(
            currentPage = pagerState.currentPage,
            allDayEvents = allDayEventsByDate,
            calendarColors = calendarColors,
            onEventClick = onEventClick,
            onOverflowBadgeClick = { events -> overflowEvents = events },
            visibleDays = WeekViewUtils.VISIBLE_DAYS
        )

        // Early events row (before 6am) - shown after all-day row
        InfiniteOverflowEventsRow(
            label = "Before 6am",
            currentPage = pagerState.currentPage,
            overflowEvents = earlyEventsByDate,
            calendarColors = calendarColors,
            onEventClick = onEventClick,
            onOverflowBadgeClick = { events -> overflowEvents = events },
            visibleDays = WeekViewUtils.VISIBLE_DAYS
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
                    // Time grid with events (only normal events - 6am to 11pm)
                    InfiniteTimeGrid(
                        pagerState = pagerState,
                        eventsByDate = normalEventsByDate,
                        calendarColors = calendarColors,
                        scrollState = scrollState,
                        onEventClick = onEventClick,
                        onOverflowClick = { events -> overflowEvents = events },
                        onLongPress = onLongPress,
                        onScrollPositionChange = onScrollPositionChange,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Late events row (after 11pm) - shown after time grid
        InfiniteOverflowEventsRow(
            label = "After 11pm",
            currentPage = pagerState.currentPage,
            overflowEvents = lateEventsByDate,
            calendarColors = calendarColors,
            onEventClick = onEventClick,
            onOverflowBadgeClick = { events -> overflowEvents = events },
            visibleDays = WeekViewUtils.VISIBLE_DAYS
        )
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

/**
 * Group events by LocalDate (for infinite pager).
 *
 * @param occurrences All occurrences
 * @param events Events corresponding to occurrences (by exceptionEventId ?: eventId)
 * @return Map of LocalDate to list of (Event, Occurrence) pairs
 */
private fun groupEventsByDate(
    occurrences: List<Occurrence>,
    events: List<Event>
): Map<LocalDate, List<Pair<Event, Occurrence>>> {
    val eventMap = events.associateBy { it.id }
    val result = mutableMapOf<LocalDate, MutableList<Pair<Event, Occurrence>>>()

    for (occurrence in occurrences) {
        val eventId = occurrence.exceptionEventId ?: occurrence.eventId
        val event = eventMap[eventId] ?: continue
        val date = WeekViewUtils.epochMsToDate(occurrence.startTs)
        result.getOrPut(date) { mutableListOf() }.add(event to occurrence)
    }

    return result
}

/**
 * Separates events by time slot into early (before 6am), normal (6am-11pm), and late (after 11pm).
 * Uses LocalDate keys for infinite pager compatibility.
 *
 * @param eventsByDate Map of LocalDate to list of events
 * @return Triple of (earlyEventsByDate, normalEventsByDate, lateEventsByDate)
 */
private fun separateEventsByTimeSlotByDate(
    eventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>
): Triple<Map<LocalDate, List<Pair<Event, Occurrence>>>, Map<LocalDate, List<Pair<Event, Occurrence>>>, Map<LocalDate, List<Pair<Event, Occurrence>>>> {
    val earlyEvents = mutableMapOf<LocalDate, MutableList<Pair<Event, Occurrence>>>()
    val normalEvents = mutableMapOf<LocalDate, MutableList<Pair<Event, Occurrence>>>()
    val lateEvents = mutableMapOf<LocalDate, MutableList<Pair<Event, Occurrence>>>()

    for ((date, events) in eventsByDate) {
        for (eventPair in events) {
            val (_, occurrence) = eventPair
            when (WeekViewUtils.classifyTimeSlot(occurrence)) {
                WeekViewUtils.TimeSlot.EARLY -> {
                    earlyEvents.getOrPut(date) { mutableListOf() }.add(eventPair)
                }
                WeekViewUtils.TimeSlot.NORMAL -> {
                    normalEvents.getOrPut(date) { mutableListOf() }.add(eventPair)
                }
                WeekViewUtils.TimeSlot.LATE -> {
                    lateEvents.getOrPut(date) { mutableListOf() }.add(eventPair)
                }
            }
        }
    }

    return Triple(earlyEvents, normalEvents, lateEvents)
}

// ==================== Infinite Day Pager Components ====================

/**
 * Day headers row for infinite pager (uses page-based dates).
 */
@Composable
fun InfiniteDayHeadersRow(
    currentPage: Int,
    visibleDays: Int = 3,
    timeColumnWidth: androidx.compose.ui.unit.Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()

    Row(modifier = modifier.fillMaxWidth()) {
        // Spacer for time column alignment
        Box(modifier = Modifier.width(timeColumnWidth))

        // Day headers for visible days
        repeat(visibleDays) { offset ->
            val page = currentPage + offset
            val date = WeekViewUtils.pageToDate(page)
            val isToday = date == today
            val isWeekend = WeekViewUtils.isWeekend(date)

            InfiniteDayHeader(
                date = date,
                isToday = isToday,
                isWeekend = isWeekend,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Single day header for infinite pager.
 */
@Composable
private fun InfiniteDayHeader(
    date: LocalDate,
    isToday: Boolean,
    isWeekend: Boolean,
    modifier: Modifier = Modifier
) {
    val dayName = remember(date) {
        date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
    }
    val dayNumber = date.dayOfMonth.toString()

    val textColor = when {
        isWeekend -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayName,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = TextAlign.Center
        )

        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .then(
                    if (isToday) {
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = dayNumber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else textColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * All-day events row for infinite pager.
 *
 * Shows up to 2 all-day events per day column, plus a clickable
 * "+N more" badge when more events exist. Tapping the badge opens a bottom
 * sheet with all all-day events for that day.
 *
 * @param currentPage Current pager page (leftmost visible day)
 * @param allDayEvents Map of LocalDate to all-day events for that day
 * @param calendarColors Map of calendar ID to color
 * @param onEventClick Called when an individual event is tapped
 * @param onOverflowBadgeClick Called when "+N more" badge is tapped with all events for that day
 * @param visibleDays Number of visible day columns (default 3)
 * @param timeColumnWidth Width of the label column
 * @param modifier Modifier for the row
 */
@Composable
fun InfiniteAllDayEventsRow(
    currentPage: Int,
    allDayEvents: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowBadgeClick: (List<Pair<Event, Occurrence>>) -> Unit = {},
    visibleDays: Int = 3,
    timeColumnWidth: androidx.compose.ui.unit.Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    // Check if there are any all-day events in visible range
    val hasAnyAllDayEvents = (0 until visibleDays).any { offset ->
        val date = WeekViewUtils.pageToDate(currentPage + offset)
        allDayEvents[date]?.isNotEmpty() == true
    }

    if (!hasAnyAllDayEvents) return

    Row(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(timeColumnWidth))

        repeat(visibleDays) { offset ->
            val date = WeekViewUtils.pageToDate(currentPage + offset)
            val dayEvents = allDayEvents[date] ?: emptyList()

            Box(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                Column {
                    dayEvents.take(2).forEach { (event, occurrence) ->
                        val color = calendarColors[event.calendarId] ?: 0xFF6200EE.toInt()
                        CompactEventChip(
                            event = event,
                            occurrence = occurrence,
                            color = color,
                            onClick = { onEventClick(event, occurrence) }
                        )
                    }
                    if (dayEvents.size > 2) {
                        Text(
                            text = "+${dayEvents.size - 2} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onOverflowBadgeClick(dayEvents) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Overflow events row (early/late) for infinite pager.
 *
 * Shows up to 2 events per day column with time prefix, plus a clickable
 * "+N more" badge when more events exist. Tapping the badge opens a bottom
 * sheet with all events for that time slot.
 *
 * @param label Row label (e.g., "Before 6am", "After 11pm")
 * @param currentPage Current pager page (leftmost visible day)
 * @param overflowEvents Map of LocalDate to events for that day
 * @param calendarColors Map of calendar ID to color
 * @param onEventClick Called when an individual event is tapped
 * @param onOverflowBadgeClick Called when "+N more" badge is tapped with all events for that day
 * @param visibleDays Number of visible day columns (default 3)
 * @param timeColumnWidth Width of the label column
 * @param modifier Modifier for the row
 */
@Composable
fun InfiniteOverflowEventsRow(
    label: String,
    currentPage: Int,
    overflowEvents: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowBadgeClick: (List<Pair<Event, Occurrence>>) -> Unit = {},
    visibleDays: Int = 3,
    timeColumnWidth: androidx.compose.ui.unit.Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    // Check if there are any overflow events in visible range
    val hasAnyOverflowEvents = (0 until visibleDays).any { offset ->
        val date = WeekViewUtils.pageToDate(currentPage + offset)
        overflowEvents[date]?.isNotEmpty() == true
    }

    if (!hasAnyOverflowEvents) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(vertical = 4.dp)
    ) {
        // Label column
        Box(
            modifier = Modifier.width(timeColumnWidth),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Event columns
        repeat(visibleDays) { offset ->
            val date = WeekViewUtils.pageToDate(currentPage + offset)
            val dayEvents = overflowEvents[date] ?: emptyList()

            Box(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                Column {
                    dayEvents.take(2).forEach { (event, occurrence) ->
                        val color = calendarColors[event.calendarId] ?: 0xFF6200EE.toInt()
                        CompactEventChip(
                            event = event,
                            occurrence = occurrence,
                            color = color,
                            onClick = { onEventClick(event, occurrence) },
                            showTime = true
                        )
                    }
                    if (dayEvents.size > 2) {
                        Text(
                            text = "+${dayEvents.size - 2} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onOverflowBadgeClick(dayEvents) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact event chip for all-day and overflow rows.
 */
@Composable
private fun CompactEventChip(
    event: Event,
    occurrence: Occurrence,
    color: Int,
    onClick: () -> Unit,
    showTime: Boolean = false,
    modifier: Modifier = Modifier
) {
    val displayText = if (showTime) {
        "${WeekViewUtils.formatOverflowTime(occurrence.startTs)} ${event.title}"
    } else {
        event.title
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(color))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== Infinite Time Grid ====================

/**
 * Time grid with infinite day pager.
 * Each page represents one absolute day (pageToDate(page)).
 * Uses HorizontalPager with PageSize.Fixed for smooth 3-day scrolling.
 */
@Composable
fun InfiniteTimeGrid(
    pagerState: PagerState,
    eventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    scrollState: ScrollState = rememberScrollState(),
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalHeight = hourHeight * WeekViewUtils.TOTAL_HOURS
    val timeColumnWidth = 48.dp
    val today = LocalDate.now()

    // Track scroll position changes
    LaunchedEffect(scrollState.value) {
        onScrollPositionChange(scrollState.value)
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
                InfiniteTimeLabel(hour = hour, height = hourHeight)
            }
        }

        // Day columns pager (infinite)
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val columnWidth = maxWidth / 3  // 3-day view

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
                    InfiniteGridLines(
                        hourHeight = hourHeight,
                        totalHours = WeekViewUtils.TOTAL_HOURS
                    )

                    // Infinite pager - each page is one day
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSize = PageSize.Fixed(columnWidth),
                        beyondViewportPageCount = 3,  // Preload 3 pages each direction
                        key = { page -> page }  // Stable key for recomposition
                    ) { page ->
                        val date = WeekViewUtils.pageToDate(page)
                        val dayEvents = eventsByDate[date] ?: emptyList()

                        DayColumn(
                            date = date,
                            events = dayEvents,
                            calendarColors = calendarColors,
                            hourHeight = hourHeight,
                            isToday = date == today,
                            onEventClick = onEventClick,
                            onOverflowClick = onOverflowClick,
                            onLongPress = onLongPress,
                            modifier = Modifier.width(columnWidth)
                        )
                    }

                    // Current time indicator
                    InfiniteCurrentTimeIndicator(
                        hourHeight = hourHeight,
                        pagerState = pagerState,
                        columnWidth = columnWidth
                    )
                }
            }
        }
    }
}

/**
 * Time label for infinite time grid.
 */
@Composable
private fun InfiniteTimeLabel(
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

private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
    }
}

/**
 * Grid lines for infinite time grid.
 */
@Composable
private fun InfiniteGridLines(
    hourHeight: Dp,
    totalHours: Int,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxSize()) {
        repeat(totalHours) {
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
 * Current time indicator for infinite time grid.
 * Positioned on today's column if visible.
 */
@Composable
private fun InfiniteCurrentTimeIndicator(
    hourHeight: Dp,
    pagerState: PagerState,
    columnWidth: Dp,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val todayPage = WeekViewUtils.dateToPage(today)

    // Update current time every minute
    var currentMinutes by remember { mutableStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            val secondsUntilNextMinute = 60 - now.second
            delay(secondsUntilNextMinute * 1000L)
            val newTime = LocalTime.now()
            currentMinutes = newTime.hour * 60 + newTime.minute
        }
    }

    // Only show if current time is in visible range (6am - 11pm)
    val startMinutes = WeekViewUtils.START_HOUR * 60
    val endMinutes = WeekViewUtils.END_HOUR * 60
    if (currentMinutes < startMinutes || currentMinutes >= endMinutes) return

    // Calculate today's visible position (0, 1, or 2 for the 3 visible columns)
    val currentPage = pagerState.currentPage
    val todayVisibleOffset = todayPage - currentPage

    // Only show if today is in visible range (0, 1, or 2)
    if (todayVisibleOffset !in 0..2) return

    val minutesFromStart = currentMinutes - startMinutes
    val density = LocalDensity.current
    val yOffset = with(density) { (minutesFromStart.toFloat() / 60f * hourHeight.toPx()).toDp() }
    val xOffset = columnWidth * todayVisibleOffset
    val indicatorColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier
            .offset(x = xOffset, y = yOffset)
            .width(columnWidth)
            .height(2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = indicatorColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f
            )
            drawCircle(
                color = indicatorColor,
                radius = 4f,
                center = Offset(4f, size.height / 2)
            )
        }
    }
}
