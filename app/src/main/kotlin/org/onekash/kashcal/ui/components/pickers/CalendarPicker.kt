package org.onekash.kashcal.ui.components.pickers

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.PickerCalendar
import org.onekash.kashcal.ui.model.toPickerSections

/** Tags the divider above the picker's device calendars section. */
internal const val TAG_PICKER_DEVICE_DIVIDER = "calendar_picker_device_divider"

/** Tags the divider above the picker's Offline section. */
internal const val TAG_PICKER_OFFLINE_DIVIDER = "calendar_picker_offline_divider"

/**
 * Lists the calendars to pick from in [org.onekash.kashcal.ui.model.PickerSections] order,
 * ending with the New offline calendar row when [onNewOfflineCalendar] is set and an Offline group
 * is listed.
 */
@Composable
fun CalendarPickerContent(
    selectedCalendarId: Long?,
    calendarGroups: List<CalendarGroup>,
    deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    isSelectedDeviceCalendar: Boolean = false,
    onSelect: (Long, String, Int?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNewOfflineCalendar: (() -> Unit)? = null,
) {
    val sections = remember(calendarGroups, deviceCalendarGroups) {
        calendarGroups.toPickerSections(deviceCalendarGroups)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
    ) {
        if (sections.isEmpty) {
            Text(
                stringResource(R.string.empty_no_calendars),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
            return@Column
        }

        @Composable
        fun RoomGroup(group: CalendarGroup) {
            AccountHeader(group.accountName)
            group.calendars.forEach { calendar ->
                CalendarItem(
                    calendar = calendar,
                    isSelected = !isSelectedDeviceCalendar && selectedCalendarId == calendar.id,
                    onClick = { onSelect(calendar.id, calendar.displayName, calendar.color, false) }
                )
            }
        }

        sections.accounts.forEach { RoomGroup(it) }

        if (sections.device.isNotEmpty()) {
            if (sections.dividerBeforeDevice) SectionDivider(TAG_PICKER_DEVICE_DIVIDER)
            Text(
                text = stringResource(R.string.label_device_calendars),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            sections.device.forEach { group ->
                AccountHeader(group.accountName)
                group.pickerCalendars.forEach { pickerCal ->
                    PickerCalendarItem(
                        pickerCalendar = pickerCal,
                        isSelected = isSelectedDeviceCalendar && selectedCalendarId == pickerCal.id,
                        onClick = {
                            onSelect(pickerCal.id, pickerCal.displayName, pickerCal.color, true)
                        }
                    )
                }
            }
        }

        if (sections.offline.isNotEmpty()) {
            if (sections.dividerBeforeOffline) SectionDivider(TAG_PICKER_OFFLINE_DIVIDER)
            sections.offline.forEach { RoomGroup(it) }
            if (onNewOfflineCalendar != null) {
                NewOfflineCalendarItem(onClick = onNewOfflineCalendar)
            }
        }
    }
}

/** Draws an account header above that account's calendars. */
@Composable
private fun AccountHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .padding(top = 4.dp)
    )
}

/** Separates the device calendars or the Offline section from what is listed above it. */
@Composable
private fun SectionDivider(tag: String) {
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag(tag),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun CalendarPickerRow(
    selectedCalendarId: Long?,
    selectedCalendarName: String,
    selectedCalendarColor: Int?,
    calendarGroups: List<CalendarGroup>,
    deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    isSelectedDeviceCalendar: Boolean = false,
    isExpanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    onSelect: (Long, String, Int?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNewOfflineCalendar: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    EventFormRow(
        icon = Icons.Default.CalendarMonth,
        iconContentDescription = stringResource(R.string.label_calendar),
        isExpanded = isExpanded,
        showExpandIcon = true,
        enabled = enabled,
        onToggle = {
            focusManager.clearFocus()
            onToggle()
        },
        expandedContent = {
            CalendarPickerContent(
                selectedCalendarId = selectedCalendarId,
                calendarGroups = calendarGroups,
                deviceCalendarGroups = deviceCalendarGroups,
                isSelectedDeviceCalendar = isSelectedDeviceCalendar,
                onSelect = onSelect,
                onNewOfflineCalendar = onNewOfflineCalendar,
            )
        },
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedCalendarColor != null) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(selectedCalendarColor))
                )
            }
            Text(
                selectedCalendarName.ifEmpty { stringResource(R.string.dialog_select_calendar) },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/** Ends the Offline section with an item that starts creating an offline calendar. */
@Composable
private fun NewOfflineCalendarItem(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            stringResource(R.string.offline_calendar_new_in_picker),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Shows one Room calendar row in the picker list. */
@Composable
private fun CalendarItem(
    calendar: Calendar,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .padding(start = 8.dp), // Indent under account header
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(calendar.color))
        )
        Text(
            calendar.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
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

/**
 * Shows one [PickerCalendar] row. The type covers Room and device calendars; this file passes
 * device calendars.
 */
@Composable
private fun PickerCalendarItem(
    pickerCalendar: PickerCalendar,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onClick() }
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .padding(start = 8.dp), // Indent under account header
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(pickerCalendar.color))
        )
        Text(
            pickerCalendar.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
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

/**
 * Shows a flat Room calendar list grouped by account, with no expand state of its own.
 *
 * @param onCalendarSelect called with the tapped calendar's ID.
 */
@Composable
fun SimpleCalendarPicker(
    calendarGroups: List<CalendarGroup>,
    selectedCalendarId: Long?,
    onCalendarSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val allCalendars = calendarGroups.flatMap { it.calendars }
        if (allCalendars.isEmpty()) {
            Text(
                stringResource(R.string.empty_no_calendars),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        } else {
            calendarGroups.forEach { group ->
                Text(
                    text = group.accountName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .padding(top = 4.dp)
                )
                group.calendars.forEach { calendar ->
                    CalendarItem(
                        calendar = calendar,
                        isSelected = selectedCalendarId == calendar.id,
                        onClick = { onCalendarSelect(calendar.id) }
                    )
                }
            }
        }
    }
}
