package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.model.CalendarGroup

/** Selection mode of [GroupedCalendarList]. */
enum class CalendarSelectionMode {
    /** Multiple selection with checkboxes, read from `selectedCalendarIds`. */
    CHECKBOX,
    /** Single selection with a check mark, read from `selectedCalendarId`; no caller uses it. */
    RADIO
}

/**
 * Lists calendars under an account header per group, or an empty-state text when [groups] is
 * empty.
 *
 * @param groups calendars already grouped by account.
 * @param selectedCalendarIds checked calendars in [CalendarSelectionMode.CHECKBOX] mode.
 * @param selectedCalendarId the selected calendar in [CalendarSelectionMode.RADIO] mode.
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
                stringResource(R.string.empty_no_calendars),
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

/** Adds one group's header and calendar rows to an existing LazyColumn. */
fun LazyListScope.groupedCalendarItems(
    group: CalendarGroup,
    selectionMode: CalendarSelectionMode,
    selectedCalendarIds: Set<Long> = emptySet(),
    selectedCalendarId: Long? = null,
    onCalendarClick: (Calendar) -> Unit
) {
    item(key = "header_${group.accountId}", contentType = "account_header") {
        AccountHeader(accountName = group.accountName)
    }

    items(
        items = group.calendars,
        key = { "calendar_${it.id}" },
        contentType = { "calendar_item" }
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
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
