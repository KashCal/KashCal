package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.ui.components.InlineNote
import org.onekash.kashcal.ui.components.SettingsTopAppBar

/** Test tag of the "Show device calendars" switch. */
const val DEVICE_CALENDARS_SWITCH_TAG = "device_calendars_switch"

/**
 * Shows the device calendars settings; stateless. The host (SettingsRoute, in
 * SettingsActivity) owns the permission launcher, so turning the switch on without calendar
 * access through [onToggle] asks for it, and the host turns the feature on once read access is
 * granted. The switch shows on only while the feature is on and read access is held.
 *
 * Without read access a note above the switch says calendar access is off, whatever the feature
 * setting; its Allow calls [onAllowCalendarAccess], which the host wires to the same request as
 * the switch. The note has no dismiss: it leaves once read access is held.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCalendarsScreen(
    isEnabled: Boolean,
    hasReadPermission: Boolean,
    deviceCalendars: List<DeviceCalendar>,
    enabledCalendarIds: Set<Long>,
    onNavigateBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleCalendar: (Long, Boolean) -> Unit,
    onAllowCalendarAccess: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    // On only while the feature is on and read access is held.
    val shownOn = isEnabled && hasReadPermission
    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NestedSettingsHeading(
                    text = stringResource(R.string.label_device_calendars),
                    modifier = Modifier
                )
                if (shownOn) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_calendars),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                stringResource(R.string.device_calendars_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(
                visible = !hasReadPermission,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                InlineNote(
                    text = stringResource(R.string.device_calendars_access_off),
                    action = stringResource(R.string.action_allow),
                    onAction = onAllowCalendarAccess,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            // The label and switch form one control, so a tap on either toggles it and TalkBack
            // reads them together.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = shownOn,
                        onValueChange = onToggle,
                        role = Role.Switch
                    )
                    .testTag(DEVICE_CALENDARS_SWITCH_TAG)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.device_calendars_show_switch),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = shownOn,
                    onCheckedChange = null
                )
            }

            AnimatedVisibility(
                visible = shownOn,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (deviceCalendars.isEmpty()) {
                        Text(
                            stringResource(R.string.device_calendars_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        if (deviceCalendars.none { it.id in enabledCalendarIds }) {
                            Text(
                                stringResource(R.string.device_calendars_pick_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val grouped = remember(deviceCalendars) {
                            deviceCalendars.groupBy { it.accountName }
                        }

                        grouped.forEach { (accountName, calendars) ->
                            // An empty account name shows as "Local".
                            Text(
                                accountName.ifEmpty { stringResource(R.string.device_calendars_local) },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )

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
                                    if (!calendar.isWritable) {
                                        Text(
                                            stringResource(R.string.device_calendars_read_only_calendar),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
