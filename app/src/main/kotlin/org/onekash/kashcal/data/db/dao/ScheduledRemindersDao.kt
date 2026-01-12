package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder

/**
 * Data Access Object for scheduled reminders.
 *
 * Manages the lifecycle of reminder alarms:
 * - Schedule reminders when events are created/updated
 * - Cancel reminders when events are deleted
 * - Update status on fire/snooze/dismiss
 * - Reschedule after device boot
 */
@Dao
interface ScheduledRemindersDao {

    // ========== Insert Operations ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ScheduledReminder): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ScheduledReminder>)

    // ========== Query Operations ==========

    @Query("SELECT * FROM scheduled_reminders WHERE id = :id")
    suspend fun getById(id: Long): ScheduledReminder?

    /**
     * Get all pending/snoozed reminders for an event.
     * Used when rescheduling after event update.
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE event_id = :eventId
        AND status IN ('PENDING', 'SNOOZED')
    """)
    suspend fun getPendingForEvent(eventId: Long): List<ScheduledReminder>

    /**
     * Get all pending/snoozed reminders after a given time.
     * Used for boot recovery - reschedule all future reminders.
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE status IN ('PENDING', 'SNOOZED')
        AND trigger_time > :afterTime
        ORDER BY trigger_time ASC
    """)
    suspend fun getAllPendingAfter(afterTime: Long): List<ScheduledReminder>

    /**
     * Get reminders in a time range.
     * Used for batch scheduling (e.g., schedule next 7 days).
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE status = 'PENDING'
        AND trigger_time BETWEEN :fromTime AND :toTime
        ORDER BY trigger_time ASC
    """)
    suspend fun getPendingInRange(fromTime: Long, toTime: Long): List<ScheduledReminder>

    /**
     * Check if a reminder already exists for this event/occurrence/offset combo.
     * Prevents duplicate reminders.
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time = :occurrenceTime
        AND reminder_offset = :reminderOffset
        LIMIT 1
    """)
    suspend fun findExisting(
        eventId: Long,
        occurrenceTime: Long,
        reminderOffset: String
    ): ScheduledReminder?

    // ========== Update Operations ==========

    /**
     * Update reminder status.
     * Called after alarm fires or user dismisses.
     */
    @Query("UPDATE scheduled_reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ReminderStatus)

    /**
     * Snooze a reminder: update trigger time, status, and increment count.
     */
    @Query("""
        UPDATE scheduled_reminders
        SET trigger_time = :newTriggerTime,
            status = 'SNOOZED',
            snooze_count = snooze_count + 1
        WHERE id = :id
    """)
    suspend fun snooze(id: Long, newTriggerTime: Long)

    // ========== Delete Operations ==========

    /**
     * Delete all reminders for an event.
     * Called when event is deleted or updated (before rescheduling).
     */
    @Query("DELETE FROM scheduled_reminders WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Delete reminders for a specific occurrence.
     * Called when a single occurrence is deleted from recurring event.
     */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time = :occurrenceTime
    """)
    suspend fun deleteForOccurrence(eventId: Long, occurrenceTime: Long)

    /**
     * Delete reminders for occurrences at or after a certain time.
     * Called when truncating a recurring series (deleteThisAndFuture).
     */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time >= :fromTimeMs
    """)
    suspend fun deleteForOccurrencesAfter(eventId: Long, fromTimeMs: Long)

    /**
     * Delete old reminders (cleanup).
     * Removes fired/dismissed reminders older than specified time.
     */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE trigger_time < :beforeTime
        AND status IN ('FIRED', 'DISMISSED')
    """)
    suspend fun deleteOldReminders(beforeTime: Long)

    // ========== Statistics ==========

    /**
     * Count pending reminders.
     * Useful for debugging and status display.
     */
    @Query("SELECT COUNT(*) FROM scheduled_reminders WHERE status IN ('PENDING', 'SNOOZED')")
    suspend fun getPendingCount(): Int

    /**
     * Count all reminders for an event.
     */
    @Query("SELECT COUNT(*) FROM scheduled_reminders WHERE event_id = :eventId")
    suspend fun getCountForEvent(eventId: Long): Int
}
