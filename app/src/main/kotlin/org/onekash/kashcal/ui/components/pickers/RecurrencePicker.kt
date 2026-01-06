package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Calendar as JavaCalendar
import java.util.Locale

/**
 * Recurrence picker card with frequency chips, weekday selector,
 * monthly pattern options, and end conditions.
 *
 * @param selectedRrule Current RRULE string (null = does not repeat)
 * @param startDateMillis Start date timestamp for pattern calculation
 * @param isExpanded Whether the picker is expanded
 * @param onToggle Toggle expansion state
 * @param onSelect Called with new RRULE string (null = no repeat)
 */
@Composable
fun RecurrencePickerCard(
    selectedRrule: String?,
    startDateMillis: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val displayText = RruleBuilder.formatForDisplay(selectedRrule)

    // Parse start date info
    val startZoned = remember(startDateMillis) {
        Instant.ofEpochMilli(startDateMillis)
            .atZone(ZoneId.systemDefault())
    }
    val startDayOfWeek = startZoned.dayOfWeek
    val startDayOfMonth = startZoned.dayOfMonth

    // Calculate ordinal (1st, 2nd, 3rd, 4th, or -1 for last) of weekday in month
    val startOrdinalInMonth = remember(startDateMillis) {
        val day = startZoned.dayOfMonth
        (day - 1) / 7 + 1  // 1-based ordinal
    }

    // Parse existing rrule to state
    val parsed = remember(selectedRrule, startDayOfWeek, startDayOfMonth) {
        RruleBuilder.parseRrule(selectedRrule, startDayOfWeek, startDayOfMonth, startOrdinalInMonth)
    }

    // Local state for interactive editing
    var selectedFreqOption by remember(parsed) {
        mutableStateOf(
            when {
                parsed.frequency == RecurrenceFrequency.NONE -> FrequencyOption.NEVER
                parsed.frequency == RecurrenceFrequency.DAILY -> FrequencyOption.DAILY
                parsed.frequency == RecurrenceFrequency.WEEKLY && parsed.interval == 2 -> FrequencyOption.BIWEEKLY
                parsed.frequency == RecurrenceFrequency.WEEKLY -> FrequencyOption.WEEKLY
                parsed.frequency == RecurrenceFrequency.MONTHLY && parsed.interval == 3 -> FrequencyOption.QUARTERLY
                parsed.frequency == RecurrenceFrequency.MONTHLY -> FrequencyOption.MONTHLY
                parsed.frequency == RecurrenceFrequency.YEARLY -> FrequencyOption.YEARLY
                else -> FrequencyOption.NEVER
            }
        )
    }

    var selectedWeekdays by remember(parsed) {
        mutableStateOf(parsed.weekdays.ifEmpty { setOf(startDayOfWeek) })
    }

    var monthlyPattern by remember(parsed) {
        mutableStateOf(parsed.monthlyPattern ?: MonthlyPattern.SameDay(startDayOfMonth))
    }

    var endCondition by remember(parsed) {
        mutableStateOf(parsed.endCondition)
    }

    // Build RRULE from current state
    fun buildRrule(): String? {
        if (selectedFreqOption == FrequencyOption.NEVER) return null

        val base = when (selectedFreqOption) {
            FrequencyOption.NEVER -> return null
            FrequencyOption.DAILY -> RruleBuilder.daily()
            FrequencyOption.WEEKLY -> RruleBuilder.weekly(days = selectedWeekdays)
            FrequencyOption.BIWEEKLY -> RruleBuilder.weekly(interval = 2, days = selectedWeekdays)
            FrequencyOption.MONTHLY -> when (val pattern = monthlyPattern) {
                is MonthlyPattern.SameDay -> RruleBuilder.monthly(dayOfMonth = pattern.dayOfMonth)
                is MonthlyPattern.LastDay -> RruleBuilder.monthlyLastDay()
                is MonthlyPattern.NthWeekday -> RruleBuilder.monthlyNthWeekday(pattern.ordinal, pattern.weekday)
            }
            FrequencyOption.QUARTERLY -> when (val pattern = monthlyPattern) {
                is MonthlyPattern.SameDay -> RruleBuilder.monthly(interval = 3, dayOfMonth = pattern.dayOfMonth)
                is MonthlyPattern.LastDay -> RruleBuilder.monthlyLastDay(interval = 3)
                is MonthlyPattern.NthWeekday -> RruleBuilder.monthlyNthWeekday(pattern.ordinal, pattern.weekday, interval = 3)
            }
            FrequencyOption.YEARLY -> RruleBuilder.yearly()
        }

        return when (val end = endCondition) {
            is EndCondition.Never -> base
            is EndCondition.Count -> RruleBuilder.withCount(base, end.count)
            is EndCondition.Until -> RruleBuilder.withUntil(base, end.dateMillis)
        }
    }

    // Update parent when state changes
    fun notifyChange() {
        onSelect(buildRrule())
    }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        focusManager.clearFocus()
                        onToggle()
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Repeat", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Frequency chips - 2 rows
                    Text(
                        "Frequency",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    FrequencyChipRow(
                        options = listOf(FrequencyOption.NEVER, FrequencyOption.DAILY, FrequencyOption.WEEKLY, FrequencyOption.BIWEEKLY),
                        selected = selectedFreqOption,
                        onSelect = { option ->
                            selectedFreqOption = option
                            notifyChange()
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FrequencyChipRow(
                        options = listOf(FrequencyOption.MONTHLY, FrequencyOption.QUARTERLY, FrequencyOption.YEARLY),
                        selected = selectedFreqOption,
                        onSelect = { option ->
                            selectedFreqOption = option
                            notifyChange()
                        }
                    )

                    // Weekday selector (for Weekly/Biweekly)
                    if (selectedFreqOption == FrequencyOption.WEEKLY || selectedFreqOption == FrequencyOption.BIWEEKLY) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "On days",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        WeekdaySelector(
                            selectedDays = selectedWeekdays,
                            onDaysChange = { days ->
                                selectedWeekdays = days
                                notifyChange()
                            }
                        )
                    }

                    // Monthly pattern selector
                    if (selectedFreqOption == FrequencyOption.MONTHLY || selectedFreqOption == FrequencyOption.QUARTERLY) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Pattern",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        MonthlyPatternSelector(
                            pattern = monthlyPattern,
                            dayOfMonth = startDayOfMonth,
                            ordinalInMonth = startOrdinalInMonth,
                            weekday = startDayOfWeek,
                            onPatternChange = { pattern ->
                                monthlyPattern = pattern
                                notifyChange()
                            }
                        )
                    }

                    // End condition selector (for all frequencies except Never)
                    if (selectedFreqOption != FrequencyOption.NEVER) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Ends",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        EndConditionSelector(
                            endCondition = endCondition,
                            startDateMillis = startDateMillis,
                            onEndConditionChange = { condition ->
                                endCondition = condition
                                notifyChange()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Row of frequency option chips.
 */
@Composable
fun FrequencyChipRow(
    options: List<FrequencyOption>,
    selected: FrequencyOption,
    onSelect: (FrequencyOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Day of week selector circles.
 */
@Composable
fun WeekdaySelector(
    selectedDays: Set<DayOfWeek>,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    modifier: Modifier = Modifier
) {
    val daysOrder = listOf(
        DayOfWeek.SUNDAY,
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        daysOrder.forEach { day ->
            val isSelected = day in selectedDays
            val label = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable {
                        val newDays = if (isSelected) {
                            // Don't allow deselecting the last day
                            if (selectedDays.size > 1) selectedDays - day else selectedDays
                        } else {
                            selectedDays + day
                        }
                        onDaysChange(newDays)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Monthly pattern selector with radio options.
 */
@Composable
fun MonthlyPatternSelector(
    pattern: MonthlyPattern,
    dayOfMonth: Int,
    ordinalInMonth: Int,
    weekday: DayOfWeek,
    onPatternChange: (MonthlyPattern) -> Unit,
    modifier: Modifier = Modifier
) {
    val ordinalLabel = when (ordinalInMonth) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        4 -> "4th"
        else -> "${ordinalInMonth}th"
    }
    val weekdayLabel = weekday.getDisplayName(TextStyle.FULL, Locale.getDefault())

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Option 1: Same day of month
        RadioOption(
            label = "On day $dayOfMonth",
            selected = pattern is MonthlyPattern.SameDay,
            onClick = { onPatternChange(MonthlyPattern.SameDay(dayOfMonth)) }
        )

        // Option 2: Last day of month
        RadioOption(
            label = "On last day of month",
            selected = pattern is MonthlyPattern.LastDay,
            onClick = { onPatternChange(MonthlyPattern.LastDay) }
        )

        // Option 3: Nth weekday
        RadioOption(
            label = "On the $ordinalLabel $weekdayLabel",
            selected = pattern is MonthlyPattern.NthWeekday,
            onClick = { onPatternChange(MonthlyPattern.NthWeekday(ordinalInMonth, weekday)) }
        )
    }
}

/**
 * End condition selector with radio options and inline date picker.
 */
@Composable
fun EndConditionSelector(
    endCondition: EndCondition,
    startDateMillis: Long,
    onEndConditionChange: (EndCondition) -> Unit,
    modifier: Modifier = Modifier
) {
    var countText by remember(endCondition) {
        mutableStateOf(
            if (endCondition is EndCondition.Count) endCondition.count.toString() else "10"
        )
    }

    var untilMillis by remember(endCondition) {
        mutableStateOf(
            if (endCondition is EndCondition.Until) endCondition.dateMillis
            else startDateMillis + (365L * 24 * 60 * 60 * 1000) // Default: 1 year from now
        )
    }

    // State for showing inline date picker
    var showDatePicker by remember { mutableStateOf(false) }

    // Calendar state for the date picker
    var displayedMonth by remember(untilMillis) {
        mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = untilMillis })
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Option 1: Never
        RadioOption(
            label = "Never",
            selected = endCondition is EndCondition.Never,
            onClick = {
                onEndConditionChange(EndCondition.Never)
                showDatePicker = false
            }
        )

        // Option 2: After N occurrences
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (endCondition is EndCondition.Count)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable {
                        val count = countText.toIntOrNull() ?: 10
                        onEndConditionChange(EndCondition.Count(count))
                        showDatePicker = false
                    },
                contentAlignment = Alignment.Center
            ) {
                if (endCondition is EndCondition.Count) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
            Text("After", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = countText,
                onValueChange = { newValue ->
                    countText = newValue.filter { it.isDigit() }.take(3)
                    val count = countText.toIntOrNull() ?: 10
                    if (endCondition is EndCondition.Count) {
                        onEndConditionChange(EndCondition.Count(count))
                    }
                },
                modifier = Modifier.width(60.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                enabled = endCondition is EndCondition.Count
            )
            Text("occurrences", style = MaterialTheme.typography.bodyMedium)
        }

        // Option 3: Until date (clickable to show date picker)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onEndConditionChange(EndCondition.Until(untilMillis))
                    showDatePicker = !showDatePicker
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (endCondition is EndCondition.Until)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (endCondition is EndCondition.Until) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
            Text("On date", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = DateTimeUtils.formatEventDate(untilMillis, isAllDay = false, "MMM d, yyyy"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (endCondition is EndCondition.Until)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (endCondition is EndCondition.Until) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (showDatePicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showDatePicker) "Hide calendar" else "Show calendar",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Inline date picker for "Until date" option
        AnimatedVisibility(
            visible = showDatePicker && endCondition is EndCondition.Until,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
        ) {
            InlineDatePickerContent(
                selectedDateMillis = untilMillis,
                displayedMonth = displayedMonth,
                onDateSelect = { newDateMillis ->
                    untilMillis = newDateMillis
                    onEndConditionChange(EndCondition.Until(newDateMillis))
                },
                onMonthChange = { newMonth ->
                    displayedMonth = newMonth
                }
            )
        }
    }
}

/**
 * Radio option row with circle indicator.
 */
@Composable
fun RadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
