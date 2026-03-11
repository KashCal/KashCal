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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.util.DayPagerUtils
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
    timedEvents: ImmutableList<DisplayEvent>,
    allDayEvents: ImmutableList<DisplayEvent>,
    isLoading: Boolean,
    error: String?,
    scrollPosition: Int,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    weekMode: Boolean = false,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    onDatePickerRequest: () -> Unit,
    onEventClick: (DisplayEvent) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit,
    onPageChanged: (Int) -> Unit = {},
    pendingNavigateToPage: Int? = null,
    onNavigationConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Compute is24Hour flag for clamp indicators
    val is24Hour = timePattern.startsWith("H")

    // Grid time range: 24h for week mode, 6am-11pm for 3-day mode
    val startHour = if (weekMode) WeekViewUtils.FULL_DAY_START_HOUR else WeekViewUtils.START_HOUR
    val endHour = if (weekMode) WeekViewUtils.FULL_DAY_END_HOUR else WeekViewUtils.END_HOUR
    val totalHours = endHour - startHour
    val visibleDays = if (weekMode) 7 else 3

    // Pager: day-based (THREE_DAYS) or week-based (WEEK)
    val pagerState = if (weekMode) {
        rememberPagerState(
            initialPage = WeekViewUtils.CENTER_WEEK_PAGE,
            pageCount = { WeekViewUtils.TOTAL_WEEK_PAGES }
        )
    } else {
        rememberPagerState(
            initialPage = WeekViewUtils.CENTER_DAY_PAGE,
            pageCount = { WeekViewUtils.TOTAL_DAY_PAGES }
        )
    }

    // Track pager position changes - use settledPage for debounced loading
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()  // Prevent duplicate emissions
            .collect { page ->
                Log.d(TAG, "Pager settled on page $page")
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

            Log.d(TAG, "Navigating to page $targetPage")
            pagerState.animateScrollToPage(targetPage)
            onNavigationConsumed()
        }
    }

    // Scroll state for time grid
    val scrollState = rememberScrollState(initial = scrollPosition)

    // Group events by date (LocalDate key)
    val timedEventsByDate = remember(timedEvents) {
        groupEventsByDate(timedEvents.toList())
    }

    val allDayEventsByDate = remember(allDayEvents) {
        groupEventsByDate(allDayEvents.toList())
    }

    // Separate timed events into early/normal/late — only needed for 3-day mode
    // In week mode (24h grid), all timed events are "normal" (no clamping)
    val earlyEventsByDate: Map<LocalDate, List<DisplayEvent>>
    val normalEventsByDate: Map<LocalDate, List<DisplayEvent>>
    val lateEventsByDate: Map<LocalDate, List<DisplayEvent>>

    if (weekMode) {
        earlyEventsByDate = emptyMap()
        normalEventsByDate = timedEventsByDate
        lateEventsByDate = emptyMap()
    } else {
        val separated = remember(timedEventsByDate) {
            separateEventsByTimeSlotByDate(timedEventsByDate)
        }
        earlyEventsByDate = separated.first
        normalEventsByDate = separated.second
        lateEventsByDate = separated.third
    }

    // State for overflow sheet
    var overflowEvents by remember { mutableStateOf<List<DisplayEvent>?>(null) }

    // Main content — always render the grid immediately so the structure
    // (time labels, grid lines, headers) appears without a spinner flash.
    // Events populate when the Flow emits. Empty columns are fine during load.
    when {
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
                startHour = startHour,
                endHour = endHour,
                totalHours = totalHours,
                visibleDays = visibleDays,
                weekMode = weekMode,
                firstDayOfWeek = firstDayOfWeek,
                scrollState = scrollState,
                showEventEmojis = showEventEmojis,
                timePattern = timePattern,
                is24Hour = is24Hour,
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
            showEventEmojis = showEventEmojis,
            timePattern = timePattern,
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
    normalEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    allDayEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    earlyEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    lateEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    startHour: Int = WeekViewUtils.START_HOUR,
    endHour: Int = WeekViewUtils.END_HOUR,
    totalHours: Int = WeekViewUtils.TOTAL_HOURS,
    visibleDays: Int = 3,
    weekMode: Boolean = false,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    scrollState: ScrollState = rememberScrollState(),
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    is24Hour: Boolean = false,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalHeight = hourHeight * totalHours
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

    // Derive visible dates once — shared by headers, all-day, overflow, and time indicator.
    val visibleDates by remember(weekMode, firstDayOfWeek) {
        derivedStateOf {
            if (weekMode) {
                val weekStart = WeekViewUtils.weekPageToStartDate(pagerState.currentPage, firstDayOfWeek)
                List(7) { offset -> weekStart.plusDays(offset.toLong()) }
            } else {
                val page = pagerState.currentPage
                List(3) { offset -> WeekViewUtils.pageToDate(page + offset) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header row
        Row(modifier = Modifier.fillMaxWidth()) {
            // Empty spacer for time column alignment
            Box(modifier = Modifier.width(timeColumnWidth))

            // Day headers - render columns directly from derived visibleDates
            Row(modifier = Modifier.weight(1f)) {
                visibleDates.forEach { date ->
                    DayHeaderCell(
                        date = date,
                        isToday = date == today,
                        isWeekend = WeekViewUtils.isWeekend(date),
                        compact = weekMode,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // All-day events row
        AllDayEventsPagerRow(
            visibleDates = visibleDates,
            allDayEventsByDate = allDayEventsByDate,
            timeColumnWidth = timeColumnWidth,
            showEventEmojis = showEventEmojis,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )

        // Early events row (before 6am) — only in 3-day mode
        if (!weekMode) {
            OverflowEventsPagerRow(
                visibleDates = visibleDates,
                overflowEventsByDate = earlyEventsByDate,
                timeColumnWidth = timeColumnWidth,
                showEventEmojis = showEventEmojis,
                timePattern = timePattern,
                onEventClick = onEventClick,
                onOverflowClick = onOverflowClick
            )
        }

        // Main time grid area
        Row(modifier = Modifier.weight(1f)) {
            // Time labels column (fixed)
            Column(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .verticalScroll(scrollState)
                    .height(totalHeight)
            ) {
                for (hour in startHour until endHour) {
                    TimeLabel(hour = hour, height = hourHeight, is24Hour = is24Hour)
                }
            }

            // Day columns area
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val columnWidth = this.maxWidth / visibleDays

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
                            totalHours = totalHours
                        )

                        if (weekMode) {
                            // Week mode: 1 page = 1 week (Row of 7 DayColumns)
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondViewportPageCount = 1,
                                key = { page -> "week_$page" }
                            ) { page ->
                                val weekStart = WeekViewUtils.weekPageToStartDate(page, firstDayOfWeek)

                                Row(modifier = Modifier.fillMaxSize()) {
                                    for (dayOffset in 0 until 7) {
                                        val date = weekStart.plusDays(dayOffset.toLong())
                                        val dayEvents = normalEventsByDate[date] ?: emptyList()

                                        DayColumn(
                                            date = date,
                                            events = dayEvents,
                                            hourHeight = hourHeight,
                                            isToday = date == today,
                                            showEventEmojis = showEventEmojis,
                                            timePattern = timePattern,
                                            is24Hour = is24Hour,
                                            startHour = startHour,
                                            onEventClick = onEventClick,
                                            onOverflowClick = onOverflowClick,
                                            onLongPress = onLongPress,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // Current time indicator (week mode)
                            val weekStart = remember {
                                derivedStateOf {
                                    WeekViewUtils.weekPageToStartDate(pagerState.currentPage, firstDayOfWeek)
                                }
                            }
                            CurrentTimeIndicator(
                                hourHeight = hourHeight,
                                visibleDays = 7,
                                startHour = startHour,
                                todayOffset = {
                                    val ws = weekStart.value
                                    val todayPage = WeekViewUtils.dateToPage(today)
                                    val wsPage = WeekViewUtils.dateToPage(ws)
                                    todayPage - wsPage
                                },
                                columnWidth = columnWidth
                            )
                        } else {
                            // 3-day mode: 1 page = 1 day, PageSize.Fixed
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
                                    hourHeight = hourHeight,
                                    isToday = date == today,
                                    showEventEmojis = showEventEmojis,
                                    timePattern = timePattern,
                                    is24Hour = is24Hour,
                                    startHour = startHour,
                                    onEventClick = onEventClick,
                                    onOverflowClick = onOverflowClick,
                                    onLongPress = onLongPress,
                                    modifier = Modifier.width(columnWidth)
                                )
                            }

                            // Current time indicator (3-day mode)
                            CurrentTimeIndicator(
                                hourHeight = hourHeight,
                                visibleDays = 3,
                                startHour = startHour,
                                todayOffset = {
                                    WeekViewUtils.dateToPage(today) - pagerState.currentPage
                                },
                                columnWidth = columnWidth
                            )
                        }
                    }
                }
            }
        }

        // Late events row (after 11pm) — only in 3-day mode
        if (!weekMode) {
            OverflowEventsPagerRow(
                visibleDates = visibleDates,
                overflowEventsByDate = lateEventsByDate,
                timeColumnWidth = timeColumnWidth,
                showEventEmojis = showEventEmojis,
                timePattern = timePattern,
                onEventClick = onEventClick,
                onOverflowClick = onOverflowClick
            )
        }
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
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dayName = remember(date, compact) {
        if (compact) {
            // Single letter: M, T, W, T, F, S, S
            date.format(DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault()))
        } else {
            date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
        }
    }
    val dayNumber = date.dayOfMonth.toString()

    val textColor = when {
        isWeekend -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    if (compact) {
        // Compact vertical layout for 7-day view: single letter + number stacked
        Column(
            modifier = modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = dayName,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .then(
                        if (isToday) {
                            Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        } else {
                            Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayNumber,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // Standard horizontal layout for 3-day view: "Wed 11"
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
}

/**
 * All-day events row with pager synchronization.
 * Shows 1 item + "+N more" for compact display.
 */
@Composable
private fun AllDayEventsPagerRow(
    visibleDates: List<LocalDate>,
    allDayEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    timeColumnWidth: Dp,
    showEventEmojis: Boolean = true,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if any visible day has all-day events
    val hasAnyEvents = visibleDates.any { date ->
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

        // All-day events - render 3 columns from derived visibleDates
        // (No HorizontalPager to avoid gesture conflicts with main time grid)
        Row(modifier = Modifier.weight(1f)) {
            visibleDates.forEach { date ->
                val dayEvents = allDayEventsByDate[date] ?: emptyList()

                CompactEventCell(
                    events = dayEvents,
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
    visibleDates: List<LocalDate>,
    overflowEventsByDate: Map<LocalDate, List<DisplayEvent>>,
    timeColumnWidth: Dp,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if any visible day has overflow events
    val hasAnyEvents = visibleDates.any { date ->
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

        // Overflow events - render 3 columns from derived visibleDates
        // (No HorizontalPager to avoid gesture conflicts with main time grid)
        Row(modifier = Modifier.weight(1f)) {
            visibleDates.forEach { date ->
                val dayEvents = overflowEventsByDate[date] ?: emptyList()

                CompactEventCell(
                    events = dayEvents,
                    showTime = true,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
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
    events: List<DisplayEvent>,
    showTime: Boolean,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        // Show only 1 event
        val firstEvent = events.first()

        CompactEventChip(
            displayEvent = firstEvent,
            onClick = { onEventClick(firstEvent) },
            showTime = showTime,
            showEventEmojis = showEventEmojis,
            timePattern = timePattern
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
    displayEvent: DisplayEvent,
    onClick: () -> Unit,
    showTime: Boolean = false,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    modifier: Modifier = Modifier
) {
    val color = displayEvent.calendarColor
    val formattedTitle = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
    val displayText = if (showTime) {
        "${WeekViewUtils.formatOverflowTime(displayEvent.startTs, timePattern)} $formattedTitle"
    } else {
        formattedTitle
    }

    // Cached color calculation (avoids recomputing luminance on every recomposition)
    val (backgroundColor, textColor) = remember(color) {
        val bg = Color(color)
        val luminance = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue)
        bg to if (luminance > 0.5f) Color.Black else Color.White
    }

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
    is24Hour: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth(),
        contentAlignment = Alignment.TopEnd
    ) {
        Text(
            text = WeekViewUtils.formatHourLabel(hour, is24Hour),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp, top = 0.dp)
        )
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
 *
 * @param hourHeight Height of one hour in the grid
 * @param visibleDays Number of visible day columns (3 or 7)
 * @param startHour First hour of the grid (6 for 3-day, 0 for week)
 * @param todayOffset Lambda returning today's column offset (0-based) from current page
 * @param columnWidth Width of one day column
 */
@Composable
private fun CurrentTimeIndicator(
    hourHeight: Dp,
    visibleDays: Int = 3,
    startHour: Int = WeekViewUtils.START_HOUR,
    todayOffset: () -> Int,
    columnWidth: Dp,
    modifier: Modifier = Modifier
) {
    val endHour = if (startHour == 0) 24 else WeekViewUtils.END_HOUR

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

    // Only show if current time is in visible grid range
    val startMinutes = startHour * 60
    val endMinutes = endHour * 60
    if (currentMinutes < startMinutes || currentMinutes >= endMinutes) return

    // Calculate today's visible position
    val todayVisibleOffset = todayOffset()

    // Only show if today is in visible range
    if (todayVisibleOffset !in 0 until visibleDays) return

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
 * Uses pre-calculated startDay/endDay from DisplayEvent which are already
 * UTC-aware for all-day events.
 * Expands multi-day events to appear on all days they span.
 */
private fun groupEventsByDate(
    events: List<DisplayEvent>
): Map<LocalDate, List<DisplayEvent>> {
    val result = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()

    for (displayEvent in events) {
        // Expand multi-day events to all days they span
        var currentDay = displayEvent.startDay
        while (currentDay <= displayEvent.endDay) {
            val date = DayPagerUtils.dayCodeToLocalDate(currentDay)
            result.getOrPut(date) { mutableListOf() }.add(displayEvent)
            currentDay = Occurrence.incrementDayCode(currentDay)
        }
    }

    return result
}

/**
 * Separates events by time slot into early (before 6am), normal (6am-11pm), and late (after 11pm).
 */
private fun separateEventsByTimeSlotByDate(
    eventsByDate: Map<LocalDate, List<DisplayEvent>>
): Triple<Map<LocalDate, List<DisplayEvent>>, Map<LocalDate, List<DisplayEvent>>, Map<LocalDate, List<DisplayEvent>>> {
    val earlyEvents = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()
    val normalEvents = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()
    val lateEvents = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()

    for ((date, events) in eventsByDate) {
        for (displayEvent in events) {
            when (WeekViewUtils.classifyTimeSlot(displayEvent)) {
                WeekViewUtils.TimeSlot.EARLY -> {
                    earlyEvents.getOrPut(date) { mutableListOf() }.add(displayEvent)
                }
                WeekViewUtils.TimeSlot.NORMAL -> {
                    normalEvents.getOrPut(date) { mutableListOf() }.add(displayEvent)
                }
                WeekViewUtils.TimeSlot.LATE -> {
                    lateEvents.getOrPut(date) { mutableListOf() }.add(displayEvent)
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
