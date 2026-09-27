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

// Test tags `AllDayStripOcclusionTest` uses to check that the all-day strip doesn't cover the
// timed grid's earliest hours.
internal const val TEST_TAG_ALL_DAY_STRIP = "allDayStrip"
internal const val TEST_TAG_FIRST_TIME_LABEL = "firstTimeLabel"

/**
 * Shows the day, 3-day and week time grids over a pseudo-infinite pager.
 *
 * With [visibleDays] 1 or 3, each page is one day and [WeekViewUtils.CENTER_DAY_PAGE] is today;
 * with 7, each page is one week starting on [firstDayOfWeek] and
 * [WeekViewUtils.CENTER_WEEK_PAGE] is the current week. The settled page goes to
 * [onPageChanged].
 *
 * Top to bottom: day headers (not in Day view), the all-day strip, then the 24-hour grid. The
 * headers and the strip sit above the pager and follow its current page. A "+N" badge opens
 * [OverlapListSheet].
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
     * Localized prefix for the week-number label ("W") in the header corner, left of the day
     * headers. Shown only in the 7-day week view; blank hides it.
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
    /** Called with the date of a tapped day header. */
    onDayHeaderClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The grid covers the full 24 hours in every view.
    val startHour = WeekViewUtils.START_HOUR
    val endHour = WeekViewUtils.END_HOUR
    val totalHours = WeekViewUtils.TOTAL_HOURS

    // Day view (visibleDays = 1) raises the side-by-side cap to 5 since events get the full
    // width; multi-day views keep the default 2 so columns stay readable.
    val maxVisibleOverlap = if (visibleDays == 1) 5 else WeekViewUtils.MAX_VISIBLE_OVERLAP

    // One page per day (Day, 3-day) or per week (Week).
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

    // Report the settled page only, so a swipe in progress doesn't trigger loads.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                Log.d(TAG, "Pager settled on page $page")
                onPageChanged(page)
            }
    }

    // Programmatic navigation, for example the Today button or the date picker.
    LaunchedEffect(pendingNavigateToPage) {
        pendingNavigateToPage?.let { targetPage ->
            // Wait for any user scroll to finish so the animation doesn't race it.
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

    val timedEventsByDate = remember(timedEvents) {
        groupEventsByDate(timedEvents.toList())
    }

    val allDayEventsList = remember(allDayEvents) { allDayEvents.toList() }

    // Every timed event goes to the grid, which covers the full 24 hours.
    val normalEventsByDate = timedEventsByDate

    // Events of the tapped "+N" badge; non-null shows OverlapListSheet.
    var overflowEvents by remember { mutableStateOf<List<DisplayEvent>?>(null) }

    // Render the grid at once, while loading too, so time labels, grid lines and headers
    // appear without a spinner flash; events fill in when the Flow emits.
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
 * Lays out the day headers, the all-day strip and the scrolling time grid.
 *
 * The grid handles pinch-zoom of the hour height, long-press drag to reschedule
 * ([onReschedule] on release), edge auto-scroll while dragging, and the current-time line.
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

    // rememberUpdatedState lets the scroll-minutes collector, the pinch gesture and the drag
    // callbacks read the live hour height without restarting on every pinch-zoom.
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }
    val currentHourHeight by rememberUpdatedState(hourHeight)
    val currentHourHeightPx by rememberUpdatedState(hourHeightPx)

    // Report the scroll position, debounced so scrolling doesn't update state every pixel.
    LaunchedEffect(scrollState) {
        @OptIn(FlowPreview::class)
        snapshotFlow { scrollState.value }
            .debounce(100)
            .collect { position ->
                onScrollPositionChange(position)
            }
    }

    // Report the scroll position as clock minutes, restored after a restart. The longer
    // debounce keeps active scrolling from hammering DataStore; only the settled position
    // matters. The live hour height keeps the minutes right after a pinch-zoom.
    LaunchedEffect(scrollState) {
        @OptIn(FlowPreview::class)
        snapshotFlow { scrollState.value }
            .debounce(1000)
            .map { position -> WeekViewUtils.pixelsToMinutesOfDay(position.toFloat(), currentHourHeightPx) }
            .distinctUntilChanged()
            .collect { minutes -> onScrollMinutesChange(minutes) }
    }

    // Recenter the grid after a pinch-zoom. The gesture changes the hour height and records
    // the scroll offset that keeps the clock time under the viewport center fixed. Scrolling
    // before the grid re-measures would clamp against the pre-zoom max, leaving the recenter
    // short and sliding the current-time line and every event off their time when zooming in.
    LaunchedEffect(pendingZoomScrollPx) {
        val target = pendingZoomScrollPx ?: return@LaunchedEffect
        // Wait for the grid to grow to the target. The timeout stops a rounding mismatch
        // between the target and the measured max from suspending forever; by then the
        // re-measure has landed, so scrollTo() clamps against the new max.
        withTimeoutOrNull(250) {
            snapshotFlow { scrollState.maxValue }.first { it.toFloat() >= target - 1f }
        }
        scrollState.scrollTo(target.toInt())
        pendingZoomScrollPx = null
    }

    // The current page's dates, shared by the headers, the week label, the all-day strip and
    // drag targeting.
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
        // Day headers, skipped in Day view since the screen-level header already labels the
        // single day.
        if (visibleDays > 1) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Header corner, left of the day headers. In the 7-day week view it carries
                // the week-number label so the top bar stays uncrowded; otherwise it's the
                // blank spacer above the all-day gutter.
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

        // The all-day strip is its own row above the timed grid, so the grid starts below it
        // and the earliest hours are never hidden behind it.
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

        Box(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Hour labels, scrolling vertically with the grid but not paging.
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
                                    // The scroll offset and hour height this gesture steers
                                    // toward. The applied height (currentHourHeightPx) and
                                    // scrollState.value settle a frame apart, so reading them
                                    // mid-gesture pairs a new height with an old scroll and
                                    // drifts the center. These locals advance together every
                                    // frame. Seed from a still-settling recenter so a quick
                                    // re-pinch doesn't anchor to an offset that hasn't landed.
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
                                                // Move toward the target this frame. The delta is
                                                // clamped to the not-yet-grown range, so it lands
                                                // short when zooming in; the recentring effect
                                                // snaps to the target once the grid re-measures.
                                                scrollState.dispatchRawDelta(recenter - scrollState.value.toFloat())
                                                pendingZoomScrollPx = recenter
                                            }
                                        } else if (abs(pan.y) > 0.5f) {
                                            scrollState.dispatchRawDelta(-pan.y)
                                            // Advance the tracked offset by the pan, not by
                                            // scrollState.value, which may not have applied yet,
                                            // and fold it into a settling recenter so the
                                            // deferred scrollTo keeps the pan.
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
                                // Week mode: one page is a week, a Row of 7 DayColumns.
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
                                // Day and 3-day mode: one page is one day, one column wide.
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

/** Shows one day header: "Mon 6", or in [compact] mode a stacked narrow day name and number. */
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
            // Narrow day name, for example M, T, W in English.
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
        // 7-day view: narrow day name above the number.
        Column(
            modifier = modifier
                // 48dp minimum tap target (WCAG and Material); the stacked name and number
                // are shorter.
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = dayName,
                // A narrow day name and a 1-2 digit number don't fill even a 7-day column,
                // so use the 3-day header's bodyMedium for legibility. The 48dp minimum
                // height means the larger text neither wraps nor grows the row.
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
        // 3-day view: "Wed 11" on one line.
        Row(
            modifier = modifier
                // 48dp minimum tap target (WCAG and Material).
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
 * Shows the all-day strip for [visibleDates], or nothing when no visible day has an event.
 *
 * Collapsed (the default) shows [WeekViewUtils.MAX_ALLDAY_ROWS_COLLAPSED] row; expanded shows
 * up to [WeekViewUtils.MAX_ALLDAY_ROWS_EXPANDED]. Events that don't fit a day's rows go behind
 * a "+N" badge overlaid on that column ([computeAllDayStripRender]). Tapping the "All day" label
 * toggles the two states; its chevron and the toggle show only when some visible day has more
 * events than the collapsed row holds.
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

    // Per-day event counts, a multi-day event once per day it touches. They decide only whether
    // the strip renders and whether it can toggle, not how spans are laid out.
    val perDayCounts = remember(visibleDayCodes, allDayEvents) {
        visibleDayCodes.map { dayCode ->
            allDayEvents.count { it.startDay <= dayCode && it.endDay >= dayCode }
        }
    }

    val hasAnyEvents = perDayCounts.any { it > 0 }
    if (!hasAnyEvents) return

    // Whether any day has more events than the collapsed row holds (`WeekViewUtilsTest`).
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
            // Same 300ms tween as the chevron, so the resize and the rotation end together.
            .animateContentSize(animationSpec = tween(300))
            .padding(vertical = 4.dp)
    ) {
        // Label column: the "All day" caption, with the chevron below it when the strip can
        // toggle. The column is 48dp wide, so a chevron beside the caption would overflow it in
        // English and longer locales.
        Box(
            modifier = Modifier
                .width(timeColumnWidth)
                // When the strip can toggle, the whole label is the toggle, with a 48dp
                // minimum tap target (WCAG and Material); the caption and chevron are shorter.
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
                    // The gutter is a fixed 48dp; a longer translation ("Toute la journée")
                    // would wrap and grow the strip out of line with the day cells. Keep one
                    // line and shrink to fit, down to a still-legible floor; only the longest
                    // few locales pass that floor and ellipsize.
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

        // One row per lane, one column per visible day. A bar is one wide chip across the
        // columns it covers. No HorizontalPager here, so the strip has no gesture conflict with
        // the time grid. The "+N" badges are an overlay (the second Row below) because a
        // column's rows can all be bars, and a badge row could be squeezed out and silently
        // drop events.
        //
        // When a column overflows, this box gets the label's 48dp minimum height so the
        // bottom-anchored overlay, which matchParentSize's this box, reaches the strip's bottom
        // instead of sitting mid-strip over a bar's end time. It must stay a conditional
        // minimum, never fillMaxHeight: filling would starve the scrolling time grid, and
        // without overflow the strip stays tight to its rows.
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
                                        // Show the start or end time only when that day is
                                        // visible: a leftFlush bar starts before the window,
                                        // a rightFlush bar ends after it.
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
                                        // Start time only on the event's start day: a
                                        // multi-day event without a lane has a cell on every
                                        // day it touches.
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

            // Overflow badges, one per column, at the bottom-end corner whatever the column's
            // rows show.
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
                            // "+N" without "more" to fit the narrow columns. The minimum
                            // size makes the tap target larger than the small text.
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
 * Shows one event of the all-day strip as a one-line chip: title, optional start time after it
 * and optional end time at the right edge.
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
    // End time at the bar's right edge, pushed there by the Spacer below. Without it,
    // "Sample event, 6:15am" doesn't say when the event ends.
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
        // TextOverflow.Ellipsis can size its box to the full available width while the glyphs
        // stop short, leaving a gap before the time label. Truncating the title here to the
        // pixel budget keeps it flush against the label.
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
        // When the chip has no room for the title, truncatedTitle is empty; drop the leading
        // ", " so no comma dangles before the time.
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
 * Truncates [text] to fit [maxWidthPx], with an ellipsis when it doesn't fit whole.
 *
 * Measures with [measurer] and [style] instead of Text's overflow handling, so the returned
 * string's width is its rendered width. Returns "" when [maxWidthPx] is 0 or less and the
 * ellipsis alone when nothing else fits; never splits a surrogate pair.
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

/** Shows one hour label of the time grid, aligned to the top of its hour. */
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

/** Draws a line at the top of each hour of the time grid. */
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
 * Draws the current-time line and dot across today's column, updated each minute.
 *
 * Draws nothing when today isn't visible or the time is outside the grid's hours.
 *
 * @param visibleDays number of visible day columns: 1, 3 or 7
 * @param startHour first hour of the grid
 * @param todayOffset returns today's 0-based column index from the current page
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

    // Update at the start of each minute.
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

    val startMinutes = startHour * 60
    val endMinutes = endHour * 60
    if (currentMinutes < startMinutes || currentMinutes >= endMinutes) return

    val todayVisibleOffset = todayOffset()

    if (todayVisibleOffset !in 0 until visibleDays) return

    val minutesFromStart = currentMinutes - startMinutes
    val density = LocalDensity.current
    val yOffset = with(density) { (minutesFromStart.toFloat() / 60f * hourHeight.toPx()).toDp() }
    val xOffset = columnWidth * todayVisibleOffset
    val indicatorColor = MaterialTheme.colorScheme.error

    // The marker box is as tall as the dot and offset up by half its height so the line
    // lands on the current minute.
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
 * Groups events by date, listing a multi-day event under every day from its startDay to its
 * endDay.
 *
 * Uses DisplayEvent's precomputed startDay and endDay, which already handle all-day events'
 * UTC dates.
 */
private fun groupEventsByDate(
    events: List<DisplayEvent>
): Map<LocalDate, List<DisplayEvent>> {
    val result = mutableMapOf<LocalDate, MutableList<DisplayEvent>>()

    for (displayEvent in events) {
        var currentDay = displayEvent.startDay
        while (currentDay <= displayEvent.endDay) {
            val date = DayPagerUtils.dayCodeToLocalDate(currentDay)
            result.getOrPut(date) { mutableListOf() }.add(displayEvent)
            currentDay = Occurrence.incrementDayCode(currentDay)
        }
    }

    return result
}


/** Shows the no-events-this-week message. */
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


