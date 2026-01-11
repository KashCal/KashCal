package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp

/**
 * Week view header with navigation arrows and tappable date range.
 *
 * Shows:
 * - Previous week arrow (jumps 7 days back)
 * - Current visible date range (e.g., "Jan 6-8" or "Jan 6-8, 2027")
 * - Next week arrow (jumps 7 days forward)
 *
 * Tapping the date range opens the date picker.
 *
 * @param weekStartMs Start of the current week in milliseconds
 * @param pagerState Pager state for deriving visible range
 * @param onPreviousWeek Called when previous week arrow is tapped
 * @param onNextWeek Called when next week arrow is tapped
 * @param onDatePickerRequest Called when date range is tapped (opens date picker)
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
    // Calculate visible date range based on pager position
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Previous week button
        IconButton(onClick = onPreviousWeek) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous week",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Tappable date range
        Row(
            modifier = Modifier
                .clickable(onClick = onDatePickerRequest)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = visibleRange,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
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
