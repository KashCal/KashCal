package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.FormDateFields
import org.onekash.kashcal.ui.components.backInLatestDialog
import org.onekash.kashcal.ui.components.topFraction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Compose tests for [DateTimeSheet] on a 411x914dp phone: it opens at full height on the side
 * the user tapped, the Starts | Ends switch edits both ends in one sheet, only the strip with
 * the handle and switch drags the sheet, and every way of closing it hands the edited start and
 * end back once. Run the class alone: Robolectric runs of more than one class hit a native
 * crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class DateTimeSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val savedZone = TimeZone.getDefault()

    @Before
    fun pin() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private class Host {
        var open by mutableStateOf(true)
        var commits = 0
        var last: FormDateFields? = null
    }

    private fun midnight(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Tue Oct 27 2026, 9:00-9:30 AM.
    private fun timedForm() = EventFormState(
        isAllDay = false,
        dateMillis = midnight(LocalDate.of(2026, 10, 27)), startHour = 9, startMinute = 0,
        endDateMillis = midnight(LocalDate.of(2026, 10, 27)), endHour = 9, endMinute = 30,
    )

    private fun render(
        form: EventFormState = timedForm(),
        side: DateSelectionMode = DateSelectionMode.START,
        use24Hour: Boolean = false,
    ): Host {
        val host = Host()
        rule.setContent {
            MaterialTheme {
                if (host.open) {
                    DateTimeSheet(
                        initial = form,
                        openingSide = side,
                        defaultDurationMinutes = 30,
                        onCommit = { host.commits++; host.last = it; host.open = false },
                        use24Hour = use24Hour,
                    )
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    private fun starts() = rule.onNodeWithText("Starts", substring = true)
    private fun ends() = rule.onNodeWithText("Ends", substring = true)
    private fun hourWheel() = rule.onAllNodesWithContentDescription("Wheel picker with 12 options")[0]

    private fun swipeDownFrom(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performTouchInput { swipeDown(startY = centerY, endY = centerY + 700f, durationMillis = 200) }
        rule.waitForIdle()
    }

    // ---- opening ----

    @Test
    fun `the sheet opens at full height`() {
        render()

        assertTrue("switch at ${rule.topFraction(starts())}", rule.topFraction(starts()) < 0.1f)
    }

    @Test
    fun `opening from the Starts row edits the start`() {
        render()

        starts().assertIsSelected()
        ends().assertIsNotSelected()
    }

    @Test
    fun `opening from the Ends row edits the end`() {
        render(side = DateSelectionMode.END)

        ends().assertIsSelected()
        starts().assertIsNotSelected()
    }

    @Test
    fun `the switch halves are 48dp touch targets`() {
        render()

        listOf(starts(), ends()).forEach { half ->
            val bounds = half.getUnclippedBoundsInRoot()
            assertTrue("half height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 48.dp)
        }
    }

    @Test
    fun `the old Cancel and title bar is gone`() {
        render()

        rule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    // ---- the switch ----

    @Test
    fun `tapping Ends edits the end in the same sheet`() {
        render()

        ends().performClick()
        rule.waitForIdle()

        ends().assertIsSelected()
        starts().assertIsNotSelected()
    }

    @Test
    fun `moving the start date keeps the length and the end follows`() {
        render()

        rule.onNodeWithText("29").performClick()
        rule.waitForIdle()

        ends().assert(hasText("9:30 AM", substring = true))
        rule.onNodeWithText("29").assertIsSelected()
    }

    @Test
    fun `a same-day event shows times only and a multi-day one adds the date`() {
        render()
        starts().assert(hasText("Starts")).assert(hasText("9:00 AM"))
        ends().assert(hasText("Ends")).assert(hasText("9:30 AM"))

        ends().performClick()
        rule.onNodeWithText("29").performClick()
        rule.waitForIdle()

        starts().assert(hasText("Starts")).assert(hasText("Oct 27 9:00 AM"))
        ends().assert(hasText("Ends")).assert(hasText("Oct 29 9:30 AM"))
    }

    // A fling still running on the Starts wheel must not move the end after a flip.
    @Test
    fun `flipping sides mid-fling leaves the other end alone`() {
        render()
        rule.mainClock.autoAdvance = false

        hourWheel().performTouchInput { swipeUp(startY = centerY, endY = centerY - 200f, durationMillis = 80) }
        rule.mainClock.advanceTimeBy(80)
        ends().performClick()
        rule.mainClock.advanceTimeBy(3_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val endHalf = ends().fetchSemanticsNode()
        val text = endHalf.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        val startText = starts().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        // The end moved only as the start's kept length moved it: still 30 minutes after it.
        val startTime = Regex("""(\d{1,2}):(\d{2}) (AM|PM)""").find(startText)!!.value
        val endTime = Regex("""(\d{1,2}):(\d{2}) (AM|PM)""").find(text)!!.value
        assertEquals(addMinutes(startTime, 30), endTime)
    }

    private fun addMinutes(time: String, minutes: Int): String {
        val parsed = java.time.LocalTime.parse(time, java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return parsed.plusMinutes(minutes.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }

    // ---- drags: only the top strip moves the sheet ----

    @Test
    fun `a drag down from the switch strip closes the sheet and keeps the times`() {
        val host = render()
        rule.onNodeWithText("29").performClick()
        rule.waitForIdle()

        swipeDownFrom(starts())

        assertEquals(1, host.commits)
        assertEquals(midnight(LocalDate.of(2026, 10, 29)), host.last!!.dateMillis)
        assertEquals(9, host.last!!.startHour)
    }

    @Test
    fun `a drag down on the month grid leaves the sheet open`() {
        val host = render()

        swipeDownFrom(rule.onNodeWithText("15"))

        assertEquals(0, host.commits)
        starts().assertExists()
    }

    @Test
    fun `a drag down on the wheel scrolls it and leaves the sheet open`() {
        val host = render()

        swipeDownFrom(hourWheel())

        assertEquals(0, host.commits)
        starts().assertExists()
        assertTrue("start time moved", !starts().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("9:00 AM"))
    }

    @Test
    fun `a drag down between the wheels leaves the sheet open`() {
        val host = render()

        swipeDownFrom(rule.onNodeWithText(":"))

        assertEquals(0, host.commits)
        starts().assertExists()
    }

    @Test
    fun `a drag down at the left edge of the wheel row leaves the sheet open`() {
        val host = render()
        val wheelLeft = hourWheel().getUnclippedBoundsInRoot().left

        hourWheel().performTouchInput {
            val x = 2.dp.toPx() - wheelLeft.toPx()
            swipe(
                start = androidx.compose.ui.geometry.Offset(x, centerY),
                end = androidx.compose.ui.geometry.Offset(x, centerY + 700f),
                durationMillis = 200,
            )
        }
        rule.waitForIdle()

        assertEquals(0, host.commits)
        starts().assertExists()
    }

    @Test
    fun `a drag down on Done leaves the sheet open`() {
        val host = render()

        swipeDownFrom(rule.onNodeWithText("Done"))

        assertEquals(0, host.commits)
        starts().assertExists()
    }

    // ---- sizes, chips and centering ----

    private fun height(node: androidx.compose.ui.test.SemanticsNodeInteraction) =
        node.getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun `a tall phone shows a five-row wheel`() {
        render()

        assertEquals(32.dp * 5, height(hourWheel()))
    }

    @Test
    fun `a tall phone shows the timed length chips`() {
        render()

        rule.onNodeWithText("30m").assertExists()
        rule.onNodeWithText("1h").assertExists()
        rule.onNodeWithText("2h").assertExists()
        rule.onNodeWithText("15m").assertDoesNotExist()
    }

    @Test
    fun `the chip matching the length is marked`() {
        render()

        rule.onNodeWithText("30m").assertIsSelected()
        rule.onNodeWithText("1h").assertIsNotSelected()
    }

    @Test
    fun `a length chip sets the end from the Starts side`() {
        render()

        rule.onNodeWithText("1h").performClick()
        rule.waitForIdle()

        ends().assert(hasText("10:00 AM", substring = true))
        rule.onNodeWithText("1h").assertIsSelected()
    }

    @Test
    fun `a length chip sets the end from the Ends side`() {
        val host = render(side = DateSelectionMode.END)

        rule.onNodeWithText("2h").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals(11, host.last!!.endHour)
        assertEquals(9, host.last!!.startHour)
    }

    @Test
    fun `the content is centered between the switch and Done`() {
        render()
        val stripBottom = starts().getUnclippedBoundsInRoot().bottom
        // The arrow button is drawn 40dp tall, centered in the 48dp month header.
        val headerTop = rule.onNodeWithContentDescription("Previous month").getUnclippedBoundsInRoot()
            .let { (it.top + it.bottom) / 2 } - 24.dp
        val chipsBottom = rule.onNodeWithText("2h").getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 } + 24.dp
        val doneBarTop = rule.onNodeWithText("Done").getUnclippedBoundsInRoot().top - 8.dp

        val above = headerTop - stripBottom
        val below = doneBarTop - chipsBottom
        assertTrue("above $above, below $below", above > 40.dp && (above - below).value in -4f..4f)
    }

    // ---- all-day ----

    private fun allDayForm(start: LocalDate, end: LocalDate) = EventFormState(
        isAllDay = true,
        dateMillis = midnight(start),
        endDateMillis = midnight(end),
    )

    @Test
    fun `an all-day event has no wheel and shows the day chips`() {
        render(form = allDayForm(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27)))

        rule.onAllNodesWithContentDescription("Wheel picker", substring = true).assertCountEquals(0)
        rule.onNodeWithText("2 days").assertExists()
        rule.onNodeWithText("5 days").assertExists()
        rule.onNodeWithText("7 days").assertExists()
    }

    @Test
    fun `an all-day day chip sets the inclusive end`() {
        val host = render(form = allDayForm(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27)))

        rule.onNodeWithText("5 days").performClick()
        rule.waitForIdle()
        ends().assert(hasText("Oct 31", substring = true))
        rule.onNodeWithText("5 days").assertIsSelected()

        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(midnight(LocalDate.of(2026, 10, 31)), host.last!!.endDateMillis)
    }

    @Test
    fun `the all-day switch shows dates only`() {
        render(form = allDayForm(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 31)))

        starts().assert(hasText("Oct 27", substring = true))
        ends().assert(hasText("Oct 31", substring = true))
        assertTrue(!starts().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("AM"))
    }

    @Test
    fun `the all-day grid marks the end being edited`() {
        render(form = allDayForm(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 31)), side = DateSelectionMode.END)

        rule.onNodeWithText("31").assertIsSelected()
        rule.onNodeWithText("27").assertIsNotSelected()
    }

    // A month change after a day tap applies to the state the tap left, not the one before it.
    @Test
    fun `a month swipe after a day tap keeps the tapped day`() {
        val host = render()
        rule.onNodeWithText("20").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("15").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 400f, durationMillis = 200) }
        rule.waitForIdle()
        rule.onNodeWithText("November 2026").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals(midnight(LocalDate.of(2026, 10, 20)), host.last!!.dateMillis)
    }

    // ---- length chips and the all-day end hint ----

    private val hint = "Tap a day to change the end"

    @Test
    fun `the timed chips share one width`() {
        render()

        val widths = listOf("30m", "1h", "2h").map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().let { b -> b.right - b.left } }
        assertTrue("widths $widths", widths.all { (it - widths[0]).value in -0.5f..0.5f })
    }

    @Test
    fun `a timed date tap shows no hint and stays on Starts`() {
        render()

        rule.onNodeWithText("20").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(hint).assertDoesNotExist()
        starts().assertIsSelected()
    }

    @Test
    fun `a new all-day event's first tap moves it, flips to Ends and shows the hint`() {
        val host = render(form = allDayForm(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28)))

        rule.onNodeWithText("20").performClick()
        rule.waitForIdle()

        ends().assertIsSelected()
        rule.onNodeWithText(hint)
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite))
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(midnight(LocalDate.of(2026, 10, 20)), host.last!!.dateMillis)
        assertEquals(midnight(LocalDate.of(2026, 10, 20)), host.last!!.endDateMillis)
    }

    @Test
    fun `a second all-day tap sets the end and hides the hint`() {
        val host = render(form = allDayForm(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28)))

        rule.onNodeWithText("20").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("23").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(hint).assertDoesNotExist()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(midnight(LocalDate.of(2026, 10, 20)), host.last!!.dateMillis)
        assertEquals(midnight(LocalDate.of(2026, 10, 23)), host.last!!.endDateMillis)
    }

    @Test
    fun `a day chip after the first tap hides the hint`() {
        render(form = allDayForm(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28)))

        rule.onNodeWithText("20").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("5 days").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(hint).assertDoesNotExist()
        ends().assert(hasText("Oct 24", substring = true))
    }

    @Test
    fun `the hint line keeps its height so the chips don't move`() {
        render(form = allDayForm(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28)))
        val before = rule.onNodeWithText("2 days").getUnclippedBoundsInRoot().top

        rule.onNodeWithText("28").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(hint).assertExists()
        assertEquals(before, rule.onNodeWithText("2 days").getUnclippedBoundsInRoot().top)
    }

    // ---- closing keeps the times ----

    @Test
    fun `Back closes the sheet and keeps the edited times`() {
        val host = render()
        rule.onNodeWithText("29").performClick()
        rule.waitForIdle()

        rule.backInLatestDialog()

        assertEquals(1, host.commits)
        assertEquals(midnight(LocalDate.of(2026, 10, 29)), host.last!!.dateMillis)
    }

    @Test
    fun `Done closes the sheet and keeps the edited times`() {
        val host = render()
        ends().performClick()
        rule.onNodeWithText("28").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals(1, host.commits)
        assertEquals(midnight(LocalDate.of(2026, 10, 28)), host.last!!.endDateMillis)
        assertEquals(midnight(LocalDate.of(2026, 10, 27)), host.last!!.dateMillis)
    }

    @Test
    fun `Done with the month and year wheel open only closes the wheel`() {
        val host = render()
        rule.onNodeWithContentDescription("Pick month and year").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals(0, host.commits)
        rule.onNodeWithContentDescription("Pick month and year").assertExists()
    }

    @Test
    fun `TalkBack's close action on the strip keeps the times`() {
        val host = render()
        val closeAction = SemanticsMatcher("has a Close dismiss action") {
            it.config.getOrNull(SemanticsActions.Dismiss)?.label == "Close"
        }

        rule.onNode(closeAction).performSemanticsAction(SemanticsActions.Dismiss)
        rule.waitForIdle()

        assertEquals(1, host.commits)
        assertNotNull(host.last)
    }

    @Test
    fun `Done tapped mid-fling commits the hour centered at the tap`() {
        val host = render(use24Hour = true)
        rule.mainClock.autoAdvance = false

        rule.onNode(hasContentDescription("Wheel picker with 24 options"))
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - 200f, durationMillis = 80) }
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithText("Done").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(1, host.commits)
        assertNotEquals(9, host.last!!.startHour)
    }
}
