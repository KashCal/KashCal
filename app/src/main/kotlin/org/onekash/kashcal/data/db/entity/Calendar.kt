package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one calendar of an account, such as "Work" or "Holidays".
 *
 * For iCloud and CalDAV accounts it is one CalDAV calendar collection. Local, ICS-subscription
 * and contact birthday or anniversary calendars keep a `local://` or feed URL in [caldavUrl].
 */
@Entity(
    tableName = "calendars",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["caldav_url"], unique = true),
        Index(value = ["is_visible"])
    ]
)
data class Calendar(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Owning account; deleting the account cascades to its calendars. */
    @ColumnInfo(name = "account_id")
    val accountId: Long,

    /**
     * Collection URL, unique across all accounts, e.g.
     * `https://caldav.icloud.com/123456789/calendars/home/`.
     */
    @ColumnInfo(name = "caldav_url")
    val caldavUrl: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** ARGB color, synced from the server for iCloud and CalDAV calendars. */
    @ColumnInfo(name = "color")
    val color: Int,

    /** CalDAV ctag: changes when any event in the calendar changes, a cheap change check. */
    @ColumnInfo(name = "ctag")
    val ctag: String? = null,

    /** RFC 6578 sync-token for delta sync. */
    @ColumnInfo(name = "sync_token")
    val syncToken: String? = null,

    /** Whether the calendar shows in the UI; hiding keeps its events. */
    @ColumnInfo(name = "is_visible", defaultValue = "1")
    val isVisible: Boolean = true,

    /** Whether this is the default calendar for new events; at most one per account. */
    @ColumnInfo(name = "is_default", defaultValue = "0")
    val isDefault: Boolean = false,

    /** Whether the calendar is read-only, e.g. an ICS subscription. */
    @ColumnInfo(name = "is_read_only", defaultValue = "0")
    val isReadOnly: Boolean = false,

    /** Display order; lower values come first. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * Per-calendar notification mute (#137). Nothing reads it yet: reminders are scheduled
     * whatever its value.
     */
    @ColumnInfo(name = "is_notification_muted", defaultValue = "0")
    val isNotificationMuted: Boolean = false,

    /**
     * A user ARGB color for this calendar (#102). Nothing sets it yet; pending invitations and
     * insights show it over [color] when set. Sync updates [color] and never touches this field.
     */
    @ColumnInfo(name = "local_color_override")
    val localColorOverride: Int? = null,

    /**
     * Default reminder offset for new events in this calendar as an ISO 8601 duration
     * ("-PT15M" is 15 minutes before); null for none. Nothing reads it yet.
     */
    @ColumnInfo(name = "default_reminder")
    val defaultReminder: String? = null,

    /**
     * Whether the collection advertises server-side auto-scheduling (RFC 6638 §2
     * "calendar-auto-schedule" in the DAV header of an OPTIONS reply on the collection).
     *
     * Null when not yet probed or the probe failed; false when probed and not advertised;
     * true when advertised. Nothing reads it; the authoritative delivery signal is read back
     * at runtime.
     */
    @ColumnInfo(name = "auto_schedule_supported")
    val autoScheduleSupported: Boolean? = null,

    /**
     * Whether a pull of this calendar has succeeded once. The first pull lists every event the
     * server has, so its changes stay out of Recent changes; PullStrategy sets this after it.
     * The upgrade to schema 25 sets it only where a sync-token or ctag was stored
     * ([org.onekash.kashcal.data.db.migration.Migrations.MIGRATION_24_25]).
     */
    @ColumnInfo(name = "initial_pull_done", defaultValue = "0")
    val initialPullDone: Boolean = false
)
