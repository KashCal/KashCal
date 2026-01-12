package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Calendar visibility picker sheet.
 * Allows users to show/hide individual calendars from the view.
 *
 * @param calendars List of available calendars (visibility from Calendar.isVisible)
 * @param onToggleCalendar Called when a calendar's visibility is toggled
 * @param onShowAll Called to show all calendars
 * @param onDismiss Called when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarVisibilitySheet(
    calendars: List<Calendar>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Calendars",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Calendar list
            if (calendars.isEmpty()) {
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(calendars, key = { it.id }) { calendar ->
                        // Use Calendar.isVisible (DB source of truth)
                        val isVisible = calendar.isVisible

                        CalendarItem(
                            calendar = calendar,
                            isVisible = isVisible,
                            onToggle = { onToggleCalendar(calendar.id, !isVisible) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Show All button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onShowAll) {
                        Text("Show All")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarItem(
    calendar: Calendar,
    isVisible: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(calendar.color))
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Calendar name
        Text(
            text = calendar.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )

        // Checkbox
        Checkbox(
            checked = isVisible,
            onCheckedChange = { onToggle() }
        )
    }
}
