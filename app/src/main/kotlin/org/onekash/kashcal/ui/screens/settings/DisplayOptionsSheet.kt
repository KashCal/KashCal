package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.getEventDurationOptions

/**
 * Bottom sheet for default event duration setting.
 *
 * A one-tap radio list of duration options for new events, matching the
 * [FirstDayOfWeekSheet]/[SyncLookbackSheet] pattern: tap an option to select
 * it and dismiss.
 *
 * @param sheetState Material3 sheet state
 * @param defaultEventDuration Current default event duration (minutes)
 * @param onEventDurationChange Callback when duration changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDurationSheet(
    sheetState: SheetState,
    defaultEventDuration: Int,
    onEventDurationChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val durationOptions = getEventDurationOptions(LocalResources.current)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .selectableGroup()
        ) {
            Text(
                stringResource(R.string.settings_default_event_length),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
            )
            Text(
                stringResource(R.string.settings_default_event_length_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            durationOptions.forEach { option ->
                OptionRow(
                    label = option.label,
                    isSelected = option.minutes == defaultEventDuration,
                    onSelect = {
                        onEventDurationChange(option.minutes)
                        onDismiss()
                    }
                )
            }
        }
    }
}

private val WIDGET_EVENT_LIMIT_OPTIONS = listOf(3, 5, 8, 10, 15)

/**
 * Bottom sheet for configuring widget event limit.
 *
 * A one-tap radio list; labels read "N per day" to match the value shown on
 * the settings row.
 *
 * @param sheetState Material3 sheet state
 * @param currentLimit Current widget event limit
 * @param onLimitChange Callback when limit changes
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetEventLimitSheet(
    sheetState: SheetState,
    currentLimit: Int,
    onLimitChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val perDayTemplate = stringResource(R.string.settings_per_day)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .selectableGroup()
        ) {
            Text(
                stringResource(R.string.settings_widget_event_limit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
            )
            Text(
                stringResource(R.string.settings_widget_event_limit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            WIDGET_EVENT_LIMIT_OPTIONS.forEach { limit ->
                OptionRow(
                    label = perDayTemplate.format(limit),
                    isSelected = limit == currentLimit,
                    onSelect = {
                        onLimitChange(limit)
                        onDismiss()
                    }
                )
            }
        }
    }
}

/**
 * A single selectable option row in a picker sheet: label + trailing check
 * when selected, highlighted background when selected. Tapping selects.
 */
@Composable
private fun OptionRow(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                // Decorative: the row's radio-button selected state already announces selection.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

