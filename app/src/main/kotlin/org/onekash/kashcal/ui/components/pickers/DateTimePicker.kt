package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.WheelTimePicker
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar as JavaCalendar

/**
 * Selection mode for date range picker.
 * Tracks which date (start or end) the user is currently editing.
 */
enum class DateSelectionMode {
    START,
    END
}

/**
 * Active datetime sheet state.
 * Tracks which combined datetime sheet is currently open.
 */
enum class ActiveDateTimeSheet {
    NONE,
    START,
    END
}

/**
 * Date/time picker card that displays the selected date and time.
 * Clicking opens a datetime picker sheet.
 *
 * @param label "Starts" or "Ends" - floats on border like OutlinedTextField
 * @param dateMillis Date timestamp for display
 * @param hour Hour (0-23)
 * @param minute Minute (0-59)
 * @param isAllDay Hide time when true
 * @param onClick Opens datetime picker sheet
 * @param isError Show error state with red border and strikethrough text
 * @param errorMessage Error message to display below the field
 * @param timezone Timezone ID for display (null = device default, no suffix shown)
 */
@Deprecated("Use DateTimeDisplayRow instead", level = DeprecationLevel.WARNING)
@Composable
fun DateTimePickerCard(
    label: String,
    dateMillis: Long,
    hour: Int,
    minute: Int,
    isAllDay: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
    timezone: String? = null,
    timePattern: String = "h:mm a"
) {
    val focusManager = LocalFocusManager.current

    // Get timezone abbreviation for badge (only when different from device timezone)
    val deviceTimezone = TimezoneUtils.getDeviceTimezone()
    val timezoneAbbrev = if (!isAllDay && timezone != null && timezone != deviceTimezone) {
        TimezoneUtils.getAbbreviation(timezone)
    } else null

    val displayText = buildString {
        append(formatDateForPicker(dateMillis, isAllDay))
        if (!isAllDay) {
            append("   ")
            append(DateTimeUtils.formatTime(hour, minute, timePattern))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.cd_select_label, label))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
            } else null,
            textStyle = androidx.compose.ui.text.TextStyle(
                textDecoration = if (isError) TextDecoration.LineThrough else TextDecoration.None
            )
        )

        // Timezone badge - floats on top-right border, mirrors "Starts" label on left
        if (timezoneAbbrev != null) {
            Text(
                text = timezoneAbbrev,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = 0.dp) // Match M3 floating label (centered on border)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp)
            )
        }

        // Invisible clickable overlay (OutlinedTextField readOnly still shows cursor on tap)
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                    onClick()
                }
        )
    }
}

/**
 * Format date for picker display.
 * Always uses local timezone since form state is already in local time.
 *
 * Note: For all-day events, form state contains local midnight (converted from
 * UTC via utcMidnightToLocalDate). We must NOT pass isAllDay=true here, as that
 * would re-interpret the local timestamp as UTC, causing wrong date display in
 * UTC+ timezones (e.g., Australia UTC+11 would show Jan 5 instead of Jan 6).
 */
@Suppress("UNUSED_PARAMETER") // isAllDay kept for API compatibility
private fun formatDateForPicker(dateMillis: Long, isAllDay: Boolean): String {
    // Always use local timezone - form state is already converted to local time
    return DateTimeUtils.formatEventDate(dateMillis, isAllDay = false)
}

@Composable
fun DateTimeDisplayRow(
    startDateMillis: Long,
    startHour: Int,
    startMinute: Int,
    endDateMillis: Long,
    endHour: Int,
    endMinute: Int,
    isAllDay: Boolean,
    onAllDayToggle: (Boolean) -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
    isStartError: Boolean = false,
    isEndError: Boolean = false,
    startErrorMessage: String? = null,
    endErrorMessage: String? = null,
    timezone: String? = null,
    timePattern: String = "h:mm a"
) {
    val focusManager = LocalFocusManager.current
    val deviceTimezone = TimezoneUtils.getDeviceTimezone()
    val timezoneAbbrev = if (!isAllDay && timezone != null && timezone != deviceTimezone) {
        TimezoneUtils.getAbbreviation(timezone)
    } else null

    Column(modifier = modifier) {
        // All-day toggle with clock icon. A Switch reserves a 48dp interactive
        // box, which inflates this row's height above the single-line rows and
        // opens a larger gap above the label after the divider. Opt the switch
        // out of that minimum so the row sits at the text/switch height.
        EventFormRow(
            icon = Icons.Default.Schedule,
            iconContentDescription = stringResource(R.string.label_all_day),
            onToggle = { onAllDayToggle(!isAllDay) }
        ) {
            Text(
                stringResource(R.string.label_all_day),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Switch(
                    checked = isAllDay,
                    onCheckedChange = onAllDayToggle
                )
            }
        }

        // Start date/time — indented to align with text (past icon column)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    focusManager.clearFocus()
                    onStartClick()
                }
                .padding(start = 52.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val startDisplay = buildString {
                append(formatDateForPicker(startDateMillis, isAllDay))
                if (!isAllDay) {
                    append("  ")
                    append(DateTimeUtils.formatTime(startHour, startMinute, timePattern))
                }
                if (timezoneAbbrev != null) append("  $timezoneAbbrev")
            }
            Text(
                startDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isStartError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isStartError) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }

        if (startErrorMessage != null) {
            Text(
                startErrorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }

        // End date/time
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    focusManager.clearFocus()
                    onEndClick()
                }
                .padding(start = 52.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val endDisplay = buildString {
                append(formatDateForPicker(endDateMillis, isAllDay))
                if (!isAllDay) {
                    append("  ")
                    append(DateTimeUtils.formatTime(endHour, endMinute, timePattern))
                }
                if (timezoneAbbrev != null) append("  $timezoneAbbrev")
            }
            Text(
                endDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEndError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isEndError) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }

        if (endErrorMessage != null) {
            Text(
                endErrorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Combined date + time picker sheet with buffered state and blocked gestural dismiss.
 * Shows calendar and wheel time picker in a single sheet.
 *
 * Features:
 * - Local buffered state (changes don't apply until Done)
 * - Gestural dismiss blocked (explicit Cancel/Done buttons only)
 * - Done button to commit changes
 * - Timezone picker chip (for timed events)
 *
 * @param selectedDateMillis Initial date timestamp
 * @param selectedHour Initial hour (0-23)
 * @param selectedMinute Initial minute (0-59)
 * @param selectedTimezone Initial timezone ID (null = device default)
 * @param isAllDay Hide time picker when true
 * @param onConfirm Called with new date/time/timezone when user taps Done
 * @param onDismiss Called when user dismisses without saving
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeSheet(
    label: String = "Date",
    selectedDateMillis: Long,
    selectedHour: Int,
    selectedMinute: Int,
    selectedTimezone: String? = null,
    isAllDay: Boolean,
    use24Hour: Boolean = false,
    onConfirm: (dateMillis: Long, hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    // Local buffered state
    var localDateMillis by remember { mutableStateOf(selectedDateMillis) }
    var localHour by remember { mutableIntStateOf(selectedHour) }
    var localMinute by remember { mutableIntStateOf(selectedMinute) }
    var displayedMonth by remember {
        mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = selectedDateMillis })
    }
    // Hoisted so the Done buttons can collapse the month/year wheel back to the day grid
    // when it's open, instead of confirming the whole sheet.
    var showMonthYearPicker by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Done: collapse wheel if open, otherwise confirm and dismiss
    val onDoneClick: () -> Unit = {
        if (showMonthYearPicker) showMonthYearPicker = false
        else onConfirm(localDateMillis, localHour, localMinute)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onDismiss() }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Text(
                    text = label,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onDoneClick) {
                    Text(
                        text = stringResource(R.string.action_done),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            HorizontalDivider()

            // Calendar picker - updates LOCAL state (compact)
            InlineDatePickerContent(
                selectedDateMillis = localDateMillis,
                displayedMonth = displayedMonth,
                onDateSelect = { localDateMillis = it },
                onMonthChange = { displayedMonth = it },
                firstDayOfWeek = firstDayOfWeek,
                showMonthYearPicker = showMonthYearPicker,
                onShowMonthYearPickerChange = { showMonthYearPicker = it }
            )

            // Time picker - updates LOCAL state (unless all-day)
            if (!isAllDay) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // The keyboard icon opens an exact-time dialog (text entry). It floats above
                // the soft keyboard, unlike an inline field which the keyboard would cover.
                var showTimeDialog by remember { mutableStateOf(false) }
                val timePattern = if (use24Hour) "HH:mm" else "h:mm a"

                // No fixed height: the wheel renders at its intrinsic size (as it did before
                // the exact-time entry was added), and the Box wraps it so the overlaid icon
                // aligns to the wheel's own top-right corner, just below the divider.
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (isOnWheelGrid(localMinute)) {
                        // Common case: the 5-minute wheel can represent this time exactly.
                        WheelTimePicker(
                            selectedHour = localHour,
                            selectedMinute = localMinute,
                            onTimeSelected = { h, m ->
                                // Ignore late fling emissions once the dialog is open, so a
                                // settling wheel can't change the buffer behind the dialog.
                                if (!showTimeDialog) {
                                    localHour = h
                                    localMinute = m
                                }
                            },
                            use24Hour = use24Hour,
                            visibleItems = 5,
                            itemHeight = 32.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    } else {
                        // Off-grid minute (e.g. 9:47): the wheel can't show it without
                        // snapping to a 5-minute step, so display the exact time as tappable
                        // text that opens the dialog instead of mounting the wheel.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp) // match the wheel's intrinsic height (5 x 32dp)
                                .clickable { showTimeDialog = true },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = DateTimeUtils.formatTime(localHour, localMinute, timePattern),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.time_entry_tap_to_edit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Overlaid in the top-end corner so the wheel keeps full width. It covers
                    // only a faded, non-selected wheel edge (the center selection is unaffected).
                    // Nudged up/right to hug the divider and the sheet edge.
                    IconButton(
                        onClick = { showTimeDialog = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-8).dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Keyboard,
                            contentDescription = stringResource(R.string.cd_time_entry_keyboard)
                        )
                    }
                }

                if (showTimeDialog) {
                    ExactTimeDialog(
                        initialHour = localHour,
                        initialMinute = localMinute,
                        use24Hour = use24Hour,
                        onConfirm = { h, m ->
                            localHour = h
                            localMinute = m
                            showTimeDialog = false
                        },
                        onDismiss = { showTimeDialog = false }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDoneClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.action_done), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Inline date picker content (calendar grid).
 * Supports swipe gestures for month navigation.
 */
@Composable
fun InlineDatePickerContent(
    selectedDateMillis: Long,
    displayedMonth: JavaCalendar,
    onDateSelect: (Long) -> Unit,
    onMonthChange: (JavaCalendar) -> Unit,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    showMonthYearPicker: Boolean? = null,
    onShowMonthYearPickerChange: ((Boolean) -> Unit)? = null
) {
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDateMillis }
    // Re-sample "today" whenever the screen resumes so the marker can't go stale
    // when the picker stays open across midnight or the app is backgrounded.
    var todayRefreshKey by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        todayRefreshKey++
        onPauseOrDispose { }
    }
    val today = remember(todayRefreshKey) { JavaCalendar.getInstance() }

    var totalDrag by remember { mutableFloatStateOf(0f) }
    val monthYearFormat = remember { SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMM"), Locale.getDefault()) }
    // Fall back to internal state when the caller isn't managing visibility (e.g., RecurrencePicker "Until" date).
    var localShowMonthYearPicker by remember { mutableStateOf(false) }
    val effectiveShowMonthYearPicker = showMonthYearPicker ?: localShowMonthYearPicker
    val setShowMonthYearPicker: (Boolean) -> Unit = { value ->
        if (onShowMonthYearPickerChange != null) onShowMonthYearPickerChange(value)
        else localShowMonthYearPicker = value
    }
    val cdShowCalendar = stringResource(R.string.cd_show_calendar)
    val cdPickMonthYear = stringResource(R.string.cd_pick_month_year)
    val cdPreviousMonth = stringResource(R.string.cd_previous_month)
    val cdNextMonth = stringResource(R.string.cd_next_month)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .then(
                if (!effectiveShowMonthYearPicker) Modifier.pointerInput(displayedMonth) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag > 100f) {
                                val newMonth = displayedMonth.clone() as JavaCalendar
                                newMonth.add(JavaCalendar.MONTH, -1)
                                onMonthChange(newMonth)
                            } else if (totalDrag < -100f) {
                                val newMonth = displayedMonth.clone() as JavaCalendar
                                newMonth.add(JavaCalendar.MONTH, 1)
                                onMonthChange(newMonth)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        }
                    )
                } else Modifier
            )
    ) {
        // Month navigation header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!effectiveShowMonthYearPicker) {
                IconButton(
                    onClick = {
                        val newMonth = displayedMonth.clone() as JavaCalendar
                        newMonth.add(JavaCalendar.MONTH, -1)
                        onMonthChange(newMonth)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, cdPreviousMonth, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { setShowMonthYearPicker(!effectiveShowMonthYearPicker) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (effectiveShowMonthYearPicker) cdShowCalendar
                            else cdPickMonthYear
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = monthYearFormat.format(displayedMonth.time),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (effectiveShowMonthYearPicker) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (effectiveShowMonthYearPicker) Icons.Default.ExpandLess
                                  else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (effectiveShowMonthYearPicker) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!effectiveShowMonthYearPicker) {
                IconButton(
                    onClick = {
                        val newMonth = displayedMonth.clone() as JavaCalendar
                        newMonth.add(JavaCalendar.MONTH, 1)
                        onMonthChange(newMonth)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, cdNextMonth, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }
        }

        // Crossfade between calendar grid and month/year wheel picker
        Box(modifier = Modifier.heightIn(min = 220.dp)) {
            Crossfade(
                targetState = effectiveShowMonthYearPicker,
                animationSpec = tween(200),
                label = "calendar-wheel-crossfade"
            ) { showWheels ->
                if (showWheels) {
                    MonthYearWheelPicker(
                        selectedYear = displayedMonth.get(JavaCalendar.YEAR),
                        selectedMonth = displayedMonth.get(JavaCalendar.MONTH),
                        onMonthYearSelected = { year, month ->
                            val newMonth = displayedMonth.clone() as JavaCalendar
                            newMonth.set(JavaCalendar.YEAR, year)
                            newMonth.set(JavaCalendar.MONTH, month)
                            newMonth.set(JavaCalendar.DAY_OF_MONTH, 1)
                            onMonthChange(newMonth)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Column {
                        // Day of week headers
                        val orderedDays = remember(firstDayOfWeek) {
                            DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val locale = LocalLocale.current.platformLocale
                            orderedDays.forEach { day ->
                                Text(
                                    text = day.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Calendar grid
                        val firstDayOfMonth = displayedMonth.clone() as JavaCalendar
                        firstDayOfMonth.set(JavaCalendar.DAY_OF_MONTH, 1)
                        val gridOffset = DateTimeUtils.getFirstDayOffset(firstDayOfMonth, firstDayOfWeek)
                        val daysInMonth = displayedMonth.getActualMaximum(JavaCalendar.DAY_OF_MONTH)
                        val weeks = ((gridOffset + daysInMonth + 6) / 7)

                        for (week in 0 until weeks) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (dayOfWeekIdx in 0..6) {
                                    val dayIndex = week * 7 + dayOfWeekIdx - gridOffset + 1

                                    if (dayIndex in 1..daysInMonth) {
                                        val dayCal = displayedMonth.clone() as JavaCalendar
                                        dayCal.set(JavaCalendar.DAY_OF_MONTH, dayIndex)

                                        val isSelected = selectedCal.get(JavaCalendar.YEAR) == dayCal.get(JavaCalendar.YEAR) &&
                                            selectedCal.get(JavaCalendar.MONTH) == dayCal.get(JavaCalendar.MONTH) &&
                                            selectedCal.get(JavaCalendar.DAY_OF_MONTH) == dayIndex
                                        val isToday = isSameCalendarDay(today, dayCal)
                                        val cellStyle = dayCellStyle(isToday = isToday, isSelected = isSelected)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .padding(2.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when (cellStyle) {
                                                        DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseSurface
                                                        DayCellStyle.TODAY -> MaterialTheme.colorScheme.primaryContainer
                                                        DayCellStyle.PLAIN -> Color.Transparent
                                                    }
                                                )
                                                .clickable {
                                                    val selectedTime = dayCal.clone() as JavaCalendar
                                                    val origCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDateMillis }
                                                    selectedTime.set(JavaCalendar.HOUR_OF_DAY, origCal.get(JavaCalendar.HOUR_OF_DAY))
                                                    selectedTime.set(JavaCalendar.MINUTE, origCal.get(JavaCalendar.MINUTE))
                                                    onDateSelect(selectedTime.timeInMillis)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = dayIndex.toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = when (cellStyle) {
                                                    DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseOnSurface
                                                    DayCellStyle.TODAY -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    DayCellStyle.PLAIN -> MaterialTheme.colorScheme.onSurface
                                                },
                                                fontWeight = if (cellStyle == DayCellStyle.PLAIN) FontWeight.Normal else FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Helper Functions ====================

/**
 * Visual state of a single day cell in the calendar grid.
 *
 * Today and the selected day are independent states that can land on the same
 * cell. When they do, the SELECTED treatment wins (its fill already makes the
 * cell unmistakable). Otherwise today keeps its own fill, so it stays visible
 * whenever a different date is selected.
 */
enum class DayCellStyle {
    PLAIN,
    TODAY,
    SELECTED
}

/**
 * Resolve a day cell's visual state. Selection takes precedence over today.
 */
fun dayCellStyle(isToday: Boolean, isSelected: Boolean): DayCellStyle = when {
    isSelected -> DayCellStyle.SELECTED
    isToday -> DayCellStyle.TODAY
    else -> DayCellStyle.PLAIN
}

/**
 * The wheel only offers minutes in 5-minute steps, so it can represent a minute
 * exactly only when it is a multiple of five. Off-grid minutes (e.g. 9:47, entered
 * via the exact-time dialog) route to a tappable text display instead of the wheel.
 */
fun isOnWheelGrid(minute: Int): Boolean = minute % 5 == 0

/**
 * Exact-time entry dialog: a centered text-input picker (hour/minute fields, plus
 * AM/PM in 12-hour mode) with no clock dial. Because it is a centered dialog rather
 * than inline sheet content, it floats above the soft keyboard instead of being
 * covered by it. Accepts any minute, so a value like 9:47 round-trips exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExactTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    use24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // TimePickerState.hour is always 0-23; is24Hour only affects display (two fields
    // vs three with AM/PM), so no manual 12<->24 conversion is needed.
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = use24Hour
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        title = { Text(stringResource(R.string.time_entry_dialog_title)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimeInput(state = timeState)
            }
        }
    )
}

/**
 * True when two calendars fall on the same civil day (year + day-of-year), so a
 * "today" instant sampled at 11:59 PM does not match tomorrow's grid cell.
 */
fun isSameCalendarDay(a: JavaCalendar, b: JavaCalendar): Boolean =
    a.get(JavaCalendar.YEAR) == b.get(JavaCalendar.YEAR) &&
        a.get(JavaCalendar.DAY_OF_YEAR) == b.get(JavaCalendar.DAY_OF_YEAR)

/**
 * Check if time range crosses midnight (requires +1 badge on end date).
 */
fun isMidnightCrossing(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int
): Boolean {
    val startMinutes = startHour * 60 + startMinute
    val endMinutes = endHour * 60 + endMinute
    return endMinutes < startMinutes
}
