package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.DefaultCalendar
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
                stringResource(R.string.settings_visible_calendars),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Text(
                stringResource(R.string.label_visible_count, visibleCount, totalCount),
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
                        stringResource(R.string.empty_no_calendars),
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
 * Supports both Room calendars (local, iCloud, CalDAV) and device calendars.
 *
 * @param sheetState Material3 sheet state
 * @param calendarGroups Room calendars grouped by account
 * @param deviceCalendarGroups Device calendars grouped by account (requires WRITE_CALENDAR)
 * @param currentDefault Current default calendar selection
 * @param onSelectDefault Callback when default calendar is selected
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultCalendarSheet(
    sheetState: SheetState,
    calendarGroups: List<CalendarGroup>,
    deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    currentDefault: DefaultCalendar?,
    onSelectDefault: (DefaultCalendar) -> Unit,
    onDismiss: () -> Unit
) {
    val allRoomCalendars = remember(calendarGroups) {
        calendarGroups.flatMap { it.calendars }
    }
    val allDeviceCalendars = remember(deviceCalendarGroups) {
        deviceCalendarGroups.flatMap { it.pickerCalendars }
    }
    val hasAnyCalendars = allRoomCalendars.isNotEmpty() || allDeviceCalendars.isNotEmpty()

    // Track selection - which ID and which type
    val selectedRoomId = (currentDefault as? DefaultCalendar.Room)?.calendarId
    val selectedDeviceId = (currentDefault as? DefaultCalendar.Device)?.calendarId

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
                stringResource(R.string.settings_default_calendar),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Text(
                stringResource(R.string.label_default_calendar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (!hasAnyCalendars) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.empty_no_calendars),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    // Room calendars section
                    calendarGroups.forEach { group ->
                        // Account header
                        item(key = "room_header_${group.accountId}", contentType = "account_header") {
                            Text(
                                text = group.accountName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .padding(top = 8.dp)
                            )
                        }
                        // Calendars in group
                        items(
                            items = group.calendars,
                            key = { "room_cal_${it.id}" },
                            contentType = { "calendar_item" }
                        ) { calendar ->
                            DefaultCalendarItem(
                                name = calendar.displayName,
                                color = calendar.color,
                                isSelected = selectedRoomId == calendar.id,
                                onClick = {
                                    onSelectDefault(DefaultCalendar.Room(calendar.id))
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // Device calendars section (if available)
                    if (allDeviceCalendars.isNotEmpty()) {
                        item(key = "device_section_header", contentType = "section_header") {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(
                                    text = stringResource(R.string.label_device_calendars),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        deviceCalendarGroups.forEach { group ->
                            // Account header
                            item(key = "device_header_${group.accountName}", contentType = "account_header") {
                                Text(
                                    text = group.accountName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .padding(top = 4.dp)
                                )
                            }
                            // Calendars in group
                            items(
                                items = group.pickerCalendars,
                                key = { "device_cal_${it.id}" },
                                contentType = { "calendar_item" }
                            ) { pickerCal ->
                                DefaultCalendarItem(
                                    name = pickerCal.displayName,
                                    color = pickerCal.color,
                                    isSelected = selectedDeviceId == pickerCal.id,
                                    onClick = {
                                        onSelectDefault(DefaultCalendar.Device(pickerCal.id))
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Calendar item for default calendar selection.
 */
@Composable
private fun DefaultCalendarItem(
    name: String,
    color: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(start = 16.dp),
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
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(color))
            )
            // Calendar name
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Selection indicator
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
