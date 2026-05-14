package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.ui.screens.monthfull.SlotContent
import org.onekash.kashcal.ui.screens.monthfull.SnippetStyle
import org.onekash.kashcal.ui.screens.monthfull.SpanStyle
import org.onekash.kashcal.ui.screens.monthfull.WeekSpan
import org.onekash.kashcal.ui.screens.monthfull.computeMonthFullWeekRender
import org.onekash.kashcal.ui.screens.monthfull.snippetStyleFor
import org.onekash.kashcal.ui.screens.monthfull.spanStyleFor
import java.util.Calendar as JavaCalendar

/**
 * Full-height month grid with event title snippets in day cells.
 *
 * @param monthEventsMap Events grouped by dayCode (YYYYMMDD). Loaded by HomeViewModel.loadMonthEvents().
 * @param onDateSelected Called when a day cell is tapped, with the date as epoch millis
 */
@Composable
internal fun FullHeightMonthGrid(
    year: Int,
    month: Int,
    selectedDate: Long,
    monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    onDateSelected: (Long) -> Unit,
    firstDayOfWeekPref: Int,
    showWeekNumbers: Boolean,
    showEventEmojis: Boolean,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val monthGrid = remember(year, month, firstDayOfWeekPref) {
        MonthGrid.compute(year, month, firstDayOfWeekPref)
    }

    val today = remember(refreshKey) { JavaCalendar.getInstance() }
    val todayDay = today.get(JavaCalendar.DAY_OF_MONTH)
    val todayMonth = today.get(JavaCalendar.MONTH)
    val todayYear = today.get(JavaCalendar.YEAR)
    val isTodayInThisMonth = todayMonth == month && todayYear == year

    val selectedCal = remember(selectedDate) {
        JavaCalendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val selectedDay = selectedCal.get(JavaCalendar.DAY_OF_MONTH)
    val selectedInThisMonth = selectedCal.get(JavaCalendar.MONTH) == month &&
        selectedCal.get(JavaCalendar.YEAR) == year

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
            val weekRender = remember(weekDayCodes, monthEventsMap) {
                computeMonthFullWeekRender(weekDayCodes, monthEventsMap)
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
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { cell ->
                            val isInOutDate = cell.position != MonthGrid.DayPosition.MonthDate
                            val isToday = cell.position == MonthGrid.DayPosition.MonthDate &&
                                isTodayInThisMonth &&
                                cell.dayOfMonth == todayDay
                            val isSelected = cell.position == MonthGrid.DayPosition.MonthDate &&
                                selectedInThisMonth && cell.dayOfMonth == selectedDay
                            DayHeaderCell(
                                cell = cell,
                                year = year,
                                month = month,
                                isToday = isToday,
                                isSelected = isSelected,
                                isInOutDate = isInOutDate,
                                onDateSelected = onDateSelected,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        for (slotRow in weekRender.slots) {
                            SlotRow(
                                slotRow = slotRow,
                                row = row,
                                year = year,
                                month = month,
                                showEventEmojis = showEventEmojis,
                                onDateSelected = onDateSelected,
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
    year: Int,
    month: Int,
    showEventEmojis: Boolean,
    onDateSelected: (Long) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        var col = 0
        while (col < 7) {
            val content = slotRow[col]
            when (content) {
                is SlotContent.BarSegment -> {
                    val span = content.span
                    val width = span.endCol - span.startCol + 1
                    SpanBar(
                        span = span,
                        showEventEmojis = showEventEmojis,
                        modifier = Modifier
                            .weight(width.toFloat())
                            .padding(horizontal = 1.dp, vertical = 1.dp),
                    )
                    col = span.endCol + 1
                }
                is SlotContent.CellEvent -> {
                    val cell = row[col]
                    val isInOutDate = cell.position != MonthGrid.DayPosition.MonthDate
                    EventSnippetSlot(
                        displayEvent = content.displayEvent,
                        showEventEmojis = showEventEmojis,
                        isInOutDate = isInOutDate,
                        onClick = { onDateSelected(timeMsForCell(cell, year, month)) },
                        modifier = Modifier.weight(1f),
                    )
                    col++
                }
                is SlotContent.Overflow -> {
                    val cell = row[col]
                    OverflowSlot(
                        count = content.count,
                        onClick = { onDateSelected(timeMsForCell(cell, year, month)) },
                        modifier = Modifier.weight(1f),
                    )
                    col++
                }
                SlotContent.Empty -> {
                    val cell = row[col]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDateSelected(timeMsForCell(cell, year, month)) },
                    )
                    col++
                }
            }
        }
    }
}

@Composable
private fun SpanBar(
    span: WeekSpan,
    showEventEmojis: Boolean,
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
    when (style) {
        is SpanStyle.AllDayBusy -> StyledBox(
            modifier = modifier,
            shape = shape,
            fill = Color(style.fillColor),
            borderColor = null,
        ) {
            BarText(title, color = style.textColor)
        }
        is SpanStyle.AllDayFree -> StyledBox(
            modifier = modifier,
            shape = shape,
            fill = style.tintFill,
            borderColor = Color(style.borderColor),
        ) {
            BarText(title, color = MaterialTheme.colorScheme.onSurface)
        }
        is SpanStyle.TimedSpan -> Box(
            modifier = modifier
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(style.tintFill),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!span.leftFlush) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(Color(style.stripeColor))
                    )
                }
                BarText(title, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StyledBox(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
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
private fun BarText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
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
    year: Int,
    month: Int,
    isToday: Boolean,
    isSelected: Boolean,
    isInOutDate: Boolean,
    onDateSelected: (Long) -> Unit,
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
            .clickable { onDateSelected(timeMsForCell(cell, year, month)) }
            .background(stripBackground)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cell.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = numberColor,
        )
    }
}

@Composable
private fun EventSnippetSlot(
    displayEvent: DisplayEvent,
    showEventEmojis: Boolean,
    isInOutDate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = rememberDisplayEventTitle(displayEvent, showEventEmojis)
    val style = remember(displayEvent) { snippetStyleFor(displayEvent) }
    val rowAlpha = if (isInOutDate) 0.4f else 1f
    val slotHeight = slotHeight()
    val baseModifier = modifier
        .alpha(rowAlpha)
        .clickable(onClick = onClick)
        .padding(horizontal = 1.dp)
    when (style) {
        is SnippetStyle.Stripe -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = baseModifier.height(slotHeight),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(STRIPE_SHAPE)
                    .background(Color(style.barColor))
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 3.dp, end = 1.dp)
            )
        }
        is SnippetStyle.AllDayBusy -> StyledBox(
            modifier = baseModifier.height(slotHeight),
            shape = SPAN_SHAPE,
            fill = Color(style.fillColor),
            borderColor = null,
        ) {
            BarText(title, color = style.textColor)
        }
        is SnippetStyle.AllDayFree -> StyledBox(
            modifier = baseModifier.height(slotHeight),
            shape = SPAN_SHAPE,
            fill = style.tintFill,
            borderColor = Color(style.borderColor),
        ) {
            BarText(title, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun OverflowSlot(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(slotHeight()).clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.status_more_events, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun timeMsForCell(cell: MonthGrid.DayCell, gridYear: Int, gridMonth: Int): Long {
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

/**
 * Slot height tracks `labelSmall`'s line height (16sp) so descenders fit
 * at any user font scale (accessibility settings). Returns the lineHeight
 * resolved to dp at the current density.
 */
@Composable
private fun slotHeight(): androidx.compose.ui.unit.Dp {
    val lineHeight = MaterialTheme.typography.labelSmall.lineHeight
    return with(androidx.compose.ui.platform.LocalDensity.current) { lineHeight.toDp() }
}
