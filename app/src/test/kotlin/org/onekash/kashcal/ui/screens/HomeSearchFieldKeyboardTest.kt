package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.SearchResultsState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search field's keyboard: the Search key closes it and leaves the query as typed; a search
 * opened on a tag keeps it down, with the cursor after the tag text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "en-w360dp-h640dp-xxhdpi")
class HomeSearchFieldKeyboardTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the Search key closes the keyboard and keeps the query`() {
        var hides = 0
        val queries = mutableListOf<String>()
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() { hides++ }
        }
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                KashCalTheme {
                    HomeScreen(
                        uiState = HomeUiState(
                            isSearchActive = true,
                            searchQuery = "dentist",
                            searchState = SearchResultsState.Loaded(SearchOutcome()),
                        ),
                        onDateSelected = {},
                        onGoToToday = {},
                        onSetViewingMonth = { _, _ -> },
                        onClearNavigateToToday = {},
                        onClearNavigateToMonth = {},
                        onSearchQueryChange = { queries += it },
                    )
                }
            }
        }

        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()

        assertEquals(1, hides)
        assertEquals(emptyList<String>(), queries)
    }

    private class Keyboard : SoftwareKeyboardController {
        var shows = 0
        var hides = 0
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }

    private fun showSearch(state: androidx.compose.runtime.MutableState<HomeUiState>, keyboard: SoftwareKeyboardController) {
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                KashCalTheme {
                    HomeScreen(
                        uiState = state.value,
                        onDateSelected = {},
                        onGoToToday = {},
                        onSetViewingMonth = { _, _ -> },
                        onClearNavigateToToday = {},
                        onClearNavigateToMonth = {},
                        onSearchQueryChange = { q -> state.value = state.value.copy(searchQuery = q) },
                        hubMakeItYours = {},
                    )
                }
            }
        }
    }

    @Test
    fun `a search opened on a tag leaves the field unfocused, the keyboard down and the cursor at the end`() {
        val keyboard = Keyboard()
        val state = androidx.compose.runtime.mutableStateOf(HomeUiState())
        showSearch(state, keyboard)

        state.value = HomeUiState(
            isSearchActive = true,
            searchQuery = "#\"Team meeting\"",
            searchFocusOnOpen = false,
            searchState = SearchResultsState.Loaded(SearchOutcome()),
        )
        rule.waitForIdle()

        val field = rule.onNode(hasSetTextAction())
        field.assertIsNotFocused()
        assertEquals(0, keyboard.shows)
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange("#\"Team meeting\"".length), selection)
    }

    @Test
    fun `a search opened from the search icon focuses the field`() {
        val state = androidx.compose.runtime.mutableStateOf(HomeUiState())
        showSearch(state, Keyboard())

        state.value = HomeUiState(isSearchActive = true, searchState = SearchResultsState.Idle)
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `typing after a tag search keeps the typed text and its cursor`() {
        val state = androidx.compose.runtime.mutableStateOf(
            HomeUiState(isSearchActive = true, searchQuery = "#Doctor", searchFocusOnOpen = false)
        )
        showSearch(state, Keyboard())

        rule.onNode(hasSetTextAction()).performTextInput(" kids")
        rule.waitForIdle()

        assertEquals("#Doctor kids", state.value.searchQuery)
        val selection = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange("#Doctor kids".length), selection)
    }

    @Test
    fun `a search opened while the account hub is up closes the hub`() {
        val state = androidx.compose.runtime.mutableStateOf(HomeUiState())
        showSearch(state, Keyboard())
        rule.onNode(hasContentDescription("More menu", substring = true)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Go to date").assertIsDisplayed()

        // What returning from the Tags screen's "Show events" does to the state.
        state.value = state.value.copy(
            isSearchActive = true,
            searchQuery = "#Work",
            searchFocusOnOpen = false,
            searchState = SearchResultsState.Loaded(SearchOutcome()),
        )
        rule.waitForIdle()

        rule.onNodeWithText("Go to date").assertDoesNotExist()
    }

    @Test
    fun `a second tag search closes a keyboard the user opened in between`() {
        val keyboard = Keyboard()
        val state = androidx.compose.runtime.mutableStateOf(
            HomeUiState(isSearchActive = true, searchQuery = "#Doctor", searchFocusOnOpen = false, tagSearchOpens = 1)
        )
        showSearch(state, keyboard)
        val before = keyboard.hides

        state.value = state.value.copy(searchQuery = "#Family", tagSearchOpens = 2)
        rule.waitForIdle()

        assertEquals(before + 1, keyboard.hides)
    }
}

