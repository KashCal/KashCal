package org.onekash.icaldav.model

import java.time.Duration

/**
 * Holds a VCALENDAR: its RFC 5545 and RFC 7986 properties, the non-standard X-WR-CALNAME and
 * X-APPLE-CALENDAR-COLOR, and its VEVENT, VTODO and VJOURNAL components.
 *
 * Example:
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
    /** PRODID: the product that created this calendar. */
    val prodId: String?,

    val version: String = "2.0",

    val calscale: String = "GREGORIAN",

    /** METHOD: the iTIP method, for example PUBLISH, REQUEST or REPLY. */
    val method: String? = null,

    /** NAME (RFC 7986); see [effectiveName]. */
    val name: String? = null,

    /** SOURCE (RFC 7986): the URL to refresh the calendar from. */
    val source: String? = null,

    /** COLOR (RFC 7986); see [effectiveColor]. */
    val color: String? = null,

    /** REFRESH-INTERVAL (RFC 7986): the suggested time between subscription refreshes. */
    val refreshInterval: Duration? = null,

    /** X-WR-CALNAME: the non-standard calendar name. */
    val xWrCalname: String? = null,

    /** X-APPLE-CALENDAR-COLOR: the non-standard calendar color. */
    val xAppleCalendarColor: String? = null,

    /** IMAGE (RFC 7986). The generator writes it; `ICalParser.parse` doesn't read it. */
    val image: ICalImage? = null,

    val events: List<ICalEvent> = emptyList(),

    val todos: List<ICalTodo> = emptyList(),

    val journals: List<ICalJournal> = emptyList()
) {
    /** Returns NAME, falling back to X-WR-CALNAME. */
    val effectiveName: String?
        get() = name ?: xWrCalname

    /** Returns COLOR, falling back to X-APPLE-CALENDAR-COLOR. */
    val effectiveColor: String?
        get() = color ?: xAppleCalendarColor

    fun hasEvents(): Boolean = events.isNotEmpty()

    fun hasTodos(): Boolean = todos.isNotEmpty()

    fun hasJournals(): Boolean = journals.isNotEmpty()

    val componentCount: Int
        get() = events.size + todos.size + journals.size

    companion object {
        /** Creates an empty calendar with [prodId] and [name]; the rest take their defaults. */
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
 * Holds a VTODO task (RFC 5545 §3.6.2): its dates, status, assignment through ORGANIZER and
 * ATTENDEE, recurrence and VALARMs. Every property but [uid] has a default.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5545#section-3.6.2">RFC 5545 Section 3.6.2 - To-Do
 *      Component</a>
 */
data class ICalTodo(
    /** UID; the parser assigns a random UUID when it is missing or blank. */
    val uid: String,

    val summary: String? = null,

    val description: String? = null,

    val due: ICalDateTime? = null,

    /** PERCENT-COMPLETE, 0 to 100. */
    val percentComplete: Int = 0,

    /** STATUS; see [TodoStatus.fromString] for missing or unknown values. */
    val status: TodoStatus = TodoStatus.NEEDS_ACTION,

    /** PRIORITY: 0 is undefined, 1 highest, 9 lowest. */
    val priority: Int = 0,

    // ============ Other properties ============

    /** Key of the task or one of its exceptions; the format is on [generateImportId]. */
    val importId: String = "",

    val dtStart: ICalDateTime? = null,

    /** COMPLETED: when the task was completed. */
    val completed: ICalDateTime? = null,

    /** SEQUENCE: the revision number. */
    val sequence: Int = 0,

    val dtstamp: ICalDateTime? = null,

    val created: ICalDateTime? = null,

    val lastModified: ICalDateTime? = null,

    val location: String? = null,

    /** Every CATEGORIES value, split on commas, with blank entries dropped. */
    val categories: List<String> = emptyList(),

    /** ORGANIZER: who assigned the task. */
    val organizer: Organizer? = null,

    /** ATTENDEEs: who the task is assigned to. */
    val attendees: List<Attendee> = emptyList(),

    val alarms: List<ICalAlarm> = emptyList(),

    /** RRULE; the parser reads it only on a master, so an exception has null. */
    val rrule: RRule? = null,

    /** RECURRENCE-ID; non-null marks this as an exception of a recurring task. */
    val recurrenceId: ICalDateTime? = null,

    val url: String? = null,

    /** GEO, as the raw property value. */
    val geo: String? = null,

    /** CLASS, the access classification. */
    val classification: String? = null,

    /** Unknown properties keyed by name and parameters; see [unknownPropertyLines]. */
    val rawProperties: Map<String, String> = emptyMap(),

    /**
     * Unknown properties as the original, unfolded content lines, in document
     * order. When non-empty the generator writes these and ignores
     * [rawProperties].
     */
    val unknownPropertyLines: List<String> = emptyList()
) {
    companion object {
        /** Builds [importId]: the UID, or `uid:RECID:<RECURRENCE-ID>` for an exception. */
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }
    }

    /** Returns true when [due] has passed and the task is neither COMPLETED nor CANCELLED. */
    fun isOverdue(): Boolean {
        if (status == TodoStatus.COMPLETED || status == TodoStatus.CANCELLED) return false
        val dueTime = due ?: return false
        return dueTime.timestamp < System.currentTimeMillis()
    }

    fun isRecurring(): Boolean = rrule != null

    /** Returns true for an exception of a recurring task. */
    fun isModifiedInstance(): Boolean = recurrenceId != null
}

/** VTODO STATUS values (RFC 5545 §3.8.1.11). */
enum class TodoStatus {
    NEEDS_ACTION,
    IN_PROCESS,
    COMPLETED,
    CANCELLED;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        /** Maps [value] ignoring case; null or any unknown value maps to NEEDS_ACTION. */
        fun fromString(value: String?): TodoStatus {
            val normalized = value?.uppercase()?.replace("-", "_")
            return entries.find { it.name == normalized } ?: NEEDS_ACTION
        }
    }
}
