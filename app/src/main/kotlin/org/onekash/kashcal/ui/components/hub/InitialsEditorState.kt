package org.onekash.kashcal.ui.components.hub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * Holds the state of the hub's inline initials editor.
 *
 * Transitions:
 * - [start] enters edit mode seeded with [current].
 * - [onType] applies [normalizeInitials] live, so the draft only ever holds up to two
 *   uppercased letters.
 * - [cancel] discards the draft and exits without changing [current].
 * - [save] exits and returns the normalized value for the caller to persist.
 * - [syncCurrent] adopts the persisted value only when not editing, so an external re-emit
 *   can't clobber an in-progress draft.
 *
 * Snapshot state drives recomposition, and [Saver] keeps an in-progress edit across
 * configuration changes (HomeScreen's `showHub` flag is saveable too). There are no framework
 * entry points, so the transitions are unit tested directly.
 */
class InitialsEditorState(
    current: String,
    isEditing: Boolean = false,
    draft: String = "",
) {

    var isEditing: Boolean by mutableStateOf(isEditing)
        private set

    /** The committed value; [start] seeds the draft from it and [cancel] leaves it unchanged. */
    var current: String by mutableStateOf(current)
        private set

    /** The normalized draft shown in the text field while editing. */
    var draft: String by mutableStateOf(draft)
        private set

    fun start() {
        draft = current
        isEditing = true
    }

    fun onType(raw: String) {
        draft = normalizeInitials(raw)
    }

    fun cancel() {
        isEditing = false
        draft = ""
    }

    /** Commits the draft: updates [current], exits edit mode, and returns the value to persist. */
    fun save(): String {
        val value = normalizeInitials(draft)
        current = value
        isEditing = false
        draft = ""
        return value
    }

    /** Adopts a persisted value change while idle; ignored mid-edit to protect the draft. */
    fun syncCurrent(value: String) {
        if (!isEditing) current = value
    }

    companion object {
        val Saver: Saver<InitialsEditorState, *> = listSaver(
            save = { listOf(it.current, it.isEditing.toString(), it.draft) },
            restore = {
                InitialsEditorState(
                    current = it[0],
                    isEditing = it[1].toBoolean(),
                    draft = it[2],
                )
            },
        )
    }
}
