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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.DurationOption
import org.onekash.kashcal.ui.shared.EVENT_DURATION_OPTIONS
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.formatDurationShort
import org.onekash.kashcal.ui.shared.formatReminderShort

/**
 * Bottom sheet for selecting default alerts/reminders and event duration.
 *
 * Shows timed reminders, all-day reminders, and event duration options in a single sheet.
 *
 * @param sheetState Material3 sheet state
 * @param defaultReminderTimed Current default for timed events (minutes)
 * @param defaultReminderAllDay Current default for all-day events (minutes)
 * @param defaultEventDuration Current default event duration (minutes)
 * @param onTimedReminderChange Callback when timed reminder changes
 * @param onAllDayReminderChange Callback when all-day reminder changes
 * @param onEventDurationChange Callback when event duration changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    sheetState: SheetState,
    defaultReminderTimed: Int,
    defaultReminderAllDay: Int,
    defaultEventDuration: Int,
    onTimedReminderChange: (Int) -> Unit,
    onAllDayReminderChange: (Int) -> Unit,
    onEventDurationChange: (Int) -> Unit,
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
                "Default Alerts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Summary
            Text(
                "Scheduled: ${formatReminderShort(defaultReminderTimed)} · All-day: ${formatReminderShort(defaultReminderAllDay)} · Duration: ${formatDurationShort(defaultEventDuration)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Timed events section
            AlertSectionHeader("Scheduled Events")
            TIMED_REMINDER_OPTIONS.forEach { option ->
                ReminderOptionRow(
                    option = option,
                    isSelected = option.minutes == defaultReminderTimed,
                    onSelect = { onTimedReminderChange(option.minutes) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // All-day events section
            AlertSectionHeader("All-Day Events")
            ALL_DAY_REMINDER_OPTIONS.forEach { option ->
                ReminderOptionRow(
                    option = option,
                    isSelected = option.minutes == defaultReminderAllDay,
                    onSelect = { onAllDayReminderChange(option.minutes) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Event duration section
            AlertSectionHeader("Event Duration")
            EVENT_DURATION_OPTIONS.forEach { option ->
                DurationOptionRow(
                    option = option,
                    isSelected = option.minutes == defaultEventDuration,
                    onSelect = { onEventDurationChange(option.minutes) }
                )
            }
        }
    }
}

/**
 * Section header for reminder categories.
 */
@Composable
private fun AlertSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (title == "Scheduled Events") "⏰" else "📅",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Single reminder option row.
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

/**
 * Single duration option row.
 */
@Composable
private fun DurationOptionRow(
    option: DurationOption,
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

/**
 * Simplified alerts sheet for single picker (timed OR all-day).
 *
 * Use this when you want separate sheets for each type.
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
