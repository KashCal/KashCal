package org.onekash.kashcal.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Bottom sheet displaying sync changes with details.
 *
 * Shows a list of events that were added, modified, or deleted during sync.
 * Each item shows:
 * - Color-coded icon (green=add, blue=edit, red=delete)
 * - Event title
 * - Event date/time
 * - Calendar color indicator
 *
 * @param changes List of sync changes to display
 * @param onDismiss Called when sheet is dismissed
 * @param onEventClick Called when an event is tapped (with event ID)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncChangesBottomSheet(
    changes: List<SyncChange>,
    onDismiss: () -> Unit,
    onEventClick: (Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Text(
                text = "Sync Changes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Summary
            val newCount = changes.count { it.type == ChangeType.NEW }
            val modCount = changes.count { it.type == ChangeType.MODIFIED }
            val delCount = changes.count { it.type == ChangeType.DELETED }

            Text(
                text = buildSummaryText(newCount, modCount, delCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

            // Changes list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(changes, key = { "${it.type}_${it.eventId}_${it.eventStartTs}" }) { change ->
                    SyncChangeItem(
                        change = change,
                        onClick = {
                            // Only navigate for non-deleted events with valid ID
                            if (change.type != ChangeType.DELETED && change.eventId != null) {
                                onEventClick(change.eventId)
                            }
                        }
                    )
                }
            }

            // Bottom padding for gesture navigation
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Individual sync change item in the list.
 */
@Composable
private fun SyncChangeItem(
    change: SyncChange,
    onClick: () -> Unit
) {
    val (icon, iconColor) = when (change.type) {
        ChangeType.NEW -> Icons.Default.Add to Color(0xFF4CAF50) // Green
        ChangeType.MODIFIED -> Icons.Default.Edit to Color(0xFF2196F3) // Blue
        ChangeType.DELETED -> Icons.Default.Delete to Color(0xFFF44336) // Red
    }

    val isClickable = change.type != ChangeType.DELETED && change.eventId != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isClickable) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Change type icon
        Icon(
            imageVector = icon,
            contentDescription = change.type.name,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Calendar color indicator
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(change.calendarColor))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Event details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.eventTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatEventTime(change.eventStartTs, change.isAllDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (change.isRecurring) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Recurring",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Build summary text from counts.
 */
private fun buildSummaryText(newCount: Int, modCount: Int, delCount: Int): String {
    val parts = mutableListOf<String>()
    if (newCount > 0) parts.add("$newCount new")
    if (modCount > 0) parts.add("$modCount updated")
    if (delCount > 0) parts.add("$delCount removed")
    return parts.joinToString(", ")
}

/**
 * Format event timestamp for display with smart year handling.
 *
 * Following Android DateUtils pattern: show year only when different from current year.
 *
 * @param timestampMs Event start timestamp in milliseconds
 * @param isAllDay Whether this is an all-day event
 * @return Formatted date/time string:
 *   - All-day events: "Tue, Jan 7" or "Tue, Jan 7, 2024" (UTC date, no time)
 *   - Timed events: "Tue, Jan 7 at 2:30 PM" or "Tue, Jan 7, 2024 at 2:30 PM"
 */
internal fun formatEventTime(timestampMs: Long, isAllDay: Boolean): String {
    val instant = Instant.ofEpochMilli(timestampMs)
    val currentYear = Year.now().value

    return if (isAllDay) {
        // All-day: use UTC to avoid date shift, show date only
        val localDate = instant.atZone(ZoneOffset.UTC).toLocalDate()
        val pattern = if (localDate.year == currentYear) "EEE, MMM d" else "EEE, MMM d, yyyy"
        localDate.format(DateTimeFormatter.ofPattern(pattern))
    } else {
        // Timed: use local timezone with time
        val localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
        val pattern = if (localDateTime.year == currentYear) {
            "EEE, MMM d 'at' h:mm a"
        } else {
            "EEE, MMM d, yyyy 'at' h:mm a"
        }
        localDateTime.format(DateTimeFormatter.ofPattern(pattern))
    }
}
