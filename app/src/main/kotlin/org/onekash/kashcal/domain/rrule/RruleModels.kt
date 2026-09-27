package org.onekash.kashcal.domain.rrule

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek

/**
 * Domain models for the parsed state of an RFC 5545 RRULE, for display and editing in the UI.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10">RFC 5545 RRULE</a>
 */

/** Frequency of a recurrence rule. */
enum class RecurrenceFrequency {
    /** No recurrence: a single event. */
    NONE,
    DAILY,
    /** May include BYDAY. */
    WEEKLY,
    /** May include BYMONTHDAY or BYDAY. */
    MONTHLY,
    YEARLY,
    /** A rule that doesn't fit the simple frequencies. */
    CUSTOM
}

/**
 * Day within the month of a monthly rule.
 *
 * Examples:
 * - SameDay(15) -> BYMONTHDAY=15 (15th of each month)
 * - LastDay -> BYMONTHDAY=-1 (last day of month)
 * - NthWeekday(2, TUESDAY) -> BYDAY=2TU (2nd Tuesday)
 * - NthWeekday(-1, FRIDAY) -> BYDAY=-1FR (last Friday)
 */
@Immutable
sealed class MonthlyPattern {
    /**
     * The same day of each month (e.g. the 15th).
     * @property dayOfMonth 1-31 from the picker; a parsed BYMONTHDAY other than -1 is kept as
     *   written, so it can be negative.
     */
    data class SameDay(val dayOfMonth: Int) : MonthlyPattern()

    /** The last day of the month (BYMONTHDAY=-1). */
    data object LastDay : MonthlyPattern()

    /**
     * The nth weekday of the month (e.g. the 2nd Tuesday).
     * @property ordinal 1-4 for 1st-4th, -1 for last; a parsed BYDAY ordinal is kept as written.
     */
    data class NthWeekday(val ordinal: Int, val weekday: DayOfWeek) : MonthlyPattern()
}

/** End of a recurrence rule. */
@Immutable
sealed class EndCondition {
    /** Repeats forever (no COUNT or UNTIL). */
    data object Never : EndCondition()

    /** Ends after [count] occurrences (COUNT). */
    data class Count(val count: Int) : EndCondition()

    /** Ends on or before [dateMillis], an epoch-millisecond UNTIL. */
    data class Until(val dateMillis: Long) : EndCondition()
}

/**
 * Holds the recurrence options [RruleBuilder.parseRrule] extracts from an RRULE.
 *
 * @property interval INTERVAL, default 1.
 * @property weekdays BYDAY days, for a weekly rule.
 * @property wkst WKST when the rule has one, else null. Kept across no-op edits so a
 *   CalDAV-pulled `WKST=SU` doesn't silently become the device's week start on save;
 *   [RruleBuilder.weekly] drops it when it has no effect.
 * @property extraTokens BY* parts the picker doesn't model for this frequency, captured
 *   verbatim: BYMONTH, BYWEEKNO, BYYEARDAY, BYSETPOS, and BYDAY or BYMONTHDAY where the picker
 *   doesn't show them. Re-appended on emission so a CalDAV-pulled rule like
 *   `FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15` survives a no-op save instead of degrading to
 *   `FREQ=YEARLY`. Any extra opens the picker on [FrequencyOption.CUSTOM]
 *   (`selectInitialFrequencyOption`), so the rule is rebuilt from its unit and interval, not
 *   coerced to a preset.
 */
@Immutable
data class ParsedRecurrence(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyPattern: MonthlyPattern? = null,
    val endCondition: EndCondition = EndCondition.Never,
    val wkst: DayOfWeek? = null,
    val extraTokens: List<String> = emptyList(),
)

/**
 * Names a chip of the recurrence picker's frequency selector.
 *
 * The picker lays them out in two rows of three: NEVER, DAILY, WEEKLY, then MONTHLY, YEARLY,
 * CUSTOM. NEVER builds no RRULE; CUSTOM is a UI-only marker that builds from the picker's
 * separate unit, interval, weekday and monthly state.
 */
enum class FrequencyOption(val label: String) {
    NEVER("Never"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    CUSTOM("Custom")
}

/** Returns the option's frequency, or null for NEVER and CUSTOM, which map to none. */
fun FrequencyOption.toFrequency(): RecurrenceFrequency? = when (this) {
    FrequencyOption.NEVER -> null
    FrequencyOption.DAILY -> RecurrenceFrequency.DAILY
    FrequencyOption.WEEKLY -> RecurrenceFrequency.WEEKLY
    FrequencyOption.MONTHLY -> RecurrenceFrequency.MONTHLY
    FrequencyOption.YEARLY -> RecurrenceFrequency.YEARLY
    FrequencyOption.CUSTOM -> null
}
