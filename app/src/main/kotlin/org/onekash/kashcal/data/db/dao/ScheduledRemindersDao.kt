package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.ScheduledReminder

/**
 * Stores one row per scheduled reminder, unique on event, occurrence and offset.
 *
 * ReminderScheduler writes a row before arming its alarm and deletes it if the alarm is
 * refused, updates it on fire, snooze and dismiss, deletes rows when their event, occurrences
 * or account go away, and re-arms from them after boot, app update or a timezone change. A
 * pending row may have no alarm: only rows inside the scheduling window are armed.
 */
@Dao
interface ScheduledRemindersDao {

    // ========== Insert Operations ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ScheduledReminder): Long

    /**
     * Inserts unless a row for the same event, occurrence and offset exists; returns the new
     * row id, or -1 if one did.
     *
     * Checking and inserting in one statement means a save and a scan running at once never
     * both write and arm the same reminder.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(reminder: ScheduledReminder): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ScheduledReminder>)

    // ========== Query Operations ==========

    @Query("SELECT * FROM scheduled_reminders WHERE id = :id")
    suspend fun getById(id: Long): ScheduledReminder?

    /**
     * Returns [eventId]'s PENDING and SNOOZED reminders, whose alarms the cancel paths clear
     * before deleting the rows.
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE event_id = :eventId
        AND status IN ('PENDING', 'SNOOZED')
    """)
    suspend fun getPendingForEvent(eventId: Long): List<ScheduledReminder>

    /**
     * Returns every PENDING and SNOOZED reminder triggering after [afterTime], soonest first,
     * for re-arming after boot, app update or a timezone change.
     */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE status IN ('PENDING', 'SNOOZED')
        AND trigger_time > :afterTime
        ORDER BY trigger_time ASC
    """)
    suspend fun getAllPendingAfter(afterTime: Long): List<ScheduledReminder>

    /** Returns the PENDING reminders triggering in [fromTime]..[toTime], soonest first. */
    @Query("""
        SELECT * FROM scheduled_reminders
        WHERE status = 'PENDING'
        AND trigger_time BETWEEN :fromTime AND :toTime
        ORDER BY trigger_time ASC
    """)
    suspend fun getPendingInRange(fromTime: Long, toTime: Long): List<ScheduledReminder>

    /** Returns the row for this event, occurrence and offset in any status, or null. */
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

    /**
     * Returns the ids of the other reminders on the same occurrence as [excludeId].
     *
     * An occurrence with several reminders (say 1 hour and 15 minutes before) has one row per
     * offset, each with a notification keyed to its own id. A firing reminder clears the
     * others' notifications, so the user sees one notification per occurrence.
     *
     * Deliberately not filtered on status. The reminder whose notification is on screen is
     * already FIRED, so PENDING/SNOOZED would skip the only row that matters, and FIRED alone
     * would miss a sibling that posted moments ago and isn't marked yet, which is the case when
     * several offsets come due together after a doze.
     *
     * Some returned rows never posted anything. Cancelling those is normally a no-op, but
     * notification ids fold the row id modulo 10000, so the cancel still clears whatever holds
     * that slot. Rows 10000 apart alias; that stays theoretical only because reminders past
     * their window are pruned, keeping live row ids in a narrow band. The band widens while the
     * app is at the platform's alarm limit, since each refused reminder's row is dropped and
     * later re-inserted under a new id. Widening the id space is the fix if that stops holding.
     */
    @Query("""
        SELECT id FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time = :occurrenceTime
        AND id != :excludeId
    """)
    suspend fun getSiblingIdsForOccurrence(
        eventId: Long,
        occurrenceTime: Long,
        excludeId: Long
    ): List<Long>

    // ========== Update Operations ==========

    /** Sets a reminder's status, when its alarm fires or the user dismisses it. */
    @Query("UPDATE scheduled_reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ReminderStatus)

    /** Snoozes a reminder to [newTriggerTime] and counts the snooze. */
    @Query("""
        UPDATE scheduled_reminders
        SET trigger_time = :newTriggerTime,
            status = 'SNOOZED',
            snooze_count = snooze_count + 1
        WHERE id = :id
    """)
    suspend fun snooze(id: Long, newTriggerTime: Long)

    /**
     * Sets a reminder's trigger time, when an all-day reminder is recalculated for the current
     * timezone.
     */
    @Query("UPDATE scheduled_reminders SET trigger_time = :triggerTime WHERE id = :id")
    suspend fun updateTriggerTime(id: Long, triggerTime: Long)

    // ========== Delete Operations ==========

    /** Deletes a reminder whose alarm couldn't be set, so the refresh scan retries it. */
    @Query("DELETE FROM scheduled_reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Deletes [eventId]'s reminders, for example when the event is deleted or declined, or
     * before its reminders are rescheduled.
     */
    @Query("DELETE FROM scheduled_reminders WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: Long)

    /**
     * Deletes one occurrence's reminders, when it is deleted, edited into an exception, or found
     * cancelled at fire time.
     */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time = :occurrenceTime
    """)
    suspend fun deleteForOccurrence(eventId: Long, occurrenceTime: Long)

    /**
     * Deletes the reminders of occurrences at or after [fromTimeMs], when a this-and-future
     * edit or delete ends the series there.
     */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE event_id = :eventId
        AND occurrence_time >= :fromTimeMs
    """)
    suspend fun deleteForOccurrencesAfter(eventId: Long, fromTimeMs: Long)

    /** Deletes FIRED and DISMISSED reminders that triggered before [beforeTime]. */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE trigger_time < :beforeTime
        AND status IN ('FIRED', 'DISMISSED')
    """)
    suspend fun deleteOldReminders(beforeTime: Long)

    /**
     * Returns the PENDING and SNOOZED reminders of [calendarId]'s events, so their alarms can
     * be cancelled when the account is removed.
     */
    @Query("""
        SELECT sr.* FROM scheduled_reminders sr
        INNER JOIN events e ON sr.event_id = e.id
        WHERE e.calendar_id = :calendarId
        AND sr.status IN ('PENDING', 'SNOOZED')
    """)
    suspend fun getPendingForCalendar(calendarId: Long): List<ScheduledReminder>

    /** Deletes every reminder of [calendarId]'s events, when the account is removed. */
    @Query("""
        DELETE FROM scheduled_reminders
        WHERE event_id IN (SELECT id FROM events WHERE calendar_id = :calendarId)
    """)
    suspend fun deleteForCalendar(calendarId: Long)

    // ========== Statistics ==========

    /** Returns the number of PENDING and SNOOZED reminders. */
    @Query("SELECT COUNT(*) FROM scheduled_reminders WHERE status IN ('PENDING', 'SNOOZED')")
    suspend fun getPendingCount(): Int

    /** Returns the number of [eventId]'s reminders, any status. */
    @Query("SELECT COUNT(*) FROM scheduled_reminders WHERE event_id = :eventId")
    suspend fun getCountForEvent(eventId: Long): Int
}
