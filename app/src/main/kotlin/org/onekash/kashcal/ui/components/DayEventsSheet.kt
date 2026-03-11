package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet showing all events for a selected day.
 * Replaces DayEventsPager in the full-height month view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayEventsSheet(
    dateMs: Long,
    events: ImmutableList<DisplayEvent>,
    showEventEmojis: Boolean,
    timePattern: String,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { Box(Modifier) } // No drag handle — header serves as visual anchor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Date header
            val dateLabel = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
                .format(Date(dateMs))
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )

            if (events.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No events",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(events, key = { event ->
                        when (event) {
                            is DisplayEvent.Room -> "room-${event.event.id}-${event.occurrence.startTs}"
                            is DisplayEvent.Device -> "device-${event.instance.instanceId}-${event.startTs}"
                        }
                    }) { displayEvent ->
                        val eventColor = Color(displayEvent.calendarColor)
                        val isPast = DateTimeUtils.isEventPast(
                            displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay
                        )

                        EventCard(
                            displayEvent = displayEvent,
                            eventColor = eventColor,
                            isPast = isPast,
                            selectedDate = dateMs,
                            showEventEmojis = showEventEmojis,
                            timePattern = timePattern,
                            onClick = {
                                when (displayEvent) {
                                    is DisplayEvent.Room -> onEventClick(
                                        displayEvent.event,
                                        displayEvent.occurrence.startTs
                                    )
                                    is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // New event button
            TextButton(
                onClick = { onCreateEvent(dateMs) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("New Event")
                }
            }
        }
    }
}
