package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.entity.Category
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes tag (category) metadata, per-tag custom colors and recency, for ViewModels,
 * which must not touch [CategoryDao]. Domain code (the event reader and writer, the sync pull,
 * settings backup) uses the DAO directly.
 *
 * A tag's color is a lookup, not a stored property of the event: a null custom color means the
 * caller falls back to the hash-derived `colorForTag(name)`.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
) {

    /**
     * Returns the user's chosen color for [name], or null when the tag has no metadata row or no
     * custom color. Case-insensitive.
     */
    suspend fun colorFor(name: String): Int? = categoryDao.getByName(name)?.color

    /**
     * Emits a map of tag name to custom color (null for none), so tag chips repaint as colors
     * change.
     */
    fun observeColors(): Flow<Map<String, Int?>> =
        categoryDao.observeAll().map { rows -> rows.associate { it.name to it.color } }

    /**
     * Sets or clears a tag's custom color, creating the row if absent. An existing tag keeps its
     * recency: recoloring is not a use.
     */
    suspend fun setColor(name: String, color: Int?, now: Long) =
        categoryDao.setColor(name, color, now)

    /** Emits up to [limit] tag names for suggestions, most recently used first. */
    fun observeSuggestions(limit: Int = SUGGESTION_LIMIT): Flow<List<String>> =
        categoryDao.observeSuggestions(limit)

    /** Returns the metadata row for [name], or null if the tag has none. */
    suspend fun get(name: String): Category? = categoryDao.getByName(name)

    /**
     * Removes a tag's metadata row. Events keep their labels, so their chips fall back to the
     * hash color and the tag drops out of suggestions.
     */
    suspend fun delete(name: String) = categoryDao.deleteByName(name)

    /**
     * Re-inserts a deleted metadata row as it was, with its custom color and recency, to undo a
     * delete. A no-op if a row with that name exists again (for example, a sync pull or an event
     * save re-created it during the undo window).
     */
    suspend fun restore(category: Category) = categoryDao.insertIgnore(category)

    private companion object {
        const val SUGGESTION_LIMIT = 20
    }
}
