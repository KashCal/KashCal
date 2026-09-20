package org.onekash.kashcal.ui.components.weekview

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val TAG = "WeekViewContent"

// Test tags for headless layout verification that the all-day strip does not
// occlude the timed grid's earliest hours.
internal const val TEST_TAG_ALL_DAY_STRIP = "allDayStrip"
internal const val TEST_TAG_FIRST_TIME_LABEL = "firstTimeLabel"

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
    savedScrollMinutes: Int = -1,
    hourHeight: Float = 60f,
    onHourHeightChange: (Float) -> Unit = {},
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    visibleDays: Int = 3,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    /**
     * Localized prefix for the week-number label ("W") shown in the header corner,
     * left of the day strip. Only rendered in the full 7-day week view; blank hides it.
     */
    weekLabelPrefix: String = "",
    allDayRowsExpanded: Boolean = false,
    onAllDayRowsToggle: () -> Unit = {},
    onDatePickerRequest: () -> Unit,
    onEventClick: (DisplayEvent) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit,
    onScrollMinutesChange: (Int) -> Unit = {},
    onPageChanged: (Int) -> Unit = {},
    pendingNavigateToPage: Int? = null,
    onNavigationConsumed: () -> Unit = {},
    onReschedule: (DisplayEvent, LocalDate, Int) -> Unit = { _, _, _ -> },
    /** Tap on a day column header — drills into Day view for that date. */
    onDayHeaderClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Grid time range: full 24h for both views
    val startHour = WeekViewUtils.START_HOUR
    val endHour = WeekViewUtils.END_HOUR
    val totalHours = WeekViewUtils.TOTAL_HOURS

    // DAY view (visibleDays=1) raises the side-by-side cap to 5 since each event has the full
    // viewport width available; multi-day views stay at the default 2 to keep columns readable.
    val maxVisibleOverlap = if (visibleDays == 1) 5 else WeekViewUtils.MAX_VISIBLE_OVERLAP

    // Pager: day-based (DAY/THREE_DAYS) or week-based (WEEK)
    val pagerState = if (visibleDays == 7) {
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

    val density = LocalDensity.current.density
    val initialScrollPx = WeekViewUtils.resolveInitialScrollPx(
        savedPosition = scrollPosition,
        hourHeightDp = hourHeight,
        density = density,
        savedMinutes = savedScrollMinutes
    )
    val scrollState = rememberScrollState(initial = initialScrollPx)

    // Group events by date (LocalDate key)
    val timedEventsByDate = remember(timedEvents) {
        groupEventsByDate(timedEvents.toList())
    }

    val allDayEventsList = remember(allDayEvents) { allDayEvents.toList() }

    // All timed events go directly to the grid (full 24h range, no overflow separation)
    val normalEventsByDate = timedEventsByDate

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
                allDayEvents = allDayEventsList,
                startHour = startHour,
                endHour = endHour,
                totalHours = totalHours,
                visibleDays = visibleDays,
                firstDayOfWeek = firstDayOfWeek,
                weekLabelPrefix = weekLabelPrefix,
                allDayRowsExpanded = allDayRowsExpanded,
                onAllDayRowsToggle = onAllDayRowsToggle,
                hourHeight = hourHeight.dp,
                onHourHeightChange = onHourHeightChange,
                scrollState = scrollState,
                showEventEmojis = showEventEmojis,
                timePattern = timePattern,
                maxVisibleOverlap = maxVisibleOverlap,
                onEventClick = onEventClick,
                onOverflowClick = { events -> overflowEvents = events },
                onEmptyTap = onEmptyTap,
                onScrollPositionChange = onScrollPositionChange,
                onScrollMinutesChange = onScrollMinutesChange,
                onReschedule = onReschedule,
                onDayHeaderClick = onDayHeaderClick,
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
    allDayEvents: List<DisplayEvent>,
    startHour: Int = WeekViewUtils.START_HOUR,
    endHour: Int = WeekViewUtils.END_HOUR,
    totalHours: Int = WeekViewUtils.TOTAL_HOURS,
    visibleDays: Int = 3,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    weekLabelPrefix: String = "",
    allDayRowsExpanded: Boolean = false,
    onAllDayRowsToggle: () -> Unit = {},
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    onHourHeightChange: (Float) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    maxVisibleOverlap: Int = WeekViewUtils.MAX_VISIBLE_OVERLAP,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onScrollPositionChange: (Int) -> Unit = {},
    onScrollMinutesChange: (Int) -> Unit = {},
    onReschedule: (DisplayEvent, LocalDate, Int) -> Unit = { _, _, _ -> },
    onDayHeaderClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val is24Hour = timePattern.startsWith("H")
    val totalHeight = hourHeight * totalHours
    val timeColumnWidth = WeekViewUtils.TIME_COLUMN_WIDTH
    val today = LocalDate.now()

    var dragState by remember { mutableStateOf(WeekViewUtils.DragState.Idle) }
    val isDragging by remember { derivedStateOf { dragState.isDragging } }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    // Scroll offset a pinch-zoom wants to settle on, applied once the grid has re-measured
    // to its new height (see the recentring LaunchedEffect below). Applying it inline during
    // the gesture would let the framework re-clamp against the stale, pre-zoom scroll range.
    var pendingZoomScrollPx by remember { mutableStateOf<Float?>(null) }
    val hapticFeedback = LocalHapticFeedback.current

    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "dragScale"
    )

    val autoScrollEdgePx = with(LocalDensity.current) { 48.dp.toPx() }
    val autoScrollSpeedPx = with(LocalDensity.current) { 600.dp.toPx() }
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        while (true) {
            val fingerYInViewport = dragState.currentOffsetY - scrollState.value
            val scrollDelta = when {
                fingerYInViewport < autoScrollEdgePx && scrollState.value > 0 -> -autoScrollSpeedPx / 60f
                fingerYInViewport > viewportHeightPx - autoScrollEdgePx && scrollState.value < scrollState.maxValue -> autoScrollSpeedPx / 60f
                else -> 0f
            }
            if (scrollDelta != 0f) {
                scrollState.dispatchRawDelta(scrollDelta)
            }
            kotlinx.coroutines.delay(16L)
        }
    }

    // Density / hour-height are needed both here (to persist scroll as clock minutes) and
    // by the grid body below. rememberUpdatedState lets the debounced collectors read the
    // live hour-height without restarting on every pinch-zoom.
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }
    val currentHourHeight by rememberUpdatedState(hourHeight)
    val currentHourHeightPx by rememberUpdatedState(hourHeightPx)

    // Track scroll position changes - debounced to prevent per-pixel state updates
    LaunchedEffect(scrollState) {
        @OptIn(FlowPreview::class)
        snapshotFlow { scrollState.value }
            .debounce(100)  // 100ms debounce prevents recomposition storms
            .collect { position ->
                onScrollPositionChange(position)
            }
    }

    // Persist the scroll position as clock minutes for cross-restart restore. Longer debounce
    // than the in-memory pixel path above so active scrolling doesn't hammer DataStore — only
    // the settled position matters for restore. Uses the live hour-height so a pinch-zoom
    // before settling still records the correct clock time.
    LaunchedEffect(scrollState) {
        @OptIn(FlowPreview::class)
        snapshotFlow { scrollState.value }
            .debounce(1000)
            .map { position -> WeekViewUtils.pixelsToMinutesOfDay(position.toFloat(), currentHourHeightPx) }
            .distinctUntilChanged()  // don't re-persist when the settled clock-minute is unchanged
            .collect { minutes -> onScrollMinutesChange(minutes) }
    }

    // Recenter the grid after a pinch-zoom. The gesture changes the hour-row height (which
    // grows/shrinks the scrollable content) and records the scroll offset that keeps the
    // clock time under the viewport center fixed. We wait for the grid to re-measure to its
    // new height before scrolling, otherwise scrollTo() clamps the target against the stale
    // pre-zoom max — stranding the recenter short and sliding the current-time line and every
    // event off their correct time when zooming in.
    LaunchedEffect(pendingZoomScrollPx) {
        val target = pendingZoomScrollPx ?: return@LaunchedEffect
        // Wait for the grid to grow to the target before scrolling; bounded so a rounding
        // mismatch between our target and the measured max can never suspend forever. By
        // the timeout the re-measure has long since landed, so scrollTo() clamps correctly.
        withTimeoutOrNull(250) {
            snapshotFlow { scrollState.maxValue }.first { it.toFloat() >= target - 1f }
        }
        scrollState.scrollTo(target.toInt())
        pendingZoomScrollPx = null
    }

    // Derive visible dates once — shared by headers, all-day, overflow, and time indicator.
    val visibleDates by remember(visibleDays, firstDayOfWeek) {
        derivedStateOf {
            if (visibleDays == 7) {
                val weekStart = WeekViewUtils.weekPageToStartDate(pagerState.currentPage, firstDayOfWeek)
                List(7) { offset -> weekStart.plusDays(offset.toLong()) }
            } else {
                val page = pagerState.currentPage
                List(visibleDays) { offset -> WeekViewUtils.pageToDate(page + offset) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Per-column day headers — skipped in Day view since the screen-level
        // header already labels the single day.
        if (visibleDays > 1) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Header corner (left of the day strip). In the 7-day week view it
                // carries the week-number label so the top bar can stay uncrowded;
                // otherwise it's the blank spacer above the all-day gutter.
                Box(
                    modifier = Modifier.width(timeColumnWidth),
                    contentAlignment = Alignment.Center
                ) {
                    if (visibleDays == 7 && weekLabelPrefix.isNotEmpty()) {
                        val weekLabel = remember(visibleDates, firstDayOfWeek, weekLabelPrefix) {
                            WeekViewUtils.formatWeekLabel(
                                visibleDates.first(),
                                firstDayOfWeek,
                                weekLabelPrefix,
                            )
                        }
                        Text(
                            text = weekLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Row(modifier = Modifier.weight(1f)) {
                    visibleDates.forEach { date ->
                        DayHeaderCell(
                            date = date,
                            isToday = date == today,
                            isWeekend = WeekViewUtils.isWeekend(date),
                            compact = visibleDays == 7,
                            onClick = { onDayHeaderClick(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // All-day strip sits above the timed grid as its own row: it reserves
        // its height so the grid starts below it and the earliest hours
        // (midnight onward) are never hidden behind it.
        AllDayEventsPagerRow(
            visibleDates = visibleDates,
            allDayEvents = allDayEvents,
            timeColumnWidth = timeColumnWidth,
            allDayRowsExpanded = allDayRowsExpanded,
            onAllDayRowsToggle = onAllDayRowsToggle,
            showEventEmojis = showEventEmojis,
            timePattern = timePattern,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )

        // Main time grid area (density / hour-height hoisted above the scroll
        // collectors).
        Box(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Time labels column (fixed)
                Column(
                    modifier = Modifier
                        .width(timeColumnWidth)
                        .verticalScroll(scrollState)
                        .height(totalHeight)
                ) {
                    for (hour in startHour until endHour) {
                        TimeLabel(
                            hour = hour,
                            height = hourHeight,
                            is24Hour = is24Hour,
                            modifier = if (hour == startHour) {
                                Modifier.testTag(TEST_TAG_FIRST_TIME_LABEL)
                            } else {
                                Modifier
                            }
                        )
                    }
                }

                // Day columns area
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val columnWidth = this.maxWidth / visibleDays
                    val localViewportHeight = with(density) { this@BoxWithConstraints.maxHeight.toPx() }
                    viewportHeightPx = localViewportHeight

                    val touchSlop = LocalViewConfiguration.current.touchSlop * 0.5f

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                val pass = PointerEventPass.Initial
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = pass)
                                    var pastSlop = false
                                    // Track the scroll offset and hour-height this gesture is
                                    // steering toward. The applied hour-height (currentHourHeightPx)
                                    // and the scroll offset (scrollState.value) each settle a frame
                                    // apart from a pinch — reading the live values mid-gesture pairs
                                    // a fresh height with a stale scroll and drifts the center. These
                                    // locals advance together every frame so the center math stays
                                    // self-consistent regardless of recomposition timing.
                                    // Seed from a still-settling recenter if one exists, so a rapid
                                    // re-pinch doesn't anchor to a scroll offset that hasn't landed.
                                    var zoomScrollPx = pendingZoomScrollPx ?: scrollState.value.toFloat()
                                    var zoomHourHeightPx = currentHourHeightPx
                                    do {
                                        val event = awaitPointerEvent(pass)
                                        if (event.changes.count { it.pressed } < 2) continue
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        if (!pastSlop) {
                                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                            val effectiveSize = centroidSize.coerceAtLeast(48f)
                                            if (abs(1 - zoom) * effectiveSize > touchSlop) {
                                                pastSlop = true
                                            } else continue
                                        }
                                        event.changes.forEach { it.consume() }
                                        if (abs(zoom - 1f) > 0.001f) {
                                            val newHourHeightPx = (zoomHourHeightPx * zoom)
                                                .coerceIn(
                                                    WeekViewUtils.MIN_HOUR_HEIGHT_DP * density.density,
                                                    WeekViewUtils.MAX_HOUR_HEIGHT_DP * density.density
                                                )
                                            if (abs(newHourHeightPx - zoomHourHeightPx) > 0.01f) {
                                                val recenter = WeekViewUtils.resolveZoomScrollPx(
                                                    currentScrollPx = zoomScrollPx,
                                                    viewportHeightPx = localViewportHeight,
                                                    oldHourHeightPx = zoomHourHeightPx,
                                                    newHourHeightPx = newHourHeightPx,
                                                    totalHours = totalHours,
                                                    panYPx = pan.y
                                                )
                                                onHourHeightChange(newHourHeightPx / density.density)
                                                zoomScrollPx = recenter
                                                zoomHourHeightPx = newHourHeightPx
                                                // Move toward the target this frame for continuity.
                                                // This is self-clamped to the not-yet-grown range, so
                                                // it lands short when zooming in; the recentring effect
                                                // snaps to the exact target once the grid re-measures.
                                                scrollState.dispatchRawDelta(recenter - scrollState.value.toFloat())
                                                pendingZoomScrollPx = recenter
                                            }
                                        } else if (abs(pan.y) > 0.5f) {
                                            scrollState.dispatchRawDelta(-pan.y)
                                            // Advance the tracked offset by the pan (not the possibly
                                            // not-yet-applied scrollState.value), and fold it into any
                                            // settling recenter so the deferred scrollTo keeps the pan.
                                            zoomScrollPx = (zoomScrollPx - pan.y).coerceAtLeast(0f)
                                            pendingZoomScrollPx?.let {
                                                pendingZoomScrollPx = (it - pan.y).coerceAtLeast(0f)
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
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

                            val columnWidthPx = with(density) { columnWidth.toPx() }

                            val handleDragEnd: () -> Unit = {
                                val ds = dragState
                                val event = ds.draggedEvent
                                val target = ds.targetDate
                                if (ds.isDragging && event != null && target != null) {
                                    onReschedule(event, target, ds.targetStartMinutes)
                                }
                                dragState = WeekViewUtils.DragState.Idle
                            }
                            val handleDragCancel: () -> Unit = {
                                dragState = WeekViewUtils.DragState.Idle
                            }

                            if (visibleDays == 7) {
                                // Week mode: 1 page = 1 week (Row of 7 DayColumns)
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize(),
                                    userScrollEnabled = !isDragging,
                                    beyondViewportPageCount = 1,
                                    key = { page -> "week_$page" }
                                ) { page ->
                                    val weekStart = WeekViewUtils.weekPageToStartDate(page, firstDayOfWeek)

                                    Row(modifier = Modifier.fillMaxSize()) {
                                        for (dayOffset in 0 until 7) {
                                            val date = weekStart.plusDays(dayOffset.toLong())
                                            val dayEvents = normalEventsByDate[date].orEmpty()

                                            DayColumn(
                                                date = date,
                                                events = dayEvents,
                                                hourHeight = hourHeight,
                                                isToday = date == today,
                                                showEventEmojis = showEventEmojis,
                                                timePattern = timePattern,
                                                startHour = startHour,
                                                maxVisibleOverlap = maxVisibleOverlap,
                                                onEventClick = onEventClick,
                                                onOverflowClick = onOverflowClick,
                                                onEmptyTap = onEmptyTap,
                                                onEventDragStart = { event, offset ->
                                                    val localStart = Instant.ofEpochMilli(event.startTs).atZone(ZoneId.systemDefault())
                                                    val eventStartMinutes = localStart.hour * 60 + localStart.minute
                                                    val durationMs = event.endTs - event.startTs
                                                    val durationMinutes = (durationMs / 60000).toInt().coerceAtLeast(15)
                                                    val eventHeightDp = (durationMinutes.toFloat() / 60f * currentHourHeight.value).dp
                                                    dragState = WeekViewUtils.DragState(
                                                        isDragging = true,
                                                        draggedEvent = event,
                                                        originalDate = date,
                                                        originalStartMinutes = eventStartMinutes,
                                                        currentOffsetX = dayOffset * columnWidthPx + offset.x,
                                                        currentOffsetY = offset.y + (eventStartMinutes - startHour * 60) / 60f * currentHourHeightPx,
                                                        targetDate = date,
                                                        targetStartMinutes = eventStartMinutes,
                                                        eventHeight = eventHeightDp,
                                                        durationMinutes = durationMinutes
                                                    )
                                                },
                                                onEventDrag = { offset ->
                                                    if (dragState.isDragging) {
                                                        val newX = dragState.currentOffsetX + offset.x
                                                        val newY = dragState.currentOffsetY + offset.y
                                                        val (targetDate, targetMinutes) = WeekViewUtils.calculateDragTarget(
                                                            fingerX = newX,
                                                            fingerY = newY,
                                                            columnWidth = columnWidthPx,
                                                            visibleDates = visibleDates,
                                                            hourHeightPx = currentHourHeightPx,
                                                            scrollOffsetPx = 0,
                                                            startHour = startHour
                                                        )
                                                        val clampedMinutes = WeekViewUtils.clampDragStartMinutes(targetMinutes, dragState.durationMinutes)
                                                        dragState = dragState.copy(
                                                            currentOffsetX = newX,
                                                            currentOffsetY = newY,
                                                            targetDate = targetDate,
                                                            targetStartMinutes = clampedMinutes
                                                        )
                                                    }
                                                },
                                                onEventDragEnd = handleDragEnd,
                                                onEventDragCancel = handleDragCancel,
                                                isDropTarget = isDragging && dragState.targetDate == date,
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
                                // Day-pager mode: 1 page = 1 day, PageSize.Fixed (used by DAY and THREE_DAYS)
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize(),
                                    userScrollEnabled = !isDragging,
                                    pageSize = PageSize.Fixed(columnWidth),
                                    beyondViewportPageCount = 3,
                                    key = { page -> "grid_$page" }
                                ) { page ->
                                    val date = WeekViewUtils.pageToDate(page)
                                    val dayEvents = normalEventsByDate[date].orEmpty()

                                    DayColumn(
                                        date = date,
                                        events = dayEvents,
                                        hourHeight = hourHeight,
                                        isToday = date == today,
                                        showEventEmojis = showEventEmojis,
                                        timePattern = timePattern,
                                        startHour = startHour,
                                        maxVisibleOverlap = maxVisibleOverlap,
                                        onEventClick = onEventClick,
                                        onOverflowClick = onOverflowClick,
                                        onEmptyTap = onEmptyTap,
                                        onEventDragStart = { event, offset ->
                                            val dayOffset = page - pagerState.currentPage
                                            val localStart = Instant.ofEpochMilli(event.startTs).atZone(ZoneId.systemDefault())
                                            val eventStartMinutes = localStart.hour * 60 + localStart.minute
                                            val durationMs = event.endTs - event.startTs
                                            val durationMinutes = (durationMs / 60000).toInt().coerceAtLeast(15)
                                            val eventHeightDp = (durationMinutes.toFloat() / 60f * currentHourHeight.value).dp
                                            dragState = WeekViewUtils.DragState(
                                                isDragging = true,
                                                draggedEvent = event,
                                                originalDate = date,
                                                originalStartMinutes = eventStartMinutes,
                                                currentOffsetX = dayOffset * columnWidthPx + offset.x,
                                                currentOffsetY = offset.y + (eventStartMinutes - startHour * 60) / 60f * currentHourHeightPx,
                                                targetDate = date,
                                                targetStartMinutes = eventStartMinutes,
                                                eventHeight = eventHeightDp,
                                                durationMinutes = durationMinutes
                                            )
                                        },
                                        onEventDrag = { offset ->
                                            if (dragState.isDragging) {
                                                val newX = dragState.currentOffsetX + offset.x
                                                val newY = dragState.currentOffsetY + offset.y
                                                val (targetDate, targetMinutes) = WeekViewUtils.calculateDragTarget(
                                                    fingerX = newX,
                                                    fingerY = newY,
                                                    columnWidth = columnWidthPx,
                                                    visibleDates = visibleDates,
                                                    hourHeightPx = currentHourHeightPx,
                                                    scrollOffsetPx = 0,
                                                    startHour = startHour
                                                )
                                                val clampedMinutes = WeekViewUtils.clampDragStartMinutes(targetMinutes, dragState.durationMinutes)
                                                dragState = dragState.copy(
                                                    currentOffsetX = newX,
                                                    currentOffsetY = newY,
                                                    targetDate = targetDate,
                                                    targetStartMinutes = clampedMinutes
                                                )
                                            }
                                        },
                                        onEventDragEnd = handleDragEnd,
                                        onEventDragCancel = handleDragCancel,
                                        isDropTarget = isDragging && dragState.targetDate == date,
                                        modifier = Modifier.width(columnWidth)
                                    )
                                }

                                // Current time indicator (day-pager mode: DAY/THREE_DAYS)
                                CurrentTimeIndicator(
                                    hourHeight = hourHeight,
                                    visibleDays = visibleDays,
                                    startHour = startHour,
                                    todayOffset = {
                                        WeekViewUtils.dateToPage(today) - pagerState.currentPage
                                    },
                                    columnWidth = columnWidth
                                )
                            }

                            val draggedEvent = dragState.draggedEvent
                            if (isDragging && draggedEvent != null && dragState.targetDate != null) {
                                val targetMinutesFromStart = dragState.targetStartMinutes - startHour * 60
                                val targetYDp = with(density) { (targetMinutesFromStart.toFloat() / 60f * hourHeightPx).toDp() }
                                val targetColumnIndex = visibleDates.indexOf(dragState.targetDate)
                                val targetXDp = if (targetColumnIndex >= 0) columnWidth * targetColumnIndex else 0.dp
                                val eventWidthDp = columnWidth - 2.dp

                                val originalMinutesFromStart = dragState.originalStartMinutes - startHour * 60
                                val originalYDp = with(density) { (originalMinutesFromStart.toFloat() / 60f * hourHeightPx).toDp() }
                                val originalColumnIndex = visibleDates.indexOf(dragState.originalDate)
                                if (originalColumnIndex >= 0) {
                                    val originalXDp = columnWidth * originalColumnIndex
                                    EventBlock(
                                        displayEvent = draggedEvent,
                                        height = dragState.eventHeight,
                                        showEventEmojis = showEventEmojis,
                                        timePattern = timePattern,
                                        onClick = {},
                                        modifier = Modifier
                                            .offset(x = originalXDp, y = originalYDp)
                                            .width(eventWidthDp)
                                            .graphicsLayer { alpha = 0.3f }
                                    )
                                }

                                if (targetColumnIndex >= 0) {
                                    EventBlock(
                                        displayEvent = draggedEvent,
                                        height = dragState.eventHeight,
                                        showEventEmojis = showEventEmojis,
                                        timePattern = timePattern,
                                        onClick = {},
                                        modifier = Modifier
                                            .offset(x = targetXDp, y = targetYDp)
                                            .width(eventWidthDp)
                                            .graphicsLayer {
                                                alpha = 0.85f
                                                shadowElevation = 8f
                                                scaleX = dragScale
                                                scaleY = dragScale
                                            }
                                    )

                                    val timeLabel = WeekViewUtils.minutesToTimeLabel(dragState.targetStartMinutes, is24Hour)
                                    Surface(
                                        shadowElevation = 2.dp,
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.inverseSurface,
                                        modifier = Modifier
                                            .offset(x = targetXDp, y = targetYDp - 24.dp)
                                    ) {
                                        Text(
                                            text = timeLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.inverseOnSurface,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
    onClick: () -> Unit = {},
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
            modifier = modifier
                // Guarantee a 48dp tap target (WCAG / Material minimum): the
                // stacked letter + number alone are shorter than that.
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = dayName,
                // The 7-day columns are narrow, but a single letter + 1-2 digit
                // number never fill them, so match the 3-day header's bodyMedium
                // size for legibility. The cell is already pinned to a 48dp min
                // height, so the larger text neither wraps nor grows the row.
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .then(
                        if (isToday) {
                            Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.inverseSurface)
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
                    color = if (isToday) MaterialTheme.colorScheme.inverseOnSurface else textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        // Standard horizontal layout for 3-day view: "Wed 11"
        Row(
            modifier = modifier
                // Guarantee a 48dp tap target (WCAG / Material minimum).
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
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
                                .background(MaterialTheme.colorScheme.inverseSurface)
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
                    color = if (isToday) MaterialTheme.colorScheme.inverseOnSurface else textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * All-day events row with pager synchronization.
 *
 * Collapsed (the default) shows at most one row per day; expanded fills up to
 * [WeekViewUtils.MAX_ALLDAY_ROWS_EXPANDED] rows adaptively. A day with events that
 * don't fit in its visible rows gets a "+N" badge overlaid on that column (see
 * [computeAllDayStripRender]) rather than a row of its own, so it's never lost even
 * when every row is taken by a spanning multi-day bar. A chevron on the fixed
 * "All day" label toggles the two states; it is hidden when no visible day has more
 * than one all-day event (nothing to expand).
 */
@Composable
private fun AllDayEventsPagerRow(
    visibleDates: List<LocalDate>,
    allDayEvents: List<DisplayEvent>,
    timeColumnWidth: Dp,
    allDayRowsExpanded: Boolean,
    onAllDayRowsToggle: () -> Unit,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleDayCodes = remember(visibleDates) {
        visibleDates.map { DayPagerUtils.localDateToDayCode(it) }
    }

    // Per-day raw event counts (each multi-day event counts once per day it
    // touches) — used only to decide chevron visibility and whether the strip
    // renders at all, independent of how spans are actually laid out into rows.
    val perDayCounts = remember(visibleDayCodes, allDayEvents) {
        visibleDayCodes.map { dayCode ->
            allDayEvents.count { it.startDay <= dayCode && it.endDay >= dayCode }
        }
    }

    val hasAnyEvents = perDayCounts.any { it > 0 }
    if (!hasAnyEvents) return

    // Chevron visibility: is there any day with more than one all-day event to
    // expand? Delegated to the unit-tested WeekViewUtils helper (single source of
    // truth).
    val canToggle = remember(perDayCounts) {
        WeekViewUtils.anyAllDayColumnHasOverflowWhenCollapsed(perDayCounts)
    }

    val maxRows = if (allDayRowsExpanded) {
        WeekViewUtils.MAX_ALLDAY_ROWS_EXPANDED
    } else {
        WeekViewUtils.MAX_ALLDAY_ROWS_COLLAPSED
    }

    // Shared across every chip in the strip instead of one TextMeasurer per chip.
    val textMeasurer = rememberTextMeasurer()

    // Spanning-bar render grid: multi-day events occupy one lane row across every
    // column they cover (rendered once, not duplicated per day); each column's
    // remaining rows fill with that day's own single-day events, with any
    // remainder collapsed into a "+N" badge.
    val render = remember(visibleDayCodes, allDayEvents, maxRows) {
        computeAllDayStripRender(visibleDayCodes, allDayEvents, maxRows)
    }

    // Chevron points up when expanded (tap to collapse), down when collapsed.
    val chevronRotation by animateFloatAsState(
        targetValue = if (allDayRowsExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "allDayChevronRotation"
    )
    val toggleLabel = if (allDayRowsExpanded) {
        stringResource(R.string.cd_collapse_all_day_rows)
    } else {
        stringResource(R.string.cd_expand_all_day_rows)
    }

    Row(
        modifier = modifier
            .testTag(TEST_TAG_ALL_DAY_STRIP)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            // Match the chevron's 300ms tween so the strip resize and the arrow
            // rotation finish together on a toggle.
            .animateContentSize(animationSpec = tween(300))
            .padding(vertical = 4.dp)
    ) {
        // Label column — the "All day" caption with, when there's something to
        // expand, a chevron stacked beneath it. The column is only 48dp wide, so
        // the chevron goes below the caption rather than beside it (which would
        // overflow the width in English and longer locales).
        Box(
            modifier = Modifier
                .width(timeColumnWidth)
                // When there's something to expand, the whole "All day" label toggles
                // the strip; guarantee a 48dp tap target (WCAG / Material minimum),
                // since the caption + chevron alone are shorter than that.
                .then(
                    if (canToggle) {
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = toggleLabel, onClick = onAllDayRowsToggle)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.label_all_day),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The gutter is a fixed 48dp; longer translations ("Toute la
                    // journée") would wrap to a second line and grow the strip out of
                    // alignment with the day cells. Keep one line and shrink to fit,
                    // down to a still-legible floor; only the longest few locales pass
                    // that floor and ellipsize.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = 11.sp,
                        stepSize = 0.5.sp
                    )
                )
                if (canToggle) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }
            }
        }

        // All-day events grid: one row per lane, one column per visible day (up
        // to 7 in WEEK mode). A multi-day event occupies a single wide cell
        // spanning every column it covers (rendered once, not duplicated per
        // day); single-day events fill the remaining per-column slots. No
        // HorizontalPager here to avoid gesture conflicts with the main time grid.
        //
        // A "+N" badge is overlaid per column (see the second Row below) rather
        // than claiming a grid row: a column can run out of free rows entirely
        // (every row taken by a spanning bar), so a row-based badge slot could
        // itself get squeezed out and silently drop events with no affordance.
        // Only when a column actually overflows do we floor this box to the label's
        // 48dp tap-target height, so the bottom-anchored badge overlay (which
        // matchParentSize's this box) reaches the strip's real bottom instead of
        // hugging short row content and stranding the badge mid-strip over a bar's
        // end-time. It's a MINIMUM applied conditionally, never fillMaxHeight: the
        // box still wraps its rows and never expands to the parent's full height, so
        // it cannot starve the scrollable timed grid; and days with no overflow keep
        // the strip tight to its content rather than always reserving 48dp.
        val hasOverflow = render.overflowByColumn.any { it != null }
        Box(
            modifier = Modifier.weight(1f)
                .then(if (hasOverflow) Modifier.heightIn(min = 48.dp) else Modifier)
        ) {
            Column {
                render.slots.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        var col = 0
                        while (col < row.size) {
                            when (val slot = row[col]) {
                                is AllDaySlot.BarSegment -> {
                                    val span = slot.span
                                    val width = span.endCol - span.startCol + 1
                                    CompactEventChip(
                                        displayEvent = span.displayEvent,
                                        onClick = { onEventClick(span.displayEvent) },
                                        textMeasurer = textMeasurer,
                                        showEventEmojis = showEventEmojis,
                                        // The start/end time is only meaningful on the segment that
                                        // actually contains that day — a leftFlush segment continues
                                        // from before the visible window, and a rightFlush segment
                                        // continues past the end of it.
                                        showStartTime = !span.displayEvent.isAllDay && !span.leftFlush,
                                        showEndTime = !span.displayEvent.isAllDay && !span.rightFlush,
                                        timePattern = timePattern,
                                        shape = RoundedCornerShape(
                                            topStart = if (span.leftFlush) 0.dp else 4.dp,
                                            bottomStart = if (span.leftFlush) 0.dp else 4.dp,
                                            topEnd = if (span.rightFlush) 0.dp else 4.dp,
                                            bottomEnd = if (span.rightFlush) 0.dp else 4.dp,
                                        ),
                                        modifier = Modifier
                                            .weight(width.toFloat())
                                            .padding(horizontal = 2.dp)
                                    )
                                    col = span.endCol + 1
                                }
                                is AllDaySlot.CellEvent -> {
                                    CompactEventChip(
                                        displayEvent = slot.event,
                                        onClick = { onEventClick(slot.event) },
                                        textMeasurer = textMeasurer,
                                        showEventEmojis = showEventEmojis,
                                        // Only show the time when this cell is the event's actual
                                        // start day (a multi-day event that spilled out of the
                                        // spanning lanes is duplicated per day it touches).
                                        showStartTime = !slot.event.isAllDay && slot.event.startDay == visibleDayCodes[col],
                                        timePattern = timePattern,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 2.dp)
                                    )
                                    col++
                                }
                                AllDaySlot.Empty -> {
                                    Box(modifier = Modifier.weight(1f).padding(horizontal = 2.dp))
                                    col++
                                }
                            }
                        }
                    }
                }
            }

            // Overflow badges, one per column, overlaid at the bottom-end corner
            // regardless of what that column's rows are showing (a bar, a cell
            // event, or nothing at all).
            Row(modifier = Modifier.matchParentSize()) {
                for (col in visibleDayCodes.indices) {
                    val overflow = render.overflowByColumn.getOrNull(col)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        if (overflow != null) {
                            // Compact "+N" badge (no "more" text) to fit the narrow
                            // all-day columns. The glyph is small, so the clickable
                            // Box carries a larger min size than the text would
                            // occupy, giving a comfortable tap target.
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { onOverflowClick(overflow.events) }
                                    .defaultMinSize(minWidth = 24.dp, minHeight = 18.dp)
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.status_more_events_compact, overflow.count),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact event chip for all-day event rows.
 */
@Composable
private fun CompactEventChip(
    displayEvent: DisplayEvent,
    onClick: () -> Unit,
    textMeasurer: TextMeasurer,
    showEventEmojis: Boolean = true,
    showStartTime: Boolean = false,
    showEndTime: Boolean = false,
    timePattern: String = "h:mma",
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
    modifier: Modifier = Modifier
) {
    val color = displayEvent.eventColor ?: displayEvent.calendarColor
    val isFree = displayEvent.isFree
    val displayText = remember(displayEvent.title, showEventEmojis) {
        EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
    }
    val calColor = Color(color)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val (backgroundColor, textColor) = remember(color, isFree, surfaceColor) {
        if (isFree) {
            lerp(surfaceColor, calColor, 0.15f) to onSurfaceColor
        } else {
            calColor to contrastForegroundOn(calColor)
        }
    }

    val stateLabel = eventStateDescription(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    val titleStyle = MaterialTheme.typography.labelSmall
    val timeText = if (showStartTime) {
        ", ${WeekViewUtils.formatTime(displayEvent.startTs, timePattern)}"
    } else {
        null
    }
    // Shown at the bar's right cap (see the Spacer-pushed Text below), distinct from
    // timeText's start time at the left — a bar with no end time reads ambiguously,
    // e.g. "Sample event, 6:15am" alone doesn't say when it ends.
    val endTimeText = if (showEndTime) {
        WeekViewUtils.formatTime(displayEvent.endTs, timePattern)
    } else {
        null
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .alpha(declinedCardAlpha(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled))
            .then(if (stateLabel != null) Modifier.semantics { stateDescription = stateLabel } else Modifier)
            .clip(shape)
            .then(
                if (isFree) Modifier.border(2.dp, calColor, shape)
                else Modifier
            )
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        // TextOverflow.Ellipsis can size its box to the full available width
        // even though the painted glyphs stop short of it, which leaves a
        // visible gap before sibling text placed right after. Truncating the
        // title ourselves to the exact pixel budget avoids relying on that
        // box-sizing behavior, so it sits flush against the time label.
        val timeWidthPx = remember(timeText, titleStyle) {
            timeText?.let { measureWidthPx(textMeasurer, it, titleStyle) } ?: 0
        }
        val endTimeWidthPx = remember(endTimeText, titleStyle) {
            // Leading gap ("  ") so the right-capped time never touches the title.
            endTimeText?.let { measureWidthPx(textMeasurer, "  $it", titleStyle) } ?: 0
        }
        val availableTitleWidthPx = (constraints.maxWidth - timeWidthPx - endTimeWidthPx).coerceAtLeast(0)
        val truncatedTitle = remember(displayText, availableTitleWidthPx, titleStyle) {
            truncateWithEllipsis(textMeasurer, displayText, titleStyle, availableTitleWidthPx)
        }
        // When the chip is too narrow for the title at all, truncatedTitle is empty —
        // drop timeText's leading ", " separator so the chip doesn't paint a dangling
        // comma before the time with no title before it.
        val displayTimeText = if (truncatedTitle.isEmpty()) timeText?.removePrefix(", ") else timeText
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = truncatedTitle,
                style = titleStyle,
                color = textColor,
                textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (displayTimeText != null) {
                Text(
                    text = displayTimeText,
                    style = titleStyle,
                    color = textColor,
                    textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
            if (endTimeText != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = endTimeText,
                    style = titleStyle,
                    color = textColor,
                    textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

private fun measureWidthPx(measurer: TextMeasurer, text: String, style: TextStyle): Int =
    measurer.measure(text = text, style = style, maxLines = 1).size.width

/**
 * Truncates [text] to fit within [maxWidthPx], appending an ellipsis if it
 * doesn't already fit. Measures with [measurer]/[style] directly instead of
 * using Text's built-in overflow handling, so the result's rendered width is
 * exact rather than settling for "some width no larger than the max".
 */
private fun truncateWithEllipsis(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int
): String {
    if (maxWidthPx <= 0) return ""
    if (measureWidthPx(measurer, text, style) <= maxWidthPx) return text

    val ellipsis = "…"
    val budget = maxWidthPx - measureWidthPx(measurer, ellipsis, style)
    if (budget <= 0) return ellipsis

    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        val candidate = safeSubstring(text, mid)
        if (measureWidthPx(measurer, candidate, style) <= budget) lo = mid else hi = mid - 1
    }
    return safeSubstring(text, lo) + ellipsis
}

private fun safeSubstring(text: String, length: Int): String = when {
    length >= text.length -> text
    length <= 0 -> ""
    Character.isHighSurrogate(text[length - 1]) -> text.substring(0, length - 1)
    else -> text.substring(0, length)
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
    val endHour = WeekViewUtils.END_HOUR

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

    // Density-independent sizing. The marker box is as tall as the dot and offset up by
    // half its height so the line lands exactly on the current minute.
    val lineThickness = 2.dp
    val dotRadius = 4.dp
    val markerHeight = dotRadius * 2

    Box(
        modifier = modifier
            .offset(x = xOffset, y = yOffset - markerHeight / 2)
            .width(columnWidth)
            .height(markerHeight)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2
            drawLine(
                color = indicatorColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = lineThickness.toPx()
            )
            drawCircle(
                color = indicatorColor,
                radius = dotRadius.toPx(),
                center = Offset(dotRadius.toPx(), centerY)
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
            text = stringResource(R.string.status_no_events_week),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


