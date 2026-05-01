package org.onekash.icaldav.model

import java.time.Duration

/**
 * Calendar-level metadata from VCALENDAR properties.
 *
 * Includes RFC 5545 standard properties and RFC 7986 extended properties:
 * - NAME: Human-readable calendar name
 * - SOURCE: URL where calendar can be refreshed from
 * - COLOR: Calendar color for UI display
 * - REFRESH-INTERVAL: Suggested refresh interval for subscriptions
 *
 * Also handles non-standard but widely-used properties:
 * - X-WR-CALNAME: Apple/Google calendar name
 * - X-APPLE-CALENDAR-COLOR: Apple calendar color
 *
 * Example iCalendar format:
 * ```
 * BEGIN:VCALENDAR
 * VERSION:2.0
 * PRODID:-//Example//Calendar//EN
 * NAME:Work Calendar
 * COLOR:crimson
 * SOURCE:https://example.com/calendar.ics
 * REFRESH-INTERVAL;VALUE=DURATION:P1D
 * BEGIN:VEVENT
 * ...
 * END:VEVENT
 * END:VCALENDAR
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986">RFC 7986 - iCalendar Extensions</a>
 */
data class ICalCalendar(
    /** PRODID - Product identifier that created this calendar */
    val prodId: String?,

    /** VERSION - iCalendar version (usually "2.0") */
    val version: String = "2.0",

    /** CALSCALE - Calendar scale (usually "GREGORIAN") */
    val calscale: String = "GREGORIAN",

    /** METHOD - iTIP method for scheduling (PUBLISH, REQUEST, REPLY, etc.) */
    val method: String? = null,

    /** NAME - Human-readable calendar name (RFC 7986) */
    val name: String? = null,

    /** SOURCE - URL where calendar can be fetched/refreshed (RFC 7986) */
    val source: String? = null,

    /** COLOR - Calendar color for display (RFC 7986) */
    val color: String? = null,

    /** REFRESH-INTERVAL - Suggested subscription refresh interval (RFC 7986) */
    val refreshInterval: Duration? = null,

    /** X-WR-CALNAME - Non-standard calendar name (Apple/Google) */
    val xWrCalname: String? = null,

    /** X-APPLE-CALENDAR-COLOR - Non-standard calendar color (Apple) */
    val xAppleCalendarColor: String? = null,

    /** IMAGE - Calendar image/icon (RFC 7986) */
    val image: ICalImage? = null,

    /** All VEVENT components in this calendar */
    val events: List<ICalEvent> = emptyList(),

    /** All VTODO components in this calendar */
    val todos: List<ICalTodo> = emptyList(),

    /** All VJOURNAL components in this calendar */
    val journals: List<ICalJournal> = emptyList()
) {
    /**
     * Get effective calendar name.
     * Prefers RFC 7986 NAME over X-WR-CALNAME.
     */
    val effectiveName: String?
        get() = name ?: xWrCalname

    /**
     * Get effective calendar color.
     * Prefers RFC 7986 COLOR over X-APPLE-CALENDAR-COLOR.
     */
    val effectiveColor: String?
        get() = color ?: xAppleCalendarColor

    /**
     * Check if this calendar has any events.
     */
    fun hasEvents(): Boolean = events.isNotEmpty()

    /**
     * Check if this calendar has any todos.
     */
    fun hasTodos(): Boolean = todos.isNotEmpty()

    /**
     * Check if this calendar has any journals.
     */
    fun hasJournals(): Boolean = journals.isNotEmpty()

    /**
     * Get the number of components (events + todos + journals).
     */
    val componentCount: Int
        get() = events.size + todos.size + journals.size

    companion object {
        /**
         * Create a minimal calendar with default values.
         *
         * @param prodId Product identifier
         * @param name Calendar display name
         * @return ICalCalendar with defaults
         */
        fun create(
            prodId: String = "-//iCalDAV//EN",
            name: String? = null
        ): ICalCalendar {
            return ICalCalendar(
                prodId = prodId,
                name = name
            )
        }
    }
}

/**
 * VTODO component representing a task per RFC 5545 Section 3.6.2.
 *
 * Supports task management including:
 * - Basic properties: summary, description, due date, priority
 * - Status tracking: NEEDS-ACTION, IN-PROCESS, COMPLETED, CANCELLED
 * - Task assignment: organizer (assigner) and attendees (assignees)
 * - Recurring tasks via RRULE
 * - Due date reminders via VALARM
 *
 * All new properties have defaults for backward compatibility.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.2">RFC 5545 Section 3.6.2 - To-Do Component</a>
 */
data class ICalTodo(
    /** Unique identifier from UID property (required) */
    val uid: String,

    /** Task summary/title from SUMMARY property */
    val summary: String? = null,

    /** Task description from DESCRIPTION property */
    val description: String? = null,

    /** Due date from DUE property */
    val due: ICalDateTime? = null,

    /** Completion percentage (0-100) from PERCENT-COMPLETE property */
    val percentComplete: Int = 0,

    /** Task status from STATUS property */
    val status: TodoStatus = TodoStatus.NEEDS_ACTION,

    /** Priority (0=undefined, 1=highest, 9=lowest) from PRIORITY property */
    val priority: Int = 0,

    // ============ NEW properties (all with defaults) ============

    /**
     * Unique import ID for database storage.
     * Format: "{uid}" or "{uid}:RECID:{datetime}" for modified instances.
     */
    val importId: String = "",

    /** Start date/time from DTSTART property */
    val dtStart: ICalDateTime? = null,

    /** Completion date/time from COMPLETED property (RFC 5545) */
    val completed: ICalDateTime? = null,

    /** Sequence number for conflict detection from SEQUENCE property */
    val sequence: Int = 0,

    /** Creation timestamp from DTSTAMP property */
    val dtstamp: ICalDateTime? = null,

    /** Created timestamp from CREATED property */
    val created: ICalDateTime? = null,

    /** Last modified timestamp from LAST-MODIFIED property */
    val lastModified: ICalDateTime? = null,

    /** Task location from LOCATION property */
    val location: String? = null,

    /** Categories/tags from CATEGORIES property */
    val categories: List<String> = emptyList(),

    /** Task organizer/assigner from ORGANIZER property */
    val organizer: Organizer? = null,

    /** Task attendees/assignees from ATTENDEE properties */
    val attendees: List<Attendee> = emptyList(),

    /** Alarms/reminders from VALARM components */
    val alarms: List<ICalAlarm> = emptyList(),

    /** Recurrence rule from RRULE property (for recurring tasks) */
    val rrule: RRule? = null,

    /**
     * RECURRENCE-ID for modified instances of recurring tasks.
     * Non-null indicates this is a modified occurrence.
     */
    val recurrenceId: ICalDateTime? = null,

    /** URL associated with the task from URL property */
    val url: String? = null,

    /** Geographic position from GEO property */
    val geo: String? = null,

    /** Class/access classification from CLASS property */
    val classification: String? = null,

    /** Preserve unknown properties for round-trip fidelity */
    val rawProperties: Map<String, String> = emptyMap()
) {
    /**
     * Generate importId for a todo.
     */
    companion object {
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }
    }

    /**
     * Check if this task is overdue.
     */
    fun isOverdue(): Boolean {
        if (status == TodoStatus.COMPLETED || status == TodoStatus.CANCELLED) return false
        val dueTime = due ?: return false
        return dueTime.timestamp < System.currentTimeMillis()
    }

    /**
     * Check if this is a recurring task.
     */
    fun isRecurring(): Boolean = rrule != null

    /**
     * Check if this is a modified instance of a recurring task.
     */
    fun isModifiedInstance(): Boolean = recurrenceId != null
}

/**
 * VTODO status values per RFC 5545.
 */
enum class TodoStatus {
    NEEDS_ACTION,
    IN_PROCESS,
    COMPLETED,
    CANCELLED;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): TodoStatus {
            val normalized = value?.uppercase()?.replace("-", "_")
            return entries.find { it.name == normalized } ?: NEEDS_ACTION
        }
    }
}
