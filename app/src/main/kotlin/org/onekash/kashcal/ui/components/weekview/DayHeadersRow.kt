package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Row of day headers that syncs with the time grid pager.
 *
 * Shows day name (Mon, Tue, etc.) and date number for each visible day.
 * Weekend days (Sat, Sun) are styled in red/error color.
 * Today is highlighted with a background circle.
 *
 * @param pagerState Pager state synced with time grid
 * @param weekStartMs Start of the week in milliseconds
 * @param timeColumnWidth Width of the time labels column (for alignment)
 * @param modifier Modifier for the row
 */
@Composable
fun DayHeadersRow(
    pagerState: PagerState,
    weekStartMs: Long,
    timeColumnWidth: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()

    Row(modifier = modifier.fillMaxWidth()) {
        // Spacer for time column alignment
        Box(modifier = Modifier.width(timeColumnWidth))

        // Day headers pager (syncs with time grid)
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val columnWidth = maxWidth / 3  // Match TimeGrid's 3-day view

            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(columnWidth),  // CRITICAL: Show 3 days
                beyondViewportPageCount = 2,
                userScrollEnabled = false  // Scrolling controlled by TimeGrid
            ) { dayIndex ->
                val date = WeekViewUtils.getDateForDayIndex(weekStartMs, dayIndex)
                val isToday = date == today
                val isWeekend = WeekViewUtils.isWeekend(date)

                DayHeader(
                    date = date,
                    isToday = isToday,
                    isWeekend = isWeekend,
                    modifier = Modifier.width(columnWidth)
                )
            }
        }
    }
}

/**
 * Single day header showing day name and date.
 * Compact format: "Mon 6" with filled circle for today.
 */
@Composable
private fun DayHeader(
    date: LocalDate,
    isToday: Boolean,
    isWeekend: Boolean,
    modifier: Modifier = Modifier
) {
    // 3-letter day name (Mon, Tue, etc.) - not uppercase
    val dayName = remember(date) {
        date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
    }

    val dayNumber = date.dayOfMonth.toString()

    // Colors based on state
    val textColor = when {
        isWeekend -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Day name
        Text(
            text = dayName,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = TextAlign.Center
        )

        // Day number with optional today highlight (filled circle)
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
 * Static day headers row (not paged, shows all visible days).
 * Used when pager snapping is disabled.
 */
@Composable
fun StaticDayHeadersRow(
    weekStartMs: Long,
    currentPage: Int,
    visibleDays: Int = 3,
    timeColumnWidth: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()

    Row(modifier = modifier.fillMaxWidth()) {
        // Spacer for time column alignment
        Box(modifier = Modifier.width(timeColumnWidth))

        // Day headers
        repeat(visibleDays) { offset ->
            val dayIndex = currentPage + offset
            if (dayIndex in 0..6) {
                val date = WeekViewUtils.getDateForDayIndex(weekStartMs, dayIndex)
                val isToday = date == today
                val isWeekend = WeekViewUtils.isWeekend(date)

                DayHeader(
                    date = date,
                    isToday = isToday,
                    isWeekend = isWeekend,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Empty space for out-of-range days
                Box(modifier = Modifier.weight(1f))
            }
        }
    }
}
