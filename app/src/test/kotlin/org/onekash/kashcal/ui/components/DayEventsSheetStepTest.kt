package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
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
 * Compose tests for moving [DayEventsSheet] one day at a time: its arrows and its sideways swipe
 * ask for one step each, a short slow swipe asks for none, the direction follows the layout
 * direction, the swipe leaves card taps, list scrolling and the drag down alone, and a new day
 * (not new events for the same day) slides in. Run the class alone: Robolectric runs of more
 * than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class DayEventsSheetStepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private lateinit var savedZone: TimeZone
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 15)
    private val nextDay = day.plusDays(1)

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

    private fun event(index: Int, on: LocalDate = day): DisplayEvent = PlannerTestEvents.room(
        id = 100L + index,
        startTs = at(on, 6 + index % 16, zone = zone),
        endTs = at(on, 6 + index % 16, 30, zone = zone),
        zone = zone,
        title = "Event $index",
    )

    private fun state(
        on: LocalDate,
        events: List<DisplayEvent>,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
    ) = sheetDay(on, zone, events, canGoBack, canGoForward)

    private class Calls {
        val steps = mutableListOf<Int>()
        val opened = mutableListOf<Long>()
        var dismissed = 0
    }

    private fun render(
        events: List<DisplayEvent> = emptyList(),
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
        direction: LayoutDirection = LayoutDirection.Ltr,
        calls: Calls = Calls(),
    ): Calls {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SheetUnderTest(
                    day = state(day, events, canGoBack, canGoForward),
                    onStepDay = { calls.steps += it },
                    onEventClick = { calls.opened += it },
                    onDismiss = { calls.dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    private fun body() = composeTestRule.onNodeWithTag(DAY_SHEET_BODY_TAG)

    // ---- arrows ----

    @Test
    fun `the arrows ask for the previous and the next day`() {
        val calls = render()
        composeTestRule.onNodeWithContentDescription("Next day").performClick()
        composeTestRule.onNodeWithContentDescription("Previous day").performClick()
        assertEquals(listOf(1, -1), calls.steps)
    }

    @Test
    fun `an arrow at the end of the span is off and asks for nothing`() {
        val calls = render(canGoBack = false, canGoForward = false)
        composeTestRule.onNodeWithContentDescription("Next day").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Previous day").assertIsNotEnabled().performClick()
        body().performTouchInput { swipeLeft() }
        body().performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<Int>(), calls.steps)
    }

    // ---- swipe ----

    @Test
    fun `a swipe left asks for the next day and a swipe right for the previous day`() {
        val calls = render(listOf(event(0)))
        body().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        body().performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1, -1), calls.steps)
    }

    @Test
    fun `a short slow swipe asks for nothing`() {
        val calls = render(listOf(event(0)))
        body().performTouchInput {
            val distance = with(composeTestRule.density) { 16.dp.toPx() }
            swipe(center, center - Offset(distance, 0f), durationMillis = 1_500)
        }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<Int>(), calls.steps)
    }

    @Test
    fun `a short fast flick asks for one day`() {
        val calls = render(listOf(event(0)))
        body().performTouchInput {
            // 30dp in 50ms, 600dp a second: under the distance, over the fling speed.
            val distance = with(composeTestRule.density) { 30.dp.toPx() }
            swipe(center, center - Offset(distance, 0f), durationMillis = 50)
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), calls.steps)
    }

    @Test
    fun `one long swipe asks for one day only`() {
        val calls = render(listOf(event(0)))
        body().performTouchInput { swipe(centerRight, centerLeft, durationMillis = 800) }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), calls.steps)
    }

    @Test
    fun `right to left, a swipe right asks for the next day`() {
        val calls = render(listOf(event(0)), direction = LayoutDirection.Rtl)
        body().performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        body().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1, -1), calls.steps)
    }

    @Test
    fun `a swipe on a day with no events steps`() {
        val calls = render(emptyList())
        composeTestRule.onNodeWithText("No events").fetchSemanticsNode()
        body().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), calls.steps)
    }

    @Test
    fun `a swipe below New Event steps`() {
        val calls = render(listOf(event(0)))
        body().performTouchInput { swipe(bottomRight - Offset(8f, 8f), bottomLeft + Offset(8f, -8f), 200) }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), calls.steps)
    }

    // ---- other gestures ----

    @Test
    fun `a swipe that starts on an event steps and doesn't open it`() {
        val calls = render(listOf(event(0)))
        composeTestRule.onNodeWithText("Event 0").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), calls.steps)
        assertEquals(emptyList<Long>(), calls.opened)
    }

    @Test
    fun `a tap on an event still opens it`() {
        val calls = render(listOf(event(0)))
        composeTestRule.onNodeWithText("Event 0").performClick()
        assertEquals(listOf(100L), calls.opened)
        assertEquals(emptyList<Int>(), calls.steps)
    }

    @Test
    fun `an upward swipe scrolls a long day and asks for nothing`() {
        val calls = render((0 until 30).map { event(it) })
        val list = composeTestRule.onNode(hasScrollAction())
        list.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<Int>(), calls.steps)
        assertTrue("the list didn't scroll", composeTestRule.onNodeWithText("Event 0").isNotDisplayed())
    }

    @Test
    fun `a drag down still closes the sheet`() {
        val calls = render(listOf(event(0)))
        composeTestRule.onNodeWithText("Thursday, October 15, 2026")
            .performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 200) }
        composeTestRule.waitForIdle()
        assertEquals(1, calls.dismissed)
        assertEquals(emptyList<Int>(), calls.steps)
    }

    // ---- the shown day ----

    @Test
    fun `a new day shows its own date, events and pane title`() {
        var shown by mutableStateOf(state(day, listOf(event(0))))
        composeTestRule.setContent { SheetUnderTest(day = shown) }
        composeTestRule.waitForIdle()

        shown = state(nextDay, listOf(event(5, nextDay)))
        composeTestRule.waitForIdle()

        val nextTitle = "Friday, October 16, 2026"
        composeTestRule.onNodeWithText(nextTitle).fetchSemanticsNode()
        composeTestRule.onNodeWithText("Event 5").fetchSemanticsNode()
        assertTrue(composeTestRule.onAllNodes(hasText("Event 0")).fetchSemanticsNodes().isEmpty())
        val paneTitles = composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.PaneTitle] }
        assertTrue("pane titles $paneTitles", nextTitle in paneTitles)
        assertTrue(
            "the date is announced through its pane title only",
            composeTestRule.onAllNodes(
                hasText(nextTitle) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `after a zone change the open sheet names the day in the new zone`() {
        var shown by mutableStateOf(state(day, listOf(event(0))))
        composeTestRule.setContent { SheetUnderTest(day = shown) }
        composeTestRule.waitForIdle()

        // 03:00 on Oct 16 in Tokyo is still Oct 15 in New York, where the sheet opened.
        val tokyo = ZoneId.of("Asia/Tokyo")
        TimeZone.setDefault(TimeZone.getTimeZone(tokyo))
        shown = sheetDay(nextDay, tokyo, emptyList()).copy(
            dateMs = nextDay.atTime(3, 0).atZone(tokyo).toInstant().toEpochMilli(),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Friday, October 16, 2026").fetchSemanticsNode()
    }

    @Test
    fun `events arriving late for the same day start no slide`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            var events by remember { mutableStateOf<ImmutableList<DisplayEvent>>(persistentListOf()) }
            LaunchedEffect(Unit) { delay(500); events = persistentListOf(event(0)) }
            SheetUnderTest(day = state(day, events))
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        // Two frames after the events arrived: a slide would still show the leaving "No events".
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Event 0").fetchSemanticsNode()
        assertTrue(composeTestRule.onAllNodes(hasText("No events")).fetchSemanticsNodes().isEmpty())
        composeTestRule.mainClock.autoAdvance = true
    }
}

/** Same RTL swipe, with the direction coming from an Arabic locale as it does in the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w360dp-h640dp-mdpi")
class DayEventsSheetStepRtlLocaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `in an Arabic locale a swipe right asks for the next day`() {
        val steps = mutableListOf<Int>()
        composeTestRule.setContent {
            SheetUnderTest(
                day = sheetDay(LocalDate.of(2026, 10, 15), ZoneId.systemDefault(), emptyList()),
                onStepDay = { steps += it },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(DAY_SHEET_BODY_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), steps)
    }
}

/** Returns the sheet's state for [on] at noon in [zone]. */
internal fun sheetDay(
    on: LocalDate,
    zone: ZoneId,
    events: List<DisplayEvent>,
    canGoBack: Boolean = true,
    canGoForward: Boolean = true,
) = DayDetailState(
    dayCode = DayPagerUtils.localDateToDayCode(on),
    dateMs = at(on, 12, zone = zone),
    events = events.toImmutableList(),
    canGoBack = canGoBack,
    canGoForward = canGoForward,
)

/** Renders [DayEventsSheet] for [day] with the callbacks a test watches and no-ops for the rest. */
@Composable
internal fun SheetUnderTest(
    day: DayDetailState,
    onStepDay: (Int) -> Unit = {},
    onEventClick: (Long) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    MaterialTheme {
        DayEventsSheet(
            day = day,
            showEventEmojis = false,
            timePattern = "h:mm a",
            onEventClick = { event, _ -> onEventClick(event.id) },
            onDeviceEventClick = {},
            onCreateEvent = {},
            onDismiss = onDismiss,
            onStepDay = onStepDay,
        )
    }
}
