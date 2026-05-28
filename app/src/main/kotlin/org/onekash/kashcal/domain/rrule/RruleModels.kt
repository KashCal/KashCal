package org.onekash.kashcal.domain.rrule

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek

/**
 * Domain models for RFC 5545 RRULE recurrence rules.
 *
 * These models represent the parsed state of recurrence rules
 * for UI display and manipulation.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10">RFC 5545 RRULE</a>
 */

/**
 * Recurrence frequency options.
 */
enum class RecurrenceFrequency {
    /** No recurrence - single event */
    NONE,
    /** Daily recurrence */
    DAILY,
    /** Weekly recurrence (may include BYDAY) */
    WEEKLY,
    /** Monthly recurrence (may include BYMONTHDAY or BYDAY) */
    MONTHLY,
    /** Yearly recurrence */
    YEARLY,
    /** Complex rule that doesn't fit simple categories */
    CUSTOM
}

/**
 * Monthly pattern options for recurring events.
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
     * Same day of month (e.g., 15th).
     * @property dayOfMonth Day of month (1-31)
     */
    data class SameDay(val dayOfMonth: Int) : MonthlyPattern()

    /**
     * Last day of month.
     * Generates BYMONTHDAY=-1
     */
    data object LastDay : MonthlyPattern()

    /**
     * Nth weekday of month (e.g., "2nd Tuesday").
     * @property ordinal 1-4 for 1st-4th, -1 for last
     * @property weekday The day of week
     */
    data class NthWeekday(val ordinal: Int, val weekday: DayOfWeek) : MonthlyPattern()
}

/**
 * End condition for recurring events.
 */
@Immutable
sealed class EndCondition {
    /** Repeats forever (no COUNT or UNTIL) */
    data object Never : EndCondition()

    /**
     * Ends after N occurrences.
     * @property count Number of occurrences (COUNT=N)
     */
    data class Count(val count: Int) : EndCondition()

    /**
     * Ends on or before a specific date.
     * @property dateMillis End date timestamp in milliseconds (UNTIL=...)
     */
    data class Until(val dateMillis: Long) : EndCondition()
}

/**
 * Parsed recurrence state from RRULE string.
 *
 * Represents all configurable recurrence options extracted from
 * an RRULE for display and editing in the UI.
 *
 * @property frequency Base frequency (DAILY, WEEKLY, etc.)
 * @property interval Interval between occurrences (INTERVAL=N, default 1)
 * @property weekdays Selected days for weekly recurrence (BYDAY)
 * @property monthlyPattern Pattern for monthly recurrence
 * @property endCondition How the recurrence ends
 * @property wkst Week-start day (WKST=XX) when present in the rule, else null.
 *   Preserved across no-op edits so a CalDAV-pulled rule with `WKST=SU` doesn't
 *   silently rewrite to the device's wkst on Save. The builder's emission gate
 *   ([RruleBuilder.weekly]) drops it when it has no semantic effect.
 * @property extraTokens RRULE parts the picker doesn't model directly
 *   (BYMONTH, BYWEEKNO, BYYEARDAY, BYSETPOS) captured verbatim from the inbound
 *   rule. Re-appended on emission so a CalDAV-pulled rule like
 *   `FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=15` round-trips intact instead of
 *   degrading to `FREQ=YEARLY` on a no-op save. Routes through `frequency =
 *   RecurrenceFrequency.CUSTOM` so the picker stays in verbatim-emit mode.
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
 * Frequency option for the chip selector in UI.
 *
 * Layout pairs with a 3+3 chip grid:
 * row 1 = NEVER / DAILY / WEEKLY, row 2 = MONTHLY / YEARLY / CUSTOM.
 *
 * CUSTOM is a UI-only marker; consumers map the option to a concrete
 * [RecurrenceFrequency] via [toFrequency], which returns null for
 * NEVER and CUSTOM. NEVER builds no RRULE; CUSTOM dispatches on the
 * picker's separate unit/interval/weekday/monthly state.
 */
enum class FrequencyOption(val label: String) {
    NEVER("Never"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    CUSTOM("Custom")
}

/**
 * Concrete frequency for the option, or null when no direct mapping
 * applies (NEVER builds no RRULE; CUSTOM defers to the picker's
 * unit/interval state).
 */
fun FrequencyOption.toFrequency(): RecurrenceFrequency? = when (this) {
    FrequencyOption.NEVER -> null
    FrequencyOption.DAILY -> RecurrenceFrequency.DAILY
    FrequencyOption.WEEKLY -> RecurrenceFrequency.WEEKLY
    FrequencyOption.MONTHLY -> RecurrenceFrequency.MONTHLY
    FrequencyOption.YEARLY -> RecurrenceFrequency.YEARLY
    FrequencyOption.CUSTOM -> null
}
