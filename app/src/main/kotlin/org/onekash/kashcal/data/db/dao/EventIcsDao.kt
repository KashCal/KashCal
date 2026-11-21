package org.onekash.kashcal.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import org.onekash.kashcal.data.db.entity.EventIcsPart

/**
 * Reads and writes the `event_ics` table. Only [org.onekash.kashcal.data.db.EventIcsStore] calls
 * it, so the pieces never reach other code and every write keeps the rule that one event's
 * pieces run 0..n-1; `EventIcsGuardTest` keeps the table's name out of code other than the
 * entity, the migrations and the debug report's counts.
 */
@Dao
interface EventIcsDao {

    /** Returns every piece of [eventId]'s ICS in order; empty when it has none. */
    @Query("SELECT * FROM event_ics WHERE event_id = :eventId ORDER BY part_index")
    suspend fun parts(eventId: Long): List<EventIcsPart>

    @Insert
    suspend fun insertAll(parts: List<EventIcsPart>)

    @Query("DELETE FROM event_ics WHERE event_id = :eventId")
    suspend fun deleteFor(eventId: Long)

    /**
     * Deletes the pieces of every occurrence of [seriesId] whose `caldav_url` is [resourceUrl]:
     * those occurrences came from the resource the caller just stored on the series, so their own
     * copies are stale. Occurrences without a URL (unsynced imports) keep theirs.
     */
    @Query(
        """
        DELETE FROM event_ics WHERE event_id IN (
            SELECT id FROM events WHERE original_event_id = :seriesId AND caldav_url = :resourceUrl
        )
        """
    )
    suspend fun deleteForOccurrencesAt(seriesId: Long, resourceUrl: String)
}
