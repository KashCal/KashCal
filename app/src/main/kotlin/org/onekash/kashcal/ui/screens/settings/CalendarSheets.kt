package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.components.CalendarSelectionMode
import org.onekash.kashcal.ui.components.GroupedCalendarList
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Bottom sheet for toggling calendar visibility with account grouping.
 *
 * Shows all calendars grouped by account with checkboxes to toggle visibility.
 *
 * @param sheetState Material3 sheet state
 * @param calendarGroups Calendars grouped by account
 * @param onToggleCalendar Callback when calendar visibility changes (calendarId, isVisible)
 * @param onShowAllCalendars Callback to show all calendars
 * @param onHideAllCalendars Callback to hide all calendars
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisibleCalendarsSheet(
    sheetState: SheetState,
    calendarGroups: List<CalendarGroup>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAllCalendars: () -> Unit,
    onHideAllCalendars: () -> Unit,
    onDismiss: () -> Unit
) {
    val allCalendars = remember(calendarGroups) {
        calendarGroups.flatMap { it.calendars }
    }
    val visibleCount = allCalendars.count { it.isVisible }
    val totalCount = allCalendars.size
    val visibleCalendarIds = remember(calendarGroups) {
        allCalendars.filter { it.isVisible }.map { it.id }.toSet()
    }

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
            Text(
                "Visible Calendars",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Text(
                "$visibleCount / $totalCount visible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Calendar list grouped by account
            if (calendarGroups.isEmpty() || allCalendars.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No calendars available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                GroupedCalendarList(
                    groups = calendarGroups,
                    selectionMode = CalendarSelectionMode.CHECKBOX,
                    selectedCalendarIds = visibleCalendarIds,
                    onCalendarClick = { calendar ->
                        onToggleCalendar(calendar.id, !calendar.isVisible)
                    }
                )
            }
        }
    }
}

/**
 * Bottom sheet for selecting default calendar with account grouping.
 *
 * Shows all calendars grouped by account with radio-style selection.
 *
 * @param sheetState Material3 sheet state
 * @param calendarGroups Calendars grouped by account
 * @param currentDefaultId Current default calendar ID
 * @param onSelectDefault Callback when default calendar is selected
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultCalendarSheet(
    sheetState: SheetState,
    calendarGroups: List<CalendarGroup>,
    currentDefaultId: Long?,
    onSelectDefault: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val allCalendars = remember(calendarGroups) {
        calendarGroups.flatMap { it.calendars }
    }

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

            // Calendar list grouped by account
            if (calendarGroups.isEmpty() || allCalendars.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No calendars available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                GroupedCalendarList(
                    groups = calendarGroups,
                    selectionMode = CalendarSelectionMode.RADIO,
                    selectedCalendarId = currentDefaultId,
                    onCalendarClick = { calendar ->
                        onSelectDefault(calendar.id)
                        onDismiss()
                    }
                )
            }
        }
    }
}
