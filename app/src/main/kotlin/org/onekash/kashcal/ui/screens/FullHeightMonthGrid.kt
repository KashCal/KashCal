package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.model.MonthGrid
import java.util.Calendar as JavaCalendar

/**
 * Full-height month grid with event title snippets in day cells.
 *
 * Replaces the compact CalendarGrid + DayEventsPager with a Google Calendar-style
 * month view where the grid fills available space and events appear directly in cells.
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
    @Suppress("UNUSED_PARAMETER") refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val monthGrid = remember(year, month, firstDayOfWeekPref) {
        MonthGrid.compute(year, month, firstDayOfWeekPref)
    }

    val today = remember(refreshKey) { JavaCalendar.getInstance() }
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDate }
    val selectedInThisMonth = selectedCal.get(JavaCalendar.MONTH) == month &&
        selectedCal.get(JavaCalendar.YEAR) == year

    // Count visible rows (skip all-OutDate rows)
    val visibleWeeks = remember(monthGrid) {
        monthGrid.weeks.filter { row ->
            row.any { it.position == MonthGrid.DayPosition.MonthDate }
        }
    }

    Column(modifier = modifier.padding(horizontal = 8.dp)) {
        visibleWeeks.forEach { row ->
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
                row.forEach { cell ->
                    val dayCode = remember(cell, year, month) {
                        MonthGrid.computeDayCodeForCell(cell, year, month)
                    }
                    val events = remember(monthEventsMap, dayCode) {
                        monthEventsMap[dayCode]
                    }
                    val isInOutDate = cell.position != MonthGrid.DayPosition.MonthDate

                    FullHeightDayCell(
                        cell = cell,
                        year = year,
                        month = month,
                        dayCode = dayCode,
                        events = events,
                        isToday = cell.position == MonthGrid.DayPosition.MonthDate &&
                            cell.dayOfMonth == today.get(JavaCalendar.DAY_OF_MONTH) &&
                            month == today.get(JavaCalendar.MONTH) &&
                            year == today.get(JavaCalendar.YEAR),
                        isSelected = when (cell.position) {
                            MonthGrid.DayPosition.MonthDate ->
                                selectedInThisMonth && cell.dayOfMonth == selectedCal.get(JavaCalendar.DAY_OF_MONTH)
                            else -> false
                        },
                        isInOutDate = isInOutDate,
                        showEventEmojis = showEventEmojis,
                        onDateSelected = onDateSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FullHeightDayCell(
    cell: MonthGrid.DayCell,
    year: Int,
    month: Int,
    dayCode: Int,
    events: ImmutableList<DisplayEvent>?,
    isToday: Boolean,
    isSelected: Boolean,
    isInOutDate: Boolean,
    showEventEmojis: Boolean,
    onDateSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val eventCount = events?.size ?: 0

    Column(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
            )
            .clickable {
                val (clickYear, clickMonth) = when (cell.position) {
                    MonthGrid.DayPosition.MonthDate -> year to month
                    MonthGrid.DayPosition.InDate ->
                        if (month == 0) (year - 1) to 11 else year to (month - 1)
                    MonthGrid.DayPosition.OutDate ->
                        if (month == 11) (year + 1) to 0 else year to (month + 1)
                }
                val clickedCal = JavaCalendar.getInstance().apply {
                    set(clickYear, clickMonth, cell.dayOfMonth)
                }
                onDateSelected(clickedCal.timeInMillis)
            }
            .padding(2.dp)
            .semantics {
                contentDescription = "Day ${cell.dayOfMonth}, $eventCount events"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Day number
        Text(
            text = cell.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                isInOutDate -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                cell.isWeekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )

        // Event snippets
        if (events != null && events.isNotEmpty()) {
            val snippetAlpha = if (isInOutDate) 0.4f else 1f
            val maxSnippets = 3
            val showOverflow = events.size > maxSnippets
            val snippetsToShow = if (showOverflow) maxSnippets - 1 else minOf(events.size, maxSnippets)

            Column(
                modifier = Modifier.alpha(snippetAlpha),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                for (i in 0 until snippetsToShow) {
                    EventSnippet(
                        displayEvent = events[i],
                        showEventEmojis = showEventEmojis
                    )
                }
                if (showOverflow) {
                    Text(
                        text = "+${events.size - snippetsToShow} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun EventSnippet(
    displayEvent: DisplayEvent,
    showEventEmojis: Boolean
) {
    val title = remember(displayEvent, showEventEmojis) {
        formatDisplayEventTitle(displayEvent, showEventEmojis)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color(displayEvent.calendarColor))
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 3.dp)
        )
    }
}
