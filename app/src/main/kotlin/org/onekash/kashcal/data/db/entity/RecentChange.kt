package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField

/**
 * Records one change a CalDAV or iCloud pull brought in, for the Recent changes sheet.
 *
 * One row per event or changed occurrence in a calendar, keyed by ([calendarId], [eventUid],
 * [instanceTs]). The UID alone isn't enough: a changed occurrence shares its series' UID
 * (RFC 5545 §3.8.4.7), and one UID can live in two calendars. A later change to the same key
 * merges into the row (`RecentChangeMerge`), so each event shows once.
 *
 * Title, times and flags are a snapshot: a deleted event has no row left to read them from.
 * Calendar name and color are read from `calendars` at display time.
 */
@Entity(
    tableName = "recent_changes",
    foreignKeys = [
        ForeignKey(
            entity = Calendar::class,
            parentColumns = ["id"],
            childColumns = ["calendar_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["calendar_id"]),
        Index(value = ["event_id"]),
        Index(value = ["detected_at"]),
        Index(value = ["calendar_id", "event_uid", "instance_ts"], unique = true)
    ]
)
data class RecentChange(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Removing the calendar (or its account) removes its rows. */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    @ColumnInfo(name = "event_uid")
    val eventUid: String,

    /** The changed occurrence's original instance time; 0 for a series or a one-off event. */
    @ColumnInfo(name = "instance_ts")
    val instanceTs: Long,

    /** The local event a tap opens; null for a deleted or cancelled row, or once it is gone. */
    @ColumnInfo(name = "event_id")
    val eventId: Long?,

    /** A cancelled occurrence is DELETED. */
    @ColumnInfo(name = "change_type")
    val changeType: ChangeType,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "start_ts")
    val startTs: Long,

    @ColumnInfo(name = "end_ts")
    val endTs: Long,

    @ColumnInfo(name = "is_all_day")
    val isAllDay: Boolean,

    @ColumnInfo(name = "is_recurring")
    val isRecurring: Boolean,

    /** When the change arrived; drives the day headers and the expiry. */
    @ColumnInfo(name = "detected_at")
    val detectedAt: Long,

    @ColumnInfo(name = "changed_fields", defaultValue = "")
    val changedFields: Set<ChangedField> = emptySet(),

    /** The start the user last knew, set only when the start moved. */
    @ColumnInfo(name = "previous_start_ts")
    val previousStartTs: Long? = null,

    /** The all-day flag that goes with [previousStartTs], to format it across a switch. */
    @ColumnInfo(name = "previous_is_all_day")
    val previousIsAllDay: Boolean? = null,

    /** Set when the user dismissed the row; Clear all's undo clears it again. */
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long? = null
)
