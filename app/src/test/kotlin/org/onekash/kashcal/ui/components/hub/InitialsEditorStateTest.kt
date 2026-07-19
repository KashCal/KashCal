package org.onekash.kashcal.ui.components.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InitialsEditorState], the state holder behind the hub's
 * inline initials editor. Extracted from the composable so its enter/type/
 * cancel/save transitions are verifiable off-device.
 */
class InitialsEditorStateTest {

    @Test
    fun `starts not editing`() {
        val state = InitialsEditorState(current = "KC")
        assertFalse(state.isEditing)
    }

    @Test
    fun `start enters edit mode seeded with current value`() {
        val state = InitialsEditorState(current = "KC")
        state.start()
        assertTrue(state.isEditing)
        assertEquals("KC", state.draft)
    }

    @Test
    fun `typing normalizes live and caps at two letters`() {
        val state = InitialsEditorState(current = "")
        state.start()
        state.onType("john")
        assertEquals("JO", state.draft)
        state.onType("a1b2")
        assertEquals("AB", state.draft)
    }

    @Test
    fun `cancel restores prior value and exits edit mode`() {
        val state = InitialsEditorState(current = "KC")
        state.start()
        state.onType("zz")
        assertEquals("ZZ", state.draft)

        state.cancel()
        assertFalse(state.isEditing)
        // Reopening shows the original, unchanged value.
        state.start()
        assertEquals("KC", state.draft)
    }

    @Test
    fun `save returns normalized draft and exits edit mode`() {
        val state = InitialsEditorState(current = "")
        state.start()
        state.onType("Ann")

        val result = state.save()
        assertEquals("AN", result)
        assertFalse(state.isEditing)
    }

    @Test
    fun `save can clear to blank`() {
        val state = InitialsEditorState(current = "KC")
        state.start()
        state.onType("")

        val result = state.save()
        assertEquals("", result)
        assertFalse(state.isEditing)
    }

    @Test
    fun `syncCurrent adopts external value when idle`() {
        val state = InitialsEditorState(current = "KC")
        state.syncCurrent("AB")
        state.start()
        assertEquals("AB", state.draft)
    }

    @Test
    fun `syncCurrent is ignored mid-edit so a draft is not clobbered`() {
        val state = InitialsEditorState(current = "KC")
        state.start()
        state.onType("ZZ")
        // An external re-emit (sync/backup) must not disturb the active draft.
        state.syncCurrent("AB")
        assertEquals("ZZ", state.draft)
        assertTrue(state.isEditing)
    }
}
