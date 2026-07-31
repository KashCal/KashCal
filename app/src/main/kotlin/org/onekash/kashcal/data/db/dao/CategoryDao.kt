package org.onekash.kashcal.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.entity.Category

/**
 * Access to the `categories` tag-metadata table (color + recency per tag).
 *
 * Rows are seeded/backfilled at migration time, auto-seeded on sync pull, and
 * touched on every save. The primary key is `COLLATE NOCASE`, so all lookups
 * and conflict resolution are case-insensitive while stored casing is kept.
 */
@Dao
interface CategoryDao {

    /**
     * Insert a tag if one with that (case-insensitive) name doesn't already
     * exist; a collision is ignored so an existing row's color and recency are
     * left intact. Used by seed/backfill/auto-seed where the first-seen row
     * must win.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(category: Category): Long

    /** Case-insensitive lookup (the PK is `COLLATE NOCASE`). */
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Category?

    /** All tags, reactive — fuels the management screen and the color map. */
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Category>>

    /**
     * Tag-suggestion names, most-recently-used first with a stable `name ASC`
     * tiebreak (tags saved on the same event share a `last_used_at`, so recency
     * alone would be nondeterministic).
     */
    @Query(
        "SELECT name FROM categories " +
            "ORDER BY last_used_at DESC, name COLLATE NOCASE ASC LIMIT :limit"
    )
    suspend fun suggestions(limit: Int): List<String>

    /** Reactive variant of [suggestions] for the autocomplete flow. */
    @Query(
        "SELECT name FROM categories " +
            "ORDER BY last_used_at DESC, name COLLATE NOCASE ASC LIMIT :limit"
    )
    fun observeSuggestions(limit: Int): Flow<List<String>>

    /** Remove a tag's metadata row; the event strings that reference it stay. */
    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteByName(name: String)

    /**
     * All tags carrying a user-chosen color, for backup. Colorless tags are
     * deliberately excluded — they reappear on their own via sync/usage and
     * their swatch is derived, so backing them up carries nothing recoverable.
     */
    @Query("SELECT * FROM categories WHERE color IS NOT NULL ORDER BY name COLLATE NOCASE ASC")
    suspend fun getColoredOnce(): List<Category>

    @Query("UPDATE categories SET last_used_at = MAX(last_used_at, :lastUsedAt) WHERE name = :name")
    suspend fun raiseLastUsedAt(name: String, lastUsedAt: Long)

    /**
     * Apply a backed-up tag: create the row if absent, then let the backup's
     * custom color win on an existing row while keeping whichever recency is
     * newer (a locally-more-recent use isn't rolled back by an older backup).
     */
    @Transaction
    suspend fun restoreFromBackup(name: String, color: Int?, lastUsedAt: Long) {
        insertIgnore(Category(name = name, color = color, lastUsedAt = lastUsedAt))
        setColorOnly(name, color)
        raiseLastUsedAt(name, lastUsedAt)
    }

    // ---- Internal statements composed by the color-preserving operations ----

    @Query("UPDATE categories SET last_used_at = :now WHERE name = :name")
    suspend fun setLastUsedAt(name: String, now: Long)

    @Query("UPDATE categories SET color = :color WHERE name = :name")
    suspend fun setColorOnly(name: String, color: Int?)

    @Query("UPDATE categories SET name = :to WHERE name = :from")
    suspend fun renameRowInPlace(from: String, to: String)

    /**
     * Record a use of [name] at time [now] without ever disturbing a stored
     * color: insert the tag (color null) only if absent, then bump its
     * recency. Deliberately two statements rather than a whole-row upsert,
     * which would reset a user's chosen color to null.
     */
    @Transaction
    suspend fun touch(name: String, now: Long) {
        insertIgnore(Category(name = name, color = null, lastUsedAt = now))
        setLastUsedAt(name, now)
    }

    /**
     * Seed a tag seen on a pulled event, dating its recency to the event's own
     * [recency] (its last-modified or start time) rather than wall-clock now.
     * Create the row if absent, then only ever *raise* recency — so pulling a
     * batch of old events doesn't rank their tags as "just used" or roll back a
     * newer local use. Never disturbs a stored color.
     */
    @Transaction
    suspend fun seedFromPull(name: String, recency: Long) {
        insertIgnore(Category(name = name, color = null, lastUsedAt = recency))
        raiseLastUsedAt(name, recency)
    }

    /**
     * Set (or clear) a tag's custom color, creating the row if the tag has no
     * metadata yet. A freshly-created row is stamped with [now]; an existing
     * row keeps its `last_used_at` (recoloring is not a use).
     */
    @Transaction
    suspend fun setColor(name: String, color: Int?, now: Long) {
        insertIgnore(Category(name = name, color = color, lastUsedAt = now))
        setColorOnly(name, color)
    }

    // ---- Rename cascade over the event category strings ----
    //
    // These rewrite the denormalized `Event.categories` JSON list (the tag is a
    // list-of-strings on each event, not an FK). The rewrite is a Kotlin
    // read-modify-write per affected event rather than a SQL `REPLACE`: only
    // Kotlin can match a list *element* exactly (so renaming "Work" never
    // touches "Teamwork") and dedup case-insensitively (so an event already
    // carrying the destination doesn't end up with it twice).

    /** Projection of an event's id + raw categories JSON for the rewrite loop. */
    data class EventCategories(
        @ColumnInfo(name = "id") val id: Long,
        @ColumnInfo(name = "categories") val categoriesJson: String?,
    )

    /**
     * Every event that carries at least one tag. The exact, per-element match
     * happens in Kotlin (Unicode-correct case folding); this only skips the
     * untagged rows. A `LIKE '%"Name"%'` prefilter was deliberately avoided:
     * SQLite's `LIKE` folds case for ASCII only, so it would silently miss an
     * event storing the tag in a different non-ASCII casing (e.g. Cyrillic
     * `работа` vs `Работа`) — leaving that event un-renamed. Renames are
     * rare, user-initiated actions, so scanning the tagged rows is a fine trade
     * for correctness across every script.
     */
    @Query("SELECT id, categories FROM events WHERE categories IS NOT NULL AND categories != '' AND categories != '[]'")
    suspend fun eventsCarrying(): List<EventCategories>

    @Query("UPDATE events SET categories = :categoriesJson WHERE id = :id")
    suspend fun setEventCategories(id: Long, categoriesJson: String?)

    /**
     * Rename tag [from] to [to] everywhere: rewrite every carrying event's list
     * (exact element match, case-insensitive dedup so renaming into a name an
     * event already has collapses to one) and move the metadata row. If a
     * *distinct* [to] row already exists this is effectively a merge — its
     * color/recency win and the [from] row is dropped; otherwise the [from] row
     * is renamed in place, keeping its color and recency.
     *
     * Returns the ids of the events whose stored list actually changed, so the
     * domain layer can mark exactly those for sync. An event that carries the
     * tag but whose rebuilt list is byte-identical (e.g. an identity rename) is
     * not reported — there is nothing new to upload.
     */
    @Transaction
    suspend fun renameTag(from: String, to: String): List<Long> {
        val changed = retagEvents(from, to)
        if (to.equals(from, ignoreCase = true)) {
            // Case-only rename ("work" -> "Work"): the NOCASE PK means source and
            // target are the same row, so a delete-then-reinsert would drop the
            // color. Just restamp the stored casing in place.
            renameRowInPlace(from, to)
            return changed
        }
        val existingTarget = getByName(to)
        val source = getByName(from)
        deleteByName(from)
        if (existingTarget == null && source != null) {
            insertIgnore(source.copy(name = to))
        }
        return changed
    }

    /**
     * Replace tag [from] with [to] in every carrying event's category list,
     * matching [from] as a whole list element (case-insensitive) and deduping
     * the result case-insensitively so [to] never appears twice. Returns the
     * ids of the events whose stored JSON actually changed.
     */
    private suspend fun retagEvents(from: String, to: String): List<Long> {
        val changed = mutableListOf<Long>()
        for (row in eventsCarrying()) {
            val current = decodeCategories(row.categoriesJson)
            if (current.none { it.equals(from, ignoreCase = true) }) continue
            val rebuilt = mutableListOf<String>()
            for (tag in current) {
                val replacement = if (tag.equals(from, ignoreCase = true)) to else tag
                if (rebuilt.none { it.equals(replacement, ignoreCase = true) }) {
                    rebuilt.add(replacement)
                }
            }
            val rebuiltJson = Json.encodeToString(rebuilt)
            if (rebuiltJson == row.categoriesJson) continue
            setEventCategories(row.id, rebuiltJson)
            changed.add(row.id)
        }
        return changed
    }

    private fun decodeCategories(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString<List<String>>(value)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
