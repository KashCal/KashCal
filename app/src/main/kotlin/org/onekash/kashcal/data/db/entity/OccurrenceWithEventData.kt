package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * Holds one row of an occurrences-events join: the occurrence columns plus the joined [Event].
 *
 * The event is embedded with the `e_` prefix because both tables have `id`, `start_ts` and
 * `end_ts`. Room observes both tables for a query returning this class, so its Flow emits
 * when an event's title or location changes, not only when occurrences change.
 */
data class OccurrenceWithEventData(
    // Occurrence columns, unprefixed
    val id: Long,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "exception_event_id") val exceptionEventId: Long?,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long,
    @ColumnInfo(name = "start_day") val startDay: Int,
    @ColumnInfo(name = "end_day") val endDay: Int,
    @ColumnInfo(name = "is_cancelled") val isCancelled: Boolean,

    // Every Event column in the query must be aliased with the "e_" prefix
    @Embedded(prefix = "e_") val event: Event
) {
    /** Returns the occurrence part as an [Occurrence]. */
    fun toOccurrence() = Occurrence(
        id = id,
        eventId = eventId,
        exceptionEventId = exceptionEventId,
        calendarId = calendarId,
        startTs = startTs,
        endTs = endTs,
        startDay = startDay,
        endDay = endDay,
        isCancelled = isCancelled
    )
}
