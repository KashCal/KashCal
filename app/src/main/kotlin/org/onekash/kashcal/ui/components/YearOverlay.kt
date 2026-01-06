package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Year overlay - 12-month picker modal for quick navigation.
 * Tap month header (e.g., "January 2025") to open, tap any month to navigate.
 *
 * Features:
 * - 4x3 grid of months
 * - Year navigation arrows
 * - Highlights currently viewing month (primary color)
 * - Highlights today's month (primaryContainer)
 * - "Go to Today" button
 *
 * @param visible Whether the overlay is visible
 * @param currentYear The year currently being viewed in the calendar
 * @param currentMonth The month currently being viewed (0-indexed, January = 0)
 * @param onMonthSelected Callback when a month is selected (year, month)
 * @param onDismiss Callback when the overlay should be dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearOverlay(
    visible: Boolean,
    currentYear: Int,
    currentMonth: Int,
    onMonthSelected: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    // Track the displayed year (can navigate through years within the overlay)
    // Key by currentYear so it resets when the viewing year changes externally
    var displayedYear by remember(currentYear) { mutableIntStateOf(currentYear) }

    // Today's year and month for highlighting
    val today = remember {
        Calendar.getInstance().let {
            it.get(Calendar.YEAR) to it.get(Calendar.MONTH)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .semantics { contentDescription = "Year picker overlay" }
        ) {
            // Header with year navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { displayedYear-- },
                    modifier = Modifier.semantics {
                        contentDescription = "Previous year"
                    }
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = null)
                }

                Text(
                    text = displayedYear.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = { displayedYear++ },
                    modifier = Modifier.semantics {
                        contentDescription = "Next year"
                    }
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4x3 grid of months
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(12) { monthIndex ->
                    val isCurrentlyViewing = displayedYear == currentYear && monthIndex == currentMonth
                    val isToday = displayedYear == today.first && monthIndex == today.second

                    MonthCell(
                        month = monthIndex,
                        isSelected = isCurrentlyViewing,
                        isToday = isToday,
                        onClick = { onMonthSelected(displayedYear, monthIndex) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // "Go to Today" button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { onMonthSelected(today.first, today.second) }
                ) {
                    Text("Go to Today")
                }
            }
        }
    }
}

/**
 * Individual month cell in the year overlay grid.
 *
 * @param month 0-indexed month (January = 0)
 * @param isSelected True if this is the currently viewing month
 * @param isToday True if this month contains today's date
 * @param onClick Callback when the cell is clicked
 */
@Composable
private fun MonthCell(
    month: Int,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit
) {
    // Get abbreviated month name (Jan, Feb, etc.)
    val monthName = remember(month) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.MONTH, month)
        }
        SimpleDateFormat("MMM", Locale.getDefault()).format(calendar.time)
    }

    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$monthName month selector" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = monthName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}
