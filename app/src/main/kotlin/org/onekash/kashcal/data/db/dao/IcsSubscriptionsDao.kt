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
 * Data Access Object for ICS subscription operations.
 *
 * Provides CRUD operations and queries for ICS calendar subscriptions.
 * All suspend functions run on IO dispatcher via Room.
 *
 * @see IcsSubscription For entity details
 */
@Dao
interface IcsSubscriptionsDao {

    // ========== Read Operations ==========

    /**
     * Get all subscriptions as reactive Flow.
     * Emits new list when subscriptions change.
     */
    @Query("SELECT * FROM ics_subscriptions ORDER BY name ASC")
    fun getAll(): Flow<List<IcsSubscription>>

    /**
     * Get all subscriptions as one-shot query.
     */
    @Query("SELECT * FROM ics_subscriptions ORDER BY name ASC")
    suspend fun getAllOnce(): List<IcsSubscription>

    /**
     * Get subscription by ID.
     */
    @Query("SELECT * FROM ics_subscriptions WHERE id = :id")
    suspend fun getById(id: Long): IcsSubscription?

    /**
     * Get subscription by URL (unique).
     */
    @Query("SELECT * FROM ics_subscriptions WHERE url = :url")
    suspend fun getByUrl(url: String): IcsSubscription?

    /**
     * Get subscription by calendar ID (unique).
     */
    @Query("SELECT * FROM ics_subscriptions WHERE calendar_id = :calendarId")
    suspend fun getByCalendarId(calendarId: Long): IcsSubscription?

    /**
     * Get all enabled subscriptions.
     */
    @Query("SELECT * FROM ics_subscriptions WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<IcsSubscription>

    /**
     * Get subscriptions with sync errors.
     */
    @Query("SELECT * FROM ics_subscriptions WHERE last_error IS NOT NULL")
    suspend fun getWithErrors(): List<IcsSubscription>

    /**
     * Get total subscription count.
     */
    @Query("SELECT COUNT(*) FROM ics_subscriptions")
    suspend fun getCount(): Int

    /**
     * Get enabled subscription count.
     */
    @Query("SELECT COUNT(*) FROM ics_subscriptions WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    // ========== Write Operations ==========

    /**
     * Insert new subscription. Returns row ID.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(subscription: IcsSubscription): Long

    /**
     * Insert or update subscription (by primary key).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: IcsSubscription): Long

    /**
     * Update existing subscription.
     */
    @Update
    suspend fun update(subscription: IcsSubscription)

    /**
     * Delete subscription.
     * Note: Associated Calendar will cascade delete due to FK.
     * This shouldn't normally be called - delete the Calendar instead.
     */
    @Delete
    suspend fun delete(subscription: IcsSubscription)

    /**
     * Delete subscription by ID.
     */
    @Query("DELETE FROM ics_subscriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ========== Sync Status Updates ==========

    /**
     * Update sync status after successful sync.
     * Clears any previous error.
     */
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

    /**
     * Record sync error.
     */
    @Query("""
        UPDATE ics_subscriptions
        SET last_error = :error
        WHERE id = :id
    """)
    suspend fun updateSyncError(id: Long, error: String)

    /**
     * Clear sync error.
     */
    @Query("UPDATE ics_subscriptions SET last_error = NULL WHERE id = :id")
    suspend fun clearError(id: Long)

    // ========== Settings Updates ==========

    /**
     * Enable or disable subscription.
     */
    @Query("UPDATE ics_subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /**
     * Update subscription settings (name, color, sync interval).
     */
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

    /**
     * Update subscription name.
     */
    @Query("UPDATE ics_subscriptions SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    /**
     * Update subscription color.
     */
    @Query("UPDATE ics_subscriptions SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    /**
     * Update sync interval.
     */
    @Query("UPDATE ics_subscriptions SET sync_interval_hours = :hours WHERE id = :id")
    suspend fun updateSyncInterval(id: Long, hours: Int)

    /**
     * Update authentication username.
     */
    @Query("UPDATE ics_subscriptions SET username = :username WHERE id = :id")
    suspend fun updateUsername(id: Long, username: String?)

    // ========== Utility Queries ==========

    /**
     * Check if URL already exists.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM ics_subscriptions WHERE url = :url)")
    suspend fun urlExists(url: String): Boolean

    /**
     * Check if subscription exists by ID.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM ics_subscriptions WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
}