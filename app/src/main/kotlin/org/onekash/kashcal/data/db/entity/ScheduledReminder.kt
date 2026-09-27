package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one reminder alarm for one occurrence, with its own status.
 *
 * As in Android's CalendarProvider, alarms live apart from the reminder config:
 * Event.reminders (`["-PT15M"]`) says what to remind, and this table says when and whether
 * each alarm fired. A recurring event's one config yields a row per occurrence.
 *
 * The notification's event data is copied in, so the alarm receiver builds the notification
 * from this row; it still checks that the event and occurrence are live before posting.
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

    /** The event. CASCADE delete: deleting the event deletes its reminders. */
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /** Start of the occurrence reminded about; event.startTs for a non-recurring event. */
    @ColumnInfo(name = "occurrence_time")
    val occurrenceTime: Long,

    /**
     * When the alarm fires: occurrenceTime plus the negative [reminderOffset] for a timed
     * event. For an all-day event the offset is applied to local midnight of its day.
     */
    @ColumnInfo(name = "trigger_time")
    val triggerTime: Long,

    /** The offset as an ISO 8601 duration: "-PT15M", "-PT1H", "-P1D". */
    @ColumnInfo(name = "reminder_offset")
    val reminderOffset: String,

    /** PENDING, then FIRED, then any number of SNOOZED and FIRED, then DISMISSED. */
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: ReminderStatus = ReminderStatus.PENDING,

    /** Times the user snoozed this reminder. Nothing reads it today. */
    @ColumnInfo(name = "snooze_count", defaultValue = "0")
    val snoozeCount: Int = 0,

    // ========== Event data copied for the notification ==========
    // The alarm receiver runs with limited time and builds the notification from these

    /** Event title, the notification title. */
    @ColumnInfo(name = "event_title")
    val eventTitle: String,

    /** Event location, shown when set. */
    @ColumnInfo(name = "event_location")
    val eventLocation: String? = null,

    /** Whether the event is all-day; changes the notification text. */
    @ColumnInfo(name = "is_all_day", defaultValue = "0")
    val isAllDay: Boolean = false,

    /** Calendar color, the notification accent. */
    @ColumnInfo(name = "calendar_color")
    val calendarColor: Int,

    /** Creation time, epoch millis. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
