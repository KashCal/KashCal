package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Week view header with navigation arrows and individual date labels.
 *
 * Shows:
 * - Previous week arrow (jumps 7 days back)
 * - Individual dates for visible days (e.g., "Jan 4  Jan 5  Jan 6")
 * - Next week arrow (jumps 7 days forward)
 *
 * Tapping the date area opens the date picker.
 * The date labels sync with the main TimeGrid pager.
 *
 * @param weekStartMs Start of the current week in milliseconds
 * @param pagerState Pager state for syncing date labels
 * @param onPreviousWeek Called when previous week arrow is tapped
 * @param onNextWeek Called when next week arrow is tapped
 * @param onDatePickerRequest Called when date area is tapped (opens date picker)
 * @param modifier Modifier for the header row
 */
@Composable
fun WeekHeader(
    weekStartMs: Long,
    pagerState: PagerState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onDatePickerRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Reserve same width as time labels column (48.dp) for alignment
    val timeColumnWidth = 48.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous week button
        IconButton(onClick = onPreviousWeek) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous week",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Spacer to align with time column (minus button width)
        Spacer(modifier = Modifier.width(timeColumnWidth - 48.dp))

        // Individual dates synced with pager
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onDatePickerRequest)
        ) {
            val columnWidth = maxWidth / 3

            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(columnWidth),
                beyondViewportPageCount = 2,
                userScrollEnabled = false  // Scrolling controlled by main pager
            ) { dayIndex ->
                Text(
                    text = WeekViewUtils.formatIndividualDate(weekStartMs, dayIndex),
                    modifier = Modifier.width(columnWidth),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Next week button
        IconButton(onClick = onNextWeek) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next week",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Compact week header for smaller screens.
 * Shows only the date range with arrows on either side.
 */
@Composable
fun CompactWeekHeader(
    weekStartMs: Long,
    pagerState: PagerState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onDatePickerRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleRange by remember(weekStartMs) {
        derivedStateOf {
            WeekViewUtils.formatHeaderRange(
                weekStartMs = weekStartMs,
                pagerPosition = pagerState.currentPage,
                visibleDays = 3
            )
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPreviousWeek) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous week"
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = visibleRange,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onDatePickerRequest)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onNextWeek) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next week"
            )
        }
    }
}
