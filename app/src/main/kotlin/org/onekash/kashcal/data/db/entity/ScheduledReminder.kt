package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks scheduled reminder alarms.
 *
 * Follows Android CalendarProvider pattern: separate table for alarm instances.
 * - Event.reminders = reminder config (["-PT15M"]) - what to remind
 * - ScheduledReminder = alarm instances with state - when/if reminded
 *
 * For recurring events: one config generates many scheduled instances.
 * Each occurrence gets its own ScheduledReminder with independent status.
 *
 * Denormalizes event data to avoid DB queries in BroadcastReceiver.
 */
@Entity(
    tableName = "scheduled_reminders",
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["trigger_time"]),
        Index(value = ["status"]),
        Index(value = ["event_id", "occurrence_time", "reminder_offset"], unique = true)
    ]
)
data class ScheduledReminder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Parent event ID.
     * CASCADE delete: when event is deleted, its reminders are deleted.
     */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /**
     * For recurring events: the specific occurrence start time being reminded about.
     * For non-recurring: same as event.startTs.
     */
    @ColumnInfo(name = "occurrence_time")
    val occurrenceTime: Long,

    /**
     * When the alarm should trigger.
     * Calculated as: occurrenceTime + reminderOffset (offset is negative).
     */
    @ColumnInfo(name = "trigger_time")
    val triggerTime: Long,

    /**
     * Original reminder offset in ISO 8601 duration format.
     * Examples: "-PT15M" (15 min before), "-PT1H" (1 hour before), "-P1D" (1 day before)
     */
    @ColumnInfo(name = "reminder_offset")
    val reminderOffset: String,

    /**
     * Current status of this reminder.
     * PENDING → FIRED → (SNOOZED → FIRED →)* DISMISSED
     */
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: ReminderStatus = ReminderStatus.PENDING,

    /**
     * Number of times user has snoozed this reminder.
     * Can be used to limit snooze count or show "snoozed X times".
     */
    @ColumnInfo(name = "snooze_count", defaultValue = "0")
    val snoozeCount: Int = 0,

    // ========== Denormalized Event Data ==========
    // Avoids DB query in BroadcastReceiver (runs with limited time)

    /**
     * Event title for notification display.
     */
    @ColumnInfo(name = "event_title")
    val eventTitle: String,

    /**
     * Event location for notification display (optional).
     */
    @ColumnInfo(name = "event_location")
    val eventLocation: String? = null,

    /**
     * Whether the event is all-day (affects notification text).
     */
    @ColumnInfo(name = "is_all_day", defaultValue = "0")
    val isAllDay: Boolean = false,

    /**
     * Calendar color for notification accent.
     */
    @ColumnInfo(name = "calendar_color")
    val calendarColor: Int,

    /**
     * Timestamp when this reminder was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
