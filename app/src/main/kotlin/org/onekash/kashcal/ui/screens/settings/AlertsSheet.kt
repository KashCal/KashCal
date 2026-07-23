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
 * Sentinel seed for the custom wheel meaning "keep the current setting". The wheel
 * only produces non-negative durations and hands a negative, untouched seed straight
 * back (WheelDurationPicker keeps `selectedMinutes` when `!touched && < 0`), so a
 * staged value still equal to this sentinel means the user opened Custom but never
 * dialed — the current value is preserved rather than reset. Int.MIN_VALUE can never
 * collide with a real reminder offset and is never decomposed (the wheel clamps a
 * <= 0 seed to a neutral 0d 0h 0m display).
 */
internal const val WHEEL_KEEP_CURRENT = Int.MIN_VALUE

/** Largest day count the duration wheel offers (its day wheel is 0..30). */
private const val WHEEL_MAX_DAYS = 30

/**
 * Whether the duration wheel can seed [minutes] and hand back the SAME value when the
 * user taps Done without dialing. True only for a positive duration that fits the day
 * wheel (0..[WHEEL_MAX_DAYS]) and already lies on the wheel's 5-minute grid — mirroring
 * the wheel's own decompose → snap-minutes → recompose path. An off-grid value (e.g. 23
 * → snapped to 25) or an out-of-range day count would be silently altered on an
 * un-scrolled Done, so those seed the keep-current sentinel instead.
 */
private fun isWheelRepresentable(minutes: Int): Boolean {
    if (minutes <= 0) return false
    val (days, hours, mins) = minutesToComponents(minutes)
    if (days > WHEEL_MAX_DAYS) return false
    return componentsToMinutes(days, hours, roundToWheelStep(mins)) == minutes
}

/**
 * The value the custom wheel's Done commits, given the [staged] wheel value, the
 * [currentValue] the sheet opened with, and whether this is the [isAllDay] picker.
 *
 * Extracted as a pure function so its branches are deterministically testable
 * (the [WHEEL_KEEP_CURRENT] preserve path and the all-day neutral→None path are
 * hard to reach reliably through the wheel's gesture layer):
 * - [WHEEL_KEEP_CURRENT] staged → the user opened Custom without dialing; keep the
 *   current setting (a preset, or a non-representable all-day offset).
 * - all-day dialed to a neutral (<= 0) value → None ([REMINDER_OFF]); a midnight
 *   all-day alarm is meaningless.
 * - otherwise → the staged value (timed 0 = "at time of event" is kept here).
 */
internal fun committedAlertValue(staged: Int, currentValue: Int, isAllDay: Boolean): Int = when {
    staged == WHEEL_KEEP_CURRENT -> currentValue
    isAllDay && staged <= 0 -> REMINDER_OFF
    else -> staged
}

/**
 * Bottom sheet for picking a single default alert (timed OR all-day).
 *
 * A one-tap radio list of presets plus a final "Custom" row that swaps the
 * sheet body to the 3-wheel days/hours/minutes [WheelDurationPicker], so
 * arbitrary durations are never lost. The preset list is always the entry view
 * — the wheel is only shown after the user taps Custom, and a Back control
 * returns to the list, so a saved custom value never traps the user on the
 * wheel. Maps to a single `onDefaultReminder*Change` callback.
 *
 * @param sheetState Material3 sheet state
 * @param title Sheet header (e.g. "Timed event alert")
 * @param options Preset reminder options (includes "None" as [REMINDER_OFF])
 * @param currentValue Currently selected reminder minutes
 * @param isAllDay Whether this is the all-day picker (affects the wheel + labels)
 * @param use24Hour Whether to render times in 24-hour format
 * @param onSelect Callback with the chosen reminder minutes
 * @param onDismiss Callback when the sheet is dismissed
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
    // When true, the sheet body shows the custom wheel instead of the preset list.
    // The wheel is only ever reached by tapping Custom; a saved custom value shows
    // as a selected "Custom" row on the list, never auto-opening the wheel.
    var showCustomWheel by remember { mutableStateOf(false) }
    // The wheel emits onDurationSelected continuously as its centered item changes
    // (including mid-fling), so that signal only STAGES the value here — it must not
    // commit or dismiss, or the sheet would close on the first scroll tick and the
    // user could never reach their target. The commit happens once, on the wheel's
    // Done control, which fires onDurationSelected(final) then its onDismiss.
    // Seeded with the "keep current" sentinel: the wheel only ever produces
    // non-negative durations, so a negative staged value means the user tapped Done
    // without dialing anything, and the current setting is preserved (see onDismiss).
    var stagedCustomMinutes by remember { mutableStateOf(WHEEL_KEEP_CURRENT) }
    val resources = LocalResources.current

    // A saved value that isn't one of the presets is a custom duration. All-day
    // presets are "9 AM, N days before" offsets, so only positive non-preset values
    // are treated as custom durations for the wheel; all-day offsets stay on presets.
    val isCurrentCustom = currentValue != REMINDER_OFF && options.none { it.minutes == currentValue }
    // Seed the wheel with the current value only when the wheel can represent it
    // losslessly — a positive duration on the 5-minute grid. Anything else (a 9-AM
    // all-day offset, or an off-grid custom like 23 min the wheel would snap to 25)
    // seeds the sentinel so an un-scrolled Done preserves the exact current value
    // rather than committing a rounded or mis-decomposed one. A <= 0 seed also renders
    // as a neutral 0d 0h 0m and, if untouched, is handed straight back.
    val wheelSeed = if (isCurrentCustom && isWheelRepresentable(currentValue)) {
        currentValue
    } else {
        WHEEL_KEEP_CURRENT
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // While the custom wheel is showing, disable the sheet's drag gestures so
        // its swipe-to-dismiss doesn't steal the wheel's vertical scroll (which
        // otherwise makes a touch dismiss the sheet or drop mid-scroll). The wheel
        // has its own Back control, and tap-outside still dismisses.
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
            // All-day presets all fire at 9 AM; the labels stay terse and this hint
            // conveys the time (both 12h and 24h) once for the whole list.
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
                // Back to the preset list — the wheel's own "Done" commits a value,
                // so this is the only non-committing way back.
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
                    // Fires on every wheel change (incl. mid-fling): stage only, never
                    // commit/dismiss. Done calls this with the final value, then onDismiss.
                    onDurationSelected = { minutes -> stagedCustomMinutes = minutes },
                    onDismiss = {
                        // Done: commit the staged value (see committedAlertValue for the
                        // keep-current and all-day-neutral→None rules) and close the sheet.
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
                // Custom row: opens the wheel. Reflects a saved custom duration so the
                // user sees their current value and can tell it's selected.
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
 * Simplified alerts sheet for single picker (timed OR all-day).
 *
 * Use this when you want separate sheets for each type.
 * Uses expanded list view (for backward compatibility with existing callers).
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
            // All-day presets fire at 9 AM; the hint conveys the time once so the
            // terse option labels ("Day of event", "1 day before") stay clean.
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

/**
 * Single reminder option row (shared by the alert picker sheets).
 */
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
