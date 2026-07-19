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

    val wkstDow = remember(firstDayOfWeek) {
        DateTimeUtils.resolveFirstDayOfWeekAsDow(firstDayOfWeek)
    }

    // Holder is keyed off the start-date inputs only, NOT off `parsed`.
    // Re-keying on `parsed` would reset the holder every time the parent
    // echoes our emission back — turning a Custom→Weekly chip detour into
    // an interval-losing round-trip (e.g. INTERVAL=200 → INTERVAL=1).
    var selections by remember(startDayOfWeek, startDayOfMonth) {
        mutableStateOf(RecurrencePickerSelections.from(parsed, startDayOfWeek, startDayOfMonth))
    }

    // Tracks the last RRULE we emitted so the LaunchedEffect below can tell a
    // self-echo (parent storing our emission) apart from an external reset
    // (e.g. user picked a different event). Only the latter rebuilds the holder.
    //
    // Contract: the parent must store the emitted string verbatim. Any
    // normalization on the way in (trimming, BYDAY reordering, dropping
    // redundant tokens) would break byte-equality, fire a false reset, and
    // silently regress the chip-detour fix — INTERVAL=200 would be lost again
    // on Custom→Weekly→Custom. EventFormSheet currently stores it verbatim.
    var lastEmitted by remember(startDayOfWeek, startDayOfMonth) { mutableStateOf(selectedRrule) }

    // Sticky across the chip-detour oscillation (self-echo guard skips
    // the reset branch below) but recomputed on a real parent-driven
    // reset. Loaded rules emit no device-wkst injection; only brand-new
    // rules let the device wkst flow into the builder gate.
    var isNewRule by remember(startDayOfWeek, startDayOfMonth) {
        mutableStateOf(selectedRrule == null)
    }

    androidx.compose.runtime.LaunchedEffect(selectedRrule) {
        if (selectedRrule != lastEmitted) {
            selections = RecurrencePickerSelections.from(parsed, startDayOfWeek, startDayOfMonth)
            lastEmitted = selectedRrule
            isNewRule = selectedRrule == null
        }
    }

    fun notifyChange() {
        val emitted = selections.toRrule(if (isNewRule) wkstDow else null)
        lastEmitted = emitted
        // Clearing the rule mid-session (Never chip) means the user is starting
        // over. The next rule they author should be treated as new — including
        // device-wkst seeding through the builder gate. The flip is one-way
        // (false → true); only the LaunchedEffect's external-reset branch can
        // restore false, so the sticky-loaded-rule contract is preserved when
        // the parent reroutes the sheet to a different loaded rule.
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
 *
 * Hand-built rather than Material3 [SingleChoiceSegmentedButtonRow] / FilterChip
 * so the row matches the inverse-surface filled chip style used elsewhere in the
 * app (ColorChipRow, calendar visibility chips, account chips). Material's
 * built-ins paint the selected state with primary/secondary container colors,
 * which would clash with the rest of the picker. Visual consistency across the
 * codebase is the reason we keep this custom — not a missed migration.
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
 * One stepper button: 40dp circle with icon. Background and border dim to 50% alpha
 * when disabled so the button reads as inert at a glance, not just slightly faded.
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
 * Custom recurrence builder: interval stepper + Day/Week/Month/Year segmented control.
 *
 * The visible stepper value equals the actual interval state (no display clamp). '+' is
 * disabled at or above 99 — that's how new in-app authoring is bounded — but '-' stays
 * enabled above 99 so an inbound `INTERVAL=200` (synced from a CalDAV server) can be
 * walked down toward range without losing the saved value.
 *
 * The Day/Week/Month/Year selector is a hand-built chip row, not Material3
 * [SingleChoiceSegmentedButtonRow]. It mirrors the [FrequencyChipRow] above so the
 * two control rows read as one visual group; switching to Material's segmented
 * button would split them stylistically.
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
            DayCircle(
                day = day,
                isSelected = isSelected,
                onClick = {
                    val newDays = if (isSelected) {
                        // Don't allow deselecting the last day
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
 * Localized label for an nth-weekday ordinal. 1-4 use the ordinal_* strings and
 * -1 uses the "last" string. Values outside {1,2,3,4,-1} (a rare imported
 * BYDAY=5FR) fall back to [R.string.ordinal_nth] so the rule renders faithfully
 * in the radio label without being coerced onto a chip.
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

/** The picker offers 1st-4th + Last; a start-date position of 5 clamps to Last. */
private fun clampSeedOrdinal(ordinalInMonth: Int): Int =
    if (ordinalInMonth in 1..4) ordinalInMonth else -1

/**
 * Monthly pattern selector with radio options.
 *
 * The third option ("On the <ordinal> <weekday>") renders its ordinal and
 * weekday from the current [pattern] when it is a [MonthlyPattern.NthWeekday],
 * so an imported rule like `BYDAY=-1FR` shows "Last" + "Friday" regardless of
 * the start date. [ordinalInMonth] and [weekday] are only the FALLBACK seed used
 * when the user first switches into the nth-weekday option from a different
 * pattern; [ordinalInMonth] is clamped to the offered set (5 -> Last).
 *
 * When the nth-weekday option is selected it expands inline (no dropdowns) into
 * a single-select ordinal chip row (1st/2nd/3rd/4th/Last, mirroring
 * [FrequencyChipRow]) and a single-select weekday circle row (the same 40dp
 * circles the weekly [WeekdaySelector] uses, respecting [firstDayOfWeek]).
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
    // Values shown by the nth-weekday option: the parsed rule wins; the
    // (clamped) start-date position is only the seed for a fresh switch-in.
    val activeOrdinal = (pattern as? MonthlyPattern.NthWeekday)?.ordinal
        ?: clampSeedOrdinal(ordinalInMonth)
    val activeWeekday = (pattern as? MonthlyPattern.NthWeekday)?.weekday ?: weekday
    val activeOrdinalLabel = nthWeekdayOrdinalLabel(activeOrdinal)
    val activeWeekdayLabel = activeWeekday.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)

    // Day shown by the by-date option: the parsed rule's day wins so an imported
    // BYMONTHDAY=9 reads "On day 9" even when the start date is the 18th; the
    // start-date day is only the seed when the pattern isn't SameDay.
    val activeDayOfMonth = (pattern as? MonthlyPattern.SameDay)?.dayOfMonth ?: dayOfMonth

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Option 1: Same day of month
        RadioOption(
            label = stringResource(R.string.recurrence_on_day, activeDayOfMonth),
            selected = pattern is MonthlyPattern.SameDay,
            onClick = { onPatternChange(MonthlyPattern.SameDay(activeDayOfMonth)) }
        )

        // Option 2: Last day of month
        RadioOption(
            label = stringResource(R.string.recurrence_on_last_day),
            selected = pattern is MonthlyPattern.LastDay,
            onClick = { onPatternChange(MonthlyPattern.LastDay) }
        )

        // Option 3: Nth weekday — label reflects the active ordinal/weekday so an
        // imported "last Friday" reads correctly even when the start date differs.
        val isNthWeekday = pattern is MonthlyPattern.NthWeekday
        RadioOption(
            label = stringResource(R.string.recurrence_on_nth_weekday, activeOrdinalLabel, activeWeekdayLabel),
            selected = isNthWeekday,
            onClick = { onPatternChange(MonthlyPattern.NthWeekday(activeOrdinal, activeWeekday)) }
        )

        // Inline expansion for the nth-weekday option: ordinal chips + weekday
        // circles, each under a caption at full width (no side-label column).
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
 * Single-select ordinal chip row (1st / 2nd / 3rd / 4th / Last), full-width and
 * flex-filled. Hand-built to mirror [FrequencyChipRow] so it reads as one visual
 * group with the rest of the picker rather than a Material segmented control.
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
 * Single-select weekday circle row for the monthly nth-weekday pattern. Reuses
 * the visual [DayCircle] from the weekly [WeekdaySelector] and its SpaceEvenly /
 * firstDayOfWeek-ordered layout, but with single-select semantics (tapping a day
 * replaces the selection) — it deliberately does NOT share the weekly selector's
 * "can't deselect the last day" accumulation rule, which has no meaning here.
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
 * One 40dp weekday circle with a NARROW day label. Visual-only; selection and
 * click semantics are owned by the caller so both the multi-select weekly
 * selector and the single-select monthly selector can share the same look.
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
            // Expose selection to TalkBack (and tests) so state isn't conveyed by
            // the filled dot alone.
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
