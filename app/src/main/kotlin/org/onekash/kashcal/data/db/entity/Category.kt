package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tag's owned metadata: an optional user-chosen color and its last-used time.
 *
 * This table is a loosely-coupled sidecar, NOT the owner of the event↔tag
 * relationship. The events themselves keep the authoritative list of tag names
 * (RFC 5545 CATEGORIES, a JSON array in `events.categories`), which is why the
 * link is by name rather than a foreign key:
 * - `events.categories` is a serialized list in one TEXT column, so no scalar
 *   FK can reference the strings inside it.
 * - Keeping the strings as the source of truth is what lets a deleted tag's
 *   events keep their labels (the chip renders via a hash color when no row
 *   exists) and lets a re-pulled tag reappear on its own.
 *
 * A missing row is therefore a valid state, not corruption.
 *
 * Fields are deliberately minimal — every one has a reader:
 * - [name]: identity and the link to event strings. `COLLATE NOCASE` so `Work`
 *   and `work` are one tag at the table level (matching the case-insensitive
 *   dedup rule) while the stored string keeps its first-seen casing for display.
 * - [color]: the chip color, or `null` to fall back to the name-hash color.
 *   Nullable so an unrecolored tag reflows automatically if the palette changes.
 * - [lastUsedAt]: recency, used to rank tag suggestions.
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
     * The user-chosen chip color (ARGB Int), or `null` when the tag has no
     * custom color and should render via the name-hash fallback. Only the
     * curated seed defaults and explicit user picks store a non-null value.
     */
    @ColumnInfo(name = "color")
    val color: Int? = null,

    /**
     * When this tag was last used (epoch millis). Advanced whenever an event
     * carrying the tag is saved or pulled; drives recency-ranked suggestions.
     */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long,
) {
    companion object {
        /**
         * The curated tags every install starts with, name to ARGB color. Seeded
         * both on a fresh install (the database create callback) and on upgrade
         * (the v21→v22 migration) so a new user and an upgrading user land on the
         * same starter set. `INSERT OR IGNORE` at both sites means a name a user
         * already tagged events with keeps its own row.
         */
        val DEFAULT_SEEDS: List<Pair<String, Int>> = listOf(
            "Work" to 0xFF4457C9.toInt(),      // indigo
            "Personal" to 0xFF2E9F63.toInt(),  // green
            "Family" to 0xFFE04A8E.toInt()     // pink
        )
    }
}
