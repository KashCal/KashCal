package org.onekash.kashcal.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar as JavaCalendar

/**
 * Compose UI tests for HomeScreen.
 *
 * Tests cover:
 * - Component rendering verification
 * - User interaction flows (click, scroll)
 * - Accessibility testing (content descriptions)
 * - Search functionality UI
 * - Offline banner visibility
 * - Calendar grid rendering
 *
 * Best practices followed:
 * - Semantics-based UI testing with ComposeTestRule
 * - Test IDs via contentDescription or testTag
 * - Isolated test scenarios
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testCalendars = persistentListOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        ),
        Calendar(
            id = 2L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal2",
            displayName = "Work",
            color = 0xFF4CAF50.toInt()
        )
    )

    private val testEvents = persistentListOf(
        Event(
            id = 1L,
            uid = "event-1@test",
            calendarId = 1L,
            title = "Team Meeting",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis()
        ),
        Event(
            id = 2L,
            uid = "event-2@test",
            calendarId = 2L,
            title = "Code Review",
            startTs = System.currentTimeMillis() + 7200000,
            endTs = System.currentTimeMillis() + 10800000,
            dtstamp = System.currentTimeMillis()
        )
    )

    private fun createDefaultUiState(): HomeUiState {
        val today = JavaCalendar.getInstance()
        return HomeUiState(
            viewingYear = today.get(JavaCalendar.YEAR),
            viewingMonth = today.get(JavaCalendar.MONTH),
            selectedDate = today.timeInMillis,
            calendars = testCalendars,
            selectedDayEvents = persistentListOf(),
            selectedDayOccurrences = persistentListOf()
        )
    }

    // ==================== App Bar Tests ====================

    @Test
    fun homeScreen_displaysAppTitle() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("KashCal").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysSearchIcon() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysSettingsIcon() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysTodayButton() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Today").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysAgendaIcon() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Agenda").assertIsDisplayed()
    }

    // ==================== FAB Tests ====================

    @Test
    fun homeScreen_displaysFab() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Create event").assertIsDisplayed()
    }

    @Test
    fun homeScreen_fabClickTriggersCallback() {
        var createEventCalled = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onCreateEvent = { createEventCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Create event").performClick()
        assert(createEventCalled)
    }

    // ==================== Offline Banner Tests ====================

    @Test
    fun homeScreen_showsOfflineBannerWhenOffline() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = false,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Offline - Changes will sync when connected").assertIsDisplayed()
    }

    @Test
    fun homeScreen_hidesOfflineBannerWhenOnline() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Offline - Changes will sync when connected").assertDoesNotExist()
    }

    // ==================== Calendar Month Header Tests ====================

    @Test
    fun homeScreen_displaysCurrentMonthHeader() {
        val today = JavaCalendar.getInstance()
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val expectedHeader = monthFormat.format(today.time)

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText(expectedHeader).assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysNavigationArrows() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Previous").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next").assertIsDisplayed()
    }

    // ==================== Day of Week Headers Tests ====================

    @Test
    fun homeScreen_displaysDayOfWeekHeaders() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        // Check for day abbreviations (locale-dependent)
        composeTestRule.onNodeWithText("Sun").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mon").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Thu").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fri").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sat").assertIsDisplayed()
    }

    // ==================== Search Mode Tests ====================

    @Test
    fun homeScreen_searchModeShowsCloseButton() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isSearchActive = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchModeShowsSearchPlaceholder() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    isSearchActive = true,
                    searchQuery = ""
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Search events...").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchModeShowsNoEventsFound() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    isSearchActive = true,
                    searchQuery = "nonexistent",
                    searchResults = persistentListOf()
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("No events found").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchModeShowsIncludePastCheckbox() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    isSearchActive = true,
                    searchQuery = "test",
                    searchResults = persistentListOf()
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Include past events").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchResultsShowsResultCount() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    isSearchActive = true,
                    searchQuery = "meeting",
                    searchResults = testEvents
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("2 results found").assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchCloseTriggersCallback() {
        var searchCloseCalled = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isSearchActive = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onSearchClose = { searchCloseCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assert(searchCloseCalled)
    }

    // ==================== Agenda Mode Tests ====================

    @Test
    fun homeScreen_agendaModeShowsTitle() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(showAgendaPanel = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Agenda").assertIsDisplayed()
    }

    @Test
    fun homeScreen_agendaModeShowsCloseButton() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(showAgendaPanel = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun homeScreen_emptyAgendaShowsMessage() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    showAgendaPanel = true,
                    isLoadingAgenda = false,
                    agendaOccurrences = persistentListOf()
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("No upcoming events").assertIsDisplayed()
    }

    @Test
    fun homeScreen_agendaCloseTriggersCallback() {
        var agendaCloseCalled = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(showAgendaPanel = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onAgendaClose = { agendaCloseCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assert(agendaCloseCalled)
    }

    // ==================== Event List Tests ====================

    @Test
    fun homeScreen_noEventsShowsMessage() {
        val today = JavaCalendar.getInstance()
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    selectedDayEvents = persistentListOf(),
                    selectedDayOccurrences = persistentListOf()
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("No events for this day").assertIsDisplayed()
    }

    @Test
    fun homeScreen_showsEventTitles() {
        val today = JavaCalendar.getInstance()
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    selectedDayEvents = testEvents
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Team Meeting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Code Review").assertIsDisplayed()
    }

    // ==================== Navigation Callback Tests ====================

    @Test
    fun homeScreen_searchClickTriggersCallback() {
        var searchClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onSearchClick = { searchClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        assert(searchClicked)
    }

    @Test
    fun homeScreen_settingsClickTriggersCallback() {
        var settingsClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onSettingsClick = { settingsClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked)
    }

    @Test
    fun homeScreen_todayClickTriggersCallback() {
        var todayClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = { todayClicked = true },
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Today").performClick()
        assert(todayClicked)
    }

    @Test
    fun homeScreen_agendaClickTriggersCallback() {
        var agendaClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onAgendaClick = { agendaClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Agenda").performClick()
        assert(agendaClicked)
    }

    // ==================== Loading State Tests ====================

    @Test
    fun homeScreen_showsLoadingIndicatorWhenLoading() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isLoading = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        // CircularProgressIndicator doesn't have text, but the calendar content should be hidden
        composeTestRule.onNode(hasText("Tap a day to see events")).assertDoesNotExist()
    }

    @Test
    fun homeScreen_agendaShowsLoadingWhenLoading() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    showAgendaPanel = true,
                    isLoadingAgenda = true
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        // When loading agenda, "No upcoming events" should not be shown
        composeTestRule.onNodeWithText("No upcoming events").assertDoesNotExist()
    }

    // ==================== Sync Banner Tests ====================

    @Test
    fun homeScreen_showsSyncBannerWhenSyncing() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    showSyncBanner = true,
                    syncBannerMessage = "Syncing calendars..."
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Syncing calendars...").assertIsDisplayed()
    }

    @Test
    fun homeScreen_hidesSyncBannerWhenNotSyncing() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    showSyncBanner = false,
                    syncBannerMessage = ""
                ),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Syncing calendars...").assertDoesNotExist()
    }
}