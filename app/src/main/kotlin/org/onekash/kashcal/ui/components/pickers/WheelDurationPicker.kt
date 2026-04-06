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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import org.onekash.kashcal.ui.shared.PresetChip
import org.onekash.kashcal.ui.shared.componentsToMinutes
import org.onekash.kashcal.ui.shared.formatReminderDuration
import org.onekash.kashcal.ui.shared.minutesToComponents
import org.onekash.kashcal.ui.shared.roundToWheelStep

/**
 * Inline three-wheel duration picker for reminders.
 * Composes three [VerticalWheelPicker] instances for days, hours, and minutes.
 *
 * Follows WheelTimePicker.kt patterns:
 * - Uses centeringOffset (not contentPadding) for centering
 * - All wheels circular (isCircular = true)
 * - Pixel-based derivedStateOf center detection
 * - Haptic feedback on selection
 *
 * @param selectedMinutes Current total minutes for the reminder
 * @param isAllDay Whether the event is all-day (affects summary labels)
 * @param use24Hour Whether to use 24-hour format for time labels
 * @param presets Quick preset chips to show above the wheels
 * @param onDurationSelected Callback with total minutes when user changes value
 * @param onDismiss Called when user taps "Done"
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

    // Decompose total minutes into wheel components
    // Round minutes to nearest 5 for wheel display (preserves original until user edits)
    val (initDays, initHours, initMinsRaw) = minutesToComponents(selectedMinutes)
    val initMins = roundToWheelStep(initMinsRaw)

    var currentDays by remember(selectedMinutes) { mutableIntStateOf(initDays) }
    var currentHours by remember(selectedMinutes) { mutableIntStateOf(initHours) }
    var currentMins by remember(selectedMinutes) { mutableIntStateOf(initMins) }

    // Wheel item lists
    val dayItems = (0..30).toList()
    val hourItems = (0..23).toList()
    val minuteItems = (0..55 step 5).toList()

    // Current total for summary and chip matching
    val currentTotal = componentsToMinutes(currentDays, currentHours, currentMins)

    fun notifyChange() {
        val total = componentsToMinutes(currentDays, currentHours, currentMins)
        onDurationSelected(total)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Preset chips
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

        // Three-wheel row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Days wheel (0-30, circular)
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

            // "days" label
            Text(
                text = "days",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Hours wheel (0-23, circular)
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

            // "hours" label
            Text(
                text = "hrs",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Minutes wheel (0-55 step 5, circular)
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

            // "min" label
            Text(
                text = "min",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Duration summary
        Text(
            text = formatReminderDuration(currentTotal, isAllDay, use24Hour),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Done button
        FilledTonalButton(
            onClick = {
                onDurationSelected(currentTotal)
                onDismiss()
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Done")
        }
    }
}
