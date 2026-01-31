package org.onekash.kashcal.ui.components.pickers

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Calendar picker card with color dot and expandable grouped list.
 *
 * Displays a list of available calendars grouped by account with color indicators.
 * Used in event form to select which calendar to save an event to.
 *
 * @param selectedCalendarId Currently selected calendar ID
 * @param selectedCalendarName Display name of selected calendar
 * @param selectedCalendarColor Color of selected calendar (nullable)
 * @param calendarGroups Calendars grouped by account
 * @param isExpanded Whether the picker list is expanded
 * @param enabled When false, the picker cannot be expanded or changed.
 *                Used to disable calendar changes for single occurrence edits.
 * @param onToggle Toggle expansion state
 * @param onSelect Called with (calendarId, displayName, color) when selection changes
 */
@Composable
fun CalendarPickerCard(
    selectedCalendarId: Long?,
    selectedCalendarName: String,
    selectedCalendarColor: Int?,
    calendarGroups: List<CalendarGroup>,
    isExpanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    onSelect: (Long, String, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        focusManager.clearFocus()
                        onToggle()
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Calendar", style = MaterialTheme.typography.bodyMedium)
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
                        selectedCalendarName.ifEmpty { "Select calendar" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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

                    val allCalendars = calendarGroups.flatMap { it.calendars }
                    if (allCalendars.isEmpty()) {
                        Text(
                            "No calendars available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        calendarGroups.forEach { group ->
                            // Account header
                            Text(
                                text = group.accountName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .padding(top = 4.dp)
                            )
                            // Calendars in group
                            group.calendars.forEach { calendar ->
                                CalendarItem(
                                    calendar = calendar,
                                    isSelected = selectedCalendarId == calendar.id,
                                    onClick = { onSelect(calendar.id, calendar.displayName, calendar.color) }
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
 * Individual calendar item in the picker list.
 */
@Composable
private fun CalendarItem(
    calendar: Calendar,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .padding(start = 8.dp), // Indent under account header
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
            calendar.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Simple calendar picker without expansion state management.
 * Use when you want to manage expansion state externally.
 *
 * @param calendarGroups Calendars grouped by account
 * @param selectedCalendarId Currently selected calendar ID
 * @param onCalendarSelect Called with calendar ID when selection changes
 */
@Composable
fun SimpleCalendarPicker(
    calendarGroups: List<CalendarGroup>,
    selectedCalendarId: Long?,
    onCalendarSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val allCalendars = calendarGroups.flatMap { it.calendars }
        if (allCalendars.isEmpty()) {
            Text(
                "No calendars available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        } else {
            calendarGroups.forEach { group ->
                // Account header
                Text(
                    text = group.accountName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .padding(top = 4.dp)
                )
                // Calendars in group
                group.calendars.forEach { calendar ->
                    CalendarItem(
                        calendar = calendar,
                        isSelected = selectedCalendarId == calendar.id,
                        onClick = { onCalendarSelect(calendar.id) }
                    )
                }
            }
        }
    }
}
