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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lists the events a sync added, changed or deleted, under a count summary. Each row shows a
 * change icon (green add, blue edit, red delete), the calendar color, the title, and the date
 * and time with a repeat icon for recurring events.
 *
 * @param onEventClick called with the event ID on a tap; deleted events and changes without an
 *   ID aren't tappable.
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
                text = stringResource(R.string.status_sync_changes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Summary, e.g. "2 new, 1 updated".
            val newCount = changes.count { it.type == ChangeType.NEW }
            val modCount = changes.count { it.type == ChangeType.MODIFIED }
            val delCount = changes.count { it.type == ChangeType.DELETED }

            val summaryParts = mutableListOf<String>()
            if (newCount > 0) summaryParts.add(stringResource(R.string.sync_summary_new, newCount))
            if (modCount > 0) summaryParts.add(stringResource(R.string.sync_summary_updated, modCount))
            if (delCount > 0) summaryParts.add(stringResource(R.string.sync_summary_removed, delCount))

            Text(
                text = summaryParts.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

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
                            if (change.type != ChangeType.DELETED && change.eventId != null) {
                                onEventClick(change.eventId)
                            }
                        }
                    )
                }
            }

            // Room above the gesture navigation bar.
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** Shows one sync change row. */
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
        Icon(
            imageVector = icon,
            contentDescription = change.type.name,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(change.calendarColor))
        )

        Spacer(modifier = Modifier.width(12.dp))

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
                        contentDescription = stringResource(R.string.cd_recurring),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/**
 * Formats an event start, with the year only when it isn't the current year.
 *
 * - All-day: the UTC date, no time: "Tue, Jan 7" or "Tue, Jan 7, 2024".
 * - Timed: the device-zone date and a 12-hour time: "Tue, Jan 7, 2:30 PM" or
 *   "Tue, Jan 7, 2024, 2:30 PM".
 */
internal fun formatEventTime(timestampMs: Long, isAllDay: Boolean): String {
    val instant = Instant.ofEpochMilli(timestampMs)
    val currentYear = Year.now().value

    return if (isAllDay) {
        val localDate = instant.atZone(ZoneOffset.UTC).toLocalDate()
        val pattern = if (localDate.year == currentYear) DateTimeUtils.localizedPattern("EEEMMMd") else DateTimeUtils.localizedPattern("yEEEMMMd")
        localDate.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    } else {
        val localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
        val datePattern = if (localDateTime.year == currentYear) {
            DateTimeUtils.localizedPattern("EEEMMMd")
        } else {
            DateTimeUtils.localizedPattern("yEEEMMMd")
        }
        val dateStr = localDateTime.format(DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()))
        val timeStr = localDateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        "$dateStr, $timeStr"
    }
}
