package org.onekash.kashcal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ShareAvailabilityUiState
import org.onekash.kashcal.util.DateTimeUtils

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
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
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

@OptIn(ExperimentalMaterial3Api::class)
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
            .padding(bottom = 24.dp)
    ) {
        // ----- Header bar -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Spacer matching Done's intrinsic width (best-effort; small
            // visual asymmetry is tolerable to keep the layout simple).
            Spacer(modifier = Modifier.width(72.dp))
            Text(
                text = stringResource(R.string.share_availability_sheet_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .wrapContentWidth(Alignment.End)
            ) {
                Text(text = stringResource(R.string.action_done))
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // ----- Days card -----
            DaysCard(
                days = uiState.days,
                onDaysPreview = onDaysPreview,
                onDaysCommit = onDaysCommit
            )

            Spacer(Modifier.height(12.dp))

            // ----- Working hours card (24h day strip) -----
            WorkingHoursCard(
                workStartMin = uiState.workStartMin,
                workEndMin = uiState.workEndMin,
                is24Hour = is24Hour,
                onHoursPreview = onHoursPreview,
                onHoursCommit = onHoursCommit
            )

            Spacer(Modifier.height(12.dp))

            // ----- All-day grouped row -----
            AllDayRow(
                includeAllDay = uiState.includeAllDay,
                onAllDayToggle = onAllDayToggle
            )

            Spacer(Modifier.height(20.dp))

            // ----- Bubble preview (grows freely with the outer scroll) -----
            BubblePreview(text = uiState.previewText)

            Spacer(Modifier.height(20.dp))

            // ----- Share button (full width) -----
            Button(
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
}

@Composable
private fun BubblePreview(text: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.85f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 4.dp
                ),
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DaysCard(
    days: Int,
    onDaysPreview: (Int) -> Unit,
    onDaysCommit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Section header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.share_availability_window_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                val locale = remember { Locale.getDefault() }
                val formatter = remember(locale) {
                    DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEMMMd"), locale)
                }
                val today = remember { LocalDate.now() }
                val formatted = remember(today, days, formatter) {
                    formatter.format(today.plusDays((days - 1).coerceAtLeast(0).toLong()))
                }
                Text(
                    text = stringResource(R.string.share_availability_days_through, formatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            // Hero number
            val heroLabel = pluralStringResource(
                R.plurals.share_availability_days_hero, days, days
            )
            Text(
                text = heroLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Slider + tick labels
            // Accessibility: stateDescription uses the original "Next N days"
            // plural so screen readers announce the same phrase as before the
            // visual redesign — the hero "N days" text is decorative.
            val a11yLabel = pluralStringResource(
                R.plurals.share_availability_days_label, days, days
            )
            Slider(
                value = days.toFloat(),
                onValueChange = { v -> onDaysPreview(Math.round(v).coerceIn(1, 14)) },
                onValueChangeFinished = onDaysCommit,
                valueRange = 1f..14f,
                steps = 12,
                modifier = Modifier.semantics { stateDescription = a11yLabel }
            )

            // Tick labels positioned at the slider's anchor values 1, 3, 7, 14.
            DaysTicks()
        }
    }
}

@Composable
private fun DaysTicks() {
    // Position labels by fractional offset so they line up with the slider's
    // value-to-pixel mapping (value 1..14 → fraction 0..1).
    val tickValues = listOf(1, 3, 7, 14)
    Layout(
        content = {
            tickValues.forEach { v ->
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val fractions = tickValues.map { (it - 1) / 13f }
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p ->
                val centerX = (fractions[i] * width).toInt()
                val rawX = centerX - p.width / 2
                val x = rawX.coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x = x, y = 0)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkingHoursCard(
    workStartMin: Int,
    workEndMin: Int,
    is24Hour: Boolean,
    onHoursPreview: (Int, Int) -> Unit,
    onHoursCommit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Section header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.share_availability_hours_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                val durationMinutes = (workEndMin - workStartMin).coerceAtLeast(0)
                val durationLabel = formatHourDuration(durationMinutes)
                Text(
                    text = stringResource(R.string.share_availability_window_duration, durationLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // ----- Day-strip range slider -----
            val workStartLabel = formatMinutesAsClock(workStartMin, is24Hour)
            val workEndLabel = formatMinutesAsClock(workEndMin, is24Hour)
            val workHoursLabel = stringResource(
                R.string.share_availability_hours_label, workStartLabel, workEndLabel
            )
            DayStripRangeSlider(
                workStartMin = workStartMin,
                workEndMin = workEndMin,
                onValueChange = { range ->
                    val rawStart = (Math.round(range.start / 30f) * 30).coerceIn(0, 1440)
                    val rawEnd = (Math.round(range.endInclusive / 30f) * 30).coerceIn(0, 1440)
                    val startMoving = rawStart != workStartMin
                    val endMoving = rawEnd != workEndMin
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
                stateDescription = workHoursLabel
            )

            Spacer(Modifier.height(2.dp))

            // ----- Axis labels -----
            DayStripAxis(is24Hour = is24Hour)

            Spacer(Modifier.height(10.dp))

            // ----- Pills row: start → end -----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimePill(text = workStartLabel)
                Text(
                    text = " → ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                TimePill(text = workEndLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayStripRangeSlider(
    workStartMin: Int,
    workEndMin: Int,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onValueChangeFinished: () -> Unit,
    stateDescription: String
) {
    // Theme-adaptive dim color for outside-window regions (matches surface so
    // light/dark themes both read naturally).
    val dimColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary

    val gradient = remember {
        Brush.horizontalGradient(
            0.0f to Color(0xFF1A1B3A),  // midnight
            0.25f to Color(0xFFFFB55A), // dawn
            0.5f to Color(0xFFFFE9A8),  // mid-day
            0.75f to Color(0xFF7A5DC9), // dusk
            1.0f to Color(0xFF1A1B3A)   // midnight
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        // Day-strip background (gradient + dim outside window).
        // Coordinate space matches the RangeSlider on top: both use the full
        // BoxWithConstraints width so thumb position and the highlighted
        // window outline track each other across the entire 0..1440 range.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .align(Alignment.Center)
        ) {
            drawRect(brush = gradient)
            val startFraction = (workStartMin / 1440f).coerceIn(0f, 1f)
            val endFraction = (workEndMin / 1440f).coerceIn(0f, 1f)
            // Dim left of window
            if (startFraction > 0f) {
                drawRect(
                    color = dimColor,
                    topLeft = Offset.Zero,
                    size = Size(startFraction * size.width, size.height)
                )
            }
            // Dim right of window
            if (endFraction < 1f) {
                drawRect(
                    color = dimColor,
                    topLeft = Offset(endFraction * size.width, 0f),
                    size = Size(size.width - endFraction * size.width, size.height)
                )
            }
            // Highlight outline on selected window
            val left = startFraction * size.width
            val right = endFraction * size.width
            val outlineWidth = 3f
            drawRect(
                color = accent,
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(0f), outlineWidth)
            )
            drawRect(
                color = accent,
                topLeft = Offset(left, size.height - outlineWidth),
                size = Size((right - left).coerceAtLeast(0f), outlineWidth)
            )
        }

        // The actual interactive RangeSlider sits on top. Its track slot is a
        // transparent full-width placeholder so the gradient strip shows
        // through while still giving the thumbs a track to travel along.
        RangeSlider(
            value = workStartMin.toFloat()..workEndMin.toFloat(),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1440f,
            steps = (1440 / 30) - 1,
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .semantics { this.stateDescription = stateDescription }
        )
    }
}

@Composable
private fun DayStripAxis(is24Hour: Boolean) {
    // Four anchor labels at fractions 0, 0.25, 0.5, 0.75. The right edge
    // (fraction 1.0) is implicitly midnight again; labeling both ends is
    // visual noise.
    val labels = if (is24Hour) {
        listOf("00", "06", "12", "18")
    } else {
        listOf("12 AM", "6 AM", "12 PM", "6 PM")
    }
    Layout(
        content = {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val fractions = listOf(0f, 0.25f, 0.5f, 0.75f)
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p ->
                val centerX = (fractions[i] * width).toInt()
                val rawX = centerX - p.width / 2
                val x = rawX.coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.place(x = x, y = 0)
            }
        }
    }
}

@Composable
private fun TimePill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AllDayRow(
    includeAllDay: Boolean,
    onAllDayToggle: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAllDayToggle(!includeAllDay) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.share_availability_all_day_toggle_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            )
            Switch(
                checked = includeAllDay,
                onCheckedChange = onAllDayToggle
            )
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

/**
 * Format a duration in minutes as a number of hours suitable for the
 * "X hour window" caption. Whole hours render as integers (e.g. "8");
 * fractional values render with one decimal in the user's locale (e.g.
 * "1.5" en-US, "1,5" de-DE). Half-hour granularity is enforced upstream
 * by the slider quantization.
 */
private fun formatHourDuration(minutes: Int): String {
    val whole = minutes / 60
    val rem = minutes % 60
    val locale = Locale.getDefault()
    return if (rem == 0) whole.toString()
    else String.format(locale, "%.1f", minutes / 60.0)
}
