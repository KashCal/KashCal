package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar

/**
 * Bottom sheet for configuring device calendar integration.
 *
 * Features:
 * - Enable/disable toggle
 * - Permission gate (shows warning if READ_CALENDAR not granted)
 * - Write permission banner (shows if READ but not WRITE granted)
 * - Calendar list grouped by account with checkboxes
 * - Count footer
 *
 * Follows ContactBirthdaysSheet (toggle + permission) and
 * VisibleCalendarsSheet (grouped calendar list) patterns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCalendarsSheet(
    isEnabled: Boolean,
    hasReadPermission: Boolean,
    hasWritePermission: Boolean,
    deviceCalendars: List<DeviceCalendar>,
    enabledCalendarIds: Set<Long>,
    showDeclinedEvents: Boolean,
    deviceCalendarRemindersEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onToggleShowDeclined: (Boolean) -> Unit,
    onToggleDeviceCalendarReminders: (Boolean) -> Unit,
    onRequestWritePermission: () -> Unit,
    onRefresh: () -> Unit = {}
) {
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
            // Title with beta badge and refresh button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Device Calendars",
                        style = MaterialTheme.typography.titleLarge
                    )
                    BetaBadge()
                }
                if (isEnabled && hasReadPermission) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh calendars",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Description
            Text(
                "Show events from other calendar apps on your device (sync adapters, local calendars).",
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

            // Read permission warning (if enabled but no READ permission)
            AnimatedVisibility(
                visible = isEnabled && !hasReadPermission,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    "Calendar permission required. Tap Enable to grant access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Write permission banner (if has READ but not WRITE)
            AnimatedVisibility(
                visible = isEnabled && hasReadPermission && !hasWritePermission,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.shapes.small
                        )
                        .clickable { onRequestWritePermission() }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Read-only access",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Grant write permission to edit device calendar events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "Grant",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Calendar list (only visible when enabled and has read permission)
            AnimatedVisibility(
                visible = isEnabled && hasReadPermission,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (deviceCalendars.isEmpty()) {
                        Text(
                            "No device calendars found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Group by account
                        val grouped = deviceCalendars.groupBy { it.accountName }

                        grouped.forEach { (accountName, calendars) ->
                            // Account header
                            Text(
                                accountName.ifEmpty { "Local" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )

                            // Calendar rows
                            calendars.forEach { calendar ->
                                val isChecked = calendar.id in enabledCalendarIds
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onToggleCalendar(calendar.id, !isChecked)
                                        }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Calendar color dot
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color(calendar.color))
                                        )
                                        Text(
                                            calendar.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                onToggleCalendar(calendar.id, checked)
                                            }
                                        )
                                    }
                                    // Read-only indicator
                                    // Writable = has WRITE permission AND calendar has write access
                                    val isWritable = hasWritePermission && calendar.isWritable
                                    if (!isWritable) {
                                        Text(
                                            if (!hasWritePermission) "Read only (no write permission)"
                                            else "Read only (calendar is read-only)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Count footer
                        Spacer(modifier = Modifier.height(8.dp))
                        val enabledCount = enabledCalendarIds.size
                        val totalCount = deviceCalendars.size
                        Text(
                            "$enabledCount / $totalCount enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Show declined events toggle
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Show declined events",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = showDeclinedEvents,
                                onCheckedChange = onToggleShowDeclined
                            )
                        }

                        // Device calendar reminders toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Reminders",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Get notified for device calendar events",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = deviceCalendarRemindersEnabled,
                                onCheckedChange = onToggleDeviceCalendarReminders
                            )
                        }
                    }
                }
            }
        }
    }
}
