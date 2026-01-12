package org.onekash.kashcal.sync.parser

/**
 * Interface for iCal (RFC 5545) parsing.
 *
 * Implementations:
 * - [Ical4jParser]: Production parser using ical4j library
 * - [RegexICalParser]: Reference implementation ported from ical-app
 *
 * Both implementations should produce identical [ParsedEvent] output for
 * the same input, verified by comparison tests.
 */
interface ICalParser {

    /**
     * Parse iCal data (VCALENDAR) into events.
     *
     * @param icalData Raw iCal text (RFC 5545 format)
     * @return ParseResult containing events and/or errors
     */
    fun parse(icalData: String): ParseResult

    /**
     * Generate iCal data from a list of events.
     *
     * @param events Events to serialize
     * @return RFC 5545 compliant VCALENDAR string
     */
    fun generate(events: List<ParsedEvent>): String

    /**
     * Generate iCal data for a single event.
     *
     * @param event Event to serialize
     * @return RFC 5545 compliant VCALENDAR string
     */
    fun generate(event: ParsedEvent): String = generate(listOf(event))

    /**
     * Merge an exception VEVENT into a master event's iCal data.
     *
     * Used for RECURRENCE-ID handling where iCloud expects master and
     * exceptions in the same .ics file.
     *
     * @param masterIcal iCal data containing the master VEVENT
     * @param exception Exception event to merge
     * @return Updated iCal data with exception VEVENT added/updated
     */
    fun mergeException(masterIcal: String, exception: ParsedEvent): String

    /**
     * Remove an exception VEVENT from a master event's iCal data.
     *
     * @param masterIcal iCal data containing master and exception VEVENTs
     * @param recurrenceId RECURRENCE-ID of the exception to remove
     * @return Updated iCal data with exception removed
     */
    fun removeException(masterIcal: String, recurrenceId: String): String
}

/**
 * Constants for RRULE encoding compatible with ical-app.
 */
object RRuleConstants {
    // Time intervals in seconds
    const val DAY = 86400
    const val WEEK = 604800
    const val MONTH = 2592000  // 30 days (approximate)
    const val YEAR = 31536000  // 365 days

    // Day bits for weekly BYDAY
    const val SUNDAY_BIT = 0x01
    const val MONDAY_BIT = 0x02
    const val TUESDAY_BIT = 0x04
    const val WEDNESDAY_BIT = 0x08
    const val THURSDAY_BIT = 0x10
    const val FRIDAY_BIT = 0x20
    const val SATURDAY_BIT = 0x40

    // Monthly repeat patterns
    const val REPEAT_SAME_DAY = 0        // Same day of month (e.g., 15th)
    const val REPEAT_LAST_DAY = 0x4000   // Last day of month

    // Bit masks
    const val LAST_OCCURRENCE_FLAG = 0x8000  // -1 ordinal (last Monday, etc.)
    const val ORDINAL_SHIFT = 12             // Bits 12-15 for ordinal (1-5)
}
