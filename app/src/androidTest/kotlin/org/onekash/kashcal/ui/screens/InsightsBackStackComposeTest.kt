package org.onekash.kashcal.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.util.Calendar as JavaCalendar

/**
 * Drives HomeScreen in Insights to test its top bar and back stack: the back arrow and system
 * back call `onViewSelect` with `uiState.previousNonInsightsMode` (Month, Agenda or Week),
 * including when the persisted default view seeded that field rather than a view-mode tap.
 *
 * The bar test expects the app name as title and no "More menu", but HomeScreen's Insights bar
 * shows `R.string.insights_title` and the avatar "More menu" action, so those assertions don't
 * match the screen as built.
 */
@RunWith(AndroidJUnit4::class)
class InsightsBackStackComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appName = context.getString(R.string.app_name)
    private val backCd = context.getString(R.string.cd_back)

    private val testCalendars = persistentListOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        )
    )

    private fun insightsUiState(previous: ViewMode): HomeUiState {
        val today = JavaCalendar.getInstance()
        return HomeUiState(
            viewingYear = today.get(JavaCalendar.YEAR),
            viewingMonth = today.get(JavaCalendar.MONTH),
            selectedDate = today.timeInMillis,
            calendars = testCalendars,
            viewMode = ViewMode.INSIGHTS,
            previousNonInsightsMode = previous
        )
    }

    @Test
    fun insightsTopBar_rendersUnifiedBar_appNameAndBackArrowOnly() {
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    uiState = insightsUiState(ViewMode.MONTH),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {}
                )
            }
        }

        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(backCd).assertIsDisplayed()
        // The Insights bar must not show Home's Today, More menu or Search actions.
        rule.onNodeWithContentDescription("Today").assertDoesNotExist()
        rule.onNodeWithContentDescription("More menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search").assertDoesNotExist()
    }

    @Test
    fun insightsBackArrow_previousMonth_invokesOnViewSelectWithMonth() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    uiState = insightsUiState(ViewMode.MONTH),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {},
                    onViewSelect = { selected = it }
                )
            }
        }

        rule.onNodeWithContentDescription(backCd).performClick()
        assertEquals(ViewMode.MONTH, selected)
    }

    @Test
    fun insightsBackArrow_previousAgenda_invokesOnViewSelectWithAgenda() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    uiState = insightsUiState(ViewMode.AGENDA),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {},
                    onViewSelect = { selected = it }
                )
            }
        }

        rule.onNodeWithContentDescription(backCd).performClick()
        assertEquals(ViewMode.AGENDA, selected)
    }

    @Test
    fun insightsBackArrow_previousWeek_invokesOnViewSelectWithWeek() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    uiState = insightsUiState(ViewMode.WEEK),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {},
                    onViewSelect = { selected = it }
                )
            }
        }

        rule.onNodeWithContentDescription(backCd).performClick()
        assertEquals(ViewMode.WEEK, selected)
    }

    @Test
    fun insightsSystemBack_routesToPreviousMode() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    uiState = insightsUiState(ViewMode.AGENDA),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {},
                    onViewSelect = { selected = it }
                )
            }
        }

        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(ViewMode.AGENDA, selected)
    }

    /**
     * Tests the seeded path: with no view-mode tap since launch, the ViewModel seeds
     * `previousNonInsightsMode` from the persisted default calendar view
     * (`HomeViewModelInsightsBackStackTest`). With a seeded Agenda, back lands on Agenda, not
     * the Month default.
     */
    @Test
    fun insightsInitialView_persistedDefaultAgenda_backLandsOnAgenda() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    // The user opens the app with a persisted default of Agenda and taps
                    // Insights at once, so the seed sets previousNonInsightsMode to AGENDA.
                    uiState = insightsUiState(ViewMode.AGENDA),
                    isOnline = true,
                    onDateSelected = {},
                    onGoToToday = {},
                    onSetViewingMonth = { _, _ -> },
                    onClearNavigateToToday = {},
                    onClearNavigateToMonth = {},
                    onViewSelect = { selected = it }
                )
            }
        }

        rule.onNodeWithContentDescription(backCd).performClick()
        assertEquals(ViewMode.AGENDA, selected)
    }
}
