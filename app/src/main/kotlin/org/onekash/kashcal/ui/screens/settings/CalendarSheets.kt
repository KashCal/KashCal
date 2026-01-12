package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Bottom sheet for toggling calendar visibility.
 *
 * Shows all calendars with checkboxes to toggle visibility.
 * Includes Show All / Hide All actions.
 *
 * @param sheetState Material3 sheet state
 * @param calendars List of all calendars
 * @param onToggleCalendar Callback when calendar visibility changes (calendarId, isVisible)
 * @param onShowAllCalendars Callback to show all calendars
 * @param onHideAllCalendars Callback to hide all calendars
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisibleCalendarsSheet(
    sheetState: SheetState,
    calendars: List<Calendar>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAllCalendars: () -> Unit,
    onHideAllCalendars: () -> Unit,
    onDismiss: () -> Unit
) {
    val visibleCount = calendars.count { it.isVisible }
    val totalCount = calendars.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header with count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Visible Calendars",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "$visibleCount / $totalCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Show All / Hide All - Hidden from UI, callbacks preserved for future use
            // onShowAllCalendars and onHideAllCalendars are still wired but not rendered

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Calendar list
            LazyColumn {
                items(
                    items = calendars,
                    key = { it.id }
                ) { calendar ->
                    CalendarVisibilityRow(
                        calendar = calendar,
                        onToggle = { isVisible ->
                            onToggleCalendar(calendar.id, isVisible)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Single calendar row with visibility toggle.
 */
@Composable
private fun CalendarVisibilityRow(
    calendar: Calendar,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!calendar.isVisible) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Color dot
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(calendar.color))
            )
            // Calendar name
            Text(
                calendar.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Checkbox
        Checkbox(
            checked = calendar.isVisible,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Bottom sheet for selecting default calendar.
 *
 * Shows all calendars with radio-style selection.
 *
 * @param sheetState Material3 sheet state
 * @param calendars List of all calendars
 * @param currentDefaultId Current default calendar ID
 * @param onSelectDefault Callback when default calendar is selected
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultCalendarSheet(
    sheetState: SheetState,
    calendars: List<Calendar>,
    currentDefaultId: Long?,
    onSelectDefault: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                "Default Calendar",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Text(
                "New events will be created in this calendar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Calendar list
            LazyColumn {
                items(
                    items = calendars,
                    key = { it.id }
                ) { calendar ->
                    DefaultCalendarRow(
                        calendar = calendar,
                        isSelected = calendar.id == currentDefaultId,
                        onSelect = {
                            onSelectDefault(calendar.id)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Single calendar row for default selection.
 */
@Composable
private fun DefaultCalendarRow(
    calendar: Calendar,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Color dot
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(calendar.color))
            )
            // Calendar name
            Text(
                calendar.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Check mark if selected
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
