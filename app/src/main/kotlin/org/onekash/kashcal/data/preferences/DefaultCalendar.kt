package org.onekash.kashcal.data.preferences

import androidx.compose.runtime.Immutable

/**
 * Names the default calendar for new events: a Room calendar or a device calendar.
 *
 * Stored as "room:123" or "device:456" ([toStorageString], [parse], [parseLegacy]).
 */
@Immutable
sealed class DefaultCalendar {

    abstract val calendarId: Long

    /** Room calendar: local, iCloud, CalDAV or ICS subscription. */
    data class Room(override val calendarId: Long) : DefaultCalendar()

    /** Device calendar from the Android CalendarProvider. */
    data class Device(override val calendarId: Long) : DefaultCalendar()

    /** Returns the stored form, "room:<id>" or "device:<id>". */
    fun toStorageString(): String = when (this) {
        is Room -> "$PREFIX_ROOM$calendarId"
        is Device -> "$PREFIX_DEVICE$calendarId"
    }

    companion object {
        private const val PREFIX_ROOM = "room:"
        private const val PREFIX_DEVICE = "device:"

        /**
         * Parses "room:123" or "device:456"; returns null for anything else, including a
         * negative or non-numeric id.
         */
        fun parse(value: String?): DefaultCalendar? {
            if (value.isNullOrBlank()) return null

            return when {
                value.startsWith(PREFIX_ROOM) -> {
                    val idStr = value.removePrefix(PREFIX_ROOM)
                    val id = idStr.toLongOrNull()
                    if (id != null && id >= 0) Room(id) else null
                }
                value.startsWith(PREFIX_DEVICE) -> {
                    val idStr = value.removePrefix(PREFIX_DEVICE)
                    val id = idStr.toLongOrNull()
                    if (id != null && id >= 0) Device(id) else null
                }
                else -> null
            }
        }

        /**
         * Parses like [parse], and also reads a plain non-negative number ("123") as [Room].
         * Returns null when neither form matches.
         */
        fun parseLegacy(value: String?): DefaultCalendar? {
            if (value.isNullOrBlank()) return null

            parse(value)?.let { return it }

            val id = value.toLongOrNull()
            return if (id != null && id >= 0) Room(id) else null
        }
    }
}
