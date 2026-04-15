package org.onekash.kashcal.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Bottom sheet for importing ICS file events.
 *
 * Shows event preview list, calendar picker, and import action.
 * Supports both Room and device calendars as import targets.
 *
 * @param events List of events parsed from ICS file
 * @param calendars Available Room calendars (read-only calendars are filtered out)
 * @param defaultCalendarId Default Room calendar to select
 * @param deviceCalendarGroups Device calendars grouped by account (writable only)
 * @param defaultDeviceCalendarId Default device calendar to select
 * @param onDismiss Called when sheet is dismissed
 * @param onImport Called with selected calendar ID, events, and whether target is a device calendar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IcsImportSheet(
    events: List<Event>,
    calendars: List<Calendar>,
    defaultCalendarId: Long?,
    deviceCalendarGroups: List<CalendarGroup>,
    defaultDeviceCalendarId: Long?,
    onDismiss: () -> Unit,
    onImport: (calendarId: Long, events: List<Event>, isDeviceCalendar: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Filter to writable calendars only
    val writableCalendars = remember(calendars) {
        calendars.filter { !it.isReadOnly }
    }

    // All writable device calendars from groups
    val writableDeviceCalendars = remember(deviceCalendarGroups) {
        deviceCalendarGroups.flatMap { group ->
            group.pickerCalendars.filter { it.isWritable }
        }
    }

    // Resolve default selection: prefer Room default, fall back to device default
    val (initialCalendarId, initialIsDevice) = remember(
        defaultCalendarId, defaultDeviceCalendarId, writableCalendars, writableDeviceCalendars
    ) {
        // Try Room default first
        val roomDefault = defaultCalendarId?.takeIf { id ->
            writableCalendars.any { it.id == id }
        }
        if (roomDefault != null) return@remember roomDefault to false

        // Try device default
        val deviceDefault = defaultDeviceCalendarId?.takeIf { id ->
            writableDeviceCalendars.any { it.id == id }
        }
        if (deviceDefault != null) return@remember deviceDefault to true

        // Fall back to first writable Room calendar, then first writable device
        val firstRoom = writableCalendars.firstOrNull()?.id
        if (firstRoom != null) return@remember firstRoom to false

        val firstDevice = writableDeviceCalendars.firstOrNull()?.id
        if (firstDevice != null) return@remember firstDevice to true

        0L to false
    }

    var selectedCalendarId by remember { mutableLongStateOf(initialCalendarId) }
    var selectedIsDevice by remember { mutableStateOf(initialIsDevice) }

    val (selectedCalendarName, selectedCalendarColor) = if (selectedIsDevice) {
        val cal = writableDeviceCalendars.find { it.id == selectedCalendarId }
        cal?.displayName to cal?.color
    } else {
        val cal = writableCalendars.find { it.id == selectedCalendarId }
        cal?.displayName to cal?.color
    }

    var showCalendarPicker by remember { mutableStateOf(false) }

    val hasAnyWritableCalendar = writableCalendars.isNotEmpty() || writableDeviceCalendars.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = if (events.size == 1) "Add Event" else "Import ${events.size} Events",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Event preview list
            LazyColumn(
                modifier = Modifier.heightIn(max = 250.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(events.take(10)) { event ->
                    IcsEventPreviewItem(event)
                }
                if (events.size > 10) {
                    item {
                        Text(
                            text = "...and ${events.size - 10} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Calendar picker
            if (hasAnyWritableCalendar) {
                ImportCalendarPicker(
                    selectedCalendarId = selectedCalendarId,
                    selectedCalendarName = selectedCalendarName,
                    selectedCalendarColor = selectedCalendarColor,
                    selectedIsDevice = selectedIsDevice,
                    writableCalendars = writableCalendars,
                    deviceCalendarGroups = deviceCalendarGroups,
                    isExpanded = showCalendarPicker,
                    onToggle = { showCalendarPicker = !showCalendarPicker },
                    onSelect = { id, isDevice ->
                        selectedCalendarId = id
                        selectedIsDevice = isDevice
                        showCalendarPicker = false
                    }
                )
            } else {
                // No writable calendars warning
                Text(
                    text = "No writable calendars available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (selectedCalendarId > 0) {
                            onImport(selectedCalendarId, events, selectedIsDevice)
                        }
                    },
                    enabled = selectedCalendarId > 0 && hasAnyWritableCalendar
                ) {
                    Text(if (events.size == 1) "Add" else "Import")
                }
            }
        }
    }
}

/**
 * Preview item for a single event in the import list.
 */
@Composable
private fun IcsEventPreviewItem(event: Event) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatEventDateTime(event),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        event.location?.takeIf { it.isNotBlank() }?.let { location ->
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider()
}

/**
 * Format event date/time for preview display.
 */
private fun formatEventDateTime(event: Event): String {
    val startDateStr = DateTimeUtils.formatEventDateShort(event.startTs, event.isAllDay)

    return if (event.isAllDay) {
        val isMultiDay = DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, isAllDay = true)
        if (isMultiDay) {
            val endDateStr = DateTimeUtils.formatEventDateShort(event.endTs, event.isAllDay)
            "$startDateStr - $endDateStr (All day)"
        } else {
            "$startDateStr (All day)"
        }
    } else {
        val startTime = DateTimeUtils.formatEventTime(event.startTs, event.isAllDay)
        val endTime = DateTimeUtils.formatEventTime(event.endTs, event.isAllDay)
        "$startDateStr $startTime - $endTime"
    }
}

/**
 * Calendar picker for import target selection.
 * Supports both Room and device calendars.
 */
@Composable
private fun ImportCalendarPicker(
    selectedCalendarId: Long,
    selectedCalendarName: String?,
    selectedCalendarColor: Int?,
    selectedIsDevice: Boolean,
    writableCalendars: List<Calendar>,
    deviceCalendarGroups: List<CalendarGroup>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (id: Long, isDevice: Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add to calendar",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedCalendarColor != null) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(selectedCalendarColor))
                        )
                    }
                    Text(
                        text = selectedCalendarName ?: "Select calendar",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded calendar list
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

                    // Room calendars
                    writableCalendars.forEach { calendar ->
                        ImportCalendarItem(
                            name = calendar.displayName,
                            color = calendar.color,
                            isSelected = !selectedIsDevice && selectedCalendarId == calendar.id,
                            onClick = { onSelect(calendar.id, false) }
                        )
                    }

                    // Device calendars section
                    val allDeviceCalendars = deviceCalendarGroups.flatMap { it.pickerCalendars }
                    if (allDeviceCalendars.isNotEmpty()) {
                        if (writableCalendars.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        Text(
                            text = "Device Calendars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )

                        deviceCalendarGroups.forEach { group ->
                            // Account header
                            Text(
                                text = group.accountName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            // Calendars in group
                            group.pickerCalendars.filter { it.isWritable }.forEach { pickerCal ->
                                ImportCalendarItem(
                                    name = pickerCal.displayName,
                                    color = pickerCal.color,
                                    isSelected = selectedIsDevice && selectedCalendarId == pickerCal.id,
                                    onClick = { onSelect(pickerCal.id, true) }
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
 * Individual calendar item in the import picker list.
 */
@Composable
private fun ImportCalendarItem(
    name: String,
    color: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(color))
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
