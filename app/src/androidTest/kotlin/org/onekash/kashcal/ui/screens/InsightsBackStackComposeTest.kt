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
 * Drives HomeScreen through its public surface to verify Insights back-stack
 * behavior: tapping the back arrow (or system back) invokes onViewSelect with
 * uiState.previousNonInsightsMode, including the case where the persisted
 * default seeded that field instead of an explicit prior view-mode tap.
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
        // Insights bar must not show Home's trailing actions
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
     * Initial-view path — Insights is the very first view-mode after process
     * restart or a deep-link tap. The VM seeds `previousNonInsightsMode` from
     * the persisted `defaultCalendarView` (verified in
     * [[HomeViewModelInsightsBackStackTest]]); here we verify the surface
     * honors it. Persisted default = Agenda → back lands on Agenda, not Month.
     */
    @Test
    fun insightsInitialView_persistedDefaultAgenda_backLandsOnAgenda() {
        var selected: ViewMode? = null
        rule.setContent {
            MaterialTheme {
                HomeScreen(
                    // Simulates: user opens app fresh with persisted default=Agenda,
                    // then immediately taps Insights — VM's seed makes
                    // previousNonInsightsMode=AGENDA from the start.
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
