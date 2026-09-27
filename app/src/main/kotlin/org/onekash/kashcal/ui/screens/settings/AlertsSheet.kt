package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.WheelDurationPicker
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.ReminderOption
import org.onekash.kashcal.ui.shared.componentsToMinutes
import org.onekash.kashcal.ui.shared.formatReminderDuration
import org.onekash.kashcal.ui.shared.minutesToComponents
import org.onekash.kashcal.ui.shared.roundToWheelStep

/**
 * Seeds the custom wheel with "keep the current setting". The wheel only produces
 * non-negative durations and hands an untouched negative seed straight back
 * ([WheelDurationPicker] keeps `selectedMinutes` when `!touched && < 0`), so a staged value
 * still equal to this means the user opened Custom but never dialed, and the current value is
 * kept. Int.MIN_VALUE can't collide with a real reminder offset and is never decomposed: the
 * wheel shows any <= 0 seed as 0d 0h 0m.
 */
internal const val WHEEL_KEEP_CURRENT = Int.MIN_VALUE

/** Largest day count the duration wheel offers (its day wheel is 0..30). */
private const val WHEEL_MAX_DAYS = 30

/**
 * Returns whether the wheel, seeded with [minutes], hands back the same value when the user
 * taps Done without dialing. True only for a positive duration that fits the day wheel
 * (0..[WHEEL_MAX_DAYS]) and already lies on the 5-minute grid, mirroring the wheel's
 * decompose, snap-minutes, recompose path. An off-grid value (23 snaps to 25) or too many days
 * would be silently altered on an untouched Done, so those seed [WHEEL_KEEP_CURRENT].
 */
private fun isWheelRepresentable(minutes: Int): Boolean {
    if (minutes <= 0) return false
    val (days, hours, mins) = minutesToComponents(minutes)
    if (days > WHEEL_MAX_DAYS) return false
    return componentsToMinutes(days, hours, roundToWheelStep(mins)) == minutes
}

/**
 * Returns the value the custom wheel's Done commits, given the [staged] wheel value and the
 * [currentValue] the sheet opened with.
 *
 * A pure function so its branches can be tested directly; the first two are hard to reach
 * through the wheel's gestures:
 * - [WHEEL_KEEP_CURRENT] staged: the user opened Custom without dialing; keep [currentValue],
 *   whatever it is (a preset, an off-grid duration, a non-representable all-day offset).
 * - all-day with a staged value <= 0: None ([REMINDER_OFF]); a midnight all-day alarm is
 *   meaningless.
 * - otherwise the staged value; a timed 0 ("at time of event") is kept.
 */
internal fun committedAlertValue(staged: Int, currentValue: Int, isAllDay: Boolean): Int = when {
    staged == WHEEL_KEEP_CURRENT -> currentValue
    isAllDay && staged <= 0 -> REMINDER_OFF
    else -> staged
}

/**
 * Shows a picker for one default alert, timed or all-day.
 *
 * A one-tap radio list of presets plus a final Custom row that swaps the body to the
 * days/hours/minutes [WheelDurationPicker], so any duration can be set. The preset list is
 * always the entry view: the wheel shows only after a tap on Custom, and a Back control returns
 * to the list, so a saved custom value never traps the user on the wheel.
 *
 * @param title sheet header, for example "Timed event alert"
 * @param options preset options, "None" ([REMINDER_OFF]) included
 * @param currentValue selected reminder minutes
 * @param isAllDay whether this is the all-day picker; changes the wheel, the labels and the
 *   9 AM hint
 * @param onSelect called with the chosen minutes, then [onDismiss] is called
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertPickerSheet(
    sheetState: SheetState,
    title: String,
    options: List<ReminderOption>,
    currentValue: Int,
    isAllDay: Boolean,
    use24Hour: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // True while the body shows the custom wheel. Only a tap on Custom opens it; a saved
    // custom value shows as a selected Custom row on the list.
    var showCustomWheel by remember { mutableStateOf(false) }
    // The wheel calls onDurationSelected on every change of its centered item, mid-fling
    // included, so that only stages the value here. It must not commit or dismiss, or the
    // sheet would close on the first scroll tick. The commit happens once, on the wheel's Done,
    // which calls onDurationSelected(final) and then its onDismiss. Starts at
    // [WHEEL_KEEP_CURRENT], so an undialed Done keeps the current setting.
    var stagedCustomMinutes by remember { mutableStateOf(WHEEL_KEEP_CURRENT) }
    val resources = LocalResources.current

    // A saved value other than None that isn't a preset shows as the selected Custom row.
    val isCurrentCustom = currentValue != REMINDER_OFF && options.none { it.minutes == currentValue }
    // Only a custom value that [isWheelRepresentable] accepts seeds the wheel. Anything else
    // (None, a preset, or a custom value the wheel would alter, like 23 minutes) seeds the
    // sentinel so an untouched Done keeps the exact current value instead of a rounded or
    // mis-decomposed one.
    val wheelSeed = if (isCurrentCustom && isWheelRepresentable(currentValue)) {
        currentValue
    } else {
        WHEEL_KEEP_CURRENT
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // While the wheel shows, the sheet's drag gestures are off so swipe-to-dismiss can't
        // steal the wheel's vertical scroll and dismiss the sheet or drop mid-scroll. The
        // wheel has its own Back control, and a tap outside still dismisses.
        sheetGesturesEnabled = !showCustomWheel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .selectableGroup()
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp, top = 12.dp,
                    bottom = if (isAllDay) 2.dp else 12.dp
                )
            )
            // All-day presets fire at 9 AM; this hint gives the time once, in 12h and 24h,
            // so the preset labels stay terse.
            if (isAllDay) {
                Text(
                    stringResource(R.string.all_day_alert_9am_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (showCustomWheel) {
                // The only way back to the list that commits nothing; the wheel's Done commits.
                TextButton(onClick = { showCustomWheel = false }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_back))
                }
                WheelDurationPicker(
                    selectedMinutes = wheelSeed,
                    isAllDay = isAllDay,
                    use24Hour = use24Hour,
                    presets = emptyList(),
                    // Stages only; see stagedCustomMinutes.
                    onDurationSelected = { minutes -> stagedCustomMinutes = minutes },
                    onDismiss = {
                        // Done: commit per [committedAlertValue] and close the sheet.
                        onSelect(committedAlertValue(stagedCustomMinutes, currentValue, isAllDay))
                        onDismiss()
                    }
                )
            } else {
                options.forEach { option ->
                    ReminderOptionRow(
                        label = option.label,
                        isSelected = option.minutes == currentValue,
                        onSelect = {
                            onSelect(option.minutes)
                            onDismiss()
                        }
                    )
                }
                // Opens the wheel. A saved custom value shows in the label and selects the row.
                val customLabel = if (isCurrentCustom) {
                    resources.getString(
                        R.string.settings_custom_alert_value,
                        formatReminderDuration(currentValue, isAllDay, use24Hour, resources)
                    )
                } else {
                    stringResource(R.string.label_custom)
                }
                ReminderOptionRow(
                    label = customLabel,
                    isSelected = isCurrentCustom,
                    onSelect = { showCustomWheel = true }
                )
            }
        }
    }
}

/**
 * Shows a one-tap radio list of preset alerts, timed or all-day, with no Custom row. Picking a
 * preset calls [onSelect] and then [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAlertPickerSheet(
    sheetState: SheetState,
    title: String,
    options: List<ReminderOption>,
    currentValue: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    isAllDay: Boolean = false
) {
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
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp, top = 12.dp,
                    bottom = if (isAllDay) 2.dp else 12.dp
                )
            )
            // All-day presets fire at 9 AM; the hint gives the time once so the option labels
            // ("Day of event", "1 day before") stay terse.
            if (isAllDay) {
                Text(
                    stringResource(R.string.all_day_alert_9am_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            options.forEach { option ->
                ReminderOptionRow(
                    label = option.label,
                    isSelected = option.minutes == currentValue,
                    onSelect = {
                        onSelect(option.minutes)
                        onDismiss()
                    }
                )
            }
        }
    }
}

/** Draws one radio row of the alert picker sheets, tinted with a check icon when selected. */
@Composable
private fun ReminderOptionRow(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                // Decorative: the row's radio-button selected state already announces selection.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
