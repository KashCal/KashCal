package org.onekash.kashcal.calendarbackup

/** How often automatic backup runs: a whole number of hours from 1 hour to 30 days. */
object CalendarBackupInterval {

    const val MIN_HOURS = 1
    const val MAX_DAYS = 30
    const val MAX_HOURS = MAX_DAYS * 24
    const val DEFAULT_HOURS = 12

    /** The result of checking a days and hours choice. */
    sealed interface Check {
        data class Valid(val hours: Int) : Check
        data object TooShort : Check
        data object TooLong : Check
    }

    /** Checks [days] and [hours] as picked on the wheels; an invalid choice is never adjusted. */
    fun check(days: Int, hours: Int): Check {
        val total = days * 24 + hours
        return when {
            total < MIN_HOURS -> Check.TooShort
            total > MAX_HOURS -> Check.TooLong
            else -> Check.Valid(total)
        }
    }

    /** Returns [stored] when it is a valid interval, else [DEFAULT_HOURS]. */
    fun storedOrDefault(stored: Int?): Int =
        stored?.takeIf { it in MIN_HOURS..MAX_HOURS } ?: DEFAULT_HOURS

    /** Splits [hours] into whole days and the hours left over. */
    fun split(hours: Int): Pair<Int, Int> = hours / 24 to hours % 24
}
