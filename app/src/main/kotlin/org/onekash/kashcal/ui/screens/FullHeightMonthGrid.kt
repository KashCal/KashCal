package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.monthfull.clampMonthFullZoom
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateRes
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.screens.monthfull.SlotContent
import org.onekash.kashcal.ui.screens.monthfull.SnippetStyle
import org.onekash.kashcal.ui.screens.monthfull.SpanStyle
import org.onekash.kashcal.ui.screens.monthfull.WeekSlotRender
import org.onekash.kashcal.ui.screens.monthfull.WeekSpan
import org.onekash.kashcal.ui.screens.monthfull.computeMonthFullWeekRender
import org.onekash.kashcal.ui.screens.monthfull.dayEventCount
import org.onekash.kashcal.ui.screens.monthfull.monthDateNumberAlpha
import org.onekash.kashcal.ui.screens.monthfull.monthEventAlpha
import org.onekash.kashcal.ui.screens.monthfull.monthFullDayDescription
import org.onekash.kashcal.ui.screens.monthfull.monthFullFittedRows
import org.onekash.kashcal.ui.screens.monthfull.shownEvents
import org.onekash.kashcal.ui.screens.monthfull.snippetStyleFor
import org.onekash.kashcal.ui.screens.monthfull.spanStyleFor
import org.onekash.kashcal.ui.shared.ClippedTitle
import org.onekash.kashcal.ui.shared.Zoomed
import org.onekash.kashcal.ui.shared.pinchZoom
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.SelectedDay
import org.onekash.kashcal.util.DateTimeUtils
import java.util.Calendar as JavaCalendar

/**
 * Draws one month of the full-height month view: the weekday header over [FullHeightMonthGrid],
 * with a two-finger pinch on the whole page zooming the day cells within [clampMonthFullZoom];
 * one-finger gestures go to the pager and pull-to-refresh around it.
 *
 * The grid fits the screen (weighted rows), so nothing in it scrolls vertically. Material3
 * pull-to-refresh is driven by nested scroll and sees no drag unless a descendant passes
 * vertical deltas up. The page's scrollable consumes nothing, so it hands the vertical gesture
 * to pull-to-refresh without moving the grid; the pager around it takes the horizontal axis.
 */
@Composable
internal fun MonthFullPage(
    year: Int,
    month: Int,
    selectedDate: Long,
    monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    onDayTap: (dayCode: Int, tapTimeMs: Long) -> Unit,
    firstDayOfWeek: Int,
    showWeekNumbers: Boolean,
    showEventEmojis: Boolean,
    nowMs: Long,
    todayDayCode: Int,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollDonor = rememberScrollableState { 0f }
    Column(
        modifier = modifier
            .fillMaxSize()
            .scrollable(orientation = Orientation.Vertical, state = scrollDonor)
            .pinchZoom(currentZoom = { zoom }, clamp = ::clampMonthFullZoom, onZoomChange = onZoomChange),
        verticalArrangement = Arrangement.Top,
    ) {
        DayOfWeekHeaders(firstDayOfWeek = firstDayOfWeek, showWeekNumbers = showWeekNumbers)
        FullHeightMonthGrid(
            year = year,
            month = month,
            selectedDate = selectedDate,
            monthEventsMap = monthEventsMap,
            onDayTap = onDayTap,
            firstDayOfWeekPref = firstDayOfWeek,
            showWeekNumbers = showWeekNumbers,
            showEventEmojis = showEventEmojis,
            nowMs = nowMs,
            todayDayCode = todayDayCode,
            modifier = Modifier.weight(1f),
            zoom = zoom,
        )
    }
}

/**
 * Draws the full-height month grid: per week row, a date strip and as many one-line slots as fit
 * the row's height at the current font scale and [zoom], filled with multi-day bars and each
 * day's events ([computeMonthFullWeekRender]); a day with more shows a "+N" count in its
 * bottom-end corner.
 *
 * Each day cell is one tap target under everything drawn, so a tap anywhere in it, on a bar
 * included, opens that day, and one screen-reader item ([monthFullDayDescription]). The drawn
 * rows take no taps and are hidden from screen readers. A week row too short for even one slot
 * cuts the slot at its edge. Past events fade, and so do past date numbers other than the
 * selected day's.
 *
 * @param monthEventsMap events keyed by dayCode (YYYYMMDD), from HomeViewModel.monthEvents.
 * @param onDayTap called when any part of a cell is tapped, with the cell's day code (the day to
 *   select) and [tapTimeMsForCell]'s value (the day sheet's).
 * @param nowMs the clock reading past events are judged against.
 * @param todayDayCode today's day code, for the today strip and past days.
 * @param zoom scale of everything drawn in a cell; the week-number column doesn't scale.
 */
@Composable
internal fun FullHeightMonthGrid(
    year: Int,
    month: Int,
    selectedDate: Long,
    monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    onDayTap: (dayCode: Int, tapTimeMs: Long) -> Unit,
    firstDayOfWeekPref: Int,
    showWeekNumbers: Boolean,
    showEventEmojis: Boolean,
    nowMs: Long,
    todayDayCode: Int,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
) {
    val monthGrid = remember(year, month, firstDayOfWeekPref) {
        MonthGrid.compute(year, month, firstDayOfWeekPref)
    }

    // selectedDate is a device-zone midnight that HomeViewModel moves on a zone change.
    val selectedDayCode = remember(selectedDate) { DayPagerUtils.msToDayCode(selectedDate) }

    val visibleWeeks = remember(monthGrid) {
        monthGrid.weeks.filter { row ->
            row.any { it.position == MonthGrid.DayPosition.MonthDate }
        }
    }

    Column(modifier = modifier.padding(horizontal = 8.dp)) {
        visibleWeeks.forEach { row ->
            val weekDayCodes = remember(row, year, month) {
                row.map { MonthGrid.computeDayCodeForCell(it, year, month) }
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (showWeekNumbers) {
                    Box(
                        modifier = Modifier.width(24.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = row.first().weekNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                WeekRow(
                    row = row,
                    weekDayCodes = weekDayCodes,
                    year = year,
                    month = month,
                    monthEventsMap = monthEventsMap,
                    nowMs = nowMs,
                    todayDayCode = todayDayCode,
                    selectedDayCode = selectedDayCode,
                    showEventEmojis = showEventEmojis,
                    zoom = zoom,
                    onDayTap = onDayTap,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Draws one week row, zoomed: seven clickable cells underneath, then the date strip and the slot
 * rows that fit its height, clipped to the row.
 */
@Composable
private fun WeekRow(
    row: List<MonthGrid.DayCell>,
    weekDayCodes: List<Int>,
    year: Int,
    month: Int,
    monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    nowMs: Long,
    todayDayCode: Int,
    selectedDayCode: Int,
    showEventEmojis: Boolean,
    zoom: Float,
    onDayTap: (dayCode: Int, tapTimeMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showDayLabel = stringResource(R.string.cd_planner_show_day_events)
    BoxWithConstraints(modifier = modifier) {
        // Read before Zoomed: the row's height in real pixels, which zoom doesn't change.
        val rowPx = if (constraints.hasBoundedHeight) constraints.maxHeight else null

        Zoomed(zoom) {
            val density = LocalDensity.current
            val typography = MaterialTheme.typography
            // Whole pixels, so the counted rows never add up to more than the row's height.
            val stripPx = with(density) { (typography.labelMedium.lineHeight.toDp() + STRIP_PADDING * 2).roundToPx() }
            val slotPx = with(density) { (typography.labelSmall.lineHeight.toDp() + SLOT_PADDING * 2).roundToPx() }
            val gapPx = with(density) { SLOT_GAP.roundToPx() }
            val rows = rowPx?.let { monthFullFittedRows(it, stripPx, slotPx, gapPx) } ?: UNBOUNDED_ROWS
            val render = remember(weekDayCodes, monthEventsMap, rows) {
                computeMonthFullWeekRender(weekDayCodes, monthEventsMap, rows)
            }
            val isInMonth = { col: Int -> row[col].position == MonthGrid.DayPosition.MonthDate }
            val isToday = { col: Int -> isInMonth(col) && weekDayCodes[col] == todayDayCode }
            val isSelected = { col: Int -> SelectedDay.isSelectedCell(row[col].position, weekDayCodes[col], selectedDayCode) }
            val descriptions = rememberDayDescriptions(render, row, weekDayCodes, monthEventsMap, todayDayCode, showEventEmojis)

            // The tap targets, drawn first, under rows that have no pointer input: every tap
            // reaches the cell under the finger.
            Row(modifier = Modifier.matchParentSize()) {
                row.forEachIndexed { col, cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("month_cell_${weekDayCodes[col]}")
                            .semantics {
                                contentDescription = descriptions[col]
                                // Only this month's days can be selected; padding days say nothing.
                                if (isInMonth(col)) selected = isSelected(col)
                            }
                            .clickable(onClickLabel = showDayLabel) {
                                onDayTap(weekDayCodes[col], tapTimeMsForCell(cell, year, month))
                            },
                    )
                }
            }

            val stripHeight = with(density) { stripPx.toDp() }
            val slotHeight = with(density) { slotPx.toDp() }
            val gap = with(density) { gapPx.toDp() }

            // Laid out at full height from the row's top and clipped to the row, so a slot that
            // doesn't fit is cut at the week's bottom edge rather than squeezed.
            Box(modifier = Modifier.matchParentSize().clipToBounds()) {
                Column(modifier = Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)) {
                    Row(modifier = Modifier.fillMaxWidth().height(stripHeight)) {
                        row.forEachIndexed { col, cell ->
                            DayHeaderCell(
                                cell = cell,
                                isToday = isToday(col),
                                isSelected = isSelected(col),
                                isInOutDate = !isInMonth(col),
                                isPastDay = isInMonth(col) && weekDayCodes[col] < todayDayCode,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for ((slotIndex, slotRow) in render.slots.withIndex()) {
                            SlotRow(
                                slotRow = slotRow,
                                row = row,
                                // Only the bottom row carries the counts; it is never a bar.
                                hiddenCounts = if (slotIndex == render.slots.lastIndex) render.hiddenCounts else null,
                                height = slotHeight,
                                showEventEmojis = showEventEmojis,
                                isPast = { e -> DateTimeUtils.isEventPast(e.endTs, e.endDay, e.isAllDay, nowMs, todayDayCode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    slotRow: List<SlotContent>,
    row: List<MonthGrid.DayCell>,
    hiddenCounts: List<Int>?,
    height: Dp,
    showEventEmojis: Boolean,
    isPast: (DisplayEvent) -> Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth().height(height)) {
        var col = 0
        while (col < 7) {
            when (val content = slotRow[col]) {
                is SlotContent.BarSegment -> {
                    val span = content.span
                    val width = span.endCol - span.startCol + 1
                    SpanBar(
                        span = span,
                        showEventEmojis = showEventEmojis,
                        isPast = isPast(span.displayEvent),
                        modifier = Modifier
                            .weight(width.toFloat())
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp, vertical = SLOT_PADDING),
                    )
                    col = span.endCol + 1
                }
                is SlotContent.CellEvent -> {
                    val snippet: @Composable BoxScope.() -> Unit = {
                        EventSnippetSlot(
                            displayEvent = content.displayEvent,
                            showEventEmojis = showEventEmojis,
                            isInOutDate = row[col].position != MonthGrid.DayPosition.MonthDate,
                            isPast = isPast(content.displayEvent),
                        )
                    }
                    val hidden = hiddenCounts?.get(col) ?: 0
                    if (hidden > 0) {
                        CellSlot(hidden = hidden, modifier = Modifier.weight(1f), content = snippet)
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), content = snippet)
                    }
                    col++
                }
                SlotContent.Empty -> {
                    val hidden = hiddenCounts?.get(col) ?: 0
                    if (hidden > 0) {
                        CellSlot(hidden = hidden, modifier = Modifier.weight(1f)) {}
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    col++
                }
            }
        }
    }
}

/**
 * Holds one day's bottom slot when the day hides [hidden] events: the "+N" count at its end, and
 * the slot's content measured narrower by the count and a small gap. Clipped, so a count wider
 * than the cell is cut at the cell's edge.
 */
@Composable
private fun CellSlot(hidden: Int, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxHeight().clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), content = content)
        if (hidden > 0) {
            Text(
                text = stringResource(R.string.status_more_events_compact, hidden),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .semantics { hideFromAccessibility() }
                    .padding(start = OVERFLOW_GAP, end = 1.dp),
            )
        }
    }
}

@Composable
private fun SpanBar(
    span: WeekSpan,
    showEventEmojis: Boolean,
    isPast: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = rememberDisplayEventTitle(span.displayEvent, showEventEmojis)
    val shape = remember(span.leftFlush, span.rightFlush) {
        RoundedCornerShape(
            topStart = if (span.leftFlush) 0.dp else SPAN_CORNER,
            bottomStart = if (span.leftFlush) 0.dp else SPAN_CORNER,
            topEnd = if (span.rightFlush) 0.dp else SPAN_CORNER,
            bottomEnd = if (span.rightFlush) 0.dp else SPAN_CORNER,
        )
    }
    val style = remember(span.displayEvent) { spanStyleFor(span.displayEvent) }
    val isDeclined = span.displayEvent.isDeclinedByMe
    val isCancelled = span.displayEvent.isCancelled
    val barModifier = modifier
        .alpha(declinedCardAlpha(isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled))
    when (style) {
        is SpanStyle.AllDayBusy -> StyledBox(
            modifier = barModifier,
            shape = shape,
            fill = Color(style.fillColor),
            borderColor = null,
        ) {
            BarText(title, color = style.textColor, isDeclined = isDeclined, isCancelled = isCancelled)
        }
        is SpanStyle.AllDayFree -> StyledBox(
            modifier = barModifier,
            shape = shape,
            fill = style.tintFill,
            borderColor = Color(style.borderColor),
        ) {
            BarText(title, color = MaterialTheme.colorScheme.onSurface, isDeclined = isDeclined, isCancelled = isCancelled)
        }
        is SpanStyle.TimedSpan -> Row(
            modifier = barModifier
                .clip(shape)
                .background(style.tintFill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!span.leftFlush) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(Color(style.stripeColor))
                )
            }
            BarText(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                isDeclined = isDeclined,
                isCancelled = isCancelled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StyledBox(
    modifier: Modifier,
    shape: Shape,
    fill: Color,
    borderColor: Color?,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = modifier
        .clip(shape)
        .background(fill)
    Box(
        modifier = if (borderColor != null) base.border(1.dp, borderColor, shape) else base,
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

@Composable
private fun BarText(
    text: String,
    color: Color,
    isDeclined: Boolean,
    isCancelled: Boolean,
    modifier: Modifier = Modifier,
) {
    ClippedTitle(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textDecoration = declinedTitleDecoration(isDeclined, isCancelled),
        modifier = modifier.padding(horizontal = 4.dp),
        hideFromAccessibility = true,
    )
}

/**
 * Returns each column's screen-reader text ([monthFullDayDescription]): its date, "Today", the
 * day's event count, the titles it shows with a declined or cancelled state, and what it hides.
 */
@Composable
private fun rememberDayDescriptions(
    render: WeekSlotRender,
    row: List<MonthGrid.DayCell>,
    weekDayCodes: List<Int>,
    monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    todayDayCode: Int,
    showEventEmojis: Boolean,
): List<String> {
    val resources = LocalResources.current
    val todayLabel = stringResource(R.string.label_today)
    return remember(render, row, monthEventsMap, todayDayCode, showEventEmojis, resources, todayLabel) {
        weekDayCodes.mapIndexed { col, dayCode ->
            val prefix = AgendaWeekBarLogic.cellContentDescription(
                date = DayPagerUtils.dayCodeToLocalDate(dayCode),
                isToday = row[col].position == MonthGrid.DayPosition.MonthDate && dayCode == todayDayCode,
                isSelected = false,
                todayLabel = todayLabel,
                selectedLabel = "",
            )
            val count = dayEventCount(monthEventsMap[dayCode])
            val titles = render.shownEvents(col).map { event ->
                val title = formatDisplayEventTitle(event, showEventEmojis, resources)
                val state = eventStateRes(isPast = false, isDeclined = event.isDeclinedByMe, isCancelled = event.isCancelled)
                if (state != null) "$title, ${resources.getString(state)}" else title
            }
            val hidden = render.hiddenCounts[col]
            monthFullDayDescription(
                datePrefix = prefix,
                countLabel = if (count > 0) resources.getQuantityString(R.plurals.event_count, count, count) else null,
                titles = titles,
                moreLabel = if (hidden > 0) resources.getString(R.string.status_more_events, hidden) else null,
            )
        }
    }
}

@Composable
private fun rememberDisplayEventTitle(displayEvent: DisplayEvent, showEventEmojis: Boolean): String {
    val resources = LocalResources.current
    return remember(displayEvent, showEventEmojis) {
        formatDisplayEventTitle(displayEvent, showEventEmojis, resources)
    }
}

@Composable
private fun DayHeaderCell(
    cell: MonthGrid.DayCell,
    isToday: Boolean,
    isSelected: Boolean,
    isInOutDate: Boolean,
    isPastDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val stripBackground = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val numberColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        isInOutDate -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        cell.isWeekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .background(stripBackground)
            // Today's tonal fill can wash out against the surface for pale accent
            // seeds; a hairline outline keeps the strip visible on any theme
            // (selected uses primary and needs no border).
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cell.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = numberColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .alpha(monthDateNumberAlpha(isPastDay = isPastDay, isSelected = isSelected))
                .semantics { hideFromAccessibility() },
        )
    }
}

@Composable
private fun EventSnippetSlot(
    displayEvent: DisplayEvent,
    showEventEmojis: Boolean,
    isInOutDate: Boolean,
    isPast: Boolean,
) {
    val title = rememberDisplayEventTitle(displayEvent, showEventEmojis)
    val style = remember(displayEvent) { snippetStyleFor(displayEvent) }
    val isDeclined = displayEvent.isDeclinedByMe
    val isCancelled = displayEvent.isCancelled
    val baseModifier = Modifier
        .fillMaxSize()
        .alpha(monthEventAlpha(isOutDate = isInOutDate, isPast = isPast, isDeclined = isDeclined, isCancelled = isCancelled))
        .padding(horizontal = 1.dp, vertical = SLOT_PADDING)
    when (style) {
        is SnippetStyle.Stripe -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = baseModifier,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(STRIPE_SHAPE)
                    .background(Color(style.barColor))
            )
            ClippedTitle(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Unspecified,
                textDecoration = declinedTitleDecoration(isDeclined, isCancelled),
                modifier = Modifier.weight(1f).padding(start = 3.dp, end = 1.dp),
                hideFromAccessibility = true,
            )
        }
        is SnippetStyle.AllDayBusy -> StyledBox(
            modifier = baseModifier,
            shape = SPAN_SHAPE,
            fill = Color(style.fillColor),
            borderColor = null,
        ) {
            BarText(title, color = style.textColor, isDeclined = isDeclined, isCancelled = isCancelled)
        }
        is SnippetStyle.AllDayFree -> StyledBox(
            modifier = baseModifier,
            shape = SPAN_SHAPE,
            fill = style.tintFill,
            borderColor = Color(style.borderColor),
        ) {
            BarText(title, color = MaterialTheme.colorScheme.onSurface, isDeclined = isDeclined, isCancelled = isCancelled)
        }
    }
}

/**
 * Returns [cell]'s day at the current time of day, built with java.util.Calendar: the day sheet
 * shows it and its "+" passes it to the event form, which reads the start hour with Calendar, so
 * this value and that read change together. The selection takes the cell's day code instead
 * ([SelectedDay]).
 */
private fun tapTimeMsForCell(cell: MonthGrid.DayCell, gridYear: Int, gridMonth: Int): Long {
    val (clickYear, clickMonth) = when (cell.position) {
        MonthGrid.DayPosition.MonthDate -> gridYear to gridMonth
        MonthGrid.DayPosition.InDate ->
            if (gridMonth == 0) (gridYear - 1) to 11 else gridYear to (gridMonth - 1)
        MonthGrid.DayPosition.OutDate ->
            if (gridMonth == 11) (gridYear + 1) to 0 else gridYear to (gridMonth + 1)
    }
    return JavaCalendar.getInstance().apply {
        set(clickYear, clickMonth, cell.dayOfMonth)
    }.timeInMillis
}

private val SPAN_CORNER = 4.dp
private val SPAN_SHAPE = RoundedCornerShape(SPAN_CORNER)
private val STRIPE_SHAPE = RoundedCornerShape(1.dp)

// The date strip is labelMedium's line plus this above and below; a slot is labelSmall's line
// plus SLOT_PADDING above and below, and slots sit SLOT_GAP apart.
private val STRIP_PADDING = 4.dp
private val SLOT_PADDING = 1.dp
private val SLOT_GAP = 1.dp
private val OVERFLOW_GAP = 4.dp

/** Rows per cell when a week row is measured with no height limit. */
private const val UNBOUNDED_ROWS = 3
