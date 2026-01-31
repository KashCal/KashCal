package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Selection mode for grouped calendar list.
 */
enum class CalendarSelectionMode {
    /** Checkbox mode - multiple selections (for visibility toggle) */
    CHECKBOX,
    /** Radio mode - single selection (for calendar picker) */
    RADIO
}

/**
 * Grouped calendar list with account headers.
 *
 * Displays calendars grouped by account with headers.
 * Supports both checkbox (visibility) and radio (selection) modes.
 *
 * @param groups List of calendar groups (pre-grouped by ViewModel)
 * @param selectionMode CHECKBOX for multi-select, RADIO for single-select
 * @param selectedCalendarIds Set of selected calendar IDs (for CHECKBOX mode, use isVisible)
 * @param selectedCalendarId Currently selected calendar ID (for RADIO mode)
 * @param onCalendarClick Called when a calendar is clicked
 * @param modifier Modifier for the LazyColumn
 */
@Composable
fun GroupedCalendarList(
    groups: List<CalendarGroup>,
    selectionMode: CalendarSelectionMode,
    selectedCalendarIds: Set<Long> = emptySet(),
    selectedCalendarId: Long? = null,
    onCalendarClick: (Calendar) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No calendars available",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        groups.forEach { group ->
            groupedCalendarItems(
                group = group,
                selectionMode = selectionMode,
                selectedCalendarIds = selectedCalendarIds,
                selectedCalendarId = selectedCalendarId,
                onCalendarClick = onCalendarClick
            )
        }
    }
}

/**
 * Extension to add grouped calendar items to a LazyListScope.
 * Use this when embedding in an existing LazyColumn.
 */
fun LazyListScope.groupedCalendarItems(
    group: CalendarGroup,
    selectionMode: CalendarSelectionMode,
    selectedCalendarIds: Set<Long> = emptySet(),
    selectedCalendarId: Long? = null,
    onCalendarClick: (Calendar) -> Unit
) {
    // Account header
    item(key = "header_${group.accountId}") {
        AccountHeader(accountName = group.accountName)
    }

    // Calendars in this group
    items(
        items = group.calendars,
        key = { "calendar_${it.id}" }
    ) { calendar ->
        val isSelected = when (selectionMode) {
            CalendarSelectionMode.CHECKBOX -> calendar.id in selectedCalendarIds
            CalendarSelectionMode.RADIO -> calendar.id == selectedCalendarId
        }

        GroupedCalendarItem(
            calendar = calendar,
            isSelected = isSelected,
            selectionMode = selectionMode,
            onClick = { onCalendarClick(calendar) }
        )
    }
}

@Composable
private fun AccountHeader(
    accountName: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = accountName,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 8.dp)
    )
}

@Composable
private fun GroupedCalendarItem(
    calendar: Calendar,
    isSelected: Boolean,
    selectionMode: CalendarSelectionMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isSelected && selectionMode == CalendarSelectionMode.RADIO)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(start = 16.dp), // Indent under account header
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
                    .background(Color(calendar.color))
            )
            // Calendar name
            Text(
                calendar.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Selection indicator
        when (selectionMode) {
            CalendarSelectionMode.CHECKBOX -> {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            }
            CalendarSelectionMode.RADIO -> {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
