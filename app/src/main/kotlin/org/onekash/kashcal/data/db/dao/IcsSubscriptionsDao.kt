package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.IcsSubscription

/**
 * Reads and writes [IcsSubscription] rows, one per calendar. Which way deletion cascades is on
 * [delete].
 */
@Dao
interface IcsSubscriptionsDao {

    // ========== Read Operations ==========

    /** Emits every subscription, ordered by name, again on each change. */
    @Query("SELECT * FROM ics_subscriptions ORDER BY name ASC")
    fun getAll(): Flow<List<IcsSubscription>>

    @Query("SELECT * FROM ics_subscriptions ORDER BY name ASC")
    suspend fun getAllOnce(): List<IcsSubscription>

    @Query("SELECT * FROM ics_subscriptions WHERE id = :id")
    suspend fun getById(id: Long): IcsSubscription?

    /** URL is unique. */
    @Query("SELECT * FROM ics_subscriptions WHERE url = :url")
    suspend fun getByUrl(url: String): IcsSubscription?

    /** Calendar id is unique. */
    @Query("SELECT * FROM ics_subscriptions WHERE calendar_id = :calendarId")
    suspend fun getByCalendarId(calendarId: Long): IcsSubscription?

    @Query("SELECT * FROM ics_subscriptions WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<IcsSubscription>

    @Query("SELECT * FROM ics_subscriptions WHERE last_error IS NOT NULL")
    suspend fun getWithErrors(): List<IcsSubscription>

    @Query("SELECT COUNT(*) FROM ics_subscriptions")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM ics_subscriptions WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    // ========== Write Operations ==========

    /** Inserts a new subscription and returns its id; throws if its URL or calendar is taken. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(subscription: IcsSubscription): Long

    /** Inserts, replacing any row that shares its id, URL or calendar id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: IcsSubscription): Long

    @Update
    suspend fun update(subscription: IcsSubscription)

    /**
     * Deletes only the subscription row; its calendar and events stay. To remove a subscription,
     * delete its calendar, which cascades here ([IcsSubscription]'s FK).
     */
    @Delete
    suspend fun delete(subscription: IcsSubscription)

    /** Same scope as [delete]. */
    @Query("DELETE FROM ics_subscriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ========== Sync Status Updates ==========

    /** Records a successful sync and clears any previous error. */
    @Query("""
        UPDATE ics_subscriptions
        SET last_sync = :timestamp,
            etag = :etag,
            last_modified = :lastModified,
            last_error = NULL
        WHERE id = :id
    """)
    suspend fun updateSyncSuccess(
        id: Long,
        timestamp: Long,
        etag: String?,
        lastModified: String?
    )

    @Query("""
        UPDATE ics_subscriptions
        SET last_error = :error
        WHERE id = :id
    """)
    suspend fun updateSyncError(id: Long, error: String)

    @Query("UPDATE ics_subscriptions SET last_error = NULL WHERE id = :id")
    suspend fun clearError(id: Long)

    // ========== Settings Updates ==========

    @Query("UPDATE ics_subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("""
        UPDATE ics_subscriptions
        SET name = :name,
            color = :color,
            sync_interval_hours = :syncIntervalHours
        WHERE id = :id
    """)
    suspend fun updateSettings(
        id: Long,
        name: String,
        color: Int,
        syncIntervalHours: Int
    )

    @Query("UPDATE ics_subscriptions SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE ics_subscriptions SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    @Query("UPDATE ics_subscriptions SET sync_interval_hours = :hours WHERE id = :id")
    suspend fun updateSyncInterval(id: Long, hours: Int)

    @Query("UPDATE ics_subscriptions SET username = :username WHERE id = :id")
    suspend fun updateUsername(id: Long, username: String?)

    // ========== Utility Queries ==========

    @Query("SELECT EXISTS(SELECT 1 FROM ics_subscriptions WHERE url = :url)")
    suspend fun urlExists(url: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM ics_subscriptions WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
}
