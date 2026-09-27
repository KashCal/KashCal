package org.onekash.kashcal.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
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
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.viewmodels.AgendaUiState
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar as JavaCalendar

/**
 * Tests HomeScreen rendering and callbacks through its semantics tree, finding nodes by text or
 * content description: the app bar icons, the create-event FAB, the offline icon, the month
 * header and day-of-week headers, search mode, agenda mode, the day's event list, the search,
 * settings and today callbacks, loading states, the sync banner, and the account hub opened
 * from the avatar.
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
            calendars = testCalendars
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

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Accounts & Settings").assertIsDisplayed()
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

    // ==================== Offline Icon Tests ====================

    @Test
    fun homeScreen_showsOfflineIconWhenOfflineAndConfigured() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isConfigured = true),
                isOnline = false,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Offline").assertIsDisplayed()
    }

    @Test
    fun homeScreen_hidesOfflineIconWhenOnline() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isConfigured = true),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Offline").assertDoesNotExist()
    }

    @Test
    fun homeScreen_hidesOfflineIconWhenNotConfigured() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(isConfigured = false),
                isOnline = false,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Offline").assertDoesNotExist()
    }

    // ==================== Calendar Month Header Tests ====================

    @Test
    fun homeScreen_displaysCurrentMonthHeader() {
        val today = JavaCalendar.getInstance()
        val monthOnly = SimpleDateFormat("MMMM", Locale.getDefault()).format(today.time)
        val yearOnly = SimpleDateFormat("y", Locale.getDefault()).format(today.time)

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

        composeTestRule.onNodeWithText(monthOnly).assertIsDisplayed()
        composeTestRule.onNodeWithText(yearOnly).assertIsDisplayed()
    }

    @Test
    fun homeScreen_doesNotDisplayChevronNavigationArrows() {
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

        composeTestRule.onNodeWithContentDescription("Previous").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Next").assertDoesNotExist()
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

        // Short day names in the device locale, as DayOfWeekHeaders renders them.
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

        // The date filter chips are All, Week, Month and Date; the first three are asserted.
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
                uiState = createDefaultUiState().copy(viewMode = ViewMode.AGENDA),
                agendaEvents = AgendaUiState(events = persistentListOf(), isLoading = false),
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
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        // The day pager shows several days, each with the empty message; at least one must
        // exist. fetchSemanticsNodes, as a node may be outside the viewport on slow emulators.
        assert(composeTestRule.onAllNodesWithText("Nothing to see here; go touch grass?")
            .fetchSemanticsNodes().isNotEmpty()) {
            "Expected empty day message to exist in semantic tree"
        }
    }

    @Test
    fun homeScreen_showsEventTitles() {
        val today = JavaCalendar.getInstance()
        // Today's dayCode, YYYYMMDD.
        val dayCode = today.get(JavaCalendar.YEAR) * 10000 +
                (today.get(JavaCalendar.MONTH) + 1) * 100 +
                today.get(JavaCalendar.DAY_OF_MONTH)

        // Two of today's occurrences, one per test event.
        val nowMs = System.currentTimeMillis()
        val testOccurrences = persistentListOf<DisplayEvent>(
            DisplayEvent.Room(
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
            DisplayEvent.Room(
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

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Accounts & Settings").performClick()
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

    // Views are switched in the calendar drawer; HomeScreen has no view-picker callback to test.

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

        // While loading, HomeScreen shows only the progress indicator, which has no text, so the
        // test checks that a calendar text is absent. No string resource carries "Tap a day to
        // see events", so the check passes either way.
        composeTestRule.onNode(hasText("Tap a day to see events")).assertDoesNotExist()
    }

    @Test
    fun homeScreen_agendaShowsLoadingWhenLoading() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(viewMode = ViewMode.AGENDA),
                agendaEvents = AgendaUiState.LOADING,
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {}
            )
        }

        // While the agenda loads, the "No upcoming events" empty message must not show.
        composeTestRule.onNodeWithText("No upcoming events").assertDoesNotExist()
    }

    // ==================== Sync Banner Tests ====================

    @Test
    fun homeScreen_showsSyncBannerWhenSyncing() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState().copy(
                    showSyncBanner = true,
                    syncBannerState = SyncBannerState.Syncing
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
                    syncBannerState = SyncBannerState.Syncing
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

    // ==================== Account Hub Tests ====================

    @Test
    fun homeScreen_topBarShowsAvatarTrigger() {
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

        composeTestRule.onNodeWithContentDescription("More menu").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More options").assertDoesNotExist()
    }

    @Test
    fun homeScreen_avatarAccessibilityLabelMatchesHelperWhenInvitesPending() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                pendingInvitesCount = 3
            )
        }

        composeTestRule.onNodeWithContentDescription("More menu, 3 invites pending").assertIsDisplayed()
    }

    @Test
    fun homeScreen_tapAvatarRevealsAllHubRows() {
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

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Invites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Go to date").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share availability").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manage tags").assertIsDisplayed()
        composeTestRule.onNodeWithText("Accounts & Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        // Privacy & Security appears at the bottom of the hub.
        composeTestRule.onNodeWithText("Privacy & Security").assertIsDisplayed()
    }

    @Test
    fun homeScreen_hubInvitesClickInvokesCallback() {
        var invitesClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onOpenInvitationInbox = { invitesClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Invites").performClick()
        assert(invitesClicked)
    }

    @Test
    fun homeScreen_hubTagsClickInvokesCallback() {
        var tagsClicked = false

        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                onTagsClick = { tagsClicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Manage tags").performClick()
        assert(tagsClicked)
    }

    @Test
    fun homeScreen_hubSettingsClickInvokesCallback() {
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

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Accounts & Settings").performClick()
        assert(settingsClicked)
    }

    @Test
    fun homeScreen_hubInvitesItemHidesChipWhenCountZero() {
        composeTestRule.setContent {
            HomeScreen(
                uiState = createDefaultUiState(),
                isOnline = true,
                onDateSelected = {},
                onGoToToday = {},
                onSetViewingMonth = { _, _ -> },
                onClearNavigateToToday = {},
                onClearNavigateToMonth = {},
                pendingInvitesCount = 0
            )
        }

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Invites").assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun homeScreen_avatarTriggerPresentInAgendaView() {
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

        composeTestRule.onNodeWithContentDescription("More menu").assertIsDisplayed()
    }

    @Test
    fun homeScreen_backButtonClosesHub() {
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

        composeTestRule.onNodeWithContentDescription("More menu").performClick()
        composeTestRule.onNodeWithText("Accounts & Settings").assertIsDisplayed()
        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Accounts & Settings").assertDoesNotExist()
    }
}
