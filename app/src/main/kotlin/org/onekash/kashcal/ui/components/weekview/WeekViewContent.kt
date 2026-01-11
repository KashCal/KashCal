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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.util.DateTimeUtils
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
 * - Headers scroll WITH content (like monthly view) for smooth, non-jarring scrolling
 * - Debounced event loading in ViewModel
 *
 * Each page contains:
 * - Day header (Mon 6, Tue 7, etc.)
 * - All-day events (1 item + "+N more" with bottom picker)
 * - Early overflow events (before 6am) with contrast background
 * - Time grid with timed events
 * - Late overflow events (after 11pm) with contrast background
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
    showEventEmojis: Boolean = true,
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
            .distinctUntilChanged()  // Prevent duplicate emissions
            .collect { page ->
                Log.d(TAG, "Pager settled on page $page (date: ${WeekViewUtils.pageToDate(page)})")
                onPageChanged(page)
            }
    }

    // Handle programmatic navigation (from Today button, date picker)
    // Wait for any ongoing gesture to complete before animating
    LaunchedEffect(pendingNavigateToPage) {
        pendingNavigateToPage?.let { targetPage ->
            // Wait for user gesture to complete (prevents racing with user scroll)
            snapshotFlow { pagerState.isScrollInProgress }
                .filter { !it }
                .first()

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

    // Group events by date (LocalDate key)
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

    // Main content
    when {
        isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Box(
                modifier = modifier
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
            // Time grid with unified day columns (headers inside pager)
            UnifiedTimeGrid(
                pagerState = pagerState,
                normalEventsByDate = normalEventsByDate,
                allDayEventsByDate = allDayEventsByDate,
                earlyEventsByDate = earlyEventsByDate,
                lateEventsByDate = lateEventsByDate,
                calendarColors = calendarColors,
                scrollState = scrollState,
                showEventEmojis = showEventEmojis,
                onEventClick = onEventClick,
                onOverflowClick = { events -> overflowEvents = events },
                onLongPress = onLongPress,
                onScrollPositionChange = onScrollPositionChange,
                modifier = modifier.fillMaxSize()
            )
        }
    }

    // Overflow sheet
    overflowEvents?.let { events ->
        OverlapListSheet(
            events = events,
            calendarColors = calendarColors,
            showEventEmojis = showEventEmojis,
            onDismiss = { overflowEvents = null },
            onEventClick = onEventClick
        )
    }
}

/**
 * Unified time grid where each page contains header + all sections.
 * This creates smooth scrolling (like monthly view) because headers move with content.
 */
@Composable
private fun UnifiedTimeGrid(
    pagerState: PagerState,
    normalEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    allDayEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    earlyEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    lateEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    scrollState: ScrollState = rememberScrollState(),
    showEventEmojis: Boolean = true,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalHeight = hourHeight * WeekViewUtils.TOTAL_HOURS
    val timeColumnWidth = 48.dp
    val today = LocalDate.now()

    // Track scroll position changes - debounced to prevent per-pixel state updates
    LaunchedEffect(scrollState) {
        @OptIn(FlowPreview::class)
        snapshotFlow { scrollState.value }
            .debounce(100)  // 100ms debounce prevents recomposition storms
            .collect { position ->
                onScrollPositionChange(position)
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header row with pager (scrolls horizontally with content)
        Row(modifier = Modifier.fillMaxWidth()) {
            // Empty spacer for time column alignment
            Box(modifier = Modifier.width(timeColumnWidth))

            // Day headers - render 3 columns directly based on currentPage
            // (No HorizontalPager to avoid lag with other direct-rendered rows)
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val columnWidth = maxWidth / 3

                Row(modifier = Modifier.fillMaxWidth()) {
                    repeat(3) { offset ->
                        val date = WeekViewUtils.pageToDate(pagerState.currentPage + offset)
                        val isToday = date == today
                        val isWeekend = WeekViewUtils.isWeekend(date)

                        DayHeaderCell(
                            date = date,
                            isToday = isToday,
                            isWeekend = isWeekend,
                            modifier = Modifier.width(columnWidth)
                        )
                    }
                }
            }
        }

        // All-day events row with pager
        AllDayEventsPagerRow(
            pagerState = pagerState,
            allDayEventsByDate = allDayEventsByDate,
            calendarColors = calendarColors,
            timeColumnWidth = timeColumnWidth,
            showEventEmojis = showEventEmojis,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )

        // Early events row (before 6am) with pager
        OverflowEventsPagerRow(
            pagerState = pagerState,
            overflowEventsByDate = earlyEventsByDate,
            calendarColors = calendarColors,
            timeColumnWidth = timeColumnWidth,
            showEventEmojis = showEventEmojis,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )

        // Main time grid area
        Row(modifier = Modifier.weight(1f)) {
            // Time labels column (fixed)
            Column(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .verticalScroll(scrollState)
                    .height(totalHeight)
            ) {
                for (hour in WeekViewUtils.START_HOUR until WeekViewUtils.END_HOUR) {
                    TimeLabel(hour = hour, height = hourHeight)
                }
            }

            // Day columns pager (infinite)
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val columnWidth = maxWidth / 3

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

                        // Infinite pager - each page is one day
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            pageSize = PageSize.Fixed(columnWidth),
                            beyondViewportPageCount = 3,
                            key = { page -> "grid_$page" }
                        ) { page ->
                            val date = WeekViewUtils.pageToDate(page)
                            val dayEvents = normalEventsByDate[date] ?: emptyList()

                            DayColumn(
                                date = date,
                                events = dayEvents,
                                calendarColors = calendarColors,
                                hourHeight = hourHeight,
                                isToday = date == today,
                                showEventEmojis = showEventEmojis,
                                onEventClick = onEventClick,
                                onOverflowClick = onOverflowClick,
                                onLongPress = onLongPress,
                                modifier = Modifier.width(columnWidth)
                            )
                        }

                        // Current time indicator
                        CurrentTimeIndicator(
                            hourHeight = hourHeight,
                            pagerState = pagerState,
                            columnWidth = columnWidth
                        )
                    }
                }
            }
        }

        // Late events row (after 11pm) with pager
        OverflowEventsPagerRow(
            pagerState = pagerState,
            overflowEventsByDate = lateEventsByDate,
            calendarColors = calendarColors,
            timeColumnWidth = timeColumnWidth,
            showEventEmojis = showEventEmojis,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )
    }
}

/**
 * Single day header cell (Mon 6, Tue 7, etc.)
 */
@Composable
private fun DayHeaderCell(
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
 * All-day events row with pager synchronization.
 * Shows 1 item + "+N more" for compact display.
 */
@Composable
private fun AllDayEventsPagerRow(
    pagerState: PagerState,
    allDayEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    timeColumnWidth: Dp,
    showEventEmojis: Boolean = true,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if any visible day has all-day events
    val hasAnyEvents = (0 until 3).any { offset ->
        val date = WeekViewUtils.pageToDate(pagerState.currentPage + offset)
        allDayEventsByDate[date]?.isNotEmpty() == true
    }

    if (!hasAnyEvents) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp)
    ) {
        // Label column
        Box(
            modifier = Modifier.width(timeColumnWidth),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "All day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // All-day events - render 3 columns directly based on currentPage
        // (No HorizontalPager to avoid gesture conflicts with main time grid)
        Row(modifier = Modifier.weight(1f)) {
            repeat(3) { offset ->
                val date = WeekViewUtils.pageToDate(pagerState.currentPage + offset)
                val dayEvents = allDayEventsByDate[date] ?: emptyList()

                CompactEventCell(
                    events = dayEvents,
                    calendarColors = calendarColors,
                    showTime = false,
                    showEventEmojis = showEventEmojis,
                    onEventClick = onEventClick,
                    onOverflowClick = onOverflowClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                )
            }
        }
    }
}

/**
 * Overflow events row (before 6am / after 11pm) with pager synchronization.
 * Shows 1 item + "+N more" with contrast background for visibility.
 */
@Composable
private fun OverflowEventsPagerRow(
    pagerState: PagerState,
    overflowEventsByDate: Map<LocalDate, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    timeColumnWidth: Dp,
    showEventEmojis: Boolean = true,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if any visible day has overflow events
    val hasAnyEvents = (0 until 3).any { offset ->
        val date = WeekViewUtils.pageToDate(pagerState.currentPage + offset)
        overflowEventsByDate[date]?.isNotEmpty() == true
    }

    if (!hasAnyEvents) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp)
    ) {
        // Moon icon column - same styling as "All day" label
        Box(
            modifier = Modifier.width(timeColumnWidth),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🌙",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Overflow events - render 3 columns directly based on currentPage
        // (No HorizontalPager to avoid gesture conflicts with main time grid)
        Row(modifier = Modifier.weight(1f)) {
            repeat(3) { offset ->
                val date = WeekViewUtils.pageToDate(pagerState.currentPage + offset)
                val dayEvents = overflowEventsByDate[date] ?: emptyList()

                CompactEventCell(
                    events = dayEvents,
                    calendarColors = calendarColors,
                    showTime = true,
                    showEventEmojis = showEventEmojis,
                    onEventClick = onEventClick,
                    onOverflowClick = onOverflowClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                )
            }
        }
    }
}

/**
 * Compact event cell showing 1 event + "+N more" badge.
 * Used for all-day and overflow rows.
 */
@Composable
private fun CompactEventCell(
    events: List<Pair<Event, Occurrence>>,
    calendarColors: Map<Long, Int>,
    showTime: Boolean,
    showEventEmojis: Boolean = true,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        // Show only 1 event
        val (firstEvent, firstOccurrence) = events.first()
        val color = calendarColors[firstEvent.calendarId] ?: DEFAULT_EVENT_COLOR

        CompactEventChip(
            event = firstEvent,
            occurrence = firstOccurrence,
            color = color,
            onClick = { onEventClick(firstEvent, firstOccurrence) },
            showTime = showTime,
            showEventEmojis = showEventEmojis
        )

        // Show "+N more" if there are more events
        if (events.size > 1) {
            Text(
                text = "+${events.size - 1} more",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onOverflowClick(events) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
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
    showEventEmojis: Boolean = true,
    modifier: Modifier = Modifier
) {
    val formattedTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)
    val displayText = if (showTime) {
        "${WeekViewUtils.formatOverflowTime(occurrence.startTs)} $formattedTitle"
    } else {
        formattedTitle
    }

    // Calculate luminance to determine text color (dark text for light backgrounds)
    val backgroundColor = Color(color)
    val luminance = (0.299f * backgroundColor.red + 0.587f * backgroundColor.green + 0.114f * backgroundColor.blue)
    val textColor = if (luminance > 0.5f) Color.Black else Color.White

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Time label for the time grid.
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

private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12a"
        hour < 12 -> "${hour}a"
        hour == 12 -> "12p"
        else -> "${hour - 12}p"
    }
}

/**
 * Grid lines for the time grid.
 */
@Composable
private fun GridLines(
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
 * Current time indicator positioned on today's column.
 */
@Composable
private fun CurrentTimeIndicator(
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

// ==================== Helper Functions ====================

/**
 * Group events by LocalDate.
 *
 * Uses DateTimeUtils.eventTsToLocalDate() for correct timezone handling:
 * - All-day events: UTC to preserve calendar date (fixes birthday showing day before)
 * - Timed events: Local timezone for user's perspective
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
        // Use DateTimeUtils for correct all-day event timezone handling
        val date = DateTimeUtils.eventTsToLocalDate(occurrence.startTs, event.isAllDay)
        result.getOrPut(date) { mutableListOf() }.add(event to occurrence)
    }

    return result
}

/**
 * Separates events by time slot into early (before 6am), normal (6am-11pm), and late (after 11pm).
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

private const val DEFAULT_EVENT_COLOR = 0xFF6200EE.toInt()
