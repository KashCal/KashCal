package org.onekash.kashcal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ShareAvailabilityUiState

/**
 * Modal bottom sheet that lets the user configure the share-availability
 * window (days, working hours, all-day handling) and shares a plain-text
 * summary via the Android chooser.
 *
 * Stateless content split off ([ShareAvailabilitySheetContent]) so it can be
 * driven directly from compose tests without a Hilt VM or sheet host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareAvailabilitySheet(
    uiState: ShareAvailabilityUiState,
    is24Hour: Boolean,
    onDaysPreview: (Int) -> Unit,
    onDaysCommit: () -> Unit,
    onHoursPreview: (Int, Int) -> Unit,
    onHoursCommit: () -> Unit,
    onAllDayToggle: (Boolean) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ShareAvailabilitySheetContent(
            uiState = uiState,
            is24Hour = is24Hour,
            onDaysPreview = onDaysPreview,
            onDaysCommit = onDaysCommit,
            onHoursPreview = onHoursPreview,
            onHoursCommit = onHoursCommit,
            onAllDayToggle = onAllDayToggle,
            onShare = onShare,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun ShareAvailabilitySheetContent(
    uiState: ShareAvailabilityUiState,
    is24Hour: Boolean,
    onDaysPreview: (Int) -> Unit,
    onDaysCommit: () -> Unit,
    onHoursPreview: (Int, Int) -> Unit,
    onHoursCommit: () -> Unit,
    onAllDayToggle: (Boolean) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.share_availability_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(20.dp))

        // ----- Days slider -----
        val daysLabel = pluralStringResource(
            R.plurals.share_availability_days_label,
            uiState.days,
            uiState.days
        )
        Text(
            text = daysLabel,
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = uiState.days.toFloat(),
            onValueChange = { v -> onDaysPreview(v.toInt().coerceIn(1, 14)) },
            onValueChangeFinished = onDaysCommit,
            valueRange = 1f..14f,
            steps = 12, // 14 - 1 - 1 = 12 internal steps for integer snaps
            modifier = Modifier.semantics { stateDescription = daysLabel }
        )

        Spacer(Modifier.height(16.dp))

        // ----- Working hours range slider -----
        val workStartLabel = formatMinutesAsClock(uiState.workStartMin, is24Hour)
        val workEndLabel = formatMinutesAsClock(uiState.workEndMin, is24Hour)
        val workHoursLabel = stringResource(
            R.string.share_availability_hours_label, workStartLabel, workEndLabel
        )
        Text(
            text = workHoursLabel,
            style = MaterialTheme.typography.titleMedium
        )
        RangeSlider(
            value = uiState.workStartMin.toFloat()..uiState.workEndMin.toFloat(),
            onValueChange = { range ->
                val rawStart = (range.start.toInt() / 30) * 30
                val rawEnd = (range.endInclusive.toInt() / 30) * 30
                // Enforce a 60-min minimum window by clamping the moving thumb
                // toward the static one rather than silently dropping the
                // change. Detect which thumb is moving by comparing to current
                // state.
                val startMoving = rawStart != uiState.workStartMin
                val endMoving = rawEnd != uiState.workEndMin
                val (newStart, newEnd) = when {
                    startMoving && rawEnd - rawStart < 60 ->
                        (rawEnd - 60).coerceAtLeast(0) to rawEnd
                    endMoving && rawEnd - rawStart < 60 ->
                        rawStart to (rawStart + 60).coerceAtMost(1440)
                    else -> rawStart to rawEnd
                }
                if (newEnd - newStart >= 60 && newStart >= 0 && newEnd <= 1440) {
                    onHoursPreview(newStart, newEnd)
                }
            },
            onValueChangeFinished = onHoursCommit,
            valueRange = 0f..1440f,
            steps = (1440 / 30) - 1,
            modifier = Modifier.semantics { stateDescription = workHoursLabel }
        )

        Spacer(Modifier.height(16.dp))

        // ----- All-day toggle -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAllDayToggle(!uiState.includeAllDay) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.share_availability_all_day_toggle_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(
                        if (uiState.includeAllDay)
                            R.string.share_availability_all_day_toggle_subtitle_on
                        else
                            R.string.share_availability_all_day_toggle_subtitle_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = uiState.includeAllDay,
                onCheckedChange = onAllDayToggle
            )
        }

        Spacer(Modifier.height(20.dp))

        // ----- Preview card -----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = uiState.previewText,
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(min = 80.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
        }

        Spacer(Modifier.height(20.dp))

        // ----- Share button -----
        FilledTonalButton(
            onClick = { onShare(uiState.previewText) },
            enabled = uiState.isShareEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(text = stringResource(R.string.share_availability_share_button))
        }
    }
}

private fun formatMinutesAsClock(minutes: Int, is24Hour: Boolean): String {
    val safeMinutes = minutes.coerceIn(0, 1440)
    // 1440 = end of day; LocalTime can't represent 24:00, so render the
    // boundary explicitly. In 12h mode show "12:00 AM" (midnight); in 24h
    // mode show "24:00". Without this, both modes would display the same as
    // 23:59 / 11:59 PM, lying to the user about a one-minute gap.
    if (safeMinutes >= 1440) {
        return if (is24Hour) "24:00" else "12:00 AM"
    }
    val h = safeMinutes / 60
    val m = safeMinutes % 60
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(LocalTime.of(h, m))
}
