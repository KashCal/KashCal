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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R
import org.onekash.kashcal.calendarbackup.CalendarBackupInterval
import org.onekash.kashcal.ui.components.VerticalWheelPicker
import org.onekash.kashcal.ui.shared.formatBackupInterval

/**
 * Holds the interval being picked on [BackupIntervalPicker]'s wheels and chips. Nothing is saved
 * until Done; an invalid choice stays as picked and is reported by [check].
 */
@Stable
class BackupIntervalPickerState(initialHours: Int) {
    var days by mutableIntStateOf(0)
    var hours by mutableIntStateOf(0)

    init {
        choose(initialHours)
    }

    val check: CalendarBackupInterval.Check get() = CalendarBackupInterval.check(days, hours)

    /** The chip matching the picked interval, in hours, or null for any other value. */
    val selectedChip: Int? get() = (check as? CalendarBackupInterval.Check.Valid)?.hours?.takeIf { it in CHIP_HOURS }

    /** Sets both wheels to [totalHours], as a chip tap does. */
    fun choose(totalHours: Int) {
        val (d, h) = CalendarBackupInterval.split(totalHours)
        days = d
        hours = h
    }

    companion object {
        /** The chips: 12 hours, 1 day, 7 days. */
        val CHIP_HOURS = listOf(12, 24, 168)

        val Saver: Saver<BackupIntervalPickerState, Int> = Saver(
            save = { it.days * 24 + it.hours },
            restore = { BackupIntervalPickerState(it) },
        )
    }
}

/** Returns a [BackupIntervalPickerState] opened on [initialHours] that survives rotation. */
@Composable
fun rememberBackupIntervalPickerState(initialHours: Int): BackupIntervalPickerState =
    rememberSaveable(initialHours, saver = BackupIntervalPickerState.Saver) {
        BackupIntervalPickerState(initialHours)
    }

/**
 * Picks how often automatic calendar backup runs: chips for 12 hours, 1 day and 7 days above
 * circular day (0 to 30) and hour (0 to 23) wheels, the resulting label, and Done, which reports
 * the total hours through [onDone]. Done is off while the choice is under 1 hour or over 30 days,
 * and a line says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupIntervalPicker(
    state: BackupIntervalPickerState,
    onDone: (hours: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val check = state.check
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackupIntervalPickerState.CHIP_HOURS.forEach { chip ->
                FilterChip(
                    selected = state.selectedChip == chip,
                    onClick = { state.choose(chip) },
                    label = { Text(chipLabel(chip)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                items = DAY_ITEMS,
                selected = state.days,
                onSelected = { state.days = it },
                unit = stringResource(R.string.label_duration_days),
                modifier = Modifier.weight(1f),
            )
            WheelColumn(
                items = HOUR_ITEMS,
                selected = state.hours,
                onSelected = { state.hours = it },
                unit = stringResource(R.string.label_duration_hrs),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        val summary = when (check) {
            is CalendarBackupInterval.Check.Valid -> formatBackupInterval(check.hours, resources)
            CalendarBackupInterval.Check.TooShort -> stringResource(R.string.calendar_backup_interval_too_short)
            CalendarBackupInterval.Check.TooLong -> stringResource(R.string.calendar_backup_interval_too_long)
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (check is CalendarBackupInterval.Check.Valid) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { (check as? CalendarBackupInterval.Check.Valid)?.let { onDone(it.hours) } },
            enabled = check is CalendarBackupInterval.Check.Valid,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
private fun chipLabel(hours: Int): String =
    if (hours % 24 == 0) {
        pluralStringResource(R.plurals.time_days, hours / 24, hours / 24)
    } else {
        pluralStringResource(R.plurals.time_hours, hours, hours)
    }

@Composable
private fun WheelColumn(
    items: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        VerticalWheelPicker(
            items = items,
            selectedItem = selected,
            onItemSelected = onSelected,
            modifier = Modifier.weight(1f),
            visibleItems = 5,
            itemHeight = 36.dp,
            isCircular = true,
        ) { item, isSelected ->
            Text(
                text = "$item",
                fontSize = if (isSelected) 18.sp else 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Text(text = unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DAY_ITEMS = (0..CalendarBackupInterval.MAX_DAYS).toList()
private val HOUR_ITEMS = (0..23).toList()
