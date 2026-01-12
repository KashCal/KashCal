package org.onekash.kashcal.ui.screens.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.formatReminderShort

/**
 * Bottom sheet for selecting default alerts/reminders.
 *
 * Uses compact dropdown selectors for timed and all-day event reminders.
 * Event duration has been moved to DisplayOptionsSheet.
 *
 * @param sheetState Material3 sheet state
 * @param defaultReminderTimed Current default for timed events (minutes)
 * @param defaultReminderAllDay Current default for all-day events (minutes)
 * @param onTimedReminderChange Callback when timed reminder changes
 * @param onAllDayReminderChange Callback when all-day reminder changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    sheetState: SheetState,
    defaultReminderTimed: Int,
    defaultReminderAllDay: Int,
    onTimedReminderChange: (Int) -> Unit,
    onAllDayReminderChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Find selected options
    val selectedTimedOption = TIMED_REMINDER_OPTIONS.find { it.minutes == defaultReminderTimed }
        ?: TIMED_REMINDER_OPTIONS[1]  // Default to 15 minutes
    val selectedAllDayOption = ALL_DAY_REMINDER_OPTIONS.find { it.minutes == defaultReminderAllDay }
        ?: ALL_DAY_REMINDER_OPTIONS[2]  // Default to 12 hours

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                "Default Alerts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Scheduled Events dropdown
            SettingsCard {
                SettingsDropdownRow(
                    label = "Scheduled Events",
                    options = TIMED_REMINDER_OPTIONS,
                    selectedOption = selectedTimedOption,
                    onOptionSelected = { onTimedReminderChange(it.minutes) },
                    optionLabel = { it.label },
                    iconEmoji = "⏰",
                    showDivider = true
                )

                // All-Day Events dropdown
                SettingsDropdownRow(
                    label = "All-Day Events",
                    options = ALL_DAY_REMINDER_OPTIONS,
                    selectedOption = selectedAllDayOption,
                    onOptionSelected = { onAllDayReminderChange(it.minutes) },
                    optionLabel = { it.label },
                    iconEmoji = "📅",
                    showDivider = false
                )
            }
        }
    }
}

/**
 * Simplified alerts sheet for single picker (timed OR all-day).
 *
 * Use this when you want separate sheets for each type.
 * Uses expanded list view (for backward compatibility with existing callers).
 *
 * @param sheetState Material3 sheet state
 * @param title Sheet title
 * @param options List of reminder options to show
 * @param currentValue Current selected value (minutes)
 * @param onSelect Callback when option is selected
 * @param onDismiss Callback when sheet is dismissed
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
            // Header
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Options
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
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
