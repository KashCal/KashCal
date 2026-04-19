package org.onekash.kashcal.ui.screens.settings

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.WheelDurationPicker
import org.onekash.kashcal.ui.shared.ALL_DAY_PRESET_CHIPS
import org.onekash.kashcal.ui.shared.PresetChip
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.shared.TIMED_PRESET_CHIPS
import org.onekash.kashcal.ui.shared.formatReminderDuration

/**
 * Bottom sheet for selecting default alerts/reminders.
 *
 * Uses expandable sections with inline wheel duration pickers for
 * timed and all-day event reminders. Each section shows preset chips
 * (including "None") and a 3-wheel days/hours/minutes picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    sheetState: SheetState,
    defaultReminderTimed: Int,
    defaultReminderAllDay: Int,
    use24Hour: Boolean,
    onTimedReminderChange: (Int) -> Unit,
    onAllDayReminderChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedSection by remember { mutableIntStateOf(-1) }

    val timedWheelMinutes = if (defaultReminderTimed == REMINDER_OFF) 15 else defaultReminderTimed
    val allDayWheelMinutes = if (defaultReminderAllDay == REMINDER_OFF) 540 else defaultReminderAllDay

    val noneLabel = stringResource(R.string.alert_none)
    val timedChips = remember(noneLabel) { listOf(PresetChip(noneLabel, REMINDER_OFF)) + TIMED_PRESET_CHIPS }
    val allDayChips = remember(noneLabel) { listOf(PresetChip(noneLabel, REMINDER_OFF)) + ALL_DAY_PRESET_CHIPS }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.settings_default_alerts),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            SettingsCard {
                AlertSection(
                    iconEmoji = "⏰",
                    label = stringResource(R.string.label_scheduled_events),
                    currentValue = defaultReminderTimed,
                    isAllDay = false,
                    use24Hour = use24Hour,
                    chips = timedChips,
                    wheelMinutes = timedWheelMinutes,
                    isExpanded = expandedSection == 0,
                    onToggle = { expandedSection = if (expandedSection == 0) -1 else 0 },
                    onCollapse = { expandedSection = -1 },
                    onChange = onTimedReminderChange
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                AlertSection(
                    iconEmoji = "📅",
                    label = stringResource(R.string.label_all_day_events),
                    currentValue = defaultReminderAllDay,
                    isAllDay = true,
                    use24Hour = use24Hour,
                    chips = allDayChips,
                    wheelMinutes = allDayWheelMinutes,
                    isExpanded = expandedSection == 1,
                    onToggle = { expandedSection = if (expandedSection == 1) -1 else 1 },
                    onCollapse = { expandedSection = -1 },
                    onChange = onAllDayReminderChange
                )
            }
        }
    }
}

/**
 * Expandable section with preset chips and a wheel duration picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertSection(
    iconEmoji: String,
    label: String,
    currentValue: Int,
    isAllDay: Boolean,
    use24Hour: Boolean,
    chips: List<PresetChip>,
    wheelMinutes: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onCollapse: () -> Unit,
    onChange: (Int) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current

    AlertSectionRow(
        iconEmoji = iconEmoji,
        label = label,
        currentValue = currentValue,
        isAllDay = isAllDay,
        use24Hour = use24Hour,
        isExpanded = isExpanded,
        onClick = onToggle
    )

    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chips.forEach { chip ->
                    val isSelected = currentValue == chip.minutes
                    AssistChip(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(chip.minutes)
                            onCollapse()
                        },
                        label = { Text(chip.label) },
                        colors = if (isSelected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        }
                    )
                }
            }

            WheelDurationPicker(
                selectedMinutes = wheelMinutes,
                isAllDay = isAllDay,
                use24Hour = use24Hour,
                presets = emptyList(),
                onDurationSelected = onChange,
                onDismiss = onCollapse
            )
        }
    }
}

/**
 * Tappable section row showing icon, label, current value, and expand/collapse chevron.
 */
@Composable
private fun AlertSectionRow(
    iconEmoji: String,
    label: String,
    currentValue: Int,
    isAllDay: Boolean,
    use24Hour: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val resources = LocalContext.current.resources
    val displayText = if (currentValue == REMINDER_OFF) {
        stringResource(R.string.alert_none)
    } else {
        formatReminderDuration(currentValue, isAllDay, use24Hour, resources)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(iconEmoji)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Simplified alerts sheet for single picker (timed OR all-day).
 *
 * Use this when you want separate sheets for each type.
 * Uses expanded list view (for backward compatibility with existing callers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAlertPickerSheet(
    sheetState: SheetState,
    title: String,
    options: List<ReminderOption>,
    currentValue: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            options.forEach { option ->
                ReminderOptionRow(
                    option = option,
                    isSelected = option.minutes == currentValue,
                    onSelect = {
                        onSelect(option.minutes)
                        onDismiss()
                    }
                )
            }
        }
    }
}

/**
 * Single reminder option row (for SingleAlertPickerSheet).
 */
@Composable
private fun ReminderOptionRow(
    option: ReminderOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option.label,
            style = MaterialTheme.typography.bodyLarge
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
