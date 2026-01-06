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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.getReminderOptionsForEventType
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Reminder picker card for event forms.
 *
 * **BUG FIX:** This component correctly shows different reminder options
 * based on whether the event is all-day or timed:
 * - Timed events: 5m, 10m, 15m, 30m, 1h, 2h before
 * - All-day events: 9 AM day of, 1 day before, 2 days before, 1 week before
 *
 * The previous implementation in EventFormSheet showed ALL options regardless
 * of event type, which led to confusing options like "9 AM day of event" for
 * meetings with specific times.
 *
 * @param reminder1Minutes First reminder in minutes before event (-1 = off)
 * @param reminder2Minutes Second reminder in minutes before event (-1 = off)
 * @param isAllDay Whether the event is all-day (affects available options)
 * @param onReminder1Change Callback when first reminder changes
 * @param onReminder2Change Callback when second reminder changes
 * @param modifier Modifier for the card
 */
@Composable
fun ReminderPickerCard(
    reminder1Minutes: Int,
    reminder2Minutes: Int,
    isAllDay: Boolean,
    onReminder1Change: (Int) -> Unit,
    onReminder2Change: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedReminder by remember { mutableStateOf<Int?>(null) } // 1 or 2

    // Get the correct options based on event type (THIS FIXES THE BUG)
    val options = getReminderOptionsForEventType(isAllDay)

    // Build summary text
    val summaryText = buildString {
        val r1Label = DateTimeUtils.formatReminderShort(reminder1Minutes)
        append(r1Label)
        if (reminder1Minutes != REMINDER_OFF && reminder2Minutes != REMINDER_OFF) {
            append(", ${DateTimeUtils.formatReminderShort(reminder2Minutes)}")
        }
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Alerts",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // First reminder
                    ReminderRow(
                        label = "First Alert",
                        selectedMinutes = reminder1Minutes,
                        options = options,
                        isExpanded = expandedReminder == 1,
                        onExpandChange = { expandedReminder = if (it) 1 else null },
                        onSelect = { minutes ->
                            onReminder1Change(minutes)
                            expandedReminder = null
                            // If turning off first reminder, also turn off second
                            if (minutes == REMINDER_OFF && reminder2Minutes != REMINDER_OFF) {
                                onReminder2Change(REMINDER_OFF)
                            }
                        }
                    )

                    // Second reminder (only show if first is set)
                    if (reminder1Minutes != REMINDER_OFF) {
                        ReminderRow(
                            label = "Second Alert",
                            selectedMinutes = reminder2Minutes,
                            options = options,
                            isExpanded = expandedReminder == 2,
                            onExpandChange = { expandedReminder = if (it) 2 else null },
                            onSelect = { minutes ->
                                onReminder2Change(minutes)
                                expandedReminder = null
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual reminder row with expandable options.
 */
@Composable
private fun ReminderRow(
    label: String,
    selectedMinutes: Int,
    options: List<ReminderOption>,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    Column {
        // Row header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onExpandChange(!isExpanded) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    options.find { it.minutes == selectedMinutes }?.label ?: "None",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Options list
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option.minutes == selectedMinutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelect(option.minutes) }
                            .background(
                                if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
