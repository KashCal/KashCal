package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one Room event (RFC 5545 VEVENT): a one-off, a recurring master (with [rrule]) or an
 * exception of a master (with [originalEventId]).
 *
 * The sync fields let local edits apply at once and push later. [endTs] is the first
 * occurrence's end, not the series end; time-range queries go through the occurrences table.
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
        Index(value = ["import_id"]),
        Index(value = ["calendar_id", "import_id"]),
        Index(value = ["original_event_id"]),
        Index(value = ["original_event_id", "original_instance_time"]),
        Index(value = ["sync_status"]),
        Index(value = ["calendar_id", "sync_status"]),
        Index(value = ["caldav_url"]),
        Index(value = ["calendar_id", "uid", "original_instance_time"], unique = true)
    ]
)
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ========== Identity ==========

    /**
     * RFC 5545 UID, such as `uuid@domain`. An exception has the same UID as its master
     * (RFC 5545), told apart by [originalInstanceTime].
     */
    @ColumnInfo(name = "uid")
    val uid: String,

    /**
     * Sync lookup key: `{uid}` for a master, `{uid}:RECID:{datetime}` for an exception, which
     * tells apart exceptions sharing a UID.
     */
    @ColumnInfo(name = "import_id")
    val importId: String? = null,

    /** Owning calendar; deleting the calendar cascades to its events. */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    // ========== Content ==========

    /** RFC 5545 SUMMARY. */
    @ColumnInfo(name = "title")
    val title: String,

    /** RFC 5545 LOCATION. */
    @ColumnInfo(name = "location")
    val location: String? = null,

    /** RFC 5545 DESCRIPTION. */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Start as epoch millis; UTC midnight of the first day for an all-day event. */
    @ColumnInfo(name = "start_ts")
    val startTs: Long,

    /**
     * End as epoch millis. For an all-day event it is inclusive: the exclusive RFC 5545 DTEND
     * minus 1 ms, so the event shows on its last day.
     */
    @ColumnInfo(name = "end_ts")
    val endTs: Long,

    /** IANA zone of the start, e.g. "America/New_York"; null for UTC or floating time. */
    @ColumnInfo(name = "timezone")
    val timezone: String? = null,

    /**
     * IANA zone of the end when it differs from the start's, e.g. a flight (#39); null means
     * the same zone as [timezone].
     */
    @ColumnInfo(name = "end_timezone")
    val endTimezone: String? = null,

    /** Whether the event is all-day: DATE instead of DATE-TIME in iCal. */
    @ColumnInfo(name = "is_all_day", defaultValue = "0")
    val isAllDay: Boolean = false,

    /** RFC 5545 STATUS: "TENTATIVE", "CONFIRMED" or "CANCELLED". */
    @ColumnInfo(name = "status", defaultValue = "'CONFIRMED'")
    val status: String = "CONFIRMED",

    // ========== RFC 5545 Round-Trip Fields ==========

    /** RFC 5545 TRANSP: "OPAQUE" (busy) or "TRANSPARENT" (free). */
    @ColumnInfo(name = "transp", defaultValue = "'OPAQUE'")
    val transp: String = "OPAQUE",

    /** RFC 5545 CLASS: "PUBLIC", "PRIVATE" or "CONFIDENTIAL". Round-tripped only. */
    @ColumnInfo(name = "classification", defaultValue = "'PUBLIC'")
    val classification: String = "PUBLIC",

    // ========== Organizer ==========

    /** RFC 5545 ORGANIZER address. */
    @ColumnInfo(name = "organizer_email")
    val organizerEmail: String? = null,

    /** The ORGANIZER's CN parameter. */
    @ColumnInfo(name = "organizer_name")
    val organizerName: String? = null,

    /**
     * The ORGANIZER's SENT-BY parameter (RFC 5545 §3.2.18): who sends on the organizer's
     * behalf, such as an assistant.
     */
    @ColumnInfo(name = "organizer_sent_by")
    val organizerSentBy: String? = null,

    /**
     * The ORGANIZER's SCHEDULE-STATUS parameter (RFC 6638 §7.3): the server-written delivery
     * status code, e.g. "1.2", without its description. The pull keeps only the first code.
     */
    @ColumnInfo(name = "organizer_schedule_status")
    val organizerScheduleStatus: String? = null,

    // ========== Recurrence ==========

    /** RFC 5545 RRULE, e.g. "FREQ=WEEKLY;BYDAY=MO,WE,FR". Only a master has one. */
    @ColumnInfo(name = "rrule")
    val rrule: String? = null,

    /** RFC 5545 RDATE: extra occurrences as comma-separated epoch millis. */
    @ColumnInfo(name = "rdate")
    val rdate: String? = null,

    /** RFC 5545 EXDATE: removed occurrences as comma-separated epoch millis. */
    @ColumnInfo(name = "exdate")
    val exdate: String? = null,

    /** RFC 5545 DURATION, e.g. "PT1H30M", the alternative to an end time. */
    @ColumnInfo(name = "duration")
    val duration: String? = null,

    // ========== Exception Linking ==========

    /** For an exception, its master's id; deleting the master cascades to its exceptions. */
    @ColumnInfo(name = "original_event_id")
    val originalEventId: Long? = null,

    /**
     * For an exception, the start of the occurrence it replaces (its RECURRENCE-ID). Unique
     * together with [calendarId] and [uid].
     */
    @ColumnInfo(name = "original_instance_time")
    val originalInstanceTime: Long? = null,

    /** Set to [uid] on an exception the pull writes, null otherwise. Nothing reads it. */
    @ColumnInfo(name = "original_sync_id")
    val originalSyncId: String? = null,

    // ========== Reminders & Extras ==========

    /**
     * Reminder offsets from the start as a JSON array, e.g. `["-PT15M", "-PT1H"]`. A pull keeps
     * at most 5, the ones closest to DTSTART; [alarmCount] and [rawIcal] cover the rest.
     */
    @ColumnInfo(name = "reminders")
    val reminders: List<String>? = null,

    /**
     * Number of alarms. A pull counts every alarm except END-relative and ACTION:NONE ones,
     * before the cap of 5 on [reminders]; a local save counts the reminders it stores.
     * ReminderScheduler re-reads [rawIcal] when this exceeds 3, and the event form reports the
     * alarms beyond 5.
     */
    @ColumnInfo(name = "alarm_count", defaultValue = "0")
    val alarmCount: Int = 0,

    /**
     * Unknown iCal properties such as X- extensions, as a JSON object, written back on push.
     * The CalDAV pull and ICS subscriptions also keep their own `X-KASHCAL-` markers here.
     */
    @ColumnInfo(name = "extra_properties")
    val extraProperties: Map<String, String>? = null,

    /**
     * The server's ICS for this event. IcsPatcher, for example, patches it so alarms,
     * attendees and other properties without a column survive a push.
     */
    @ColumnInfo(name = "raw_ical")
    val rawIcal: String? = null,

    // ========== iCal Required ==========

    /** RFC 5545 DTSTAMP, required on a VEVENT. */
    @ColumnInfo(name = "dtstamp")
    val dtstamp: Long,

    // ========== Sync Metadata ==========

    /** URL of the event's CalDAV resource, e.g. `https://caldav.icloud.com/.../event.ics`. */
    @ColumnInfo(name = "caldav_url")
    val caldavUrl: String? = null,

    /** The server's ETag, sent as If-Match for optimistic concurrency. */
    @ColumnInfo(name = "etag")
    val etag: String? = null,

    /**
     * RFC 5545 SEQUENCE. [org.onekash.kashcal.domain.scheduling.SequenceBumper] decides which
     * edits bump it; conflict resolution also compares it.
     */
    @ColumnInfo(name = "sequence", defaultValue = "0")
    val sequence: Int = 0,

    /** Whether the event needs pushing, and how. */
    @ColumnInfo(name = "sync_status", defaultValue = "'SYNCED'")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,

    /** The last push error, for diagnostics; no UI shows it. */
    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String? = null,

    /** Push errors since the last success. Nothing reads it. */
    @ColumnInfo(name = "sync_retry_count", defaultValue = "0")
    val syncRetryCount: Int = 0,

    /** Time of the last local edit. */
    @ColumnInfo(name = "local_modified_at")
    val localModifiedAt: Long? = null,

    /**
     * Server-side modification time: a pull stores LAST-MODIFIED, or the pull time without one;
     * a successful push stores the push time.
     */
    @ColumnInfo(name = "server_modified_at")
    val serverModifiedAt: Long? = null,

    // ========== RFC 5545/7986 Extended Properties ==========

    /** RFC 5545 PRIORITY: 0 undefined, 1 highest, 9 lowest. */
    @ColumnInfo(name = "priority", defaultValue = "0")
    val priority: Int = 0,

    /** Latitude from RFC 5545 GEO ("latitude;longitude"), WGS84 decimal degrees. */
    @ColumnInfo(name = "geo_lat")
    val geoLat: Double? = null,

    /** Longitude from RFC 5545 GEO ("latitude;longitude"), WGS84 decimal degrees. */
    @ColumnInfo(name = "geo_lon")
    val geoLon: Double? = null,

    /** RFC 7986 COLOR as ARGB; when set it wins over the calendar color. */
    @ColumnInfo(name = "color")
    val color: Int? = null,

    /** RFC 5545 URL. */
    @ColumnInfo(name = "url")
    val url: String? = null,

    /** RFC 5545 CATEGORIES (the event's tags) as a JSON array; see [Category]. */
    @ColumnInfo(name = "categories")
    val categories: List<String>? = null,

    // ========== Timestamps ==========

    /** Creation time; a pull takes the server's CREATED when present. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Time of the row's last update. Some single-column writes (etag, resource URL, organizer
     * SCHEDULE-STATUS) leave it unchanged.
     */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    // ========== Computed Properties ==========

    /** True when the event has an RRULE. */
    val isRecurring: Boolean
        get() = rrule != null

    /** True for an exception of a recurring master. */
    val isException: Boolean
        get() = originalEventId != null

    /** True when the event isn't SYNCED. */
    val needsSync: Boolean
        get() = syncStatus != SyncStatus.SYNCED

    /** True when the event is soft-deleted, waiting for the server delete. */
    val isPendingDelete: Boolean
        get() = syncStatus == SyncStatus.PENDING_DELETE

    /**
     * Returns true when the event has local changes a pull must not overwrite: local changes
     * win until pushed, and server data overwrites only SYNCED events.
     *
     * Protected states:
     * - PENDING_CREATE: a local event not yet on the server
     * - PENDING_UPDATE: local edits not yet pushed
     * - PENDING_DELETE: a local delete waiting for the server
     *
     * See: https://developer.android.com/topic/architecture/data-layer/offline-first
     */
    fun hasPendingChanges(): Boolean {
        return syncStatus == SyncStatus.PENDING_CREATE ||
               syncStatus == SyncStatus.PENDING_UPDATE ||
               syncStatus == SyncStatus.PENDING_DELETE
    }

    /**
     * Returns this master projected onto one occurrence: start and end moved to that
     * occurrence, keeping the master's duration, and RRULE, EXDATE and RDATE cleared.
     *
     * It seeds the exception a user is about to edit and is the baseline a SEQUENCE-bump
     * decision compares against, so the structural master-to-exception difference (RRULE
     * present or not, the first occurrence's time or this one's) doesn't read as an edit.
     */
    fun projectOntoOccurrence(occurrenceStartTs: Long): Event = copy(
        startTs = occurrenceStartTs,
        endTs = occurrenceStartTs + (endTs - startTs),
        rrule = null,
        exdate = null,
        rdate = null
    )
}
