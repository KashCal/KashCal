package org.onekash.kashcal.data.preferences

/**
 * Represents the default calendar selection for new events.
 *
 * Supports both Room-backed calendars (local, iCloud, CalDAV) and
 * device calendars (from Android CalendarProvider).
 *
 * Storage format: "room:123" or "device:456"
 * Legacy format: Plain numeric string (e.g., "123") - treated as Room calendar
 */
sealed class DefaultCalendar {

    abstract val calendarId: Long

    /**
     * Room-backed calendar (local, iCloud, CalDAV, ICS).
     */
    data class Room(override val calendarId: Long) : DefaultCalendar()

    /**
     * Device calendar from Android CalendarProvider.
     */
    data class Device(override val calendarId: Long) : DefaultCalendar()

    /**
     * Convert to storage string format.
     */
    fun toStorageString(): String = when (this) {
        is Room -> "$PREFIX_ROOM$calendarId"
        is Device -> "$PREFIX_DEVICE$calendarId"
    }

    companion object {
        private const val PREFIX_ROOM = "room:"
        private const val PREFIX_DEVICE = "device:"

        /**
         * Parse a storage string to DefaultCalendar.
         *
         * @param value Storage string in format "room:123" or "device:456"
         * @return Parsed DefaultCalendar or null if invalid format
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
         * Parse with legacy support for plain numeric strings.
         *
         * New format (preferred): "room:123", "device:456"
         * Legacy format: Plain numeric "123" -> Room(123)
         *
         * @param value Storage string (new or legacy format)
         * @return Parsed DefaultCalendar or null if invalid
         */
        fun parseLegacy(value: String?): DefaultCalendar? {
            if (value.isNullOrBlank()) return null

            // Try new format first
            parse(value)?.let { return it }

            // Fall back to legacy: plain Long -> Room
            val id = value.toLongOrNull()
            return if (id != null && id >= 0) Room(id) else null
        }
    }
}
