package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.InlineNote
import org.onekash.kashcal.ui.permission.ChangeNotificationsStep

/**
 * Opens the Calendar notifications sheet at full height: one switch per calendar KashCal shows,
 * on when the calendar notifies. The layout rules are on `CalendarNotificationsContent`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarNotificationsSheet(
    sections: List<CalendarNotificationSection>,
    deviceRemindersEnabled: Boolean,
    notificationsBlockedBy: ChangeNotificationsStep?,
    onToggle: (row: CalendarNotificationRow, muted: Boolean) -> Unit,
    onSetDeviceReminders: (enabled: Boolean) -> Unit,
    onFixNotifications: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        CalendarNotificationsContent(
            sections = sections,
            deviceRemindersEnabled = deviceRemindersEnabled,
            notificationsBlockedBy = notificationsBlockedBy,
            onToggle = onToggle,
            onSetDeviceReminders = onSetDeviceReminders,
            onFixNotifications = onFixNotifications,
        )
    }
}

/**
 * Lays out the sheet: the title with a Beta badge, the intro line with its info button
 * ([LineWithInfo]), an inline banner when notifications are blocked, then each section's
 * calendars. The Device calendars header carries the switch for all device calendar reminders and
 * an info button of its own.
 *
 * `notificationsBlockedBy` is what keeps notifications from showing, null when nothing does; the
 * banner names that cause: the permission, the app's notifications, or the event reminders
 * channel.
 *
 * A toggle commits at once and another tap undoes it, so there is no confirm or undo. The banner
 * never disables a switch. With the Device calendars switch off (`deviceRemindersEnabled` false)
 * the device rows keep their own values but are disabled, and that switch stays usable; Room rows
 * stay enabled.
 */
@Composable
internal fun CalendarNotificationsContent(
    sections: List<CalendarNotificationSection>,
    deviceRemindersEnabled: Boolean,
    notificationsBlockedBy: ChangeNotificationsStep?,
    onToggle: (row: CalendarNotificationRow, muted: Boolean) -> Unit,
    onSetDeviceReminders: (enabled: Boolean) -> Unit,
    onFixNotifications: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_calendar_notifications),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            BetaBadge()
        }
        LineWithInfo(
            detail = stringResource(R.string.calendar_notifications_info),
            infoDescription = stringResource(R.string.cd_calendar_notifications_info),
            modifier = Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.calendar_notifications_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        val banner = when (notificationsBlockedBy) {
            ChangeNotificationsStep.RequestPermission ->
                R.string.error_permission_notification to R.string.action_enable
            ChangeNotificationsStep.OpenAppSettings ->
                R.string.calendar_notifications_app_off to R.string.action_open_settings
            ChangeNotificationsStep.OpenChannelSettings ->
                R.string.calendar_notifications_reminders_off to R.string.action_open_settings
            ChangeNotificationsStep.TurnOn, ChangeNotificationsStep.TurnOff, null -> null
        }
        if (banner != null) {
            InlineNote(
                text = stringResource(banner.first),
                action = stringResource(banner.second),
                onAction = onFixNotifications,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            sections.forEach { section ->
                item(key = "header_${section.key}") {
                    val headerText: @Composable RowScope.() -> Unit = {
                        Text(
                            text = section.header,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).semantics { heading() }
                        )
                    }
                    if (section.isDevice) {
                        val switchLabel = stringResource(R.string.cd_device_calendar_notifications)
                        // The heading, the info button and the switch stay separate targets, so a
                        // tap on the heading can't silence every device calendar.
                        LineWithInfo(
                            detail = stringResource(R.string.calendar_notifications_device_info),
                            infoDescription = stringResource(R.string.cd_calendar_notifications_device_info),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                            trailing = {
                                Switch(
                                    checked = deviceRemindersEnabled,
                                    onCheckedChange = onSetDeviceReminders,
                                    modifier = Modifier.semantics { contentDescription = switchLabel },
                                )
                            },
                            line = headerText,
                        )
                    } else {
                        Row(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
                            headerText()
                        }
                    }
                }
                section.groups.forEachIndexed { index, group ->
                    // By index: an empty device account name and one named like the label share
                    // a sub-header text.
                    if (group.subheader != null) {
                        item(key = "sub_${section.key}_$index") {
                            Text(
                                text = group.subheader,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 24.dp, end = 24.dp, top = 8.dp)
                                    .semantics { heading() }
                            )
                        }
                    }
                    items(group.rows, key = { "${it.source}_${it.id}" }) { row ->
                        CalendarNotificationRowItem(
                            row = row,
                            enabled = row.source == CalendarNotificationSource.ROOM || deviceRemindersEnabled,
                            onToggle = onToggle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows [line] with an info button that opens [detail] in a tonal box below it, inline as on the
 * Manage tags screen, and an optional [trailing] control after the button. The detail starts
 * closed whenever its sheet opens, because a sheet composes this only while it is open.
 */
@Composable
internal fun LineWithInfo(
    detail: String,
    infoDescription: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    line: @Composable RowScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.cd_state_expanded else R.string.cd_state_collapsed)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            line()
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = state },
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = infoDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            trailing()
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(top = 4.dp, end = 16.dp),
            ) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** One calendar: the whole row is the switch's target, read by TalkBack as "<name>, switch". */
@Composable
private fun CalendarNotificationRowItem(
    row: CalendarNotificationRow,
    enabled: Boolean,
    onToggle: (row: CalendarNotificationRow, muted: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = row.notifies,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { notifies -> onToggle(row, !notifies) },
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(row.color))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.hidden) {
                Text(
                    text = stringResource(R.string.calendar_notifications_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The row handles the tap; a null callback keeps the switch from being a second target.
        Switch(checked = row.notifies, onCheckedChange = null, enabled = enabled)
    }
}
