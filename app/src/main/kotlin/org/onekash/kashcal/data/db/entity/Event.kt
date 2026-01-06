package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Calendar event entity.
 *
 * RFC 5545 compliant - stores both master events and exception instances.
 * Master events have rrule set; exceptions have originalEventId set.
 *
 * Sync metadata supports offline-first architecture with CalDAV sync.
 */
@Entity(
    tableName = "events",
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
            childColumns = ["original_event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["calendar_id"]),
        Index(value = ["start_ts"]),
        Index(value = ["end_ts"]),
        Index(value = ["calendar_id", "start_ts"]),
        Index(value = ["uid"]),
        Index(value = ["original_event_id"]),
        Index(value = ["original_event_id", "original_instance_time"], unique = true),
        Index(value = ["sync_status"]),
        Index(value = ["calendar_id", "sync_status"]),
        Index(value = ["caldav_url"])
    ]
)
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ========== Identity ==========

    /**
     * RFC 5545 UID - globally unique identifier.
     * Format: UUID@domain or similar unique string.
     */
    @ColumnInfo(name = "uid")
    val uid: String,

    /**
     * Parent calendar ID.
     * CASCADE delete: when calendar is deleted, all its events are deleted.
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    // ========== Content ==========

    /**
     * Event title/summary (RFC 5545 SUMMARY).
     */
    @ColumnInfo(name = "title")
    val title: String,

    /**
     * Event location (RFC 5545 LOCATION).
     */
    @ColumnInfo(name = "location")
    val location: String? = null,

    /**
     * Event description (RFC 5545 DESCRIPTION).
     */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /**
     * Start time as epoch milliseconds.
     * For all-day events, represents start of day in event timezone.
     */
    @ColumnInfo(name = "start_ts")
    val startTs: Long,

    /**
     * End time as epoch milliseconds.
     * For all-day events, represents end of last day (inclusive - 1 second
     * subtracted from exclusive DTEND during parsing for correct day display).
     */
    @ColumnInfo(name = "end_ts")
    val endTs: Long,

    /**
     * IANA timezone identifier (e.g., "America/New_York").
     * Null for floating time or UTC.
     */
    @ColumnInfo(name = "timezone")
    val timezone: String? = null,

    /**
     * Whether this is an all-day event.
     * All-day events use DATE instead of DATE-TIME in iCal.
     */
    @ColumnInfo(name = "is_all_day", defaultValue = "0")
    val isAllDay: Boolean = false,

    /**
     * Event status (RFC 5545 STATUS).
     * Values: "TENTATIVE", "CONFIRMED", "CANCELLED"
     */
    @ColumnInfo(name = "status", defaultValue = "'CONFIRMED'")
    val status: String = "CONFIRMED",

    // ========== RFC 5545 Round-Trip Fields ==========

    /**
     * Time transparency (RFC 5545 TRANSP).
     * Values: "OPAQUE" (busy), "TRANSPARENT" (free/available)
     * Used for free/busy calculation.
     */
    @ColumnInfo(name = "transp", defaultValue = "'OPAQUE'")
    val transp: String = "OPAQUE",

    /**
     * Access classification (RFC 5545 CLASS).
     * Values: "PUBLIC", "PRIVATE", "CONFIDENTIAL"
     * Used for privacy settings.
     */
    @ColumnInfo(name = "classification", defaultValue = "'PUBLIC'")
    val classification: String = "PUBLIC",

    // ========== Organizer ==========

    /**
     * Organizer email address (RFC 5545 ORGANIZER).
     */
    @ColumnInfo(name = "organizer_email")
    val organizerEmail: String? = null,

    /**
     * Organizer display name (CN parameter).
     */
    @ColumnInfo(name = "organizer_name")
    val organizerName: String? = null,

    // ========== Recurrence ==========

    /**
     * RFC 5545 RRULE - recurrence rule.
     * Example: "FREQ=WEEKLY;BYDAY=MO,WE,FR"
     * Only set on master events (not exceptions).
     */
    @ColumnInfo(name = "rrule")
    val rrule: String? = null,

    /**
     * RFC 5545 RDATE - additional recurrence dates.
     * Comma-separated ISO timestamps for extra occurrences.
     */
    @ColumnInfo(name = "rdate")
    val rdate: String? = null,

    /**
     * RFC 5545 EXDATE - exception dates.
     * Comma-separated ISO timestamps for cancelled occurrences.
     */
    @ColumnInfo(name = "exdate")
    val exdate: String? = null,

    /**
     * RFC 5545 DURATION - event duration.
     * Format: "PT1H30M" (1 hour 30 minutes)
     * Alternative to specifying end time.
     */
    @ColumnInfo(name = "duration")
    val duration: String? = null,

    // ========== Exception Linking ==========

    /**
     * For exception events: ID of the master recurring event.
     * CASCADE delete: when master is deleted, exceptions are deleted.
     */
    @ColumnInfo(name = "original_event_id")
    val originalEventId: Long? = null,

    /**
     * For exception events: original occurrence time being modified.
     * Combined with originalEventId forms unique constraint.
     */
    @ColumnInfo(name = "original_instance_time")
    val originalInstanceTime: Long? = null,

    /**
     * Server-side UID for sync purposes.
     * May differ from uid during sync operations.
     */
    @ColumnInfo(name = "original_sync_id")
    val originalSyncId: String? = null,

    // ========== Reminders & Extras ==========

    /**
     * JSON array of reminder configurations.
     * Example: ["-PT15M", "-PT1H"] for 15 min and 1 hour before.
     */
    @ColumnInfo(name = "reminders")
    val reminders: List<String>? = null,

    /**
     * JSON object for preserving unknown iCal properties.
     * Stores X-APPLE-*, X-GOOGLE-*, etc. for round-trip fidelity.
     */
    @ColumnInfo(name = "extra_properties")
    val extraProperties: Map<String, String>? = null,

    // ========== iCal Required ==========

    /**
     * RFC 5545 DTSTAMP - when the event was created/modified in iCal.
     * Required by RFC 5545 for VEVENT.
     */
    @ColumnInfo(name = "dtstamp")
    val dtstamp: Long,

    // ========== Sync Metadata ==========

    /**
     * CalDAV URL for this event resource.
     * Example: https://caldav.icloud.com/.../event.ics
     */
    @ColumnInfo(name = "caldav_url")
    val caldavUrl: String? = null,

    /**
     * HTTP ETag from server.
     * Used for optimistic concurrency (If-Match header).
     */
    @ColumnInfo(name = "etag")
    val etag: String? = null,

    /**
     * RFC 5545 SEQUENCE number.
     * Incremented on significant changes for sync conflict detection.
     */
    @ColumnInfo(name = "sequence", defaultValue = "0")
    val sequence: Int = 0,

    /**
     * Current sync status.
     * Determines if event needs to be pushed to server.
     */
    @ColumnInfo(name = "sync_status", defaultValue = "'SYNCED'")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,

    /**
     * Last sync error message for diagnostics.
     */
    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null,

    /**
     * Count of consecutive sync failures.
     * Used for exponential backoff.
     */
    @ColumnInfo(name = "sync_retry_count", defaultValue = "0")
    val syncRetryCount: Int = 0,

    /**
     * Timestamp of last local modification.
     */
    @ColumnInfo(name = "local_modified_at")
    val localModifiedAt: Long? = null,

    /**
     * Server-reported last modification time.
     */
    @ColumnInfo(name = "server_modified_at")
    val serverModifiedAt: Long? = null,

    // ========== Timestamps ==========

    /**
     * Local creation timestamp.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Local update timestamp.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    // ========== Computed Properties ==========

    /**
     * Whether this is a recurring event (has RRULE).
     */
    val isRecurring: Boolean
        get() = rrule != null

    /**
     * Whether this is an exception to a recurring event.
     */
    val isException: Boolean
        get() = originalEventId != null

    /**
     * Whether this event has local changes pending sync.
     */
    val needsSync: Boolean
        get() = syncStatus != SyncStatus.SYNCED

    /**
     * Whether this event is soft-deleted, awaiting server deletion.
     */
    val isPendingDelete: Boolean
        get() = syncStatus == SyncStatus.PENDING_DELETE

    /**
     * Whether this event has pending local changes that should NOT be overwritten
     * by server data during pull sync.
     *
     * LOCAL-FIRST ARCHITECTURE: Local changes take precedence until pushed.
     * Server data only overwrites SYNCED events (no pending local modifications).
     *
     * This protects:
     * - PENDING_CREATE: New local event not yet on server
     * - PENDING_UPDATE: Local modifications not yet pushed
     * - PENDING_DELETE: Local deletion awaiting server confirmation
     *
     * See: https://developer.android.com/topic/architecture/data-layer/offline-first
     */
    fun hasPendingChanges(): Boolean {
        return syncStatus == SyncStatus.PENDING_CREATE ||
               syncStatus == SyncStatus.PENDING_UPDATE ||
               syncStatus == SyncStatus.PENDING_DELETE
    }
}
