package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Calendar collection entity.
 *
 * Represents a calendar within an account (e.g., "Work", "Personal", "Holidays").
 * Maps to a CalDAV calendar collection.
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
        Index(value = ["caldav_url"], unique = true)
    ]
)
data class Calendar(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Parent account ID.
     * CASCADE delete: when account is deleted, all its calendars are deleted.
     */
    @ColumnInfo(name = "account_id")
    val accountId: Long,

    /**
     * CalDAV URL for this calendar collection.
     * Example: https://caldav.icloud.com/123456789/calendars/home/
     */
    @ColumnInfo(name = "caldav_url")
    val caldavUrl: String,

    /**
     * Display name shown in UI.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /**
     * Calendar color as ARGB integer.
     * Used for event display in UI.
     */
    @ColumnInfo(name = "color")
    val color: Int,

    /**
     * CalDAV ctag (collection tag).
     * Changes when any event in calendar is modified.
     * Used for quick "has anything changed?" check.
     */
    @ColumnInfo(name = "ctag")
    val ctag: String? = null,

    /**
     * RFC 6578 sync-token for incremental sync.
     * More efficient than ctag for large calendars.
     */
    @ColumnInfo(name = "sync_token")
    val syncToken: String? = null,

    /**
     * Whether calendar is visible in UI.
     * User can hide calendars without removing them.
     */
    @ColumnInfo(name = "is_visible", defaultValue = "1")
    val isVisible: Boolean = true,

    /**
     * Whether this is the default calendar for new events.
     * Only one calendar per account should be default.
     */
    @ColumnInfo(name = "is_default", defaultValue = "0")
    val isDefault: Boolean = false,

    /**
     * Whether calendar is read-only (e.g., subscribed calendars).
     */
    @ColumnInfo(name = "is_read_only", defaultValue = "0")
    val isReadOnly: Boolean = false,

    /**
     * Sort order for UI display.
     * Lower values appear first.
     */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0
)
