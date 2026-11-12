package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
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

/** The search field's keyboard Search key closes the keyboard and leaves the query as typed. */
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
}
