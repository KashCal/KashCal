package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [shouldShowTitleSuggestions], which decides whether the title field queries
 * autocomplete suggestions:
 * - the 3-character minimum, at and around the boundary,
 * - edit mode: a pre-filled title must not query until the user changes it,
 * - clearing to empty doesn't query (the length check catches it).
 *
 * The title-suggestions preference isn't checked here: `HomeViewModel.suggestTitles` returns
 * an empty list when it's off.
 */
class TitleSuggestionTriggerTest {

    @Test
    fun `returns false below 3 character minimum`() {
        assertFalse(shouldShowTitleSuggestions(currentText = "", initialText = ""))
        assertFalse(shouldShowTitleSuggestions(currentText = "C", initialText = ""))
        assertFalse(shouldShowTitleSuggestions(currentText = "Co", initialText = ""))
    }

    @Test
    fun `returns true at 3 character boundary for new event`() {
        assertTrue(shouldShowTitleSuggestions(currentText = "Cof", initialText = ""))
    }

    @Test
    fun `returns true when user has typed beyond 3 chars on new event`() {
        assertTrue(shouldShowTitleSuggestions(currentText = "Coffee", initialText = ""))
    }

    @Test
    fun `returns false in edit mode when text matches initial value`() {
        // The sheet loaded an existing event and the user hasn't changed anything yet.
        assertFalse(shouldShowTitleSuggestions(currentText = "Lunch", initialText = "Lunch"))
    }

    @Test
    fun `returns true after user modifies edit-mode title`() {
        assertTrue(shouldShowTitleSuggestions(currentText = "Lunches", initialText = "Lunch"))
    }

    @Test
    fun `clearing text in edit mode does not trigger`() {
        // currentText='' fails the length check before hitting the initial-text guard.
        assertFalse(shouldShowTitleSuggestions(currentText = "", initialText = "Lunch"))
    }
}
