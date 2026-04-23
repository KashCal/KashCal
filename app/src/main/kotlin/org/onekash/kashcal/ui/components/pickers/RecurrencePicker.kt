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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.rrule.RruleDisplayStrings
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Calendar as JavaCalendar
import java.util.Locale

@Composable
fun rememberRruleDisplayStrings(): RruleDisplayStrings {
    val doesNotRepeat = stringResource(R.string.rrule_does_not_repeat)
    val freqDaily = stringResource(R.string.rrule_freq_daily)
    val freqWeekly = stringResource(R.string.rrule_freq_weekly)
    val freqBiweekly = stringResource(R.string.rrule_freq_biweekly)
    val freqMonthly = stringResource(R.string.rrule_freq_monthly)
    val freqQuarterly = stringResource(R.string.rrule_freq_quarterly)
    val freqYearly = stringResource(R.string.rrule_freq_yearly)
    val repeats = stringResource(R.string.rrule_repeats)
    val everyNDays = stringResource(R.string.rrule_every_n_days)
    val everyNWeeks = stringResource(R.string.rrule_every_n_weeks)
    val everyNMonths = stringResource(R.string.rrule_every_n_months)
    val everyNYears = stringResource(R.string.rrule_every_n_years)
    val freqOnDays = stringResource(R.string.rrule_freq_on_days)
    val freqOnOrdinalDay = stringResource(R.string.rrule_freq_on_ordinal_day)
    val freqOnLastDay = stringResource(R.string.rrule_freq_on_last_day)
    val freqOnDayN = stringResource(R.string.rrule_freq_on_day_n)
    val ordinal1 = stringResource(R.string.ordinal_1st)
    val ordinal2 = stringResource(R.string.ordinal_2nd)
    val ordinal3 = stringResource(R.string.ordinal_3rd)
    val ordinal4 = stringResource(R.string.ordinal_4th)
    val ordinalLast = stringResource(R.string.rrule_ordinal_last)
    val ordinalNth = stringResource(R.string.ordinal_nth)
    val untilSuffix = stringResource(R.string.rrule_until_suffix)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    return remember {
        RruleDisplayStrings(
            doesNotRepeat = doesNotRepeat,
            freqDaily = freqDaily,
            freqWeekly = freqWeekly,
            freqBiweekly = freqBiweekly,
            freqMonthly = freqMonthly,
            freqQuarterly = freqQuarterly,
            freqYearly = freqYearly,
            repeats = repeats,
            everyNDays = everyNDays,
            everyNWeeks = everyNWeeks,
            everyNMonths = everyNMonths,
            everyNYears = everyNYears,
            freqOnDays = freqOnDays,
            freqOnOrdinalDay = freqOnOrdinalDay,
            freqOnLastDay = freqOnLastDay,
            freqOnDayN = freqOnDayN,
            ordinals = listOf(ordinal1, ordinal2, ordinal3, ordinal4),
            ordinalLast = ordinalLast,
            ordinalNth = ordinalNth,
            countSuffix = { count ->
                resources.getQuantityString(R.plurals.rrule_count_suffix, count, count)
            },
            untilSuffix = untilSuffix
        )
    }
}

@Composable
private fun frequencyLabel(option: FrequencyOption): String {
    return when (option) {
        FrequencyOption.NEVER -> stringResource(R.string.rrule_freq_never)
        FrequencyOption.DAILY -> stringResource(R.string.rrule_freq_daily)
        FrequencyOption.WEEKLY -> stringResource(R.string.rrule_freq_weekly)
        FrequencyOption.BIWEEKLY -> stringResource(R.string.rrule_freq_biweekly)
        FrequencyOption.MONTHLY -> stringResource(R.string.rrule_freq_monthly)
        FrequencyOption.QUARTERLY -> stringResource(R.string.rrule_freq_quarterly)
        FrequencyOption.YEARLY -> stringResource(R.string.rrule_freq_yearly)
    }
}

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
@Deprecated("Use RecurrencePickerRow instead", level = DeprecationLevel.WARNING)
@Composable
fun RecurrencePickerCard(
    selectedRrule: String?,
    startDateMillis: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val focusManager = LocalFocusManager.current
    val rruleStrings = rememberRruleDisplayStrings()
    val displayText = RruleBuilder.formatForDisplay(selectedRrule, rruleStrings)

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
                Text(stringResource(R.string.label_repeat), style = MaterialTheme.typography.bodyMedium)
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
                        contentDescription = if (isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
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
                    // Frequency chips - 2 rows
                    Text(
                        stringResource(R.string.label_frequency),
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
                            stringResource(R.string.label_on_days),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        WeekdaySelector(
                            selectedDays = selectedWeekdays,
                            onDaysChange = { days ->
                                selectedWeekdays = days
                                notifyChange()
                            },
                            firstDayOfWeek = firstDayOfWeek
                        )
                    }

                    // Monthly pattern selector
                    if (selectedFreqOption == FrequencyOption.MONTHLY || selectedFreqOption == FrequencyOption.QUARTERLY) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.label_pattern),
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
                            stringResource(R.string.label_ends),
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
                            },
                            firstDayOfWeek = firstDayOfWeek
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecurrencePickerRow(
    selectedRrule: String?,
    startDateMillis: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val focusManager = LocalFocusManager.current
    val rruleStrings = rememberRruleDisplayStrings()
    val displayText = RruleBuilder.formatForDisplay(selectedRrule, rruleStrings)

    val startZoned = remember(startDateMillis) {
        Instant.ofEpochMilli(startDateMillis)
            .atZone(ZoneId.systemDefault())
    }
    val startDayOfWeek = startZoned.dayOfWeek
    val startDayOfMonth = startZoned.dayOfMonth
    val startOrdinalInMonth = remember(startDateMillis) {
        val day = startZoned.dayOfMonth
        (day - 1) / 7 + 1
    }

    val parsed = remember(selectedRrule, startDayOfWeek, startDayOfMonth) {
        RruleBuilder.parseRrule(selectedRrule, startDayOfWeek, startDayOfMonth, startOrdinalInMonth)
    }

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

    fun notifyChange() {
        onSelect(buildRrule())
    }

    EventFormRow(
        icon = Icons.Default.Repeat,
        iconContentDescription = stringResource(R.string.label_repeat),
        isExpanded = isExpanded,
        showExpandIcon = true,
        onToggle = {
            focusManager.clearFocus()
            onToggle()
        },
        expandedContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                FrequencyChipRow(
                    options = listOf(FrequencyOption.NEVER, FrequencyOption.DAILY, FrequencyOption.WEEKLY, FrequencyOption.BIWEEKLY),
                    selected = selectedFreqOption,
                    onSelect = { option -> selectedFreqOption = option; notifyChange() }
                )
                Spacer(modifier = Modifier.height(6.dp))
                FrequencyChipRow(
                    options = listOf(FrequencyOption.MONTHLY, FrequencyOption.QUARTERLY, FrequencyOption.YEARLY),
                    selected = selectedFreqOption,
                    onSelect = { option -> selectedFreqOption = option; notifyChange() }
                )

                if (selectedFreqOption == FrequencyOption.WEEKLY || selectedFreqOption == FrequencyOption.BIWEEKLY) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_on_days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    WeekdaySelector(
                        selectedDays = selectedWeekdays,
                        onDaysChange = { days -> selectedWeekdays = days; notifyChange() },
                        firstDayOfWeek = firstDayOfWeek
                    )
                }

                if (selectedFreqOption == FrequencyOption.MONTHLY || selectedFreqOption == FrequencyOption.QUARTERLY) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_pattern),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MonthlyPatternSelector(
                        pattern = monthlyPattern,
                        dayOfMonth = startDayOfMonth,
                        ordinalInMonth = startOrdinalInMonth,
                        weekday = startDayOfWeek,
                        onPatternChange = { pattern -> monthlyPattern = pattern; notifyChange() }
                    )
                }

                if (selectedFreqOption != FrequencyOption.NEVER) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_ends),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    EndConditionSelector(
                        endCondition = endCondition,
                        startDateMillis = startDateMillis,
                        onEndConditionChange = { condition -> endCondition = condition; notifyChange() },
                        firstDayOfWeek = firstDayOfWeek
                    )
                }
            }
        },
        modifier = modifier
    ) {
        Text(
            displayText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
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
            val label = frequencyLabel(option)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (!isSelected)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface
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
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    // Display order based on user preference (doesn't affect RRULE storage)
    val daysOrder = remember(firstDayOfWeek) {
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
    }

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
                        if (isSelected) MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (!isSelected)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        else Modifier
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
                    color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface
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
        1 -> stringResource(R.string.ordinal_1st)
        2 -> stringResource(R.string.ordinal_2nd)
        3 -> stringResource(R.string.ordinal_3rd)
        4 -> stringResource(R.string.ordinal_4th)
        else -> stringResource(R.string.ordinal_nth, ordinalInMonth)
    }
    val weekdayLabel = weekday.getDisplayName(TextStyle.FULL, Locale.getDefault())

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Option 1: Same day of month
        RadioOption(
            label = stringResource(R.string.recurrence_on_day, dayOfMonth),
            selected = pattern is MonthlyPattern.SameDay,
            onClick = { onPatternChange(MonthlyPattern.SameDay(dayOfMonth)) }
        )

        // Option 2: Last day of month
        RadioOption(
            label = stringResource(R.string.recurrence_on_last_day),
            selected = pattern is MonthlyPattern.LastDay,
            onClick = { onPatternChange(MonthlyPattern.LastDay) }
        )

        // Option 3: Nth weekday
        RadioOption(
            label = stringResource(R.string.recurrence_on_nth_weekday, ordinalLabel, weekdayLabel),
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
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    // Use TextFieldState for modern select-all on focus support
    val initialCountText = if (endCondition is EndCondition.Count) endCondition.count.toString() else "10"
    val countTextFieldState = rememberTextFieldState(initialCountText)

    // Use rememberUpdatedState to capture current values in long-running LaunchedEffect
    val currentEndCondition by androidx.compose.runtime.rememberUpdatedState(endCondition)
    val currentOnEndConditionChange by androidx.compose.runtime.rememberUpdatedState(onEndConditionChange)

    // Track if we're in Count mode to detect type changes (not value changes)
    var wasCountMode by remember { mutableStateOf(endCondition is EndCondition.Count) }

    // Sync text field only when switching TO Count mode (not on every value change)
    androidx.compose.runtime.LaunchedEffect(endCondition) {
        val isCountMode = endCondition is EndCondition.Count
        if (isCountMode && !wasCountMode) {
            // Switched to Count mode - sync the value
            val newText = (endCondition as EndCondition.Count).count.toString()
            if (countTextFieldState.text.toString() != newText) {
                countTextFieldState.setTextAndPlaceCursorAtEnd(newText)
            }
        }
        wasCountMode = isCountMode
    }

    // Observe text changes and update the end condition (only when valid number entered)
    androidx.compose.runtime.LaunchedEffect(countTextFieldState) {
        androidx.compose.runtime.snapshotFlow { countTextFieldState.text.toString() }
            .collect { text ->
                // Only propagate valid positive numbers - allow empty/partial input while typing
                val count = text.toIntOrNull()?.takeIf { it > 0 }
                if (count != null && currentEndCondition is EndCondition.Count &&
                    (currentEndCondition as EndCondition.Count).count != count) {
                    currentOnEndConditionChange(EndCondition.Count(count))
                }
            }
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
            label = stringResource(R.string.label_recurrence_never),
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
                            MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (endCondition !is EndCondition.Count)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        else Modifier
                    )
                    .clickable {
                        val count = countTextFieldState.text.toString().toIntOrNull() ?: 10
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
                            .background(MaterialTheme.colorScheme.inverseOnSurface)
                    )
                }
            }
            Text(stringResource(R.string.label_recurrence_after), style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                state = countTextFieldState,
                modifier = Modifier
                    .width(60.dp)
                    .border(
                        width = 1.dp,
                        color = if (endCondition is EndCondition.Count)
                            MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            // Select all text when field gains focus (modern API)
                            countTextFieldState.edit { selectAll() }
                        }
                    },
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = if (endCondition is EndCondition.Count)
                        MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                ),
                enabled = endCondition is EndCondition.Count,
                inputTransformation = InputTransformation {
                    // Filter to digits only and limit to 3 characters
                    val filtered = asCharSequence().filter { it.isDigit() }.take(3)
                    if (filtered.toString() != asCharSequence().toString()) {
                        replace(0, length, filtered)
                    }
                }
            )
            Text(stringResource(R.string.label_recurrence_occurrences), style = MaterialTheme.typography.bodyMedium)
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
                            MaterialTheme.colorScheme.inverseSurface
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (endCondition !is EndCondition.Until)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (endCondition is EndCondition.Until) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.inverseOnSurface)
                    )
                }
            }
            Text(stringResource(R.string.label_recurrence_on_date), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = DateTimeUtils.formatEventDate(untilMillis, isAllDay = false, DateTimeUtils.localizedPattern("yMMMd")),
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
                    contentDescription = if (showDatePicker) stringResource(R.string.cd_hide_calendar) else stringResource(R.string.cd_show_calendar),
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
                },
                firstDayOfWeek = firstDayOfWeek
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
                    if (selected) MaterialTheme.colorScheme.inverseSurface
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    if (!selected)
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseOnSurface)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
