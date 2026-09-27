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
 * Reads and writes the `categories` tag-metadata table: a color and a recency per tag.
 *
 * Rows are seeded and backfilled by the v21 to v22 migration, seeded on sync pull
 * ([seedFromPull]) and touched on every save ([touch]). The primary key is `COLLATE NOCASE`, so
 * lookups and conflicts are case-insensitive while the stored casing is kept.
 */
@Dao
interface CategoryDao {

    /**
     * Inserts a tag unless one with that name exists (case-insensitive); an existing row's color
     * and recency stay intact. The first-seen row must win: [touch] and [seedFromPull] rely on it
     * to leave a stored color alone.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(category: Category): Long

    /** Case-insensitive lookup (the PK is `COLLATE NOCASE`). */
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Category?

    /** Emits every tag; feeds the management screen and the tag color map. */
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Category>>

    /**
     * Returns tag-suggestion names, most recently used first. Tags saved on the same event share
     * a `last_used_at`, so the `name` tiebreak keeps the order deterministic.
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

    /** Removes a tag's metadata row; the event strings that reference it stay. */
    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteByName(name: String)

    /**
     * Returns the tags with a user-chosen color, for backup. Colorless tags are left out: they
     * reappear through sync or use and their swatch is derived, so a backup would recover nothing.
     */
    @Query("SELECT * FROM categories WHERE color IS NOT NULL ORDER BY name COLLATE NOCASE ASC")
    suspend fun getColoredOnce(): List<Category>

    @Query("UPDATE categories SET last_used_at = MAX(last_used_at, :lastUsedAt) WHERE name = :name")
    suspend fun raiseLastUsedAt(name: String, lastUsedAt: Long)

    /**
     * Applies a backed-up tag: creates the row if absent, then the backup's color wins on an
     * existing row while the newer recency is kept, so an older backup doesn't roll back a local
     * use.
     */
    @Transaction
    suspend fun restoreFromBackup(name: String, color: Int?, lastUsedAt: Long) {
        insertIgnore(Category(name = name, color = color, lastUsedAt = lastUsedAt))
        setColorOnly(name, color)
        raiseLastUsedAt(name, lastUsedAt)
    }

    // ---- Single statements composed by the color-preserving operations ----

    @Query("UPDATE categories SET last_used_at = :now WHERE name = :name")
    suspend fun setLastUsedAt(name: String, now: Long)

    @Query("UPDATE categories SET color = :color WHERE name = :name")
    suspend fun setColorOnly(name: String, color: Int?)

    @Query("UPDATE categories SET name = :to WHERE name = :from")
    suspend fun renameRowInPlace(from: String, to: String)

    /**
     * Records a use of [name] at [now] without touching a stored color: inserts the tag (color
     * null) only if absent, then sets its recency to [now]. A whole-row upsert would reset a
     * user's chosen color to null.
     */
    @Transaction
    suspend fun touch(name: String, now: Long) {
        insertIgnore(Category(name = name, color = null, lastUsedAt = now))
        setLastUsedAt(name, now)
    }

    /**
     * Seeds a tag seen on a pulled event, dated to the event's [recency] (its last-modified or
     * start time), not wall-clock now. Creates the row if absent, then only raises recency, so
     * pulling old events doesn't rank their tags as just used or roll back a newer local use.
     * Never changes a stored color.
     */
    @Transaction
    suspend fun seedFromPull(name: String, recency: Long) {
        insertIgnore(Category(name = name, color = null, lastUsedAt = recency))
        raiseLastUsedAt(name, recency)
    }

    /**
     * Sets or clears a tag's custom color, creating the row if absent. A new row is stamped with
     * [now]; an existing row keeps its `last_used_at` (recoloring is not a use).
     */
    @Transaction
    suspend fun setColor(name: String, color: Int?, now: Long) {
        insertIgnore(Category(name = name, color = color, lastUsedAt = now))
        setColorOnly(name, color)
    }

    // ---- Rename cascade over the event category strings ----
    //
    // These rewrite the `Event.categories` JSON list of strings (a tag is not an FK). A SQL
    // `REPLACE` can't do it: only Kotlin can match a whole list element (renaming "Work" never
    // touches "Teamwork") and dedup case-insensitively (an event already carrying the
    // destination doesn't end up with it twice).

    /** Projects an event's id and raw categories JSON for the rewrite loop. */
    data class EventCategories(
        @ColumnInfo(name = "id") val id: Long,
        @ColumnInfo(name = "categories") val categoriesJson: String?,
    )

    /**
     * Returns every event with at least one tag. The per-element match happens in Kotlin, with
     * Unicode case folding; this only skips untagged rows. Don't add a `LIKE '%"Name"%'`
     * prefilter: SQLite's `LIKE` folds ASCII case only, so it would silently miss an event
     * storing the tag in another non-ASCII casing (Cyrillic `работа` vs `Работа`) and leave it
     * un-renamed. Renames are rare user actions, so scanning the tagged rows is cheap enough.
     */
    @Query("SELECT id, categories FROM events WHERE categories IS NOT NULL AND categories != '' AND categories != '[]'")
    suspend fun eventsCarrying(): List<EventCategories>

    @Query("UPDATE events SET categories = :categoriesJson WHERE id = :id")
    suspend fun setEventCategories(id: Long, categoriesJson: String?)

    /**
     * Renames tag [from] to [to] everywhere: rewrites every carrying event's list ([retagEvents])
     * and moves the metadata row. If a distinct [to] row exists this is a merge: its color and
     * recency win and the [from] row is dropped. Otherwise the [from] row is re-inserted under
     * [to] with its color and recency; a case-only rename restamps the row in place.
     *
     * Returns the ids of the events whose stored list changed, so the domain layer queues only
     * those for sync. An event whose rebuilt list is byte-identical (an identity rename) is not
     * reported.
     */
    @Transaction
    suspend fun renameTag(from: String, to: String): List<Long> {
        val changed = retagEvents(from, to)
        if (to.equals(from, ignoreCase = true)) {
            // Case-only rename ("work" -> "Work"): the NOCASE PK makes source and target the
            // same row, so a delete then re-insert would drop the color. Restamp the casing.
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
     * Replaces tag [from] with [to] in every carrying event's list, matching [from] as a whole
     * element (case-insensitive) and deduping case-insensitively so [to] never appears twice.
     * Returns the ids of the events whose stored JSON changed.
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
