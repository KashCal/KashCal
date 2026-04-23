package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shouldShowTitleSuggestions], the pure predicate that decides
 * whether the autocomplete dropdown should trigger.
 *
 * Covers:
 * - 3-character minimum (boundary)
 * - Edit mode: pre-filled title must not trigger until user changes it
 * - Clearing to empty hides dropdown (handled by the threshold)
 *
 * The feature-enabled preference is NOT checked here — it's enforced upstream
 * by the ViewModel returning an empty suggestion list when disabled.
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
        // Sheet loaded an existing event — user hasn't changed anything yet.
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
