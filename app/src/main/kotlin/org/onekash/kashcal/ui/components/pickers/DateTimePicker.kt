package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.ui.components.WheelTimePicker
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale

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
    timezone: String? = null
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
            append(DateTimeUtils.formatTime(hour, minute))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Default.ExpandMore, contentDescription = "Select $label")
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
    return DateTimeUtils.formatEventDate(dateMillis, isAllDay = false, "EEE, MMM d, yyyy")
}

/**
 * Combined date + time picker sheet with buffered state and dismiss protection.
 * Shows calendar and wheel time picker in a single sheet.
 *
 * Features:
 * - Local buffered state (changes don't apply until Done)
 * - Two-tap dismiss protection (swipe shows "Discard?", second swipe dismisses)
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
    selectedDateMillis: Long,
    selectedHour: Int,
    selectedMinute: Int,
    selectedTimezone: String? = null,
    isAllDay: Boolean,
    onConfirm: (dateMillis: Long, hour: Int, minute: Int, timezone: String?) -> Unit,
    onDismiss: () -> Unit
) {
    // Local buffered state
    var localDateMillis by remember { mutableStateOf(selectedDateMillis) }
    var localHour by remember { mutableIntStateOf(selectedHour) }
    var localMinute by remember { mutableIntStateOf(selectedMinute) }
    var localTimezone by remember { mutableStateOf(selectedTimezone) }
    var displayedMonth by remember {
        mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = selectedDateMillis })
    }

    // Dismiss protection state
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Check if user made changes
    val hasChanges by remember {
        derivedStateOf {
            localDateMillis != selectedDateMillis ||
                localHour != selectedHour ||
                localMinute != selectedMinute ||
                localTimezone != selectedTimezone
        }
    }

    // Sheet state with dismiss protection
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            when {
                newValue != SheetValue.Hidden -> true  // Allow expand
                !hasChanges -> true                     // No changes = allow dismiss
                showDiscardConfirm -> true              // Second attempt = allow
                else -> {
                    showDiscardConfirm = true           // First attempt = block & show confirm
                    false
                }
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            when {
                !hasChanges -> onDismiss()
                showDiscardConfirm -> onDismiss()
                else -> showDiscardConfirm = true
            }
        },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp)
        ) {
            // Calendar picker - updates LOCAL state (compact)
            InlineDatePickerContent(
                selectedDateMillis = localDateMillis,
                displayedMonth = displayedMonth,
                onDateSelect = { localDateMillis = it },
                onMonthChange = { displayedMonth = it }
            )

            // Time picker with timezone - updates LOCAL state (unless all-day)
            if (!isAllDay) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Track whether timezone search is expanded
                var isTimezoneSearchOpen by remember { mutableStateOf(false) }

                // Calculate reference time in SELECTED timezone for preview
                // This allows the preview to show "what time this will be in device timezone"
                val referenceTimeMs = remember(localDateMillis, localHour, localMinute, localTimezone) {
                    val tz = localTimezone?.let { java.util.TimeZone.getTimeZone(it) }
                        ?: java.util.TimeZone.getDefault()
                    JavaCalendar.getInstance(tz).apply {
                        timeInMillis = localDateMillis
                        set(JavaCalendar.HOUR_OF_DAY, localHour)
                        set(JavaCalendar.MINUTE, localMinute)
                        set(JavaCalendar.SECOND, 0)
                        set(JavaCalendar.MILLISECOND, 0)
                    }.timeInMillis
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time picker wheels - hidden when timezone search is open
                    AnimatedVisibility(
                        visible = !isTimezoneSearchOpen,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.weight(0.70f)
                    ) {
                        WheelTimePicker(
                            selectedHour = localHour,
                            selectedMinute = localMinute,
                            onTimeSelected = { h, m ->
                                localHour = h
                                localMinute = m
                            },
                            visibleItems = 3,
                            itemHeight = 32.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Timezone picker - expands to full width when search is open
                    TimezonePickerChip(
                        selectedTimezone = localTimezone,
                        onTimezoneSelected = { newTimezone ->
                            // Convert time to maintain same instant when timezone changes
                            val oldTz = localTimezone?.let { java.util.TimeZone.getTimeZone(it) }
                                ?: java.util.TimeZone.getDefault()
                            val newTz = newTimezone?.let { java.util.TimeZone.getTimeZone(it) }
                                ?: java.util.TimeZone.getDefault()

                            // Calculate instant in old timezone
                            val oldCal = JavaCalendar.getInstance(oldTz).apply {
                                timeInMillis = localDateMillis
                                set(JavaCalendar.HOUR_OF_DAY, localHour)
                                set(JavaCalendar.MINUTE, localMinute)
                                set(JavaCalendar.SECOND, 0)
                                set(JavaCalendar.MILLISECOND, 0)
                            }

                            // Convert to new timezone (same instant, different wall clock time)
                            val newCal = JavaCalendar.getInstance(newTz).apply {
                                timeInMillis = oldCal.timeInMillis
                            }

                            localHour = newCal.get(JavaCalendar.HOUR_OF_DAY)
                            localMinute = newCal.get(JavaCalendar.MINUTE)
                            localDateMillis = newCal.timeInMillis // Date might change (crossing midnight)
                            localTimezone = newTimezone
                        },
                        referenceTimeMs = referenceTimeMs,
                        modifier = if (isTimezoneSearchOpen) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.weight(0.30f)
                        },
                        onSearchOpenChange = { isTimezoneSearchOpen = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Done / Discard buttons - show both when user tried to dismiss with changes
            if (showDiscardConfirm) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Discard button (error color)
                    OutlinedButton(
                        onClick = { onDismiss() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Discard", fontWeight = FontWeight.SemiBold)
                    }
                    // Done button (primary) - user can still save!
                    Button(
                        onClick = { onConfirm(localDateMillis, localHour, localMinute, localTimezone) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Button(
                    onClick = { onConfirm(localDateMillis, localHour, localMinute, localTimezone) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
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
    onMonthChange: (JavaCalendar) -> Unit
) {
    val today = JavaCalendar.getInstance()
    val selectedCal = JavaCalendar.getInstance().apply { timeInMillis = selectedDateMillis }

    var totalDrag by remember { mutableFloatStateOf(0f) }
    val monthYearFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .pointerInput(displayedMonth) {
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
            }
    ) {
        // Month navigation header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val newMonth = displayedMonth.clone() as JavaCalendar
                    newMonth.add(JavaCalendar.MONTH, -1)
                    onMonthChange(newMonth)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", modifier = Modifier.size(20.dp))
            }

            Text(
                text = monthYearFormat.format(displayedMonth.time),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            IconButton(
                onClick = {
                    val newMonth = displayedMonth.clone() as JavaCalendar
                    newMonth.add(JavaCalendar.MONTH, 1)
                    onMonthChange(newMonth)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", modifier = Modifier.size(20.dp))
            }
        }

        // Day of week headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                Text(
                    text = day,
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
        val startDayOfWeek = firstDayOfMonth.get(JavaCalendar.DAY_OF_WEEK) - 1
        val daysInMonth = displayedMonth.getActualMaximum(JavaCalendar.DAY_OF_MONTH)
        val weeks = ((startDayOfWeek + daysInMonth + 6) / 7)

        for (week in 0 until weeks) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (dayOfWeek in 0..6) {
                    val dayIndex = week * 7 + dayOfWeek - startDayOfWeek + 1

                    if (dayIndex in 1..daysInMonth) {
                        val dayCal = displayedMonth.clone() as JavaCalendar
                        dayCal.set(JavaCalendar.DAY_OF_MONTH, dayIndex)

                        val isSelected = selectedCal.get(JavaCalendar.YEAR) == dayCal.get(JavaCalendar.YEAR) &&
                            selectedCal.get(JavaCalendar.MONTH) == dayCal.get(JavaCalendar.MONTH) &&
                            selectedCal.get(JavaCalendar.DAY_OF_MONTH) == dayIndex

                        val isToday = today.get(JavaCalendar.YEAR) == dayCal.get(JavaCalendar.YEAR) &&
                            today.get(JavaCalendar.MONTH) == dayCal.get(JavaCalendar.MONTH) &&
                            today.get(JavaCalendar.DAY_OF_MONTH) == dayIndex

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
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
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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

// ==================== Helper Functions ====================

/**
 * Check if event spans multiple days.
 */
fun isMultiDay(startDateMillis: Long, endDateMillis: Long): Boolean {
    return DateTimeUtils.spansMultipleDays(startDateMillis, endDateMillis, isAllDay = false)
}

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

/**
 * Determine if start and end should be shown in separate pickers.
 */
fun shouldShowSeparatePickers(
    startDateMillis: Long,
    endDateMillis: Long,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    isAllDay: Boolean
): Boolean {
    // Always separate for multi-day
    if (isMultiDay(startDateMillis, endDateMillis)) return true

    // Separate if different dates selected
    val startCal = JavaCalendar.getInstance().apply { timeInMillis = startDateMillis }
    val endCal = JavaCalendar.getInstance().apply { timeInMillis = endDateMillis }

    return startCal.get(JavaCalendar.YEAR) != endCal.get(JavaCalendar.YEAR) ||
        startCal.get(JavaCalendar.DAY_OF_YEAR) != endCal.get(JavaCalendar.DAY_OF_YEAR)
}

// ==================== Time Conversion Utilities ====================

/**
 * Convert 12-hour format to 24-hour format.
 *
 * @param hour12 Hour in 12-hour format (1-12)
 * @param isAm True if AM, false if PM
 * @return Hour in 24-hour format (0-23)
 */
fun to24Hour(hour12: Int, isAm: Boolean): Int {
    return when {
        hour12 == 12 && isAm -> 0      // 12 AM = 00:00
        hour12 == 12 && !isAm -> 12    // 12 PM = 12:00
        isAm -> hour12                  // 1-11 AM = 01:00-11:00
        else -> hour12 + 12             // 1-11 PM = 13:00-23:00
    }
}

/**
 * Convert 24-hour format to 12-hour format.
 *
 * @param hour24 Hour in 24-hour format (0-23)
 * @return Pair of (hour12, isAm) where hour12 is 1-12
 */
fun to12Hour(hour24: Int): Pair<Int, Boolean> {
    return when {
        hour24 == 0 -> Pair(12, true)   // 00:00 = 12 AM
        hour24 < 12 -> Pair(hour24, true)  // 01:00-11:00 = 1-11 AM
        hour24 == 12 -> Pair(12, false) // 12:00 = 12 PM
        else -> Pair(hour24 - 12, false) // 13:00-23:00 = 1-11 PM
    }
}
