package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.data.repository.CategoryRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.ui.components.category.colorForTag
import javax.inject.Inject

/**
 * Holds one tag as the management screen shows it: its stored name, its swatch color (the
 * user's custom color, or the name-hash fallback when none is stored), and whether that color
 * was user-chosen.
 */
data class TagUiItem(
    val name: String,
    val color: Int,
    val hasCustomColor: Boolean,
)

/**
 * Holds the tag-management screen's state. Observes the tag metadata table (already
 * name-sorted) and resolves each row's swatch color. Color, delete and undo are local-only
 * metadata edits through [CategoryRepository]. Rename goes through [EventCoordinator] because it
 * must re-upload every affected syncable event so the new tag reaches the server and the user's
 * other devices.
 */
@HiltViewModel
class TagsViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val eventCoordinator: EventCoordinator,
) : ViewModel() {

    val tags: StateFlow<ImmutableList<TagUiItem>> =
        categoryRepository.observeColors()
            .map { colors ->
                colors.entries
                    .map { (name, custom) ->
                        TagUiItem(
                            name = name,
                            color = custom ?: colorForTag(name),
                            hasCustomColor = custom != null,
                        )
                    }
                    .toImmutableList()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    /** Sets a tag's custom color, or clears it when [color] is null. */
    fun onSetColor(name: String, color: Int?) {
        viewModelScope.launch {
            categoryRepository.setColor(name, color, System.currentTimeMillis())
        }
    }

    /**
     * Renames [from] to [to] on every event that carries it and on the metadata row, then
     * re-uploads the affected syncable events ([EventCoordinator.renameTag]).
     */
    fun onRename(from: String, to: String) {
        viewModelScope.launch { eventCoordinator.renameTag(from, to) }
    }

    // The last deleted row, kept for the undo window so a restore brings back its custom color
    // and recency, not a bare row.
    private var lastDeleted: Category? = null

    /**
     * Deletes a tag's metadata row; events keep their labels, painted with the hash fallback.
     * The row is saved first so [onUndoDelete] can restore it as it was.
     */
    fun onDelete(name: String) {
        viewModelScope.launch {
            lastDeleted = categoryRepository.get(name)
            categoryRepository.delete(name)
        }
    }

    /** Restores the row removed by the most recent [onDelete], if any. */
    fun onUndoDelete() {
        val deleted = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch { categoryRepository.restore(deleted) }
    }
}
