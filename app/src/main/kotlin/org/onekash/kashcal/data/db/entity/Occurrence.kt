package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Materialized occurrence for recurring events.
 *
 * Pre-computed RRULE expansions for O(1) range queries.
 * Each occurrence represents one instance of a recurring event.
 *
 * For non-recurring events, a single occurrence is created matching the event.
 */
@Entity(
    tableName = "occurrences",
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["exception_event_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["start_ts", "end_ts"]),
        Index(value = ["start_day"]),
        Index(value = ["event_id"]),
        Index(value = ["calendar_id", "start_ts"]),
        Index(value = ["exception_event_id"]),
        Index(value = ["is_cancelled"]),
        // Unique constraint to prevent duplicate occurrences (e.g., concurrent syncs)
        Index(value = ["event_id", "start_ts"], unique = true)
    ]
)
data class Occurrence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Parent event ID.
     * CASCADE delete: when event is deleted, all its occurrences are deleted.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * Denormalized calendar ID for query performance.
     * Avoids JOIN when filtering by calendar.
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    /**
     * Occurrence start time as epoch milliseconds.
     */
    @ColumnInfo(name = "start_ts")
    val startTs: Long,

    /**
     * Occurrence end time as epoch milliseconds.
     */
    @ColumnInfo(name = "end_ts")
    val endTs: Long,

    /**
     * Start day in YYYYMMDD format for fast day queries.
     * Example: 20241225 for December 25, 2024.
     */
    @ColumnInfo(name = "start_day")
    val startDay: Int,

    /**
     * End day in YYYYMMDD format.
     * For multi-day events, different from startDay.
     */
    @ColumnInfo(name = "end_day")
    val endDay: Int,

    /**
     * Whether this occurrence is cancelled via EXDATE.
     * Cancelled occurrences are hidden but kept for sync purposes.
     */
    @ColumnInfo(name = "is_cancelled", defaultValue = "0")
    val isCancelled: Boolean = false,

    /**
     * For modified occurrences: ID of the exception event.
     * SET_NULL on delete: if exception is removed, occurrence reverts to master.
     */
    @ColumnInfo(name = "exception_event_id")
    val exceptionEventId: Long? = null
) {
    // ========== Multi-Day Event Helpers ==========

    /**
     * Check if this occurrence spans multiple days.
     */
    val isMultiDay: Boolean
        get() = startDay != endDay

    /**
     * Get total number of days this occurrence spans.
     * Single-day events return 1.
     */
    val totalDays: Int
        get() {
            if (!isMultiDay) return 1
            return calculateDaysBetween(startDay, endDay) + 1
        }

    /**
     * Get which day number (1-based) a given date is within this occurrence.
     * Returns 0 if the target day is outside the occurrence range.
     *
     * Example: 3-day event Dec 25-27
     * - getDayNumber(20241225) → 1
     * - getDayNumber(20241226) → 2
     * - getDayNumber(20241227) → 3
     * - getDayNumber(20241228) → 0 (outside range)
     */
    fun getDayNumber(targetDay: Int): Int {
        if (targetDay < startDay || targetDay > endDay) return 0
        return calculateDaysBetween(startDay, targetDay) + 1
    }

    companion object {
        /**
         * Calculate number of days between two YYYYMMDD day codes.
         */
        fun calculateDaysBetween(startDayCode: Int, endDayCode: Int): Int {
            val startCal = dayFormatToCalendar(startDayCode)
            val endCal = dayFormatToCalendar(endDayCode)
            val diffMs = endCal.timeInMillis - startCal.timeInMillis
            return (diffMs / (24 * 60 * 60 * 1000)).toInt()
        }

        /**
         * Convert YYYYMMDD day format to Calendar instance.
         */
        fun dayFormatToCalendar(dayFormat: Int): java.util.Calendar {
            val year = dayFormat / 10000
            val month = (dayFormat % 10000) / 100 - 1  // 0-indexed for Calendar
            val day = dayFormat % 100
            return java.util.Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        }

        /**
         * Increment a YYYYMMDD day code by one day.
         */
        fun incrementDayCode(dayCode: Int): Int {
            val cal = dayFormatToCalendar(dayCode)
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return toDayFormat(cal.timeInMillis, false)
        }
        /**
         * Convert epoch millis to YYYYMMDD day format.
         *
         * @param epochMillis The timestamp in milliseconds
         * @param isAllDay If true, uses UTC to preserve calendar date (all-day events are
         *                 stored as UTC midnight). If false, uses local timezone to show
         *                 which day the event occurs on from the user's perspective.
         *
         * Why this matters:
         * - All-day events: stored as UTC midnight (e.g., Jan 6 00:00 UTC)
         *   → Using local TZ would shift day: Jan 6 00:00 UTC = Jan 5 19:00 EST = wrong day
         *   → Using UTC preserves the calendar date: Jan 6
         * - Timed events: stored in UTC but represent a specific moment
         *   → Using local TZ shows correct local day (9 AM EST on Jan 6 = Jan 6)
         */
        fun toDayFormat(epochMillis: Long, isAllDay: Boolean = false): Int {
            // Delegate to centralized DateTimeUtils for DRY principle
            return org.onekash.kashcal.util.DateTimeUtils.eventTsToDayCode(epochMillis, isAllDay)
        }
    }
}
