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
 * One tag as shown on the management screen: its stored name, the color to paint
 * its swatch (a resolved value — the user's custom color, or the name-hash
 * fallback when none is stored), and whether that color was user-chosen.
 */
data class TagUiItem(
    val name: String,
    val color: Int,
    val hasCustomColor: Boolean,
)

/**
 * State holder for the tag-management screen. Observes the tag metadata table
 * (already name-sorted) and resolves each row's swatch color. Color, delete, and
 * undo are local-only metadata edits that go to [CategoryRepository]; rename goes
 * through [EventCoordinator] because it must re-upload every affected syncable
 * event so the new tag reaches the server and the user's other devices.
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

    /** Set (or clear) a tag's custom color. */
    fun onSetColor(name: String, color: Int?) {
        viewModelScope.launch {
            categoryRepository.setColor(name, color, System.currentTimeMillis())
        }
    }

    /**
     * Rename [from] to [to] across every carrying event and the metadata row,
     * then re-upload the affected syncable events so the rename reaches the
     * server. Goes through the coordinator (not the repository) for the sync.
     */
    fun onRename(from: String, to: String) {
        viewModelScope.launch { eventCoordinator.renameTag(from, to) }
    }

    // Snapshot of the last deleted row, held only for the undo window so a restore
    // brings back the exact custom color and recency rather than a bare row.
    private var lastDeleted: Category? = null

    /**
     * Drop a tag's metadata row; events keep their labels via the hash fallback.
     * The row is snapshotted first so [onUndoDelete] can restore it verbatim.
     */
    fun onDelete(name: String) {
        viewModelScope.launch {
            lastDeleted = categoryRepository.get(name)
            categoryRepository.delete(name)
        }
    }

    /** Restore the row removed by the most recent [onDelete], if any. */
    fun onUndoDelete() {
        val deleted = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch { categoryRepository.restore(deleted) }
    }
}
