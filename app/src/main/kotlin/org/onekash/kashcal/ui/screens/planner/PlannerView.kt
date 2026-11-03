package org.onekash.kashcal.ui.screens.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.isStripItem
import org.onekash.kashcal.domain.planner.MovedCue
import org.onekash.kashcal.domain.planner.PlannerSummaryItem
import org.onekash.kashcal.domain.planner.PlannerSummaryKind
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.domain.planner.clampPlannerZoom
import org.onekash.kashcal.domain.planner.differentThisWeek
import org.onekash.kashcal.domain.planner.movedCue
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.components.formatDisplayEventTimeDisplay
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.screens.monthfull.SpanStyle
import org.onekash.kashcal.ui.screens.monthfull.spanStyleFor
import org.onekash.kashcal.ui.shared.ClippedTitle
import org.onekash.kashcal.ui.shared.Zoomed
import org.onekash.kashcal.ui.shared.pinchZoom
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.plannerShownWeekStart
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.onekash.kashcal.ui.util.LocalDisplayZone

/**
 * Draws the Planner: one week as seven day boxes plus a "Different this week" box, paged
 * sideways by week. Times and days are in the system zone, as in the other views.
 *
 * Pages count weeks from [anchorDayCode] ([plannerShownWeekStart]), the same way the ViewModel
 * keys its query, and each page reads its days from [plannerState], which also holds the
 * neighbouring weeks. Until [plannerState] covers a page's days the page draws no "Free day" and
 * no "The usual week.", so nothing reads as empty before it has loaded.
 *
 * A two-finger pinch past the touch slop scales the box contents through [onZoomChange]; one
 * finger still pages, and vertical drags reach pull-to-refresh.
 *
 * @param anchorDayCode first day of the anchor week; null falls back to the week holding today.
 * @param pagerPosition the page to start on.
 * @param pendingNavigateToPage a page to scroll to (Today, the date picker), then
 *   [onNavigationConsumed] is called.
 * @param isCompactHeight the home screen's short-window check, which picks the 4 x 2 grid.
 * @param onEmptyDayTap empty space in a day box with nothing hidden was tapped, with that day at
 *   the current time of day ([plannerDayMs]).
 * @param onDayClick an event row, an all-day ribbon, the space around the rows of a box showing
 *   "+N" (the count included), or a "Different this week" line was tapped, with that day as in
 *   [onEmptyDayTap].
 */
@Composable
internal fun PlannerView(
    plannerState: PlannerWeekState?,
    anchorDayCode: Int?,
    pagerPosition: Int,
    pendingNavigateToPage: Int?,
    firstDayOfWeek: Int,
    todayDayCode: Int,
    nowMs: Long,
    timePattern: String,
    showEventEmojis: Boolean,
    zoom: Float,
    widthDp: Int,
    isCompactHeight: Boolean,
    onPageChanged: (Int) -> Unit,
    onNavigationConsumed: () -> Unit,
    onEmptyDayTap: (Long) -> Unit,
    onDayClick: (Long) -> Unit,
    onDayHeaderClick: (LocalDate) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = pagerPosition, pageCount = { WeekViewUtils.TOTAL_WEEK_PAGES })

    // Report the settled page only, so a swipe in progress doesn't re-key the query.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }

    // Programmatic navigation, for example the Today button or the date picker.
    LaunchedEffect(pendingNavigateToPage) {
        pendingNavigateToPage?.let { target ->
            snapshotFlow { pagerState.isScrollInProgress }.filter { !it }.first()
            pagerState.animateScrollToPage(target)
            onNavigationConsumed()
        }
    }

    val locale = LocalLocale.current.platformLocale
    val formats = remember(locale) { PlannerFormats(locale) }
    val shape = plannerGridShape(widthDp, isCompactHeight)
    val today = remember(todayDayCode) { DayPagerUtils.dayCodeToLocalDate(todayDayCode) }

    // The boxes fit the screen, so nothing scrolls vertically, and Material3 pull-to-refresh
    // only sees a drag that a descendant passes up through nested scroll. This scrollable
    // consumes nothing, so it hands vertical drags to pull-to-refresh while the pager keeps the
    // horizontal ones.
    val scrollDonor = rememberScrollableState { 0f }
    HorizontalPager(
        state = pagerState,
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxSize()
            .scrollable(orientation = Orientation.Vertical, state = scrollDonor)
            .pinchZoom(currentZoom = { zoom }, clamp = ::clampPlannerZoom, onZoomChange = onZoomChange),
    ) { page ->
        PlannerWeekPage(
            plannerState = plannerState,
            weekStart = plannerShownWeekStart(anchorDayCode, page, firstDayOfWeek, today),
            todayDayCode = todayDayCode,
            nowMs = nowMs,
            timePattern = timePattern,
            showEventEmojis = showEventEmojis,
            zoom = zoom,
            shape = shape,
            formats = formats,
            onEmptyDayTap = onEmptyDayTap,
            onDayClick = onDayClick,
            onDayHeaderClick = onDayHeaderClick,
        )
    }
}

/** Formatters for one locale, built once rather than on every recomposition (a pinch is many). */
private class PlannerFormats(val locale: Locale) {
    private val dayLabelFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEMMMd", locale), locale)
    val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEE", locale), locale)
    val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMd", locale), locale)

    /**
     * Returns the day box header: short weekday, month and day in the locale's order, the
     * Day view title's skeleton ("Thu, Oct 15").
     */
    fun dayLabel(date: LocalDate): String = dayLabelFormat.format(date)
}

/** One page's derived content, built once per page, state and day. */
private class PlannerPageModel(
    val dayCodes: List<Int>,
    val loaded: Boolean,
    val summary: List<PlannerSummaryItem>,
    /** Each moved occurrence's cue, keyed by [DisplayEvent.stableKey]. */
    val cues: Map<String, MovedCue>,
)

@Composable
private fun PlannerWeekPage(
    plannerState: PlannerWeekState?,
    weekStart: LocalDate,
    todayDayCode: Int,
    nowMs: Long,
    timePattern: String,
    showEventEmojis: Boolean,
    zoom: Float,
    shape: PlannerGridShape,
    formats: PlannerFormats,
    onEmptyDayTap: (Long) -> Unit,
    onDayClick: (Long) -> Unit,
    onDayHeaderClick: (LocalDate) -> Unit,
) {
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    // The moved cues and the week summary read the device zone; the key re-runs them on a change.
    val model = remember(plannerState, days, todayDayCode, LocalDisplayZone.current) {
        val dayCodes = days.map(DayPagerUtils::localDateToDayCode)
        if (plannerState == null || !dayCodes.all { plannerState.covers(it) }) {
            PlannerPageModel(dayCodes, loaded = false, summary = emptyList(), cues = emptyMap())
        } else {
            val cues = dayCodes.asSequence()
                .flatMap { plannerState.eventsByDayCode[it].orEmpty().asSequence() }
                .mapNotNull { event ->
                    movedCue(event, plannerState.roomSeriesAllDayById, dayCodes)?.let { event.stableKey to it }
                }
                .toMap()
            val summary = differentThisWeek(
                dayCodes, plannerState.eventsByDayCode, todayDayCode,
                plannerState.showMultiDayTimedInAllDayStrip, plannerState.roomSeriesAllDayById,
            )
            PlannerPageModel(dayCodes, loaded = true, summary = summary, cues = cues)
        }
    }
    val showStrip = plannerState?.showMultiDayTimedInAllDayStrip ?: false

    Column(
        modifier = Modifier.fillMaxSize().padding(start = PAGE_PADDING, end = PAGE_PADDING, bottom = PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(BOX_GAP),
    ) {
        for (row in 0 until shape.rows) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(BOX_GAP),
            ) {
                for (column in 0 until shape.columns) {
                    val index = row * shape.columns + column
                    val cellModifier = Modifier.weight(1f).fillMaxHeight()
                    if (index < days.size) {
                        val dayCode = model.dayCodes[index]
                        PlannerDayBox(
                            date = days[index],
                            dayCode = dayCode,
                            events = plannerState?.eventsByDayCode?.get(dayCode).orEmpty(),
                            loaded = model.loaded,
                            showStrip = showStrip,
                            cues = model.cues,
                            todayDayCode = todayDayCode,
                            nowMs = nowMs,
                            timePattern = timePattern,
                            showEventEmojis = showEventEmojis,
                            zoom = zoom,
                            formats = formats,
                            onEmptyDayTap = onEmptyDayTap,
                            onDayClick = onDayClick,
                            onDayHeaderClick = onDayHeaderClick,
                            modifier = cellModifier,
                        )
                    } else {
                        PlannerSummaryBox(
                            items = model.summary,
                            loaded = model.loaded,
                            showEventEmojis = showEventEmojis,
                            zoom = zoom,
                            nowMs = nowMs,
                            formats = formats,
                            onDayClick = onDayClick,
                            modifier = cellModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannerDayBox(
    date: LocalDate,
    dayCode: Int,
    events: List<DisplayEvent>,
    loaded: Boolean,
    showStrip: Boolean,
    cues: Map<String, MovedCue>,
    todayDayCode: Int,
    nowMs: Long,
    timePattern: String,
    showEventEmojis: Boolean,
    zoom: Float,
    formats: PlannerFormats,
    onEmptyDayTap: (Long) -> Unit,
    onDayClick: (Long) -> Unit,
    onDayHeaderClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isToday = dayCode == todayDayCode
    val isPastDay = dayCode < todayDayCode
    // Strip items first, busy before free as in the month view, then timed rows by start.
    val (strip, rows) = remember(events, showStrip) {
        val (strip, rows) = events.partition { it.isStripItem(showStrip) }
        strip.sortedWith(compareBy<DisplayEvent> { it.isFree }.thenBy { it.startTs }) to rows.sortedBy { it.startTs }
    }
    val todayLabel = stringResource(R.string.label_today)

    Column(
        modifier = modifier
            .testTag("planner_day_$dayCode")
            .clip(BOX_SHAPE)
            .background(colors.surfaceContainerLow)
            .border(if (isToday) 2.dp else 1.dp, if (isToday) colors.primary else colors.outlineVariant, BOX_SHAPE),
    ) {
        Zoomed(zoom) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isToday) colors.primaryContainer else Color.Transparent)
                    .clickable { onDayHeaderClick(date) }
                    .then(if (isToday) Modifier.semantics { stateDescription = todayLabel } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .alpha(if (isPastDay) 0.6f else 1f),
            ) {
                Text(
                    text = formats.dayLabel(date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    // Weekend days in the weekend colour, as the agenda week bar shows them;
                    // today's fill wins: error has no contrast pairing with primaryContainer.
                    color = when {
                        isToday -> colors.onPrimaryContainer
                        WeekViewUtils.isWeekend(date) -> colors.error
                        else -> colors.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Zoomed(zoom) {
                PlannerDayBody(
                    dayCode = dayCode,
                    strip = strip,
                    rows = rows,
                    loaded = loaded,
                    isPastDay = isPastDay,
                    cues = cues,
                    todayDayCode = todayDayCode,
                    nowMs = nowMs,
                    timePattern = timePattern,
                    showEventEmojis = showEventEmojis,
                    formats = formats,
                    onEmptyDayTap = onEmptyDayTap,
                    onDayClick = onDayClick,
                )
            }
        }
    }
}

@Composable
private fun PlannerDayBody(
    dayCode: Int,
    strip: List<DisplayEvent>,
    rows: List<DisplayEvent>,
    loaded: Boolean,
    isPastDay: Boolean,
    cues: Map<String, MovedCue>,
    todayDayCode: Int,
    nowMs: Long,
    timePattern: String,
    showEventEmojis: Boolean,
    formats: PlannerFormats,
    onEmptyDayTap: (Long) -> Unit,
    onDayClick: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dayMs = { plannerDayMs(dayCode, nowMs) }
    val createLabel = stringResource(R.string.cd_create_event)
    val showDayLabel = stringResource(R.string.cd_planner_show_day_events)
    val eventRow: @Composable (DisplayEvent, Boolean) -> Unit = { event, oneLine ->
        PlannerEventRow(
            event = event,
            cue = if (oneLine) null else cues[event.stableKey],
            dayMs = dayMs(),
            nowMs = nowMs,
            todayDayCode = todayDayCode,
            timePattern = timePattern,
            showEventEmojis = showEventEmojis,
            formats = formats,
            oneLine = oneLine,
            onClick = { onDayClick(dayMs()) },
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !loaded -> Unit
            strip.isEmpty() && rows.isEmpty() -> {
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClickLabel = createLabel) { onEmptyDayTap(dayMs()) },
                )
                if (!isPastDay) PlannerFreeDay(Modifier.fillMaxSize().padding(4.dp))
            }
            else -> PlannerFitColumn(
                itemCount = strip.size + rows.size,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp),
                background = { hidden ->
                    // Behind the rows and the count: a box showing "+N" is full, so the little
                    // space a tap can find there opens the day rather than a new event.
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClickLabel = if (hidden > 0) showDayLabel else createLabel) {
                                if (hidden > 0) onDayClick(dayMs()) else onEmptyDayTap(dayMs())
                            },
                    )
                },
                overflow = { hidden ->
                    val moreLabel = stringResource(R.string.status_more_events, hidden)
                    // No touch target of its own: a tap on it reaches the body behind, which
                    // opens the day. Screen readers get the same action on the count itself.
                    Text(
                        text = stringResource(R.string.status_more_events_compact, hidden),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .semantics {
                                contentDescription = moreLabel
                                onClick(label = showDayLabel) { onDayClick(dayMs()); true }
                            }
                            // Inset from the box's rounded corner only, so the row beside it runs
                            // up to the gap.
                            .padding(end = 5.dp, top = 1.dp, bottom = 1.dp),
                    )
                },
                // Ribbons are one line already, so only an event row gets a one-line form.
                compactItem = { index ->
                    if (index < strip.size) {
                        null
                    } else {
                        rows.getOrNull(index - strip.size)?.let { event -> { eventRow(event, true) } }
                    }
                },
            ) { index ->
                // An index past the lists draws nothing ([PlannerFitColumn]).
                val ribbon = strip.getOrNull(index)
                if (ribbon != null) {
                    PlannerRibbon(ribbon, dayCode, showEventEmojis, nowMs, todayDayCode) { onDayClick(dayMs()) }
                } else {
                    rows.getOrNull(index - strip.size)?.let { eventRow(it, false) }
                }
            }
        }
    }
}

/**
 * Shows an empty day ahead: a sun above "Free day", so a free day reads as a reward rather than
 * a box to fill; a tap on the box adds an event. A box too short for both drops the sun; the
 * label always shows. Parts are measured through copies with no semantics, the same way as
 * [PlannerFitColumn], so only what is placed reaches a screen reader.
 */
@Composable
private fun PlannerFreeDay(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val parts: List<@Composable () -> Unit> = listOf(
        {
            Icon(
                imageVector = Icons.Outlined.WbSunny,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.testTag("planner_free_icon").size(20.dp),
            )
        },
        {
            Text(
                text = stringResource(R.string.planner_free_day),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    )
    SubcomposeLayout(modifier) { constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val gap = FREE_DAY_GAP.roundToPx()
        val heights = parts.mapIndexed { index, part ->
            subcompose("probe$index") { Box(Modifier.clearAndSetSemantics {}) { part() } }.sumOf { it.measure(loose).height }
        }
        val stackHeight = { kept: List<Int> -> kept.sumOf { heights[it] } + gap * (kept.size - 1) }
        val kept = FREE_DAY_CHOICES.firstOrNull { stackHeight(it) <= constraints.maxHeight } ?: FREE_DAY_CHOICES.last()
        val placeables = kept.map { index -> subcompose("part$index") { parts[index]() }.map { it.measure(loose) } }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = (constraints.maxHeight - stackHeight(kept)) / 2
            for (part in placeables) {
                for (placeable in part) placeable.place((constraints.maxWidth - placeable.width) / 2, y)
                y += part.sumOf { it.height } + gap
            }
        }
    }
}

/**
 * Draws a timed event as a time line over its title, or with [oneLine] as one line of short
 * start time and title, the form a full box's last line takes ([PlannerFitColumn]). The
 * one-line form leaves out the end time, which the day sheet shows, and [cue]; "Different this
 * week" still lists a moved event. An event that started on an earlier day shows no start time
 * there.
 */
@Composable
private fun PlannerEventRow(
    event: DisplayEvent,
    cue: MovedCue?,
    dayMs: Long,
    nowMs: Long,
    todayDayCode: Int,
    timePattern: String,
    showEventEmojis: Boolean,
    formats: PlannerFormats,
    onClick: () -> Unit,
    oneLine: Boolean = false,
) {
    val resources = LocalResources.current
    val title = remember(event, showEventEmojis) { formatDisplayEventTitle(event, showEventEmojis, resources) }
    val rangeTemplate = stringResource(R.string.planner_time_range)
    // The block reads the device zone; the key re-runs it when the zone changes.
    val zone = LocalDisplayZone.current
    val time = remember(event, dayMs, timePattern, rangeTemplate, formats, oneLine, zone) {
        if (oneLine) {
            if (event.startDay == DayPagerUtils.msToDayCode(dayMs)) {
                plannerTime(event.startTs, timePattern, formats.locale, ZoneId.systemDefault())
            } else {
                ""
            }
        } else if (event.endDay > event.startDay) {
            formatDisplayEventTimeDisplay(event, dayMs, resources, ZoneId.systemDefault(), timePattern)
        } else {
            plannerTimeRange(
                event.startTs, event.endTs, timePattern,
                { start, end -> rangeTemplate.format(start, end) }, formats.locale, ZoneId.systemDefault(),
            )
        }
    }
    val cueText = cue?.let { stringResource(R.string.planner_moved_was, movedCueLabel(it, timePattern, formats.locale, formats.weekday, formats.shortDate, ZoneId.systemDefault())) }
    val isPast = DateTimeUtils.isEventPast(event.endTs, event.endDay, event.isAllDay, nowMs, todayDayCode)
    val isDeclined = event.isDeclinedByMe
    val isCancelled = event.isCancelled
    val stateLabel = eventStateDescription(isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled)
    val barColor = Color(event.eventColor ?: event.calendarColor)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag(if (oneLine) "planner_row_one_line" else "planner_row")
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(declinedCardAlpha(isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled))
            .clickable(onClickLabel = stringResource(R.string.cd_planner_show_day_events), onClick = onClick)
            .semantics(mergeDescendants = true) { if (stateLabel != null) stateDescription = stateLabel }
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(end = 5.dp)
                .width(3.dp)
                .fillMaxHeight()
                .clip(STRIPE_SHAPE)
                .background(barColor),
        )
        if (oneLine) {
            if (time.isNotEmpty()) {
                // Ellipsized like the time line: a hard cut could show a wrong time ("10:3").
                // Not weighted: a weighted time would cap the title at a share of the row.
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            ClippedTitle(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = declinedTitleDecoration(isDeclined, isCancelled),
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (cueText != null) {
                        Text(
                            text = cueText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ClippedTitle(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = declinedTitleDecoration(isDeclined, isCancelled),
                )
            }
        }
    }
}

@Composable
private fun PlannerRibbon(
    event: DisplayEvent,
    dayCode: Int,
    showEventEmojis: Boolean,
    nowMs: Long,
    todayDayCode: Int,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val title = remember(event, showEventEmojis) { formatDisplayEventTitle(event, showEventEmojis, resources) }
    val style = remember(event) { spanStyleFor(event) }
    val leftFlush = event.startDay < dayCode
    val rightFlush = event.endDay > dayCode
    val shape = remember(leftFlush, rightFlush) {
        RoundedCornerShape(
            topStart = if (leftFlush) 0.dp else RIBBON_CORNER,
            bottomStart = if (leftFlush) 0.dp else RIBBON_CORNER,
            topEnd = if (rightFlush) 0.dp else RIBBON_CORNER,
            bottomEnd = if (rightFlush) 0.dp else RIBBON_CORNER,
        )
    }
    val isPast = DateTimeUtils.isEventPast(event.endTs, event.endDay, event.isAllDay, nowMs, todayDayCode)
    val isDeclined = event.isDeclinedByMe
    val isCancelled = event.isCancelled
    val stateLabel = eventStateDescription(isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled)
    val (fill, border, textColor) = when (style) {
        is SpanStyle.AllDayBusy -> Triple(Color(style.fillColor), null, style.textColor)
        is SpanStyle.AllDayFree -> Triple(style.tintFill, Color(style.borderColor), MaterialTheme.colorScheme.onSurface)
        is SpanStyle.TimedSpan -> Triple(style.tintFill, null, MaterialTheme.colorScheme.onSurface)
    }
    Box(
        modifier = Modifier
            .testTag("planner_row")
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .alpha(declinedCardAlpha(isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled))
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClickLabel = stringResource(R.string.cd_planner_show_day_events), onClick = onClick)
            .semantics(mergeDescendants = true) { if (stateLabel != null) stateDescription = stateLabel },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (style is SpanStyle.TimedSpan && !leftFlush) {
                Box(modifier = Modifier.width(3.dp).height(18.dp).background(Color(style.stripeColor)))
            }
            ClippedTitle(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textDecoration = declinedTitleDecoration(isDeclined, isCancelled),
                modifier = Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PlannerSummaryBox(
    items: List<PlannerSummaryItem>,
    loaded: Boolean,
    showEventEmojis: Boolean,
    zoom: Float,
    nowMs: Long,
    formats: PlannerFormats,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .testTag("planner_summary")
            .clip(BOX_SHAPE)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, BOX_SHAPE),
    ) {
        Zoomed(zoom) {
            Text(
                text = stringResource(R.string.planner_different_this_week),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Zoomed(zoom) {
                when {
                    !loaded -> Unit
                    items.isEmpty() -> Text(
                        text = stringResource(R.string.planner_usual_week),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    // Lines that don't fit are cut without a count: each is also in its day box.
                    else -> PlannerFitColumn(
                        itemCount = items.size,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp),
                        // The FAB doesn't zoom, so the band kept clear for it mustn't either.
                        reserveEnd = FAB_CLEAR / zoom,
                        reserveBottom = FAB_CLEAR / zoom,
                    ) { index ->
                        // An index past the list draws nothing ([PlannerFitColumn]).
                        items.getOrNull(index)?.let { item ->
                            PlannerSummaryLine(item, showEventEmojis, formats) {
                                onDayClick(plannerDayMs(item.firstDayCode, nowMs))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannerSummaryLine(
    item: PlannerSummaryItem,
    showEventEmojis: Boolean,
    formats: PlannerFormats,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val event = item.event
    val title = remember(event, showEventEmojis) { formatDisplayEventTitle(event, showEventEmojis, resources) }
    val first = formats.weekday.format(DayPagerUtils.dayCodeToLocalDate(item.firstDayCode))
    val days = if (item.lastDayCode == item.firstDayCode) {
        first
    } else {
        stringResource(R.string.planner_day_range, first, formats.weekday.format(DayPagerUtils.dayCodeToLocalDate(item.lastDayCode)))
    }
    val changed = item.kind != PlannerSummaryKind.STRIP
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("planner_summary_line").fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(end = 5.dp)
                .width(3.dp)
                .height(14.dp)
                .clip(STRIPE_SHAPE)
                .background(Color(event.eventColor ?: event.calendarColor)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
            color = if (changed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            textDecoration = declinedTitleDecoration(isDeclined = false, isCancelled = item.kind == PlannerSummaryKind.CANCELLED),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = days,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Stacks [itemCount] items from the top inside [contentPadding], showing only those that fit
 * whole ([fitCount]). When some don't and [compactItem] gives the first hidden one a one-line
 * form that fits the space left, that form shows last. When some still don't and [overflow] is
 * given, `overflow(hiddenCount)` sits in the bottom-end corner and the items level with it are
 * measured narrower by its width and a small gap, so it takes no line of its own. When no
 * count shows, items whose bottom would enter the bottom [reserveBottom] band are measured
 * [reserveEnd] narrower instead, keeping that corner free (the FAB sits over it). Items are
 * measured, so a larger font or zoom shows fewer of them and none is clipped. [background]
 * fills the whole area, padding included, under the items, and learns how many are hidden.
 *
 * Heights come from measuring copies with their semantics cleared, only up to the first item
 * that doesn't fit; only the items shown are composed for real, so a screen reader never reads
 * a row the user can't see.
 *
 * [item] must draw nothing for an index past the current items: when the list shrinks, a slot
 * left from the longer list can recompose once before this measure pass drops it, and a lookup
 * that throws there crashes the app (`a box that loses events while shown keeps working`).
 */
@Composable
private fun PlannerFitColumn(
    itemCount: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reserveEnd: Dp = 0.dp,
    reserveBottom: Dp = 0.dp,
    background: (@Composable (hidden: Int) -> Unit)? = null,
    overflow: (@Composable (hidden: Int) -> Unit)? = null,
    compactItem: ((index: Int) -> (@Composable () -> Unit)?)? = null,
    item: @Composable (index: Int) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val start = contentPadding.calculateStartPadding(layoutDirection).roundToPx()
        val top = contentPadding.calculateTopPadding().roundToPx()
        val width = (constraints.maxWidth - start - contentPadding.calculateEndPadding(layoutDirection).roundToPx())
            .coerceAtLeast(0)
        val height = (constraints.maxHeight - top - contentPadding.calculateBottomPadding().roundToPx()).coerceAtLeast(0)
        val loose = Constraints(maxWidth = width)
        val heights = mutableListOf<Int>()
        var used = 0
        while (heights.size < itemCount && used <= height) {
            val index = heights.size
            val probe = subcompose("probe$index") { Box(Modifier.clearAndSetSemantics {}) { item(index) } }
                .sumOf { it.measure(loose).height }
            heights += probe
            used += probe
        }
        val shown = fitCount(heights, height)
        val shownHeight = heights.take(shown).sum()
        // The first hidden item in its one-line form, when that fits the space left.
        val compact = (if (shown < itemCount) compactItem?.invoke(shown) else null)?.let { content ->
            val probeHeight = subcompose("compactProbe") { Box(Modifier.clearAndSetSemantics {}) { content() } }
                .sumOf { it.measure(loose).height }
            (content to probeHeight).takeIf { shownHeight + probeHeight <= height }
        }
        val hidden = itemCount - shown - if (compact != null) 1 else 0
        val overflowParts = if (overflow != null && hidden > 0) {
            subcompose("overflow") { overflow(hidden) }.map { it.measure(loose) }
        } else {
            emptyList()
        }
        val overflowWidth = overflowParts.maxOfOrNull { it.width } ?: 0
        val overflowHeight = overflowParts.sumOf { it.height }
        // The corner kept clear: the count's when it shows, otherwise the reserved band's.
        val (bandTop, bandEnd) = if (overflowParts.isNotEmpty()) {
            height - overflowHeight to overflowWidth + OVERFLOW_GAP.roundToPx()
        } else {
            height - reserveBottom.roundToPx() to reserveEnd.roundToPx()
        }
        val narrow = Constraints(maxWidth = (width - bandEnd).coerceAtLeast(0))
        val placed = mutableListOf<Pair<Int, List<Placeable>>>()
        var y = 0
        for (index in 0 until shown) {
            val inBand = bandEnd > 0 && y + heights[index] > bandTop
            val parts = subcompose("item$index") { item(index) }
                .map { it.measure(if (inBand) narrow else loose) }
            placed += y to parts
            y += parts.sumOf { it.height }
        }
        compact?.let { (content, compactHeight) ->
            val inBand = bandEnd > 0 && y + compactHeight > bandTop
            placed += y to subcompose("compact") { content() }.map { it.measure(if (inBand) narrow else loose) }
        }
        val backgroundParts = background?.let { slot ->
            subcompose("background") { slot(hidden) }
                .map { it.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight)) }
        }.orEmpty()
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Placed first, so it draws under the items and only gets the taps they miss.
            backgroundParts.forEach { it.placeRelative(0, 0) }
            for ((itemTop, parts) in placed) {
                var partTop = top + itemTop
                for (part in parts) {
                    part.placeRelative(start, partTop)
                    partTop += part.height
                }
            }
            var overflowTop = top + height - overflowHeight
            for (part in overflowParts) {
                part.placeRelative(start + width - part.width, overflowTop)
                overflowTop += part.height
            }
        }
    }
}

private val PAGE_PADDING = 8.dp
private val BOX_GAP = 6.dp
private val BOX_SHAPE = RoundedCornerShape(14.dp)
private val RIBBON_CORNER = 4.dp
private val STRIPE_SHAPE = RoundedCornerShape(1.dp)
private val OVERFLOW_GAP = 4.dp
private val FREE_DAY_GAP = 2.dp

/** The free-day parts kept, by index into its parts, from most to fewest. */
private val FREE_DAY_CHOICES = listOf(listOf(0, 1), listOf(1))

/**
 * Room kept clear at the summary box's bottom-end corner, where the FAB sits: a 56dp FAB with
 * its 16dp margin reaches 72dp in from the screen's bottom and end edges, and the page padding
 * covers the rest.
 */
private val FAB_CLEAR = 72.dp - PAGE_PADDING
