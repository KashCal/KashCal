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
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Bottom sheet for importing ICS file events.
 *
 * Shows event preview list, calendar picker, and import action.
 *
 * @param events List of events parsed from ICS file
 * @param calendars Available calendars (read-only calendars are filtered out)
 * @param defaultCalendarId Default calendar to select
 * @param onDismiss Called when sheet is dismissed
 * @param onImport Called with selected calendar ID and events to import
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IcsImportSheet(
    events: List<Event>,
    calendars: List<Calendar>,
    defaultCalendarId: Long?,
    onDismiss: () -> Unit,
    onImport: (calendarId: Long, events: List<Event>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Filter to writable calendars only
    val writableCalendars = remember(calendars) {
        calendars.filter { !it.isReadOnly }
    }

    // Select default calendar or first writable
    var selectedCalendarId by remember(defaultCalendarId, writableCalendars) {
        val defaultId = defaultCalendarId?.takeIf { id ->
            writableCalendars.any { it.id == id }
        } ?: writableCalendars.firstOrNull()?.id
        mutableLongStateOf(defaultId ?: 0L)
    }

    val selectedCalendar = writableCalendars.find { it.id == selectedCalendarId }
    var showCalendarPicker by remember { mutableStateOf(false) }

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
            if (writableCalendars.isNotEmpty()) {
                ImportCalendarPicker(
                    selectedCalendar = selectedCalendar,
                    availableCalendars = writableCalendars,
                    isExpanded = showCalendarPicker,
                    onToggle = { showCalendarPicker = !showCalendarPicker },
                    onSelect = { calendar ->
                        selectedCalendarId = calendar.id
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
                            onImport(selectedCalendarId, events)
                        }
                    },
                    enabled = selectedCalendarId > 0 && writableCalendars.isNotEmpty()
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
 */
@Composable
private fun ImportCalendarPicker(
    selectedCalendar: Calendar?,
    availableCalendars: List<Calendar>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Calendar) -> Unit
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
                    if (selectedCalendar != null) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(selectedCalendar.color))
                        )
                        Text(
                            text = selectedCalendar.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            text = "Select calendar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    availableCalendars.forEach { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSelect(calendar) }
                                .background(
                                    if (selectedCalendar?.id == calendar.id)
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
                                    .background(Color(calendar.color))
                            )
                            Text(
                                text = calendar.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
