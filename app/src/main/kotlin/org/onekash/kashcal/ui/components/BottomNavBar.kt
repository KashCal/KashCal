package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.viewmodels.CalendarViewType

/**
 * Bottom navigation bar for switching between Month and Week views.
 *
 * @param currentViewType Current active view type
 * @param onViewTypeChange Called when user taps a navigation item
 * @param modifier Modifier for the navigation bar
 */
@Composable
fun CalendarBottomNavBar(
    currentViewType: CalendarViewType,
    onViewTypeChange: (CalendarViewType) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        // Month button
        NavigationBarItem(
            selected = currentViewType == CalendarViewType.MONTH,
            onClick = { onViewTypeChange(CalendarViewType.MONTH) },
            icon = {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "Month view"
                )
            },
            label = { Text("Month") }
        )

        // Week button
        NavigationBarItem(
            selected = currentViewType == CalendarViewType.WEEK,
            onClick = { onViewTypeChange(CalendarViewType.WEEK) },
            icon = {
                Icon(
                    Icons.Default.CalendarViewWeek,
                    contentDescription = "Week view"
                )
            },
            label = { Text("Week") }
        )
    }
}

/**
 * Compact version as a row of buttons (for use in toolbar).
 */
@Composable
fun ViewTypeToggle(
    currentViewType: CalendarViewType,
    onViewTypeChange: (CalendarViewType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ViewTypeButton(
            label = "Month",
            selected = currentViewType == CalendarViewType.MONTH,
            onClick = { onViewTypeChange(CalendarViewType.MONTH) }
        )
        ViewTypeButton(
            label = "Week",
            selected = currentViewType == CalendarViewType.WEEK,
            onClick = { onViewTypeChange(CalendarViewType.WEEK) }
        )
    }
}

@Composable
private fun ViewTypeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}
