package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Calendar visibility picker sheet with account grouping.
 * Allows users to show/hide individual calendars from the view.
 *
 * @param calendarGroups Calendars grouped by account
 * @param onToggleCalendar Called when a calendar's visibility is toggled
 * @param onShowAll Called to show all calendars
 * @param onDismiss Called when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarVisibilitySheet(
    calendarGroups: List<CalendarGroup>,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Compute visible calendar IDs for checkbox state
    val visibleCalendarIds = remember(calendarGroups) {
        calendarGroups.flatMap { it.calendars }
            .filter { it.isVisible }
            .map { it.id }
            .toSet()
    }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.drawer_calendars),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.action_done),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Calendar list grouped by account
            if (calendarGroups.isEmpty() || calendarGroups.all { it.calendars.isEmpty() }) {
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
                    },
                    modifier = Modifier.weight(1f, fill = false)
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Show All button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.action_show_all))
                    }
                }
            }
        }
    }
}
