package org.onekash.kashcal.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.util.Calendar as JavaCalendar

/**
 * Runs the Accessibility Test Framework (the same engine behind Accessibility
 * Scanner) against rendered HomeScreen states. Catches low color contrast,
 * small touch targets, missing labels, and traversal-order problems.
 *
 * A failing check throws with a description of the offending node, so adding
 * screens/states here widens automated a11y coverage without hand-writing
 * per-property assertions.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenAccessibilityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val testCalendars = persistentListOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt(),
        ),
    )

    private fun defaultUiState(viewMode: ViewMode = ViewMode.MONTH): HomeUiState {
        val today = JavaCalendar.getInstance()
        return HomeUiState(
            viewingYear = today.get(JavaCalendar.YEAR),
            viewingMonth = today.get(JavaCalendar.MONTH),
            selectedDate = today.timeInMillis,
            calendars = testCalendars,
            viewMode = viewMode,
        )
    }

    private fun renderAndCheck(uiState: HomeUiState) {
        composeTestRule.setContent {
            HomeScreen(
                uiState = uiState,
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
            )
        }
        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun monthView_passesAccessibilityChecks() {
        renderAndCheck(defaultUiState())
    }

    @Test
    fun agendaView_passesAccessibilityChecks() {
        renderAndCheck(defaultUiState(ViewMode.AGENDA))
    }

    @Test
    fun searchMode_passesAccessibilityChecks() {
        renderAndCheck(defaultUiState().copy(isSearchActive = true, searchQuery = ""))
    }
}
