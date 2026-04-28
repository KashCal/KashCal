package org.onekash.kashcal.ui.screens.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.text.format.DateFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.ColorPaletteSheet
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.formatReminderOption
import org.onekash.kashcal.ui.shared.getAllDayReminderOptions
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Dedicated screen for managing Contact Birthdays and Anniversaries.
 *
 * Two-card layout with Birthdays section and Anniversaries section.
 * Each section has: enable toggle, color picker, reminder picker, event count.
 * Footer shows contacts permission info.
 *
 * @param birthdaysEnabled Whether contact birthdays is enabled
 * @param birthdaysColor Current birthday calendar color
 * @param birthdaysReminder Current birthday reminder (minutes)
 * @param birthdayCount Number of birthday events
 * @param anniversariesEnabled Whether contact anniversaries is enabled
 * @param anniversariesColor Current anniversary calendar color
 * @param anniversariesReminder Current anniversary reminder (minutes)
 * @param anniversaryCount Number of anniversary events
 * @param hasPermission Whether READ_CONTACTS permission is granted
 * @param timeFormat Time format preference ("system", "12h", or "24h")
 * @param onToggleBirthdays Callback to enable/disable birthdays
 * @param onBirthdaysColorChange Callback for birthday color change
 * @param onBirthdaysReminderChange Callback for birthday reminder change
 * @param onToggleAnniversaries Callback to enable/disable anniversaries
 * @param onAnniversariesColorChange Callback for anniversary color change
 * @param onAnniversariesReminderChange Callback for anniversary reminder change
 * @param onNavigateBack Callback to navigate back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaysAndAnniversariesScreen(
    birthdaysEnabled: Boolean,
    birthdaysColor: Int,
    birthdaysReminder: Int,
    birthdayCount: Int,
    anniversariesEnabled: Boolean,
    anniversariesColor: Int,
    anniversariesReminder: Int,
    anniversaryCount: Int,
    hasPermission: Boolean,
    timeFormat: String,
    onToggleBirthdays: (Boolean) -> Unit,
    onBirthdaysColorChange: (Int) -> Unit,
    onBirthdaysReminderChange: (Int) -> Unit,
    onToggleAnniversaries: (Boolean) -> Unit,
    onAnniversariesColorChange: (Int) -> Unit,
    onAnniversariesReminderChange: (Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Compute use24Hour from timeFormat
    val context = LocalContext.current
    val use24Hour = DateTimeUtils.isUse24Hour(timeFormat, DateFormat.is24HourFormat(context))
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.birthdays_anniversaries_row_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            // ==================== Birthdays Section ====================
            SectionHeader(stringResource(R.string.birthdays_section_title))
            SettingsCard {
                ContactEventSection(
                    label = stringResource(R.string.settings_contact_birthdays),
                    description = stringResource(R.string.settings_contact_birthdays_description),
                    isEnabled = birthdaysEnabled,
                    calendarColor = birthdaysColor,
                    reminderMinutes = birthdaysReminder,
                    eventCount = birthdayCount,
                    reminderTitle = stringResource(R.string.settings_birthday_reminder),
                    use24Hour = use24Hour,
                    onToggle = onToggleBirthdays,
                    onColorChange = onBirthdaysColorChange,
                    onReminderChange = onBirthdaysReminderChange
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ==================== Anniversaries Section ====================
            SectionHeader(stringResource(R.string.anniversaries_section_title))
            SettingsCard {
                ContactEventSection(
                    label = stringResource(R.string.settings_contact_anniversaries),
                    description = stringResource(R.string.settings_contact_anniversaries_description),
                    isEnabled = anniversariesEnabled,
                    calendarColor = anniversariesColor,
                    reminderMinutes = anniversariesReminder,
                    eventCount = anniversaryCount,
                    reminderTitle = stringResource(R.string.settings_anniversary_reminder),
                    use24Hour = use24Hour,
                    onToggle = onToggleAnniversaries,
                    onColorChange = onAnniversariesColorChange,
                    onReminderChange = onAnniversariesReminderChange
                )
            }

            // ==================== Footer ====================
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_contacts_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            if ((birthdaysEnabled || anniversariesEnabled) && !hasPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_contacts_permission_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Reusable section for a contact event type (birthday or anniversary).
 * Contains toggle, color picker, reminder picker, and event count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEventSection(
    label: String,
    description: String,
    isEnabled: Boolean,
    calendarColor: Int,
    reminderMinutes: Int,
    eventCount: Int,
    reminderTitle: String,
    use24Hour: Boolean,
    onToggle: (Boolean) -> Unit,
    onColorChange: (Int) -> Unit,
    onReminderChange: (Int) -> Unit
) {
    var showReminderPicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (isEnabled && eventCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.event_count, eventCount, eventCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }

        // Description
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Color and Reminder (visible when enabled)
        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Color picker row
                Text(
                    stringResource(R.string.settings_calendar_color),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showColorPicker = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(calendarColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Text(
                        stringResource(EventColorPalette.stringResIdForColor(calendarColor)),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.action_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Reminder row
                Text(
                    stringResource(R.string.settings_default_reminder),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showReminderPicker = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatReminderOption(reminderMinutes, isAllDay = true, use24Hour = use24Hour, resources = LocalContext.current.resources),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.action_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // Reminder picker sheet
    if (showReminderPicker) {
        val reminderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        SingleAlertPickerSheet(
            sheetState = reminderSheetState,
            title = reminderTitle,
            options = getAllDayReminderOptions(use24Hour, LocalContext.current.resources),
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
        ColorPaletteSheet(
            selectedArgb = calendarColor,
            onColorSelected = { color ->
                if (color != calendarColor) onColorChange(color)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}
