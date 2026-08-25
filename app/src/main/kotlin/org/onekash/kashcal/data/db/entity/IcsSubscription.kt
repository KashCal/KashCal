package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ICS calendar subscription entity.
 *
 * Represents a subscription to an external ICS/iCal feed (e.g., public calendar feeds,
 * sports schedules, holidays). Events from subscriptions are stored in the events table
 * linked to the associated Calendar.
 *
 * Architecture:
 * - Each subscription creates a read-only Calendar for visibility management
 * - Calendars belong to a special "ics" provider Account
 * - Events are stored with syncStatus = SYNCED (read-only, no push needed)
 *
 * Follows RFC 5545 for ICS format parsing.
 *
 * @see Calendar For visibility management
 * @see Event For event storage
 */
@Entity(
    tableName = "ics_subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = Calendar::class,
            parentColumns = ["id"],
            childColumns = ["calendar_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["calendar_id"], unique = true),
        Index(value = ["enabled"])
    ]
)
data class IcsSubscription(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * ICS feed URL.
     * Supports https:// and webcal:// schemes.
     * webcal:// is automatically converted to https:// during fetch.
     */
    @ColumnInfo(name = "url")
    val url: String,

    /**
     * Display name for the subscription.
     * Shown in UI calendar list and settings.
     */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Calendar color as ARGB integer.
     * Applied to the associated Calendar for event display.
     */
    @ColumnInfo(name = "color")
    val color: Int,

    /**
     * Associated Calendar ID for visibility management.
     * Events from this subscription are stored with this calendarId.
     * CASCADE delete: when Calendar is deleted, subscription is deleted.
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    /**
     * Unix timestamp (milliseconds) of last successful sync.
     * Used with syncIntervalHours to determine if sync is due.
     */
    @ColumnInfo(name = "last_sync", defaultValue = "0")
    val lastSync: Long = 0,

    /**
     * Sync interval in hours.
     * Default: 24 hours (once per day).
     * Common values: 1, 6, 12, 24, 168 (weekly)
     */
    @ColumnInfo(name = "sync_interval_hours", defaultValue = "24")
    val syncIntervalHours: Int = 24,

    /**
     * Whether subscription is enabled for sync.
     * Disabled subscriptions are not synced but events remain visible.
     */
    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /**
     * HTTP ETag from server.
     * Used for conditional requests (If-None-Match header).
     * Server returns 304 Not Modified if unchanged.
     */
    @ColumnInfo(name = "etag")
    val etag: String? = null,

    /**
     * HTTP Last-Modified header from server.
     * Used for conditional requests (If-Modified-Since header).
     * Alternative to ETag for servers that don't support ETags.
     */
    @ColumnInfo(name = "last_modified")
    val lastModified: String? = null,

    /**
     * Optional basic auth username.
     * Used for private calendar feeds requiring authentication.
     * Password is stored in encrypted preferences (not in database).
     */
    @ColumnInfo(name = "username")
    val username: String? = null,

    /**
     * Last sync error message.
     * Set when sync fails, cleared on success.
     * Used for diagnostics display in settings UI.
     */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    /**
     * Creation timestamp (milliseconds).
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    // ========== Computed Properties ==========

    /**
     * Check if this subscription is due for a sync based on its interval.
     * Returns false if disabled.
     *
     * A tenth of the interval is allowed as slack, and the reason is narrow
     * enough to be worth stating so it isn't "simplified" away. The periodic
     * refresh job wakes at the *shortest* interval across all enabled feeds and
     * checks every feed on each wake, so a feed whose interval is longer than
     * that job period is only ever checked at job-period multiples. Since
     * [lastSync] is stamped part-way through a run, the check that lands at
     * exactly one interval measures a little *less* than the interval, finds the
     * feed not due, and defers it by a whole extra job period: an hourly feed
     * alongside a daily one pushes the daily feed out to 25 hours.
     *
     * The cost is that a feed becomes due at 90% of its interval, so the
     * effective refresh rate is up to about a ninth higher than configured — a
     * daily feed can land at ~22h. Erring slightly fresh is the right direction
     * for a read-only feed, and the bound is proportional, so no interval is
     * treated more loosely than another.
     */
    fun isDueForSync(): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        // Floor the stored value here rather than trusting the column. A 0 would make
        // this unconditionally true and re-fetch the feed on every wake of the shared
        // job; restoring a hand-edited or corrupt backup could put one there, and a
        // row written that way before the importer started flooring it is still on
        // disk. Guarding the reader covers every writer and every existing row.
        val hours = syncIntervalHours.coerceAtLeast(MIN_SYNC_INTERVAL_HOURS)
        val intervalMs = hours.toLong() * 60 * 60 * 1000
        val slackMs = intervalMs / 10
        return now - lastSync >= intervalMs - slackMs
    }

    /**
     * Check if this subscription requires authentication.
     */
    fun requiresAuth(): Boolean = !username.isNullOrBlank()

    /**
     * Get normalized URL (webcal:// → https://).
     */
    fun getNormalizedUrl(): String {
        return url
            .replace("webcal://", "https://")
            .replace("webcals://", "https://")
    }

    /**
     * Check if last sync resulted in an error.
     */
    fun hasError(): Boolean = !lastError.isNullOrBlank()

    companion object {
        /**
         * Shortest interval a feed may be checked at, in hours.
         *
         * Lives here because the invariant belongs to the row, not to whoever
         * writes it: [isDueForSync] enforces it on read, and writers of untrusted
         * values coerce to it so the stored value stays something the settings UI
         * can render.
         */
        const val MIN_SYNC_INTERVAL_HOURS = 1

        /**
         * Default email for ICS subscription account.
         */
        const val ACCOUNT_EMAIL = "subscriptions"

        /**
         * Source prefix for events from ICS subscriptions.
         * Format: "ics_subscription:{subscriptionId}"
         */
        const val SOURCE_PREFIX = "ics_subscription"

        /**
         * The `caldav_url` prefix shared by all events imported from a given
         * subscription. Used by event queries that scope to a subscription
         * (e.g. orphan deletion, empty-state recovery).
         */
        fun eventSourcePrefix(subscriptionId: Long): String =
            "$SOURCE_PREFIX:$subscriptionId:"
    }
}
