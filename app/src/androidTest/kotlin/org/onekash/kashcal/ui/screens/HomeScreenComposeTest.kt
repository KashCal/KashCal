package org.onekash.kashcal.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
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

    private val testSearchResults = persistentListOf(
        EventWithNextOccurrence(
            event = testEvents[0],
            nextOccurrenceTs = System.currentTimeMillis()
        ),
        EventWithNextOccurrence(
            event = testEvents[1],
            nextOccurrenceTs = System.currentTimeMillis() + 7200000
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
    fun homeScreen_displaysViewPickerIcon() {
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

        composeTestRule.onNodeWithContentDescription("Calendar view").assertIsDisplayed()
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

        // Check for day abbreviations - use locale-aware day names like the production code
        val daysOfWeek = java.time.DayOfWeek.values().map {
            it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        }
        daysOfWeek.forEach { dayName ->
            composeTestRule.onNodeWithText(dayName).assertIsDisplayed()
        }
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
    fun homeScreen_searchModeShowsDateFilterChips() {
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

        // Search UI shows date filter chips: All, Week, Month, Date
        composeTestRule.onNodeWithText("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("Week").assertIsDisplayed()
        composeTestRule.onNodeWithText("Month").assertIsDisplayed()
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
                uiState = createDefaultUiState().copy(viewMode = ViewMode.AGENDA),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithText("Upcoming Events").assertIsDisplayed()
    }

    @Test
    fun homeScreen_agendaModeShowsViewPickerIcon() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(viewMode = ViewMode.AGENDA),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Calendar view").assertIsDisplayed()
    }

    @Test
    fun homeScreen_emptyAgendaShowsMessage() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    viewMode = ViewMode.AGENDA,
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
    fun homeScreen_agendaModeShowsViewPickerAndSearchIcons() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(viewMode = ViewMode.AGENDA),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Calendar view").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    // ==================== Event List Tests ====================

    @Test
    fun homeScreen_noEventsShowsMessage() {
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

        // Day pager shows multiple days, each with empty message - verify at least one exists
        // Using fetchSemanticsNodes for CI reliability (may be outside viewport on slow emulators)
        assert(composeTestRule.onAllNodesWithText("Nothing to see here; go touch grass?")
            .fetchSemanticsNodes().isNotEmpty()) {
            "Expected empty day message to exist in semantic tree"
        }
    }

    @Test
    fun homeScreen_showsEventTitles() {
        val today = JavaCalendar.getInstance()
        // Calculate dayCode in YYYYMMDD format
        val dayCode = today.get(JavaCalendar.YEAR) * 10000 +
                (today.get(JavaCalendar.MONTH) + 1) * 100 +
                today.get(JavaCalendar.DAY_OF_MONTH)

        // Create test occurrences with events
        val nowMs = System.currentTimeMillis()
        val testOccurrences = persistentListOf(
            OccurrenceWithEvent(
                occurrence = Occurrence(
                    id = 1L,
                    eventId = 1L,
                    calendarId = 1L,
                    startTs = nowMs,
                    endTs = nowMs + 3600000,
                    startDay = dayCode,
                    endDay = dayCode
                ),
                event = testEvents[0],
                calendar = testCalendars[0]
            ),
            OccurrenceWithEvent(
                occurrence = Occurrence(
                    id = 2L,
                    eventId = 2L,
                    calendarId = 2L,
                    startTs = nowMs + 7200000,
                    endTs = nowMs + 10800000,
                    startDay = dayCode,
                    endDay = dayCode
                ),
                event = testEvents[1],
                calendar = testCalendars[1]
            )
        )

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    selectedDate = today.timeInMillis,
                    dayEventsCache = persistentMapOf(dayCode to testOccurrences),
                    loadedDayCodes = persistentSetOf(dayCode),
                    cacheRangeCenter = today.timeInMillis
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
    fun homeScreen_viewPickerIconClickTriggersCallback() {
        var viewPickerClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onViewPickerClick = { viewPickerClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Calendar view").performClick()
        assert(viewPickerClicked)
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
                    viewMode = ViewMode.AGENDA,
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