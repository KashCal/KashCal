package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import org.onekash.kashcal.ui.shared.PresetChip
import org.onekash.kashcal.ui.shared.componentsToMinutes
import org.onekash.kashcal.ui.shared.formatReminderDuration
import org.onekash.kashcal.ui.shared.minutesToComponents
import org.onekash.kashcal.ui.shared.roundToWheelStep

/**
 * Shows preset chips above circular day, hour and minute [VerticalWheelPicker]s for a reminder
 * offset, with a summary label and a Done button.
 *
 * @param selectedMinutes the reminder's offset in minutes; negative is after the start (for
 *   all-day events, after midnight)
 * @param isAllDay switches the summary label to all-day wording
 * @param onDurationSelected called with the total minutes on each wheel move, chip tap and Done
 * @param onDismiss called on Done and after a chip tap
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WheelDurationPicker(
    selectedMinutes: Int,
    isAllDay: Boolean,
    use24Hour: Boolean,
    presets: List<PresetChip>,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val resources = LocalResources.current

    // The wheels show only non-negative "before" offsets. A negative offset such as the
    // all-day "9 AM day of" (-540) comes from a chip; opening the wheels on one shows 0d 0h 0m,
    // and scrolling produces a normal value.
    val (initDays, initHours, initMinsRaw) =
        if (selectedMinutes <= 0) Triple(0, 0, 0) else minutesToComponents(selectedMinutes)
    val initMins = roundToWheelStep(initMinsRaw)

    var currentDays by remember(selectedMinutes) { mutableIntStateOf(initDays) }
    var currentHours by remember(selectedMinutes) { mutableIntStateOf(initHours) }
    var currentMins by remember(selectedMinutes) { mutableIntStateOf(initMins) }

    val dayItems = (0..30).toList()
    val hourItems = (0..23).toList()
    val minuteItems = (0..55 step 5).toList()

    // Whether the user moved a wheel. Until then, a negative value the wheels can't show (the
    // all-day "9 AM day of" = -540) shows 0d 0h 0m but must not be silently rewritten to 0 on
    // Done.
    var touched by remember(selectedMinutes) { mutableStateOf(false) }

    // Drives the summary and the selected chip.
    val currentTotal = componentsToMinutes(currentDays, currentHours, currentMins)

    // The wheel total, except a negative original is kept until a wheel moves.
    val committedValue = if (touched || selectedMinutes >= 0) currentTotal else selectedMinutes

    fun notifyChange() {
        touched = true
        onDurationSelected(componentsToMinutes(currentDays, currentHours, currentMins))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { preset ->
                val isSelected = currentTotal == preset.minutes
                AssistChip(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val (d, h, m) = minutesToComponents(preset.minutes)
                        currentDays = d
                        currentHours = h
                        currentMins = roundToWheelStep(m)
                        onDurationSelected(preset.minutes)
                        onDismiss()
                    },
                    label = { Text(preset.label) },
                    colors = if (isSelected) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Days 0-30.
            VerticalWheelPicker(
                items = dayItems,
                selectedItem = currentDays,
                onItemSelected = { day ->
                    currentDays = day
                    notifyChange()
                },
                modifier = Modifier.weight(1f),
                visibleItems = 5,
                itemHeight = 36.dp,
                isCircular = true
            ) { day, isSelected ->
                Text(
                    text = "$day",
                    fontSize = if (isSelected) 18.sp else 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(R.string.label_duration_days),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Hours 0-23.
            VerticalWheelPicker(
                items = hourItems,
                selectedItem = currentHours,
                onItemSelected = { hour ->
                    currentHours = hour
                    notifyChange()
                },
                modifier = Modifier.weight(1f),
                visibleItems = 5,
                itemHeight = 36.dp,
                isCircular = true
            ) { hour, isSelected ->
                Text(
                    text = "$hour",
                    fontSize = if (isSelected) 18.sp else 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(R.string.label_duration_hrs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Minutes 0-55 in steps of 5.
            VerticalWheelPicker(
                items = minuteItems,
                selectedItem = currentMins,
                onItemSelected = { minute ->
                    currentMins = minute
                    notifyChange()
                },
                modifier = Modifier.weight(1f),
                visibleItems = 5,
                itemHeight = 36.dp,
                isCircular = true
            ) { minute, isSelected ->
                Text(
                    text = "$minute",
                    fontSize = if (isSelected) 18.sp else 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(R.string.label_duration_min),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = formatReminderDuration(currentTotal, isAllDay, use24Hour, resources),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        FilledTonalButton(
            onClick = {
                onDurationSelected(committedValue)
                onDismiss()
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.action_done))
        }
    }
}
