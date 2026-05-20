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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.ui.components.KashCalTopAppBarTitle

/**
 * Stateless Device Calendars screen. Permission launchers live in the host
 * (SettingsActivity) so toggling Enable without READ_CALENDAR can prompt for
 * permission and route the result back to onToggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCalendarsScreen(
    isEnabled: Boolean,
    hasReadPermission: Boolean,
    hasWritePermission: Boolean,
    deviceCalendars: List<DeviceCalendar>,
    enabledCalendarIds: Set<Long>,
    deviceCalendarRemindersEnabled: Boolean,
    onNavigateBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onToggleDeviceCalendarReminders: (Boolean) -> Unit,
    onRequestWritePermission: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { KashCalTopAppBarTitle() },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    if (isEnabled && hasReadPermission) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_calendars),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NestedSettingsHeading(
                text = stringResource(R.string.label_device_calendars),
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Description
            Text(
                stringResource(R.string.device_calendars_description),
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
                    stringResource(R.string.action_enable),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }

            // Read permission warning (if enabled but no READ permission)
            AnimatedVisibility(
                visible = isEnabled && !hasReadPermission,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    stringResource(R.string.device_calendars_permission_required),
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
                            stringResource(R.string.device_calendars_read_only_access),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            stringResource(R.string.device_calendars_grant_write),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        stringResource(R.string.device_calendars_grant),
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
                            stringResource(R.string.device_calendars_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val grouped = remember(deviceCalendars) {
                            deviceCalendars.groupBy { it.accountName }
                        }

                        grouped.forEach { (accountName, calendars) ->
                            // Account header
                            Text(
                                accountName.ifEmpty { stringResource(R.string.device_calendars_local) },
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
                                    val isWritable = hasWritePermission && calendar.isWritable
                                    if (!isWritable) {
                                        Text(
                                            if (!hasWritePermission) stringResource(R.string.device_calendars_read_only_no_write)
                                            else stringResource(R.string.device_calendars_read_only_calendar),
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
                            stringResource(R.string.label_enabled_count, enabledCount, totalCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Device calendar reminders toggle
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.device_calendars_reminders),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.device_calendars_reminders_desc),
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
