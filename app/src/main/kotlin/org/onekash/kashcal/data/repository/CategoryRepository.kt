package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.entity.Category
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for tag (category) metadata: per-tag custom colors and
 * recency. Wraps [CategoryDao] so ViewModels, the event writer, and the sync
 * pull path never touch the DAO directly.
 *
 * A tag's color is a *lookup*, not a stored property of the event: [colorFor]
 * returns the user's chosen color or null, and callers fall back to the
 * hash-derived color when it's null (`colorFor(name) ?: colorForTag(name)`).
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
) {

    /**
     * The user's chosen color for [name], or null when the tag has no metadata
     * row or the row carries no custom color. Case-insensitive.
     */
    suspend fun colorFor(name: String): Int? = categoryDao.getByName(name)?.color

    /**
     * Reactive map of tag name to custom color (null value = no custom color),
     * for the color resolver that repaints chips as colors change.
     */
    fun observeColors(): Flow<Map<String, Int?>> =
        categoryDao.observeAll().map { rows -> rows.associate { it.name to it.color } }

    /**
     * Set (or clear) a tag's custom color, creating the row if absent. Recoloring
     * an existing tag leaves its recency untouched (recoloring is not a use).
     */
    suspend fun setColor(name: String, color: Int?, now: Long) =
        categoryDao.setColor(name, color, now)

    /** Recency-ranked tag suggestions (most recent first), capped at [limit]. */
    fun observeSuggestions(limit: Int = SUGGESTION_LIMIT): Flow<List<String>> =
        categoryDao.observeSuggestions(limit)

    /** The full metadata row for [name], or null if the tag has none. */
    suspend fun get(name: String): Category? = categoryDao.getByName(name)

    /**
     * Remove a tag's metadata row. Events keep their labels, so their chips fall
     * back to the hash color and the tag drops out of suggestions.
     */
    suspend fun delete(name: String) = categoryDao.deleteByName(name)

    /**
     * Re-insert a previously-deleted metadata row verbatim (restoring its custom
     * color and recency). Used to undo a delete; a no-op if a row with that name
     * already exists again (e.g. a sync pull re-seeded it during the undo window).
     */
    suspend fun restore(category: Category) = categoryDao.insertIgnore(category)

    private companion object {
        const val SUGGESTION_LIMIT = 20
    }
}
