package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.viewmodels.DayDetailState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Compose tests for how [DayEventsSheet] sits on a phone-sized screen: full height whatever its
 * event count, the same size when its events arrive after it opened, a drag handle with the date
 * right under it, New Event right under the last event, a list that scrolls, and the taps the
 * Planner and the full-height month view rely on. Run the class alone: Robolectric runs of more
 * than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class DayEventsSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private lateinit var savedZone: TimeZone
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 15)
    private val dateMs = at(day, 12, zone = zone)
    private val title = "Thursday, October 15, 2026"

    @Before
    fun pin() {
        Locale.setDefault(Locale.US)
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun event(index: Int): DisplayEvent = PlannerTestEvents.room(
        id = 100L + index,
        startTs = at(day, 6 + index % 16, zone = zone),
        endTs = at(day, 6 + index % 16, 30, zone = zone),
        zone = zone,
        title = "Event $index",
    )

    private class Calls {
        val created = mutableListOf<Long>()
        val opened = mutableListOf<Pair<Event, Long?>>()
        val device = mutableListOf<DisplayEvent.Device>()
    }

    /** Renders the sheet; with [lateMs] set, it opens empty and gets its events that much later. */
    private fun render(events: List<DisplayEvent>, lateMs: Long? = null, calls: Calls = Calls()): Calls {
        composeTestRule.setContent {
            var shown by remember { mutableStateOf(if (lateMs == null) events else emptyList()) }
            if (lateMs != null) LaunchedEffect(Unit) { delay(lateMs); shown = events }
            MaterialTheme {
                DayEventsSheet(
                    day = DayDetailState(
                        DayPagerUtils.localDateToDayCode(day), dateMs, shown.toImmutableList(),
                        canGoBack = true, canGoForward = true,
                    ),
                    showEventEmojis = false,
                    timePattern = "h:mm a",
                    onEventClick = { event, occurrence -> calls.opened += event to occurrence },
                    onDeviceEventClick = { calls.device += it },
                    onCreateEvent = { calls.created += it },
                    onDismiss = {},
                    onStepDay = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    private fun render(count: Int, lateMs: Long? = null) = render((0 until count).map(::event), lateMs)

    private fun titleTop(): Float = composeTestRule.topFraction(composeTestRule.onNodeWithText(title))

    private fun bottomOf(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow.bottom

    private fun topOf(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow.top

    // ---- full height ----

    @Test
    fun `an empty day opens at full height`() {
        render(count = 0)
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `a day with one event opens at full height`() {
        render(count = 1)
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    @Test
    fun `a day with a few events opens at full height`() {
        render(count = 3)
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
        composeTestRule.onNodeWithText("Event 2").assertIsDisplayed()
    }

    @Test
    fun `a day with more events than fit opens full, its list scrolls and New Event stays in view`() {
        render(count = 30)
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
        composeTestRule.onNodeWithText("Event 29").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("New Event").assertIsDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Event 29"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event 29").assertIsDisplayed()
        composeTestRule.onNodeWithText("New Event").assertIsDisplayed()
        assertTrue("the sheet stays full, title at ${titleTop()}", titleTop() < 0.2f)
    }

    @Test
    fun `events that arrive after the sheet opened leave its size alone`() {
        composeTestRule.mainClock.autoAdvance = false
        render(count = 30, lateMs = 1_500)
        composeTestRule.mainClock.advanceTimeBy(1_000)
        val before = composeTestRule.sheetSizes()
        assertTrue("no sheet found", before.isNotEmpty())

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertEquals(before, composeTestRule.sheetSizes())
        assertTrue("title at ${titleTop()}, expected at the top", titleTop() < 0.2f)
    }

    // ---- layout ----

    @Test
    fun `a drag handle sits right above the date`() {
        render(count = 1)
        val handle = composeTestRule.onNode(hasContentDescription("Drag handle")).fetchSemanticsNode().boundsInWindow
        val gap = topOf(title) - handle.bottom
        // The handle's own 22dp bottom padding, and no more padding above the title.
        val limit = with(composeTestRule.density) { 26.dp.toPx() }
        assertTrue("gap $gap between handle and title, limit $limit", gap in 0f..limit)
    }

    @Test
    fun `New Event sits right under the last event`() {
        render(count = 1)
        val gap = topOf("New Event") - bottomOf("Event 0")
        assertTrue("gap $gap under the event", gap < with(composeTestRule.density) { 24.dp.toPx() })
    }

    // ---- taps ----

    @Test
    fun `New Event asks for an event on the sheet's day`() {
        val calls = render(emptyList())
        composeTestRule.onNodeWithText("New Event").performClick()
        assertEquals(listOf(dateMs), calls.created)
    }

    @Test
    fun `an app event opens with its occurrence`() {
        val event = event(0) as DisplayEvent.Room
        val calls = render(listOf(event))
        composeTestRule.onNodeWithText("Event 0").performClick()
        assertEquals(listOf(event.event to event.occurrence.startTs), calls.opened)
    }

    @Test
    fun `a device event opens through its own callback`() {
        val device = PlannerTestEvents.device(
            instanceId = 7, eventId = 70, startTs = at(day, 9, zone = zone), endTs = at(day, 10, zone = zone), zone = zone,
        )
        val calls = render(persistentListOf(device))
        composeTestRule.onNode(hasText(device.title, substring = true)).performClick()
        assertEquals(listOf(device), calls.device)
    }
}
