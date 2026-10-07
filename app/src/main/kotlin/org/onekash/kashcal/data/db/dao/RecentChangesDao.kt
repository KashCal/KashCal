package org.onekash.kashcal.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.RecentChange

/** Pairs a recent change with its calendar's current name and color. */
data class RecentChangeWithCalendar(
    @Embedded val change: RecentChange,
    @ColumnInfo(name = "calendar_name") val calendarName: String,
    @ColumnInfo(name = "calendar_color") val calendarColor: Int
)

/**
 * Stores the Recent changes log: `RecentChangesRecorder` writes rows, `EventWriter` dismisses and
 * restores them, `EventReader` reads them.
 */
@Dao
interface RecentChangesDao {

    @Query(
        """
        SELECT * FROM recent_changes
        WHERE calendar_id = :calendarId AND event_uid = :eventUid AND instance_ts = :instanceTs
        """
    )
    suspend fun getByKey(calendarId: Long, eventUid: String, instanceTs: Long): RecentChange?

    /** Inserts [change]; throws on a key conflict, which the recorder's merge prevents. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: RecentChange): Long

    @Update
    suspend fun update(change: RecentChange)

    /** Deletes the rows of a series' occurrences (its series row, instance 0, stays). */
    @Query(
        """
        DELETE FROM recent_changes
        WHERE calendar_id = :calendarId AND event_uid = :eventUid AND instance_ts != 0
        """
    )
    suspend fun deleteOccurrencesOf(calendarId: Long, eventUid: String): Int

    /** Deletes rows that arrived before [cutoff]; returns how many. */
    @Query("DELETE FROM recent_changes WHERE detected_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Emits the rows not dismissed that arrived at or after [cutoff], newest first, with the
     * calendar's display name and color (the local override when set).
     */
    @Query(
        """
        SELECT recent_changes.*,
               calendars.display_name AS calendar_name,
               COALESCE(calendars.local_color_override, calendars.color) AS calendar_color
        FROM recent_changes
        JOIN calendars ON calendars.id = recent_changes.calendar_id
        WHERE recent_changes.dismissed_at IS NULL
        AND recent_changes.detected_at >= :cutoff
        ORDER BY recent_changes.detected_at DESC, recent_changes.id DESC
        """
    )
    fun observeVisible(cutoff: Long): Flow<List<RecentChangeWithCalendar>>

    /** Marks [ids] dismissed at [at]; rows already dismissed keep their first time. */
    @Query("UPDATE recent_changes SET dismissed_at = :at WHERE id IN (:ids) AND dismissed_at IS NULL")
    suspend fun dismiss(ids: List<Long>, at: Long): Int

    /** Shows [ids] again. */
    @Query("UPDATE recent_changes SET dismissed_at = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>): Int
}
