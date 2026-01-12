package org.onekash.kashcal.domain.rrule

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
 */
data class ParsedRecurrence(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyPattern: MonthlyPattern? = null,
    val endCondition: EndCondition = EndCondition.Never
)

/**
 * Frequency option for the chip selector in UI.
 * Maps user-friendly labels to frequency + interval combinations.
 */
enum class FrequencyOption(val label: String, val freq: RecurrenceFrequency, val interval: Int = 1) {
    NEVER("Never", RecurrenceFrequency.NONE),
    DAILY("Daily", RecurrenceFrequency.DAILY),
    WEEKLY("Weekly", RecurrenceFrequency.WEEKLY),
    BIWEEKLY("Biweekly", RecurrenceFrequency.WEEKLY, 2),
    MONTHLY("Monthly", RecurrenceFrequency.MONTHLY),
    QUARTERLY("Quarterly", RecurrenceFrequency.MONTHLY, 3),
    YEARLY("Yearly", RecurrenceFrequency.YEARLY)
}
