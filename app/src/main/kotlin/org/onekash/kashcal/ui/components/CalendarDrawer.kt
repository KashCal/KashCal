package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentSet
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Navigation drawer combining view mode switching and calendar visibility.
 *
 * Top: "KashCal" branding header.
 * View section: Compact rows for each view mode with selected state.
 * Calendars section: Room calendars grouped by account with checkboxes (left side).
 * Contacts section: Birthday/anniversary calendars under a single "Contacts" header.
 * Device calendars section: Only when feature enabled, with visibility toggles.
 */
@Composable
fun CalendarDrawer(
    currentViewMode: ViewMode,
    calendarGroups: ImmutableList<CalendarGroup>,
    deviceCalendarsEnabled: Boolean,
    enabledDeviceCalendars: ImmutableList<DeviceCalendar>,
    hiddenDeviceCalendarIds: PersistentSet<Long>,
    onViewSelect: (ViewMode) -> Unit,
    onToggleCalendar: (Long) -> Unit,
    onToggleDeviceCalendarVisibility: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (regularGroups, contactsGroups) = remember(calendarGroups) {
        calendarGroups.filter { it.calendars.isNotEmpty() }
            .partition { it.provider != AccountProvider.CONTACTS }
    }

    val groupedByAccount = remember(enabledDeviceCalendars) {
        enabledDeviceCalendars.groupBy { it.accountName }
    }

    ModalDrawerSheet(modifier = modifier.widthIn(max = 300.dp)) {
        LazyColumn {
            // ===== Branding Header =====
            item(key = "header") {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
            }

            // ===== Insights =====
            item(key = "insights") {
                val isSelected = currentViewMode == ViewMode.INSIGHTS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .clickable { onViewSelect(ViewMode.INSIGHTS) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.view_insights),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "insights_divider") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ===== View Mode Items (compact) =====
            items(
                items = viewOptions,
                key = { "view_${it.mode.key}" }
            ) { option ->
                val isSelected = currentViewMode == option.mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .clickable { onViewSelect(option.mode) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = viewModeLabel(option.mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ===== Calendars Section =====
            if (regularGroups.isNotEmpty()) {
                item(key = "cal_divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                item(key = "cal_header") {
                    Text(
                        text = stringResource(R.string.drawer_calendars),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                    )
                }

                regularGroups.forEach { group ->
                    // Account header
                    item(key = "account_${group.accountId}") {
                        Text(
                            text = group.accountName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp, vertical = 4.dp)
                                .padding(top = 4.dp)
                        )
                    }

                    // Calendar rows
                    items(
                        items = group.calendars,
                        key = { "room_cal_${it.id}" }
                    ) { calendar ->
                        CalendarCheckboxRow(
                            name = calendar.displayName,
                            color = Color(calendar.color),
                            checked = calendar.isVisible,
                            onClick = { onToggleCalendar(calendar.id) }
                        )
                    }
                }
            }

            // ===== Contacts Section (birthdays/anniversaries) =====
            if (contactsGroups.isNotEmpty()) {
                item(key = "contacts_header") {
                    Text(
                        text = stringResource(R.string.drawer_contacts),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 28.dp, vertical = 8.dp)
                            .padding(top = 4.dp)
                    )
                }

                // Flatten all contacts calendars — no per-account sub-headers
                contactsGroups.forEach { group ->
                    items(
                        items = group.calendars,
                        key = { "room_cal_${it.id}" }
                    ) { calendar ->
                        CalendarCheckboxRow(
                            name = calendar.displayName,
                            color = Color(calendar.color),
                            checked = calendar.isVisible,
                            onClick = { onToggleCalendar(calendar.id) }
                        )
                    }
                }
            }

            // ===== Device Calendars Section =====
            if (deviceCalendarsEnabled && enabledDeviceCalendars.isNotEmpty()) {
                item(key = "device_divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                item(key = "device_header") {
                    Text(
                        text = stringResource(R.string.drawer_device_calendars),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                    )
                }

                groupedByAccount.forEach { (accountName, calendars) ->
                    item(key = "device_account_$accountName") {
                        Text(
                            text = accountName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp, vertical = 4.dp)
                                .padding(top = 4.dp)
                        )
                    }

                    items(
                        items = calendars,
                        key = { "device_cal_${it.id}" }
                    ) { deviceCalendar ->
                        CalendarCheckboxRow(
                            name = deviceCalendar.displayName,
                            color = Color(deviceCalendar.color),
                            checked = deviceCalendar.id !in hiddenDeviceCalendarIds,
                            onClick = { onToggleDeviceCalendarVisibility(deviceCalendar.id) }
                        )
                    }
                }
            }

            // ===== App meta (Settings + Feedback) =====
            item(key = "settings_divider") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item(key = "settings") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onSettingsClick() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "feedback") {
                val uriHandler = LocalUriHandler.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { uriHandler.openUri("https://github.com/KashCal/KashCal/issues") }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.drawer_share_feedback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CalendarCheckboxRow(
    name: String,
    color: Color,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onClick() }
        )
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
