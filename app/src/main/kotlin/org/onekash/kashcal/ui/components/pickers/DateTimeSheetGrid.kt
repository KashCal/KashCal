package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.CalendarSpan
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Names where a day sits in an all-day event's shaded range. */
enum class RangePart {
    NONE,
    START,
    MIDDLE,
    END
}

/** Returns [date]'s place in [range]; a one-day range or no range is [RangePart.NONE]. */
fun rangePart(date: LocalDate, range: ClosedRange<LocalDate>?): RangePart = when {
    range == null || range.start >= range.endInclusive -> RangePart.NONE
    date == range.start -> RangePart.START
    date == range.endInclusive -> RangePart.END
    date > range.start && date < range.endInclusive -> RangePart.MIDDLE
    else -> RangePart.NONE
}

/** Names the look of one day in the date and time sheet's grid. */
internal enum class SheetDayLook {
    PLAIN,
    IN_RANGE,
    TODAY,
    RANGE_END,
    SELECTED
}

/**
 * Returns a day's look, by precedence: the edited day, then the range end not being edited, then
 * today, then a day inside the range, else plain.
 */
internal fun sheetDayLook(isSelected: Boolean, isToday: Boolean, part: RangePart): SheetDayLook = when {
    isSelected -> SheetDayLook.SELECTED
    part == RangePart.START || part == RangePart.END -> SheetDayLook.RANGE_END
    isToday -> SheetDayLook.TODAY
    part == RangePart.MIDDLE -> SheetDayLook.IN_RANGE
    else -> SheetDayLook.PLAIN
}

/** Returns whether a weekend day's number takes the weekend red: only on unfilled days. */
internal fun sheetDayTextIsWeekend(look: SheetDayLook, isWeekend: Boolean): Boolean =
    isWeekend && (look == SheetDayLook.PLAIN || look == SheetDayLook.IN_RANGE)

/**
 * Returns the week's days in display order from [firstDayOfWeek] (a `Calendar` day constant),
 * each with whether it is a weekend day: Saturday or Sunday, as the month view decides it.
 */
internal fun sheetWeekdays(firstDayOfWeek: Int): List<Pair<DayOfWeek, Boolean>> =
    DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map { day ->
        day to (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)
    }

/**
 * Returns the range band's start and width as fractions of a day cell's width, or null outside a
 * range. An end day carries the band on its half toward the rest of the range, which is the
 * leading side for the end and the trailing side for the start, mirrored right to left.
 */
internal fun bandSpan(part: RangePart, direction: LayoutDirection): Pair<Float, Float>? {
    val rtl = direction == LayoutDirection.Rtl
    return when (part) {
        RangePart.NONE -> null
        RangePart.MIDDLE -> 0f to 1f
        RangePart.START -> (if (rtl) 0f else 0.5f) to 0.5f
        RangePart.END -> (if (rtl) 0.5f else 0f) to 0.5f
    }
}

private val SheetCellShape = RoundedCornerShape(8.dp)
private const val SHEET_BAND_ALPHA = 0.45f
private const val SHEET_TODAY_FILL_ALPHA = 0.35f

/**
 * Shows the date and time sheet's month grid in the month view's style: the month name and
 * chevron as a button that opens the month and year wheel, short weekday names with the weekend in
 * red, and rounded-square days. The edited day takes the inverse fill; an all-day event's [range]
 * is shaded with its other end outlined; today, when it is neither the edited day nor a range end,
 * has a faint fill ([sheetDayLook] holds the precedence). Every day is a 48dp target and six week
 * rows are always reserved, so the grid is header + [weekdayRowHeight] + 6 x 48dp tall whatever
 * the month, the month and year wheel included.
 *
 * Picks and month changes go to [onSelectDate] and [onMonth] as plain values.
 */
@Composable
internal fun DateTimeSheetGrid(
    editedDate: LocalDate,
    displayedMonth: YearMonth,
    range: ClosedRange<LocalDate>?,
    weekdayRowHeight: Dp,
    firstDayOfWeek: Int,
    showMonthYearPicker: Boolean,
    onShowMonthYearPickerChange: (Boolean) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    // Re-sample today whenever the screen resumes, so the marker can't go stale when the sheet
    // stays open across midnight or the app is backgrounded.
    var todayRefreshKey by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        todayRefreshKey++
        onPauseOrDispose { }
    }
    val today = remember(todayRefreshKey) { LocalDate.now() }
    // The swipe handler keeps running across recompositions, so it reads the latest callback.
    val currentOnMonth by rememberUpdatedState(onMonth)
    var totalDrag by remember { mutableFloatStateOf(0f) }
    // The arrows and swipes stop at the CalendarSpan's ends; from a month outside it (an
    // existing event's) they only move toward it.
    val canGoBack = CalendarSpan.canStepMonth(displayedMonth, -1, today)
    val canGoForward = CalendarSpan.canStepMonth(displayedMonth, 1, today)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .then(
                if (!showMonthYearPicker) Modifier.pointerInput(displayedMonth, canGoBack, canGoForward) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag > 100f && canGoBack) currentOnMonth(displayedMonth.minusMonths(1))
                            else if (totalDrag < -100f && canGoForward) currentOnMonth(displayedMonth.plusMonths(1))
                        },
                        onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
                    )
                } else Modifier
            )
    ) {
        SheetMonthHeader(
            displayedMonth = displayedMonth,
            showMonthYearPicker = showMonthYearPicker,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onShowMonthYearPickerChange = onShowMonthYearPickerChange,
            onMonth = onMonth,
        )
        Box(modifier = Modifier.height(weekdayRowHeight + SHEET_DAY_CELL_DP.dp * 6)) {
            Crossfade(
                targetState = showMonthYearPicker,
                animationSpec = tween(200),
                label = "sheet-calendar-wheel-crossfade"
            ) { showWheels ->
                if (showWheels) {
                    MonthYearWheelPicker(
                        selectedYear = displayedMonth.year,
                        selectedMonth = displayedMonth.monthValue - 1,
                        onMonthYearSelected = { year, month -> onMonth(YearMonth.of(year, month + 1)) },
                        yearRange = CalendarSpan.yearRange(today),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SheetDays(
                        editedDate = editedDate,
                        displayedMonth = displayedMonth,
                        range = range,
                        today = today,
                        weekdayRowHeight = weekdayRowHeight,
                        firstDayOfWeek = firstDayOfWeek,
                        onSelectDate = onSelectDate,
                    )
                }
            }
        }
    }
}

/**
 * Shows the month header: 48dp arrows either side of the month name and chevron, a 48dp button
 * that opens the month and year wheel; a press shows its ripple.
 */
@Composable
private fun SheetMonthHeader(
    displayedMonth: YearMonth,
    showMonthYearPicker: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onShowMonthYearPickerChange: (Boolean) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val monthYearFormat = remember(locale) {
        DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMM", locale), locale)
    }
    val monthText = displayedMonth.format(monthYearFormat)
    val cdShowCalendar = stringResource(R.string.cd_show_calendar)
    val cdPickMonthYear = stringResource(R.string.cd_pick_month_year)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SHEET_MONTH_HEADER_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!showMonthYearPicker) {
            IconButton(onClick = { onMonth(displayedMonth.minusMonths(1)) }, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    stringResource(R.string.cd_previous_month),
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onShowMonthYearPickerChange(!showMonthYearPicker) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (showMonthYearPicker) cdShowCalendar else cdPickMonthYear
                        stateDescription = monthText
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = monthText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (showMonthYearPicker) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (showMonthYearPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!showMonthYearPicker) {
            IconButton(onClick = { onMonth(displayedMonth.plusMonths(1)) }, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    stringResource(R.string.cd_next_month),
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

/** Shows the weekday names and six rows of days. */
@Composable
private fun SheetDays(
    editedDate: LocalDate,
    displayedMonth: YearMonth,
    range: ClosedRange<LocalDate>?,
    today: LocalDate,
    weekdayRowHeight: Dp,
    firstDayOfWeek: Int,
    onSelectDate: (LocalDate) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val weekdays = remember(firstDayOfWeek) { sheetWeekdays(firstDayOfWeek) }
    val weekendRed = MaterialTheme.colorScheme.error
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(weekdayRowHeight)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            weekdays.forEach { (day, isWeekend) ->
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, locale),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isWeekend) weekendRed.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
        val first = displayedMonth.atDay(1)
        val offset = weekdays.indexOfFirst { it.first == first.dayOfWeek }
        val daysInMonth = displayedMonth.lengthOfMonth()
        val bandColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = SHEET_BAND_ALPHA)
        val direction = LocalLayoutDirection.current
        val labelStarts = stringResource(R.string.label_starts)
        val labelEnds = stringResource(R.string.label_ends)
        for (week in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0..6) {
                    val dayNumber = week * 7 + column - offset + 1
                    if (dayNumber !in 1..daysInMonth) {
                        Spacer(modifier = Modifier.weight(1f).height(SHEET_DAY_CELL_DP.dp))
                        continue
                    }
                    val date = displayedMonth.atDay(dayNumber)
                    val part = rangePart(date, range)
                    val look = sheetDayLook(isSelected = date == editedDate, isToday = date == today, part = part)
                    val weekendText = sheetDayTextIsWeekend(look, weekdays[column].second)
                    val span = bandSpan(part, direction)
                    // The whole 48dp cell takes the tap; the 2dp padding and the rounded square
                    // are only drawn inside it.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(SHEET_DAY_CELL_DP.dp)
                            .then(
                                if (span == null) Modifier else Modifier.drawBehind {
                                    val inset = 2.dp.toPx()
                                    drawRect(
                                        bandColor,
                                        topLeft = Offset(size.width * span.first, inset),
                                        size = Size(size.width * span.second, size.height - 2 * inset)
                                    )
                                }
                            )
                            .semantics {
                                selected = look == SheetDayLook.SELECTED
                                when (part) {
                                    RangePart.START -> stateDescription = labelStarts
                                    RangePart.END -> stateDescription = labelEnds
                                    else -> Unit
                                }
                            }
                            .clickable { onSelectDate(date) },
                        contentAlignment = Alignment.Center
                    ) {
                        SheetDaySquare(dayNumber = dayNumber, look = look, weekendText = weekendText)
                    }
                }
            }
        }
    }
}

/** Draws one day's rounded square and number for its [look]. */
@Composable
private fun SheetDaySquare(dayNumber: Int, look: SheetDayLook, weekendText: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill = when (look) {
        SheetDayLook.SELECTED -> colors.inverseSurface
        SheetDayLook.RANGE_END -> colors.primaryContainer
        SheetDayLook.TODAY -> colors.primaryContainer.copy(alpha = SHEET_TODAY_FILL_ALPHA)
        SheetDayLook.IN_RANGE, SheetDayLook.PLAIN -> Color.Transparent
    }
    val edge = when (look) {
        SheetDayLook.RANGE_END -> Modifier.border(1.5.dp, colors.primary, SheetCellShape)
        SheetDayLook.TODAY -> Modifier.border(1.dp, colors.outlineVariant, SheetCellShape)
        else -> Modifier
    }
    val textColor = when {
        look == SheetDayLook.SELECTED -> colors.inverseOnSurface
        look == SheetDayLook.RANGE_END -> colors.onPrimaryContainer
        weekendText -> colors.error.copy(alpha = 0.8f)
        else -> colors.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(SheetCellShape)
            .background(fill)
            .then(edge),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = if (look == SheetDayLook.PLAIN || look == SheetDayLook.IN_RANGE) FontWeight.Normal
            else FontWeight.Bold
        )
    }
}
