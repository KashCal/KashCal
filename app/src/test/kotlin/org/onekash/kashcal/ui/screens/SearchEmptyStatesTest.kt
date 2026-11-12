package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.search.SearchHit
import org.onekash.kashcal.domain.search.SearchOutcome
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.util.LocalDisplayZone
import org.onekash.kashcal.ui.viewmodels.SearchResultsState
import org.onekash.kashcal.ui.viewmodels.SearchTab
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Pins what search says when a list is empty or short (keep typing, no upcoming with a way to
 * Past, no matches with the lookback and a Sync settings link), the hidden-calendar hint and
 * footer, the labels TalkBack reads, the counts announcement, and the keyboard closing on scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "en-w360dp-h640dp-xxhdpi")
class SearchEmptyStatesTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private val visible = Calendar(id = 1, accountId = 1, caldavUrl = "local://a", displayName = "Work", color = 0)
    private val hiddenCalendar = Calendar(id = 2, accountId = 1, caldavUrl = "local://b", displayName = "Old", color = 0, isVisible = false)

    private fun hit(id: Long, start: Long, hidden: Boolean = false): SearchHit {
        val calendar = if (hidden) hiddenCalendar else visible
        val event = Event(id = id, uid = "u$id", calendarId = calendar.id, title = "Event $id", startTs = start, endTs = start + 3_600_000, dtstamp = 0)
        val occurrence = Occurrence(eventId = id, calendarId = calendar.id, startTs = start, endTs = start + 3_600_000, startDay = 20240101, endDay = 20240101)
        return SearchHit(DisplayEvent.Room(event, occurrence, calendar, zone = zone), start, false, hidden)
    }

    private class Screen(state: SearchResultsState, includeHidden: Boolean = false) {
        var state by mutableStateOf(state)
        var includeHidden by mutableStateOf(includeHidden)
        var tab by mutableStateOf(SearchTab.UPCOMING)
        val includeHiddenCalls = mutableListOf<Boolean>()
        var syncSettingsOpened = 0
    }

    private fun show(screen: Screen, query: String = "dermatology", keyboard: SoftwareKeyboardController? = null) {
        rule.setContent {
            CompositionLocalProvider(LocalDisplayZone provides zone, LocalSoftwareKeyboardController provides keyboard) {
                KashCalTheme {
                    SearchContent(
                        query = query,
                        state = screen.state,
                        tab = screen.tab,
                        dateFilter = null,
                        showDateMenu = false,
                        includeHidden = screen.includeHidden,
                        onResultClick = { _, _ -> },
                        onTabSelect = { screen.tab = it },
                        onDateChipClick = {},
                        onDateMenuDismiss = {},
                        onDateFilterSelect = {},
                        onPickDates = {},
                        onIncludeHiddenChange = { screen.includeHiddenCalls += it; screen.includeHidden = it },
                        onOpenSyncSettings = { screen.syncSettingsOpened++ },
                    )
                }
            }
        }
    }

    private fun loaded(
        upcoming: List<SearchHit> = emptyList(),
        past: List<SearchHit> = emptyList(),
        hiddenUpcoming: List<SearchHit> = emptyList(),
        hiddenPast: List<SearchHit> = emptyList(),
        lookback: Int = 90,
    ) = SearchResultsState.Loaded(
        SearchOutcome(
            upcoming = upcoming.toPersistentList(), past = past.toPersistentList(),
            hiddenUpcoming = hiddenUpcoming.toPersistentList(), hiddenPast = hiddenPast.toPersistentList(),
            lookbackDays = lookback,
        )
    )

    @Test
    fun `a query too short to search says keep typing and shows no chips`() {
        show(Screen(SearchResultsState.TooShort), query = "d")

        rule.onNodeWithText("Keep typing to search").assertIsDisplayed()
        rule.onNodeWithTag("search_chip_upcoming").assertDoesNotExist()
    }

    @Test
    fun `no upcoming matches offers the past ones and never switches by itself`() {
        val screen = Screen(loaded(past = (1L..4L).map { hit(it, now - it * 30 * day) }))
        show(screen)

        rule.onNodeWithText("No upcoming events").assertIsDisplayed()
        assertEquals(SearchTab.UPCOMING, screen.tab)
        rule.onNodeWithText("Show 4 past events").performClick()
        rule.waitForIdle()
        assertEquals(SearchTab.PAST, screen.tab)
    }

    @Test
    fun `no matches anywhere names the query and the lookback with a Sync settings link`() {
        val screen = Screen(loaded(lookback = 90))
        show(screen)

        rule.onNodeWithText("No events match “dermatology”").assertIsDisplayed()
        rule.onNodeWithText("Sync lookback: 3 months").assertIsDisplayed()
        val link = rule.onNodeWithTag("search_sync_settings")
        assertEquals(listOf("Open the sync lookback setting"), link.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        link.performClick()
        assertEquals(1, screen.syncSettingsOpened)
    }

    @Test
    fun `with All events there is no lookback line and no link`() {
        show(Screen(loaded(lookback = Int.MAX_VALUE)))

        rule.onNodeWithText("No events match “dermatology”").assertIsDisplayed()
        rule.onNodeWithTag("search_lookback_line").assertDoesNotExist()
        rule.onNodeWithTag("search_sync_settings").assertDoesNotExist()
    }

    @Test
    fun `matches in hidden calendars are counted for the list on screen and Show includes them`() {
        val screen = Screen(
            loaded(
                upcoming = listOf(hit(1, now + day)),
                hiddenUpcoming = listOf(hit(2, now + 2 * day, hidden = true), hit(3, now + 3 * day, hidden = true)),
                hiddenPast = listOf(hit(4, now - day, hidden = true)),
            )
        )
        show(screen)

        rule.onNodeWithText("2 more in hidden calendars").assertIsDisplayed()
        val show = rule.onNodeWithText("Show")
        assertEquals(listOf("Show 2 matches in hidden calendars"), show.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        show.performClick()
        rule.waitForIdle()

        assertEquals(listOf(true), screen.includeHiddenCalls)
        assertEquals(2, rule.onAllNodesWithText("Old · hidden", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Including 1 hidden calendar").assertIsDisplayed()
        rule.onNodeWithText("Leave out").performClick()
        assertEquals(listOf(true, false), screen.includeHiddenCalls)
    }

    @Test
    fun `switching lists updates the hidden count`() {
        val screen = Screen(
            loaded(
                upcoming = listOf(hit(1, now + day)),
                past = listOf(hit(5, now - 2 * day)),
                hiddenUpcoming = listOf(hit(2, now + 2 * day, hidden = true), hit(3, now + 3 * day, hidden = true)),
                hiddenPast = listOf(hit(4, now - day, hidden = true)),
            )
        )
        show(screen)
        rule.onNodeWithText("2 more in hidden calendars").assertIsDisplayed()

        screen.tab = SearchTab.PAST
        rule.waitForIdle()
        rule.onNodeWithText("1 more in a hidden calendar").assertIsDisplayed()
    }

    @Test
    fun `only hidden matches offer to show them in the empty state`() {
        val screen = Screen(loaded(hiddenUpcoming = listOf(hit(2, now + 2 * day, hidden = true))))
        show(screen)

        rule.onNodeWithText("Show 1 in a hidden calendar").performClick()
        assertEquals(listOf(true), screen.includeHiddenCalls)
    }

    @Test
    fun `the counts are announced once per new result`() {
        val screen = Screen(loaded(upcoming = listOf(hit(1, now + day)), past = (2L..15L).map { hit(it, now - it * day) }))
        show(screen)
        val node = { rule.onNodeWithTag("search_counts_announcement").fetchSemanticsNode().config }

        assertEquals(LiveRegionMode.Polite, node()[SemanticsProperties.LiveRegion])
        assertEquals(listOf("1 upcoming, 14 past"), node()[SemanticsProperties.ContentDescription])

        // A redraw with the same counts keeps the same text, so TalkBack says nothing new.
        screen.state = loaded(upcoming = listOf(hit(1, now + day)), past = (2L..15L).map { hit(it, now - it * day) })
        rule.waitForIdle()
        assertEquals(listOf("1 upcoming, 14 past"), node()[SemanticsProperties.ContentDescription])
    }

    @Test
    fun `scrolling the results closes the keyboard`() {
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() { hides++ }
        }
        show(Screen(loaded(upcoming = (1L..30L).map { hit(it, now + it * day) })), keyboard = keyboard)

        rule.onNodeWithTag("search_counts_announcement").assertExists()
        rule.onNodeWithText("Event 1").performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertTrue("hidden $hides times", hides >= 1)
    }

    private class Taps {
        val tabs = mutableListOf<SearchTab>()
        var dateChip = 0
    }

    private fun showChips(state: SearchResultsState, tab: SearchTab, dateFilter: org.onekash.kashcal.ui.viewmodels.DateFilter? = null, menu: Boolean = false): Taps {
        val taps = Taps()
        rule.setContent {
            CompositionLocalProvider(LocalDisplayZone provides zone) {
                KashCalTheme {
                    SearchContent(
                        query = "event", state = state, tab = tab, dateFilter = dateFilter, showDateMenu = menu,
                        includeHidden = false, onResultClick = { _, _ -> }, onTabSelect = { taps.tabs += it },
                        onDateChipClick = { taps.dateChip++ }, onDateMenuDismiss = {}, onDateFilterSelect = {}, onPickDates = {},
                    )
                }
            }
        }
        return taps
    }

    @Test
    fun `a chip with no results can be selected`() {
        val taps = showChips(loaded(upcoming = listOf(hit(1, now + day))), SearchTab.UPCOMING)

        rule.onNodeWithTag("search_chip_past").performClick()

        assertEquals(listOf(SearchTab.PAST), taps.tabs)
    }

    @Test
    fun `tapping the selected chip scrolls its list to the top and searches nothing`() {
        val taps = showChips(loaded(upcoming = (1L..30L).map { hit(it, now + it * day) }), SearchTab.UPCOMING)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        rule.waitForIdle()
        rule.onNodeWithText("Event 1").assertDoesNotExist()

        rule.onNodeWithTag("search_chip_upcoming").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Event 1").assertExists()
        assertTrue(taps.tabs.isEmpty())
    }

    @Test
    fun `the selected Date chip reopens its menu, whose items name their action`() {
        val range = org.onekash.kashcal.ui.viewmodels.DateFilter.ThisWeek
        val taps = showChips(SearchResultsState.Loaded(SearchOutcome(range = persistentListOf())), SearchTab.UPCOMING, range, menu = true)

        rule.onNodeWithTag("search_chip_date").performClick()
        assertEquals(1, taps.dateChip)
        for (description in listOf("Show matches this week", "Show matches this month", "Pick dates to search")) {
            rule.onNode(hasContentDescription(description)).assertExists()
        }
    }

    @Test
    fun `ended matches are dimmed in a date range`() {
        val ended = hit(1, now - 2 * day)
        showChips(
            SearchResultsState.Loaded(SearchOutcome(past = persistentListOf(ended), range = persistentListOf(ended))),
            SearchTab.UPCOMING, org.onekash.kashcal.ui.viewmodels.DateFilter.ThisWeek,
        )

        assertTrue(rule.onAllNodes(hasStateDescription()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `past matches are not dimmed under Past`() {
        showChips(loaded(past = listOf(hit(1, now - 2 * day))), SearchTab.PAST)

        assertTrue(rule.onAllNodes(hasStateDescription()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the announcement changes when the counts change`() {
        val screen = Screen(loaded(upcoming = listOf(hit(1, now + day))))
        show(screen)
        val text = { rule.onNodeWithTag("search_counts_announcement").fetchSemanticsNode().config[SemanticsProperties.ContentDescription] }
        assertEquals(listOf("1 upcoming, 0 past"), text())

        screen.state = loaded(upcoming = listOf(hit(1, now + day), hit(2, now + 2 * day)), past = listOf(hit(3, now - day)))
        rule.waitForIdle()

        assertEquals(listOf("2 upcoming, 1 past"), text())
    }

    private fun hasStateDescription() =
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
}
