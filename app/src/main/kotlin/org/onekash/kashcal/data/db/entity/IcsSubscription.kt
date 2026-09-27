package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a subscription to an external ICS feed (public calendars, sports schedules, holidays).
 *
 * - Each subscription has its own read-only [Calendar], which carries its visibility.
 * - Those calendars belong to the one ICS provider [Account] ([ACCOUNT_EMAIL]).
 * - Its events live in the events table under that calendar with syncStatus SYNCED; nothing
 *   is pushed.
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
     * Feed URL: https://, webcal:// or webcals://. [getNormalizedUrl] turns both webcal schemes
     * into https:// for the fetch.
     */
    @ColumnInfo(name = "url")
    val url: String,

    /** Display name, shown in the calendar list and settings. */
    @ColumnInfo(name = "name")
    val name: String,

    /** ARGB color, applied to the subscription's calendar. */
    @ColumnInfo(name = "color")
    val color: Int,

    /**
     * The subscription's calendar; its events are stored under this calendarId. CASCADE delete:
     * deleting the calendar deletes the subscription.
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    /** Epoch millis of the last successful sync; [isDueForSync] measures from it. */
    @ColumnInfo(name = "last_sync", defaultValue = "0")
    val lastSync: Long = 0,

    /** Sync interval in hours. The settings picker offers 1, 6, 12, 24 and 168 (weekly). */
    @ColumnInfo(name = "sync_interval_hours", defaultValue = "24")
    val syncIntervalHours: Int = 24,

    /** Whether the feed is synced. A disabled feed keeps its events visible. */
    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /** Server ETag, sent as If-None-Match; an unchanged feed answers 304 Not Modified. */
    @ColumnInfo(name = "etag")
    val etag: String? = null,

    /** Server Last-Modified, sent as If-Modified-Since; covers servers that send no ETag. */
    @ColumnInfo(name = "last_modified")
    val lastModified: String? = null,

    /**
     * Optional basic-auth username. It is stored, backed up and restored, but the fetcher sends
     * no credentials and no password is stored anywhere.
     */
    @ColumnInfo(name = "username")
    val username: String? = null,

    /** Last sync error, set on failure and cleared on success; shown in settings. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    /** Creation time, epoch millis. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    // ========== Computed Properties ==========

    /**
     * Returns true when the feed is enabled and its interval, less a tenth, has passed since
     * [lastSync].
     *
     * Keep the slack. The periodic refresh job wakes at the shortest interval across enabled
     * feeds and checks every feed, so a longer-interval feed is checked only at job-period
     * multiples. [lastSync] is stamped part-way through a run, so the check landing at exactly
     * one interval measures a little less, finds the feed not due, and defers it a whole job
     * period: an hourly feed alongside a daily one pushes the daily feed to 25 hours.
     *
     * The cost: a feed is due at 90% of its interval, so a daily feed can refresh at about 22
     * hours. Erring fresh suits a read-only feed, and the slack is proportional to every
     * interval.
     */
    fun isDueForSync(): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        // Floor the stored value on read: a 0 would make every wake of the shared job
        // re-fetch the feed. A hand-edited or corrupt backup can restore one, and rows the
        // importer wrote before it floored values are still on disk; guarding the reader
        // covers every writer and every existing row.
        val hours = syncIntervalHours.coerceAtLeast(MIN_SYNC_INTERVAL_HOURS)
        val intervalMs = hours.toLong() * 60 * 60 * 1000
        val slackMs = intervalMs / 10
        return now - lastSync >= intervalMs - slackMs
    }

    /** Returns true when a [username] is set. */
    fun requiresAuth(): Boolean = !username.isNullOrBlank()

    /** Returns [url] with webcal:// and webcals:// replaced by https://. */
    fun getNormalizedUrl(): String {
        return url
            .replace("webcal://", "https://")
            .replace("webcals://", "https://")
    }

    /** Returns true when the last sync left an error. */
    fun hasError(): Boolean = !lastError.isNullOrBlank()

    companion object {
        /**
         * Shortest interval a feed may be checked at, in hours.
         *
         * The invariant belongs to the row, not to its writers: [isDueForSync] enforces it on
         * read, and writers of untrusted values coerce to it so the stored value stays one the
         * settings UI can render.
         */
        const val MIN_SYNC_INTERVAL_HOURS = 1

        /** Email of the one ICS subscription account. */
        const val ACCOUNT_EMAIL = "subscriptions"

        /**
         * First segment of a subscription event's `caldav_url`, which is
         * `ics_subscription:{subscriptionId}:{importId}`; [eventSourcePrefix] builds the prefix.
         */
        const val SOURCE_PREFIX = "ics_subscription"

        /**
         * Returns the `caldav_url` prefix shared by every event imported from a subscription.
         * Event queries scoped to one subscription match on it, for example the orphan sweep
         * and the zero-events check that drops stale conditional headers.
         */
        fun eventSourcePrefix(subscriptionId: Long): String =
            "$SOURCE_PREFIX:$subscriptionId:"
    }
}
