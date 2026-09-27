package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.rrule.RruleDisplayStrings
import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Calendar as JavaCalendar

@Composable
fun rememberRruleDisplayStrings(): RruleDisplayStrings {
    val doesNotRepeat = stringResource(R.string.rrule_does_not_repeat)
    val freqDaily = stringResource(R.string.rrule_freq_daily)
    val freqWeekly = stringResource(R.string.rrule_freq_weekly)
    val freqMonthly = stringResource(R.string.rrule_freq_monthly)
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
    val resources = androidx.compose.ui.platform.LocalResources.current
    return remember {
        RruleDisplayStrings(
            doesNotRepeat = doesNotRepeat,
            freqDaily = freqDaily,
            freqWeekly = freqWeekly,
            freqMonthly = freqMonthly,
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
        FrequencyOption.MONTHLY -> stringResource(R.string.rrule_freq_monthly)
        FrequencyOption.YEARLY -> stringResource(R.string.rrule_freq_yearly)
        FrequencyOption.CUSTOM -> stringResource(R.string.rrule_freq_custom)
    }
}

@Composable
private fun customUnitLabel(unit: CustomRecurrenceUnit): String = when (unit) {
    CustomRecurrenceUnit.DAY -> stringResource(R.string.rrule_unit_day)
    CustomRecurrenceUnit.WEEK -> stringResource(R.string.rrule_unit_week)
    CustomRecurrenceUnit.MONTH -> stringResource(R.string.rrule_unit_month)
    CustomRecurrenceUnit.YEAR -> stringResource(R.string.rrule_unit_year)
}


@Composable
fun RecurrencePickerRow(
    selectedRrule: String?,
    startDateMillis: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    // The event's all-day flag and timezone: the end date is written and shown
    // in the event's own terms (RFC 5545 section 3.3.10).
    isAllDay: Boolean = false,
    timezone: String? = null,
) {
    val focusManager = LocalFocusManager.current
    val rruleStrings = rememberRruleDisplayStrings()
    val displayText = RruleBuilder.formatForDisplay(selectedRrule, rruleStrings, RruleBuilder.untilZoneFor(isAllDay, timezone))

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

    val wkstDow = remember(firstDayOfWeek) {
        DateTimeUtils.resolveFirstDayOfWeekAsDow(firstDayOfWeek)
    }

    // The holder is keyed on the start-date inputs only, not on `parsed`: keying on `parsed`
    // would reset it each time the parent echoes an emission back, so a Custom to Weekly chip
    // detour would lose the interval (INTERVAL=200 back to INTERVAL=1).
    var selections by remember(startDayOfWeek, startDayOfMonth) {
        mutableStateOf(RecurrencePickerSelections.from(parsed, startDayOfWeek, startDayOfMonth))
    }

    // The last emitted RRULE, so the LaunchedEffect below can tell a self-echo (the parent
    // storing an emission) from an external reset such as a different event. Only a reset
    // rebuilds the holder.
    //
    // The parent must store the emitted string verbatim. Any normalization (trimming, BYDAY
    // reordering, dropping redundant tokens) breaks byte equality and fires a false reset, so
    // INTERVAL=200 would be lost on Custom, Weekly, Custom. EventFormSheet stores it verbatim.
    var lastEmitted by remember(startDayOfWeek, startDayOfMonth) { mutableStateOf(selectedRrule) }

    // Survives a chip detour (the self-echo check skips the reset branch below) and is
    // recomputed on an external reset. Only a new rule gets the device's week start as WKST;
    // a loaded rule never does.
    var isNewRule by remember(startDayOfWeek, startDayOfMonth) {
        mutableStateOf(selectedRrule == null)
    }

    androidx.compose.runtime.LaunchedEffect(selectedRrule) {
        if (selectedRrule != lastEmitted) {
            if (onlyUntilDiffers(lastEmitted, selectedRrule)) {
                // Only the end date's form changed (an all-day toggle): keep the
                // picker's choices and whether this is a new rule.
                selections = selections.copy(endCondition = parsed.endCondition)
            } else {
                selections = RecurrencePickerSelections.from(parsed, startDayOfWeek, startDayOfMonth)
                isNewRule = selectedRrule == null
            }
            lastEmitted = selectedRrule
        }
    }

    fun notifyChange() {
        val emitted = selections.toRrule(if (isNewRule) wkstDow else null, isAllDay)
        lastEmitted = emitted
        // Picking Never starts over, so the next rule is new and gets the device's week start.
        // This only sets true; only an external reset (the LaunchedEffect's reset branch, or a
        // new start date re-keying the state) sets false, when the rule it loads isn't null.
        if (emitted == null) isNewRule = true
        onSelect(emitted)
    }

    val showWeekdaySelector = selections.frequencyOption == FrequencyOption.WEEKLY ||
        (selections.frequencyOption == FrequencyOption.CUSTOM && selections.customUnit == CustomRecurrenceUnit.WEEK)
    val showMonthlySelector = selections.frequencyOption == FrequencyOption.MONTHLY ||
        (selections.frequencyOption == FrequencyOption.CUSTOM && selections.customUnit == CustomRecurrenceUnit.MONTH)

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
                    options = listOf(FrequencyOption.NEVER, FrequencyOption.DAILY, FrequencyOption.WEEKLY),
                    selected = selections.frequencyOption,
                    onSelect = { option -> selections = selections.copy(frequencyOption = option); notifyChange() }
                )
                Spacer(modifier = Modifier.height(6.dp))
                FrequencyChipRow(
                    options = listOf(FrequencyOption.MONTHLY, FrequencyOption.YEARLY, FrequencyOption.CUSTOM),
                    selected = selections.frequencyOption,
                    onSelect = { option -> selections = selections.copy(frequencyOption = option); notifyChange() }
                )

                if (selections.frequencyOption == FrequencyOption.CUSTOM) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CustomRecurrenceBuilder(
                        interval = selections.interval,
                        unit = selections.customUnit,
                        onIntervalChange = { newInterval ->
                            selections = selections.copy(interval = newInterval)
                            notifyChange()
                        },
                        onUnitChange = { newUnit ->
                            val (nextWeekdays, nextMonthly) = applyUnitTransition(
                                previous = selections.customUnit,
                                new = newUnit,
                                weekdays = selections.weekdays,
                                monthlyPattern = selections.monthlyPattern,
                                startDayOfWeek = startDayOfWeek,
                            )
                            selections = selections.copy(
                                customUnit = newUnit,
                                weekdays = nextWeekdays,
                                monthlyPattern = nextMonthly,
                            )
                            notifyChange()
                        },
                    )
                }

                if (showWeekdaySelector) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_on_days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    WeekdaySelector(
                        selectedDays = selections.weekdays,
                        onDaysChange = { days -> selections = selections.copy(weekdays = days); notifyChange() },
                        firstDayOfWeek = firstDayOfWeek
                    )
                }

                if (showMonthlySelector) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_pattern),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MonthlyPatternSelector(
                        pattern = selections.monthlyPattern ?: MonthlyPattern.SameDay(startDayOfMonth),
                        dayOfMonth = startDayOfMonth,
                        ordinalInMonth = startOrdinalInMonth,
                        weekday = startDayOfWeek,
                        onPatternChange = { pattern -> selections = selections.copy(monthlyPattern = pattern); notifyChange() },
                        firstDayOfWeek = firstDayOfWeek
                    )
                }

                if (selections.frequencyOption != FrequencyOption.NEVER) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.label_ends),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    EndConditionSelector(
                        endCondition = selections.endCondition,
                        startDateMillis = startDateMillis,
                        onEndConditionChange = { condition -> selections = selections.copy(endCondition = condition); notifyChange() },
                        firstDayOfWeek = firstDayOfWeek,
                        isAllDay = isAllDay,
                        timezone = timezone,
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
 * Shows a row of single-select frequency chips.
 *
 * Hand-built rather than a Material3 `SingleChoiceSegmentedButtonRow` or FilterChip so the
 * selected chip uses the app's inverse-surface fill, like the selected day in the date picker
 * and agenda week bar. Material's built-ins paint the selected state with container colors,
 * which would clash with the rest of the picker. This is a deliberate choice, not a missed
 * migration.
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
 * Shows a 40dp circular stepper button. When disabled, background and border drop to 50% alpha
 * so it reads as inert at a glance, not only slightly faded.
 */
@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val backgroundAlpha = if (enabled) 1f else 0.5f
    val iconAlpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = backgroundAlpha))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = backgroundAlpha),
                CircleShape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = iconAlpha),
        )
    }
}

/**
 * Shows the Custom builder: an interval stepper and a Day/Week/Month/Year chip row.
 *
 * The stepper shows the real interval, unclamped. '+' is disabled at 99 and above, which bounds
 * in-app authoring, but '-' stays enabled above 99 so a synced `INTERVAL=200` can be walked
 * down without losing the saved value.
 *
 * The unit row is hand-built like [FrequencyChipRow], not a Material3
 * `SingleChoiceSegmentedButtonRow`, so the two rows read as one visual group.
 */
@Composable
fun CustomRecurrenceBuilder(
    interval: Int,
    unit: CustomRecurrenceUnit,
    onIntervalChange: (Int) -> Unit,
    onUnitChange: (CustomRecurrenceUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.label_repeat_every),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val canDecrement = interval > 1
            val canIncrement = interval < 99
            StepperButton(
                icon = Icons.Default.Remove,
                contentDescription = stringResource(R.string.cd_decrease_interval),
                enabled = canDecrement,
                onClick = { onIntervalChange(interval - 1) },
            )
            Text(
                text = interval.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center,
            )
            StepperButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_increase_interval),
                enabled = canIncrement,
                onClick = { onIntervalChange(interval + 1) },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CustomRecurrenceUnit.entries.forEach { entry ->
                val isSelected = entry == unit
                val label = customUnitLabel(entry)
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
                        .clickable { onUnitChange(entry) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Shows a multi-select row of weekday circles; the last selected day can't be deselected. */
@Composable
fun WeekdaySelector(
    selectedDays: Set<DayOfWeek>,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    // Display order follows the first-day-of-week setting; the RRULE doesn't depend on it.
    val daysOrder = remember(firstDayOfWeek) {
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        daysOrder.forEach { day ->
            val isSelected = day in selectedDays
            DayCircle(
                day = day,
                isSelected = isSelected,
                onClick = {
                    val newDays = if (isSelected) {
                        // The last selected day stays selected.
                        if (selectedDays.size > 1) selectedDays - day else selectedDays
                    } else {
                        selectedDays + day
                    }
                    onDaysChange(newDays)
                }
            )
        }
    }
}

/** Ordinals offered by the nth-weekday chip row: 1st-4th plus Last (-1). */
private val NTH_WEEKDAY_ORDINALS = listOf(1, 2, 3, 4, -1)

/**
 * Returns the localized label for an nth-weekday ordinal: 1-4 use the ordinal_* strings, -1
 * the "last" string. Any other value, such as an imported BYDAY=5FR, falls back to
 * [R.string.ordinal_nth] so the radio label shows the rule as is without forcing it onto a chip.
 */
@Composable
private fun nthWeekdayOrdinalLabel(ordinal: Int): String = when (ordinal) {
    1 -> stringResource(R.string.ordinal_1st)
    2 -> stringResource(R.string.ordinal_2nd)
    3 -> stringResource(R.string.ordinal_3rd)
    4 -> stringResource(R.string.ordinal_4th)
    -1 -> stringResource(R.string.rrule_ordinal_last)
    else -> stringResource(R.string.ordinal_nth, ordinal)
}

/** Clamps a start-date position to the offered 1st-4th or Last; 5 becomes Last (-1). */
private fun clampSeedOrdinal(ordinalInMonth: Int): Int =
    if (ordinalInMonth in 1..4) ordinalInMonth else -1

/**
 * Shows the monthly pattern radio options: on day N, on the last day, or on the nth weekday.
 *
 * The nth-weekday option takes its ordinal and weekday from [pattern] when it is a
 * [MonthlyPattern.NthWeekday], so an imported `BYDAY=-1FR` shows "Last" and "Friday" whatever
 * the start date. [ordinalInMonth] and [weekday] are only the seed when the user switches in
 * from another pattern; [ordinalInMonth] is clamped to the offered set (5 becomes Last).
 *
 * When selected, that option expands inline into a single-select ordinal chip row
 * (1st/2nd/3rd/4th/Last, styled like [FrequencyChipRow]) and a single-select row of the
 * weekday circles [WeekdaySelector] uses, ordered by [firstDayOfWeek].
 */
@Composable
fun MonthlyPatternSelector(
    pattern: MonthlyPattern,
    dayOfMonth: Int,
    ordinalInMonth: Int,
    weekday: DayOfWeek,
    onPatternChange: (MonthlyPattern) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    // The rule's nth weekday wins; the clamped start-date position only seeds a switch-in.
    val activeOrdinal = (pattern as? MonthlyPattern.NthWeekday)?.ordinal
        ?: clampSeedOrdinal(ordinalInMonth)
    val activeWeekday = (pattern as? MonthlyPattern.NthWeekday)?.weekday ?: weekday
    val activeOrdinalLabel = nthWeekdayOrdinalLabel(activeOrdinal)
    val activeWeekdayLabel = activeWeekday.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)

    // The rule's day wins, so an imported BYMONTHDAY=9 reads "On day 9" even when the start
    // date is the 18th; the start date's day is the seed when the pattern isn't SameDay.
    val activeDayOfMonth = (pattern as? MonthlyPattern.SameDay)?.dayOfMonth ?: dayOfMonth

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // On day N of the month.
        RadioOption(
            label = stringResource(R.string.recurrence_on_day, activeDayOfMonth),
            selected = pattern is MonthlyPattern.SameDay,
            onClick = { onPatternChange(MonthlyPattern.SameDay(activeDayOfMonth)) }
        )

        // On the last day of the month.
        RadioOption(
            label = stringResource(R.string.recurrence_on_last_day),
            selected = pattern is MonthlyPattern.LastDay,
            onClick = { onPatternChange(MonthlyPattern.LastDay) }
        )

        // On the nth weekday; the label shows the active ordinal and weekday, so an imported
        // "last Friday" reads as such whatever the start date.
        val isNthWeekday = pattern is MonthlyPattern.NthWeekday
        RadioOption(
            label = stringResource(R.string.recurrence_on_nth_weekday, activeOrdinalLabel, activeWeekdayLabel),
            selected = isNthWeekday,
            onClick = { onPatternChange(MonthlyPattern.NthWeekday(activeOrdinal, activeWeekday)) }
        )

        // Ordinal chips and weekday circles, each under a full-width caption.
        AnimatedVisibility(
            visible = isNthWeekday,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.recurrence_which),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OrdinalChipRow(
                    selectedOrdinal = activeOrdinal,
                    onSelect = { ordinal ->
                        onPatternChange(MonthlyPattern.NthWeekday(ordinal, activeWeekday))
                    }
                )
                Text(
                    stringResource(R.string.recurrence_weekday),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                SingleWeekdaySelector(
                    selectedDay = activeWeekday,
                    onDaySelect = { day ->
                        onPatternChange(MonthlyPattern.NthWeekday(activeOrdinal, day))
                    },
                    firstDayOfWeek = firstDayOfWeek
                )
            }
        }
    }
}

/**
 * Shows a full-width single-select row of 1st/2nd/3rd/4th/Last chips, hand-built like
 * [FrequencyChipRow] so it reads as one visual group with the rest of the picker.
 */
@Composable
private fun OrdinalChipRow(
    selectedOrdinal: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NTH_WEEKDAY_ORDINALS.forEach { ordinal ->
            val isSelected = ordinal == selectedOrdinal
            val label = nthWeekdayOrdinalLabel(ordinal)
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
                    .clickable { onSelect(ordinal) }
                    .semantics { selected = isSelected }
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
 * Shows a single-select weekday circle row for the monthly nth-weekday pattern. It uses
 * [WeekdaySelector]'s [DayCircle] and layout, but tapping a day replaces the selection; the
 * weekly selector's keep-the-last-day rule has no meaning here.
 */
@Composable
private fun SingleWeekdaySelector(
    selectedDay: DayOfWeek,
    onDaySelect: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val daysOrder = remember(firstDayOfWeek) {
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        daysOrder.forEach { day ->
            DayCircle(
                day = day,
                isSelected = day == selectedDay,
                onClick = { onDaySelect(day) }
            )
        }
    }
}

/**
 * Shows a 40dp weekday circle with a narrow day label. The caller owns selection and click
 * behavior, so the multi-select weekly and single-select monthly rows share the look.
 */
@Composable
private fun DayCircle(
    day: DayOfWeek,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = day.getDisplayName(TextStyle.NARROW, LocalLocale.current.platformLocale)
    Box(
        modifier = modifier
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
            .clickable { onClick() }
            .semantics { selected = isSelected },
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

/** Shows the end condition options: never, after N occurrences, or on a date picked inline. */
@Composable
fun EndConditionSelector(
    endCondition: EndCondition,
    startDateMillis: Long,
    onEndConditionChange: (EndCondition) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    isAllDay: Boolean = false,
    timezone: String? = null,
) {
    // TextFieldState supports select-all on focus.
    val initialCountText = if (endCondition is EndCondition.Count) endCondition.count.toString() else "10"
    val countTextFieldState = rememberTextFieldState(initialCountText)

    // The long-running LaunchedEffect below reads these current values.
    val currentEndCondition by androidx.compose.runtime.rememberUpdatedState(endCondition)
    val currentOnEndConditionChange by androidx.compose.runtime.rememberUpdatedState(onEndConditionChange)

    // Detects a switch into Count mode, as opposed to a new count value.
    var wasCountMode by remember { mutableStateOf(endCondition is EndCondition.Count) }

    // Sync the text field only on a switch into Count mode.
    androidx.compose.runtime.LaunchedEffect(endCondition) {
        val isCountMode = endCondition is EndCondition.Count
        if (isCountMode && !wasCountMode) {
            // Switched into Count mode.
            val newText = (endCondition as EndCondition.Count).count.toString()
            if (countTextFieldState.text.toString() != newText) {
                countTextFieldState.setTextAndPlaceCursorAtEnd(newText)
            }
        }
        wasCountMode = isCountMode
    }

    // Propagate typed counts while in Count mode.
    androidx.compose.runtime.LaunchedEffect(countTextFieldState) {
        androidx.compose.runtime.snapshotFlow { countTextFieldState.text.toString() }
            .collect { text ->
                // Only a positive number propagates; empty or partial input waits.
                val count = text.toIntOrNull()?.takeIf { it > 0 }
                if (count != null && currentEndCondition is EndCondition.Count &&
                    (currentEndCondition as EndCondition.Count).count != count) {
                    currentOnEndConditionChange(EndCondition.Count(count))
                }
            }
    }

    var untilMillis by remember(endCondition, isAllDay, timezone, startDateMillis) {
        mutableStateOf(
            if (endCondition is EndCondition.Until) endCondition.dateMillis
            else defaultUntilMillis(startDateMillis, isAllDay, timezone)
        )
    }
    // The date the rule ends on, as a device-local midnight for the grid and label.
    val untilDisplay = untilDisplayMillis(untilMillis, isAllDay, timezone)

    // Whether the inline date picker is open.
    var showDatePicker by remember { mutableStateOf(false) }

    // Month the date picker shows.
    var displayedMonth by remember(untilDisplay) {
        mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = untilDisplay })
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Never.
        RadioOption(
            label = stringResource(R.string.label_recurrence_never),
            selected = endCondition is EndCondition.Never,
            onClick = {
                onEndConditionChange(EndCondition.Never)
                showDatePicker = false
            }
        )

        // After N occurrences.
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
                            // Select all text on focus.
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
                    // Digits only, at most 3.
                    val filtered = asCharSequence().filter { it.isDigit() }.take(3)
                    if (filtered.toString() != asCharSequence().toString()) {
                        replace(0, length, filtered)
                    }
                }
            )
            Text(stringResource(R.string.label_recurrence_occurrences), style = MaterialTheme.typography.bodyMedium)
        }

        // On a date; tapping the row selects it and toggles the date picker.
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
                text = DateTimeUtils.formatEventDate(untilDisplay, isAllDay = false, DateTimeUtils.localizedPattern("yMMMd")),
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

        // Date picker for the end date, shown only while it is the selected option.
        AnimatedVisibility(
            visible = showDatePicker && endCondition is EndCondition.Until,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
        ) {
            InlineDatePickerContent(
                selectedDateMillis = untilDisplay,
                displayedMonth = displayedMonth,
                onDateSelect = { newDateMillis ->
                    untilMillis = untilForPickedDate(newDateMillis, isAllDay, timezone)
                    onEndConditionChange(EndCondition.Until(untilMillis))
                },
                onMonthChange = { newMonth ->
                    displayedMonth = newMonth
                },
                firstDayOfWeek = firstDayOfWeek
            )
        }
    }
}

/** Shows a radio row with a circle indicator. */
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
            // TalkBack and tests read the selection here, not only from the filled dot.
            .semantics { this.selected = selected }
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
