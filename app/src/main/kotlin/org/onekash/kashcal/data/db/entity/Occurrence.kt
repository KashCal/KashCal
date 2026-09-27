package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one materialized occurrence of an event, so range queries need no RRULE expansion.
 *
 * A non-recurring event has a single occurrence matching the event.
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
        // Blocks duplicate occurrences, for example from concurrent syncs
        Index(value = ["event_id", "start_ts"], unique = true)
    ]
)
data class Occurrence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The event, or a series' master. CASCADE delete: deleting it deletes its occurrences. */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /** Copy of the event's calendar ID, so calendar filters need no join. */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    /** Start, epoch millis. */
    @ColumnInfo(name = "start_ts")
    val startTs: Long,

    /** End, epoch millis. */
    @ColumnInfo(name = "end_ts")
    val endTs: Long,

    /** Start day as YYYYMMDD (20241225 for December 25, 2024), for day queries. */
    @ColumnInfo(name = "start_day")
    val startDay: Int,

    /** End day as YYYYMMDD; differs from [startDay] for a multi-day occurrence. */
    @ColumnInfo(name = "end_day")
    val endDay: Int,

    /**
     * Whether the occurrence is cancelled; set when its date joins the master's EXDATE and any
     * exception for it is deleted. Visible-occurrence queries skip cancelled rows. Regeneration
     * keeps the flag on rows still linked to an exception.
     */
    @ColumnInfo(name = "is_cancelled", defaultValue = "0")
    val isCancelled: Boolean = false,

    /**
     * The exception event of a changed occurrence. SET_NULL on delete: once the exception is
     * gone, the occurrence shows the master again.
     */
    @ColumnInfo(name = "exception_event_id")
    val exceptionEventId: Long? = null
) {
    // ========== Multi-Day Event Helpers ==========

    /** Whether the occurrence spans more than one day. */
    val isMultiDay: Boolean
        get() = startDay != endDay

    /** Number of days the occurrence spans; 1 for a single-day occurrence. */
    val totalDays: Int
        get() {
            if (!isMultiDay) return 1
            return calculateDaysBetween(startDay, endDay) + 1
        }

    /**
     * Returns the 1-based day number of [targetDay] within the occurrence, or 0 outside it.
     * For a Dec 25-27 occurrence, 20241225 is 1, 20241227 is 3 and 20241228 is 0.
     */
    fun getDayNumber(targetDay: Int): Int {
        if (targetDay < startDay || targetDay > endDay) return 0
        return calculateDaysBetween(startDay, targetDay) + 1
    }

    companion object {
        /** Returns the number of days from [startDayCode] to [endDayCode] (YYYYMMDD). */
        fun calculateDaysBetween(startDayCode: Int, endDayCode: Int): Int {
            val startCal = dayFormatToCalendar(startDayCode)
            val endCal = dayFormatToCalendar(endDayCode)
            val diffMs = endCal.timeInMillis - startCal.timeInMillis
            return (diffMs / (24 * 60 * 60 * 1000)).toInt()
        }

        /** Returns local midnight of a YYYYMMDD day code as a [java.util.Calendar]. */
        fun dayFormatToCalendar(dayFormat: Int): java.util.Calendar {
            val year = dayFormat / 10000
            val month = (dayFormat % 10000) / 100 - 1  // 0-indexed for Calendar
            val day = dayFormat % 100
            return java.util.Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        }

        /** Returns the YYYYMMDD day code of the day after [dayCode]. */
        fun incrementDayCode(dayCode: Int): Int {
            val cal = dayFormatToCalendar(dayCode)
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return toDayFormat(cal.timeInMillis, false)
        }
        /**
         * Returns the YYYYMMDD day code of [epochMillis].
         *
         * @param isAllDay reads the date in UTC, since all-day events are stored at UTC
         *   midnight: Jan 6 00:00 UTC read in EST would be Jan 5. A timed event is a moment,
         *   so its date is read in the local zone.
         */
        fun toDayFormat(epochMillis: Long, isAllDay: Boolean = false): Int {
            return org.onekash.kashcal.util.DateTimeUtils.eventTsToDayCode(epochMillis, isAllDay)
        }
    }
}
