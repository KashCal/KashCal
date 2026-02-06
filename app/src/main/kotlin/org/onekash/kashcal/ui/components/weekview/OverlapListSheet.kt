package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Bottom sheet showing a list of overlapping events.
 *
 * Displayed when user taps "+N more" badge on overlapping events.
 * Shows all events in the overlap group with compact format.
 *
 * @param events List of (Event, Occurrence) pairs to display
 * @param calendarColors Map of calendar ID to color
 * @param onDismiss Called when sheet is dismissed
 * @param onEventClick Called when an event is tapped (dismisses sheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlapListSheet(
    events: List<Pair<Event, Occurrence>>,
    calendarColors: Map<Long, Int>,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onDismiss: () -> Unit,
    onEventClick: (Event, Occurrence) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "${events.size} Events",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Event list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events) { (event, occurrence) ->
                    val color = calendarColors[event.calendarId]
                        ?: DEFAULT_EVENT_COLOR

                    CompactEventBlock(
                        event = event,
                        occurrence = occurrence,
                        color = color,
                        onClick = {
                            onEventClick(event, occurrence)
                            onDismiss()
                        },
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

private const val DEFAULT_EVENT_COLOR = 0xFF6200EE.toInt()
