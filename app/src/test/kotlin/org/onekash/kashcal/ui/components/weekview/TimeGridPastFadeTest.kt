package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.onekash.kashcal.ui.components.DEEMPHASIZED_ALPHA
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Drives the real [WeekViewContent] with a hand-set clock to check the past fade in Day, 3-day and
 * Week: a block, an all-day chip, a strip bar and an overlap-sheet row read "past event" once the
 * event has ended, a block the minute it ends without anything else changing; a faded block is
 * opaque, so an event under it doesn't show through; a multi-day event fades only when it ends.
 * mdpi, so 1dp is 1px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class TimeGridPastFadeTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)

    /** A week start the grid honours that puts yesterday and today in the shown week. */
    private val weekWithYesterday =
        if (today.dayOfWeek == DayOfWeek.SUNDAY) java.util.Calendar.SATURDAY else java.util.Calendar.SUNDAY

    private var surface = Color.Unspecified

    private fun show(
        clock: MutableTestClock,
        timed: List<DisplayEvent> = emptyList(),
        allDay: List<DisplayEvent> = emptyList(),
        visibleDays: Int = 7,
        firstDayOfWeek: Int = weekWithYesterday,
    ) {
        rule.setContent {
            MaterialTheme {
                surface = MaterialTheme.colorScheme.surface
                WeekViewContent(
                    timedEvents = timed.toImmutableList(),
                    allDayEvents = allDay.toImmutableList(),
                    isLoading = false,
                    error = null,
                    scrollPosition = 0,
                    savedScrollMinutes = 8 * 60,
                    hourHeight = 60f,
                    visibleDays = visibleDays,
                    firstDayOfWeek = firstDayOfWeek,
                    allDayRowsExpanded = true,
                    onDatePickerRequest = {},
                    onScrollPositionChange = {},
                    onEventClick = {},
                    clock = clock,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * Returns the state screen readers hear for the one shown item with [title] whose bounds pass
     * [inside].
     */
    private fun stateOf(title: String, inside: (Rect) -> Boolean = { true }): String? {
        val node = rule.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && inside(it.boundsInRoot) }
            .also { assertTrue("one shown item for '$title': ${it.map { n -> n.boundsInRoot }}", it.size == 1) }
            .single()
        return node.config.getOrNull(SemanticsProperties.StateDescription)
    }

    private fun bounds(title: String): Rect = rule.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes()
        .map { it.boundsInRoot }.single { it.width > 0f && it.left >= 0f }

    private fun assertFadesAtTheMinute(visibleDays: Int) {
        val clock = MutableTestClock.at(today, LocalTime.of(10, 59, 30))
        show(clock, timed = listOf(roomDisplayEvent(id = 1, title = "Standup", date = today, hour = 10)), visibleDays = visibleDays)
        assertNull("running at 10:59:30 ($visibleDays days)", stateOf("Standup") { it.left >= 0f })
        clock.instant = MutableTestClock.at(today, LocalTime.of(11, 0)).instant()
        rule.mainClock.advanceTimeBy(31_000)
        rule.waitForIdle()
        assertEquals("past at the 11:00 tick ($visibleDays days)", "past event", stateOf("Standup") { it.left >= 0f })
    }

    @Test
    fun `a block fades the minute it ends in the week view`() = assertFadesAtTheMinute(visibleDays = 7)

    @Test
    fun `a block fades the minute it ends in the 3-day view`() = assertFadesAtTheMinute(visibleDays = 3)

    @Test
    fun `a block fades the minute it ends in the day view`() = assertFadesAtTheMinute(visibleDays = 1)

    private val red = 0xFFF44336.toInt()
    private val blue = Color(0xFF2196F3)

    /**
     * Checks a pixel inside [title]'s block, drawn over a red event, is the blue fill at
     * [DEEMPHASIZED_ALPHA] over the surface: each channel blue·a + surface·(1−a), as alpha
     * compositing gives. Seen through to the red event, the green channel would be far lower.
     */
    private fun assertOpaqueOverRed(title: String) {
        val pixel = fillPixel(title)
        val want = listOf(blue.red to surface.red, blue.green to surface.green, blue.blue to surface.blue)
            .map { (a, b) -> a * DEEMPHASIZED_ALPHA + b * (1 - DEEMPHASIZED_ALPHA) }
        val got = listOf(pixel.red, pixel.green, pixel.blue)
        for (i in 0..2) assertEquals("channel $i of $pixel, want $want", want[i], got[i], 0.02f)
    }

    /** Returns a pixel of [title]'s fill near its bottom end corner, clear of its text. */
    private fun fillPixel(title: String): Color {
        val block = bounds(title)
        return rule.onRoot().captureToImage().toPixelMap()[(block.right - 8f).toInt(), (block.bottom - 4f).toInt()]
    }

    private fun under() = roomDisplayEvent(id = 1, title = "Workshop", date = today, hour = 9, durationMinutes = 120, color = red)

    @Test
    fun `a past block drawn over another is opaque`() {
        show(
            MutableTestClock.at(today, LocalTime.of(10, 30)),
            timed = listOf(under(), roomDisplayEvent(id = 2, title = "Bravo", date = today, hour = 9, minute = 30, durationMinutes = 30)),
            visibleDays = 3,
        )
        assertEquals("past event", stateOf("Bravo") { it.left >= 0f })
        assertOpaqueOverRed("Bravo")
    }

    @Test
    fun `a declined block drawn over another is opaque`() {
        val declined = roomDisplayEvent(id = 2, title = "Bravo", date = today, hour = 9, minute = 30, durationMinutes = 60).copy(isDeclinedByMe = true)
        show(MutableTestClock.at(today, LocalTime.of(8, 0)), timed = listOf(under(), declined), visibleDays = 3)
        assertOpaqueOverRed("Bravo")
    }

    @Test
    fun `a cancelled block drawn over another is opaque`() {
        val base = roomDisplayEvent(id = 2, title = "Bravo", date = today, hour = 9, minute = 30, durationMinutes = 60)
        val cancelled = base.copy(event = base.event.copy(status = "CANCELLED"))
        show(MutableTestClock.at(today, LocalTime.of(8, 0)), timed = listOf(under(), cancelled), visibleDays = 3)
        assertOpaqueOverRed("Bravo")
    }

    @Test
    fun `a running block keeps its plain fill`() {
        show(
            MutableTestClock.at(today, LocalTime.of(9, 40)),
            timed = listOf(roomDisplayEvent(id = 2, title = "Bravo", date = today, hour = 9, minute = 30, durationMinutes = 30)),
            visibleDays = 3,
        )
        val pixel = fillPixel("Bravo")
        assertNull(stateOf("Bravo") { it.left >= 0f })
        assertEquals("green of $pixel", blue.green, pixel.green, 0.04f)
    }

    @Test
    fun `an ended all-day chip and an ended strip bar fade, today's chip doesn't`() {
        show(
            MutableTestClock.at(today, LocalTime.NOON),
            allDay = listOf(
                allDayDisplayEvent(id = 1, title = "Day off", date = yesterday),
                allDayDisplayEvent(id = 2, title = "Holiday", date = today),
                multiDayDisplayEvent(id = 3, title = "Overnight trip", startDate = yesterday, endDate = today, allDay = false, startHour = 9),
            ),
        )
        assertEquals("past event", stateOf("Day off"))
        // Two 7-day columns leave the bar no room for its title next to its times, so it is
        // found by its start time.
        assertEquals("past event", stateOf("9:15am"))
        assertNull(stateOf("Holiday"))
        // Same colour, so the faded chip's fill is the lighter one.
        assertTrue("faded chip lighter", fillPixel("Day off").green > fillPixel("Holiday").green + 0.1f)
    }

    @Test
    fun `a multi-day event fades only when it ends`() {
        // Yesterday 14:15 to today 14:00, at today 12:00: both days' parts are running.
        show(
            MutableTestClock.at(today, LocalTime.NOON),
            timed = listOf(multiDayDisplayEvent(id = 1, title = "Conference", startDate = yesterday, endDate = today, allDay = false, startHour = 14)),
        )
        val parts = rule.onAllNodes(hasText("Conference", substring = true)).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f }
        assertEquals("both days' parts shown", 2, parts.size)
        for (part in parts) assertNull(part.config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `an ended event in the overlap sheet reads as past`() {
        val trio = (1L..3L).map { roomDisplayEvent(id = it, title = "Review $it", date = today, hour = 9) }
        show(MutableTestClock.at(today, LocalTime.NOON), timed = trio)
        rule.onNodeWithContentDescription("+1 more").performClick()
        rule.waitForIdle()
        val rows = rule.onAllNodes(hasText(" - ", substring = true)).fetchSemanticsNodes()
            .filter { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains("Review") } }
        assertTrue("sheet rows: ${rows.size}", rows.isNotEmpty())
        for (row in rows) assertEquals("past event", row.config.getOrNull(SemanticsProperties.StateDescription))
    }
}
