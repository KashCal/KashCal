package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a tag's metadata: an optional user-chosen color and its last-used time.
 *
 * This table is a sidecar, not the owner of the event-tag relationship. The events keep the
 * authoritative tag names (RFC 5545 CATEGORIES, a JSON array in `events.categories`), so the
 * link is by name, not a foreign key:
 * - `events.categories` is a serialized list in one TEXT column, so no scalar FK can reference
 *   the strings inside it.
 * - With the strings as the source of truth, a deleted tag's events keep their labels (the
 *   chip renders a hash color when no row exists) and a re-pulled tag reappears on its own.
 *
 * A missing row is a valid state, not corruption.
 *
 * Every field has a reader:
 * - [name]: identity and the link to event strings. `COLLATE NOCASE` makes `Work` and `work`
 *   one tag at the table level (matching the case-insensitive dedup rule) while the stored
 *   string keeps its first-seen casing for display.
 * - [color]: the chip color, or `null` to fall back to the name-hash color, so an unrecolored
 *   tag follows a palette change.
 * - [lastUsedAt]: recency, which ranks tag suggestions.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["last_used_at"])]
)
data class Category(
    @PrimaryKey
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    /**
     * The user-chosen ARGB chip color, or `null` to render the name-hash color. Only the
     * [DEFAULT_SEEDS], explicit user picks and a backup restore store a non-null value.
     */
    @ColumnInfo(name = "color")
    val color: Int? = null,

    /**
     * When this tag was last used (epoch millis), which ranks suggestions. Saving an event
     * with the tag sets it to now; a pull only raises it, to the event's own time
     * ([org.onekash.kashcal.data.db.dao.CategoryDao.seedFromPull]).
     */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long,
) {
    companion object {
        /**
         * The curated tags every install starts with, name to ARGB color. Seeded on a fresh
         * install (the database create callback) and on upgrade (the v21 to v22 migration) so
         * new and upgrading users get the same set. Both use `INSERT OR IGNORE`, so a name
         * the user already tagged events with keeps its own row.
         */
        val DEFAULT_SEEDS: List<Pair<String, Int>> = listOf(
            "Work" to 0xFF4457C9.toInt(),      // indigo
            "Personal" to 0xFF2E9F63.toInt(),  // green
            "Family" to 0xFFE04A8E.toInt()     // pink
        )
    }
}
