package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.ALL_DAY_PRESET_CHIPS
import org.onekash.kashcal.ui.shared.MAX_REMINDERS
import org.onekash.kashcal.ui.shared.TIMED_PRESET_CHIPS
import org.onekash.kashcal.ui.shared.formatReminderDuration
import org.onekash.kashcal.ui.shared.formatReminderSummary

/**
 * Dynamic reminder picker card for event forms.
 *
 * Supports up to [MAX_REMINDERS] (5) reminders, each configurable via
 * an inline [WheelDurationPicker]. Only one picker is open at a time.
 *
 * @param reminders Current list of reminder minutes
 * @param isAllDay Whether the event is all-day (affects presets and labels)
 * @param use24Hour Whether to use 24-hour format for time labels
 * @param onRemindersChange Callback with updated list when user changes any reminder
 * @param truncatedReminderCount Number of reminders that were truncated on load
 * @param modifier Modifier for the card
 */
@Deprecated("Use ReminderPickerRow instead", level = DeprecationLevel.WARNING)
@Composable
fun ReminderPickerCard(
    reminders: List<Int>,
    isAllDay: Boolean,
    use24Hour: Boolean,
    onRemindersChange: (List<Int>) -> Unit,
    truncatedReminderCount: Int = 0,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // -1 = no picker open; 0..N-1 = index of open inline picker
    var expandedPickerIndex by remember { mutableIntStateOf(-1) }

    val presets = if (isAllDay) ALL_DAY_PRESET_CHIPS else TIMED_PRESET_CHIPS

    // Build summary text
    val summaryText = formatReminderSummary(reminders, use24Hour, LocalResources.current, isAllDay)

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
                        stringResource(R.string.label_alerts),
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
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    // Dynamic reminder rows
                    reminders.forEachIndexed { index, minutes ->
                        ReminderItemRow(
                            index = index,
                            minutes = minutes,
                            isAllDay = isAllDay,
                            use24Hour = use24Hour,
                            isPickerOpen = expandedPickerIndex == index,
                            presets = presets,
                            onTogglePicker = {
                                expandedPickerIndex = if (expandedPickerIndex == index) -1 else index
                            },
                            onDurationSelected = { newMinutes ->
                                val updated = reminders.toMutableList()
                                updated[index] = newMinutes
                                onRemindersChange(updated)
                            },
                            onDelete = {
                                val updated = reminders.toMutableList()
                                updated.removeAt(index)
                                onRemindersChange(updated)
                                // Adjust expanded picker index
                                expandedPickerIndex = when {
                                    expandedPickerIndex == index -> -1
                                    expandedPickerIndex > index -> expandedPickerIndex - 1
                                    else -> expandedPickerIndex
                                }
                            },
                            onDismissPicker = {
                                expandedPickerIndex = -1
                            }
                        )
                    }

                    // "+ Add Alert" button (hidden at MAX_REMINDERS)
                    if (reminders.size < MAX_REMINDERS) {
                        TextButton(
                            onClick = {
                                val updated = reminders + 15 // Default new reminder: 15 min
                                onRemindersChange(updated)
                                // Open picker for new reminder
                                expandedPickerIndex = updated.size - 1
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(R.string.label_add_alert),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // Warning when reminders were truncated
                    if (truncatedReminderCount > 0) {
                        val totalReminders = reminders.size + truncatedReminderCount
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = stringResource(R.string.cd_warning),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.label_reminders_truncated, truncatedReminderCount, totalReminders, MAX_REMINDERS),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReminderPickerContent(
    reminders: List<Int>,
    isAllDay: Boolean,
    use24Hour: Boolean,
    onRemindersChange: (List<Int>) -> Unit,
    truncatedReminderCount: Int = 0,
    modifier: Modifier = Modifier
) {
    var expandedPickerIndex by remember { mutableIntStateOf(-1) }
    val presets = if (isAllDay) ALL_DAY_PRESET_CHIPS else TIMED_PRESET_CHIPS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
    ) {
        reminders.forEachIndexed { index, minutes ->
            ReminderItemRow(
                index = index,
                minutes = minutes,
                isAllDay = isAllDay,
                use24Hour = use24Hour,
                isPickerOpen = expandedPickerIndex == index,
                presets = presets,
                onTogglePicker = {
                    expandedPickerIndex = if (expandedPickerIndex == index) -1 else index
                },
                onDurationSelected = { newMinutes ->
                    val updated = reminders.toMutableList()
                    updated[index] = newMinutes
                    onRemindersChange(updated)
                },
                onDelete = {
                    val updated = reminders.toMutableList()
                    updated.removeAt(index)
                    onRemindersChange(updated)
                    expandedPickerIndex = when {
                        expandedPickerIndex == index -> -1
                        expandedPickerIndex > index -> expandedPickerIndex - 1
                        else -> expandedPickerIndex
                    }
                },
                onDismissPicker = {
                    expandedPickerIndex = -1
                }
            )
        }

        if (reminders.size < MAX_REMINDERS) {
            TextButton(
                onClick = {
                    val updated = reminders + 15
                    onRemindersChange(updated)
                    expandedPickerIndex = updated.size - 1
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.label_add_alert),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        if (truncatedReminderCount > 0) {
            val totalReminders = reminders.size + truncatedReminderCount
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(R.string.cd_warning),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.label_reminders_truncated, truncatedReminderCount, totalReminders, MAX_REMINDERS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ReminderPickerRow(
    reminders: List<Int>,
    isAllDay: Boolean,
    use24Hour: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onRemindersChange: (List<Int>) -> Unit,
    truncatedReminderCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val summaryText = formatReminderSummary(reminders, use24Hour, LocalResources.current, isAllDay)

    EventFormRow(
        icon = Icons.Default.Notifications,
        iconContentDescription = stringResource(R.string.label_alerts),
        isExpanded = isExpanded,
        showExpandIcon = true,
        onToggle = onToggle,
        expandedContent = {
            ReminderPickerContent(
                reminders = reminders,
                isAllDay = isAllDay,
                use24Hour = use24Hour,
                onRemindersChange = onRemindersChange,
                truncatedReminderCount = truncatedReminderCount
            )
        },
        modifier = modifier
    ) {
        Text(
            summaryText,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Single reminder row with optional inline WheelDurationPicker.
 */
@Composable
private fun ReminderItemRow(
    index: Int,
    minutes: Int,
    isAllDay: Boolean,
    use24Hour: Boolean,
    isPickerOpen: Boolean,
    presets: List<org.onekash.kashcal.ui.shared.PresetChip>,
    onTogglePicker: () -> Unit,
    onDurationSelected: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismissPicker: () -> Unit
) {
    Column {
        // Row header: label + delete button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onTogglePicker() }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatReminderDuration(minutes, isAllDay, use24Hour, LocalResources.current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isPickerOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isPickerOpen) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_remove_alert),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Inline wheel duration picker
        AnimatedVisibility(
            visible = isPickerOpen,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
        ) {
            WheelDurationPicker(
                selectedMinutes = minutes,
                isAllDay = isAllDay,
                use24Hour = use24Hour,
                presets = presets,
                onDurationSelected = onDurationSelected,
                onDismiss = onDismissPicker
            )
        }
    }
}
