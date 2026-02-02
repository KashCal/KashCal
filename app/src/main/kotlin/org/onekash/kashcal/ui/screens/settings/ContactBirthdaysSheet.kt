package org.onekash.kashcal.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.components.pickers.ColorPickerSheet
import org.onekash.kashcal.ui.components.pickers.argbToHex
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.formatReminderOption

/**
 * Bottom sheet for configuring Contact Birthdays calendar.
 *
 * Features:
 * - Enable/disable toggle
 * - Color picker (visible when enabled)
 * - Reminder setting (visible when enabled)
 * - Last sync time display
 * - Permission status indicator
 *
 * @param isEnabled Whether contact birthdays is currently enabled
 * @param calendarColor Current calendar color (ARGB int)
 * @param reminderMinutes Current birthday reminder setting (minutes, -1 for no reminder)
 * @param lastSyncTime Last sync timestamp (millis), 0 if never synced
 * @param hasPermission Whether READ_CONTACTS permission is granted
 * @param onDismiss Callback when sheet is dismissed
 * @param onToggle Callback when toggle is changed (will trigger permission request if needed)
 * @param onColorChange Callback when color is changed
 * @param onReminderChange Callback when reminder setting is changed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactBirthdaysSheet(
    isEnabled: Boolean,
    calendarColor: Int,
    reminderMinutes: Int,
    lastSyncTime: Long,
    hasPermission: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onColorChange: (Int) -> Unit,
    onReminderChange: (Int) -> Unit
) {
    var selectedColor by remember(calendarColor) { mutableIntStateOf(calendarColor) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    val colorPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                "Contact Birthdays",
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Description
            Text(
                "Show birthdays from your phone contacts as all-day calendar events.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Enable Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enable",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { enabled ->
                        onToggle(enabled)
                    }
                )
            }

            // Color Picker trigger (only visible when enabled)
            AnimatedVisibility(
                visible = isEnabled,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Calendar Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showColorPicker = true }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(selectedColor))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Text(
                            "#${argbToHex(selectedColor)}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Change",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Reminder Setting (only visible when enabled)
            AnimatedVisibility(
                visible = isEnabled,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Default Reminder",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReminderPicker = true }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatReminderOption(reminderMinutes, isAllDay = true),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Change",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Sync Status (only visible when enabled)
            AnimatedVisibility(
                visible = isEnabled,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))

                    val syncStatusText = if (lastSyncTime > 0) {
                        val relativeTime = DateUtils.getRelativeTimeSpanString(
                            lastSyncTime,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        )
                        "Last synced: $relativeTime"
                    } else {
                        "Syncing..."
                    }

                    Text(
                        syncStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Permission warning (if enabled but no permission)
                    if (!hasPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Contacts permission required",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    // Reminder picker sheet
    if (showReminderPicker) {
        val reminderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        SingleAlertPickerSheet(
            sheetState = reminderSheetState,
            title = "Birthday Reminder",
            options = ALL_DAY_REMINDER_OPTIONS,
            currentValue = reminderMinutes,
            onSelect = { minutes ->
                onReminderChange(minutes)
                showReminderPicker = false
            },
            onDismiss = { showReminderPicker = false }
        )
    }

    // Color picker sheet
    if (showColorPicker) {
        ColorPickerSheet(
            sheetState = colorPickerSheetState,
            currentColor = selectedColor,
            onColorSelected = { color ->
                selectedColor = color
                onColorChange(color)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}
