package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Drives the real [WeekViewContent] to check how overlapping events share a day column: the Day,
 * 3-day and Week views cascade a later event on top, indented a quarter of the column; the Day
 * view splits up to five side by side where they can't cascade; a covered event's text stops
 * above the event on top; covered events stay reachable by touch, by screen reader and by
 * long-press drag; a drag lands on the next day once the finger crosses the column's edge
 * (indented, the right half of a split, 3-day), and a straight-down drag keeps the day and moves
 * by the drag; the 7-day view draws the compact block. The window is tall enough that 9:00 to
 * 13:00 sits on screen at 60dp an hour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class DayColumnCascadeTest {

    @get:Rule
    val rule = createComposeRule()

    private val date = LocalDate.now()

    /** The shown week's first day: it has a next column whatever today is. */
    private val weekStart = WeekViewUtils.weekPageToStartDate(WeekViewUtils.CENTER_WEEK_PAGE, java.util.Calendar.SUNDAY)

    private fun event(id: Long, title: String, hour: Int, minute: Int = 0, durationMinutes: Int = 60, day: LocalDate = date): DisplayEvent =
        roomDisplayEvent(id = id, title = title, date = day, hour = hour, minute = minute, durationMinutes = durationMinutes)

    private val alpha = event(1, "Alpha", 9, durationMinutes = 90)
    private val bravo = event(2, "Bravo", 9, 30)

    private class Calls {
        val opened = mutableListOf<String>()
        val rescheduled = mutableListOf<String>()
        val drops = mutableListOf<Pair<LocalDate, Int>>()
    }

    private fun show(
        events: List<DisplayEvent>,
        visibleDays: Int,
        hourHeight: Float = 60f,
        rtl: Boolean = false,
    ): Calls {
        val calls = Calls()
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.LayoutDirection(
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                )
            ) {
                MaterialTheme {
                    WeekViewContent(
                        timedEvents = events.toImmutableList(),
                        allDayEvents = persistentListOf(),
                        isLoading = false,
                        error = null,
                        scrollPosition = 0,
                        hourHeight = hourHeight,
                        visibleDays = visibleDays,
                        onDatePickerRequest = {},
                        onScrollPositionChange = {},
                        onEventClick = { calls.opened += it.title },
                        onReschedule = { ev, day, minutes ->
                            calls.rescheduled += ev.title
                            calls.drops += day to minutes
                        },
                        // Just after midnight on the shown week's first day, before every event
                        // here, so none of them fades whatever time the test runs.
                        clock = MutableTestClock.at(weekStart, java.time.LocalTime.of(0, 1)),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
        return calls
    }

    private fun bounds(title: String): Rect = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot

    /** A full-width block is its column less 4dp (1dp at the start, 3dp at the end). */
    private fun columnOf(fullWidth: Rect) = fullWidth.width + 4f

    private fun titleLayout(title: String): TextLayoutResult {
        val node = rule.onAllNodes(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().first()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun assertCascades(visibleDays: Int) {
        show(listOf(alpha, bravo), visibleDays)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        val column = columnOf(a)
        assertEquals("Bravo indented a quarter in ($visibleDays days)", column * 0.25f, b.left - a.left, 1.5f)
        assertEquals("Bravo runs to the column's end ($visibleDays days)", a.right, b.right, 1.5f)
    }

    @Test
    fun `the week view cascades a later overlapping event`() = assertCascades(visibleDays = 7)

    @Test
    fun `the 3-day view cascades a later overlapping event`() = assertCascades(visibleDays = 3)

    @Test
    fun `the day view cascades a later overlapping event`() = assertCascades(visibleDays = 1)

    @Test
    fun `the day view splits five events side by side before the count`() {
        val starts = (1L..6L).map { event(it, "Slot $it", 9) }
        show(starts, visibleDays = 1)
        val widths = (1L..6L).mapNotNull { id ->
            rule.onAllNodes(hasText("Slot $id", substring = true)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.width
        }
        assertEquals("five shown: $widths", 5, widths.size)
        rule.onNodeWithText("+1 more").fetchSemanticsNode()
        val column = rule.onRoot().fetchSemanticsNode().boundsInRoot.width - rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.width
        for (w in widths) assertEquals("a fifth of the column ($column)", column / 5 - 4f, w, 1.5f)
    }

    @Test
    fun `a half-hour gap cascades at 35dp an hour`() {
        // 30 minutes is 17.5dp: enough for the 16dp first line; a 3dp top pad would leave too
        // little.
        show(listOf(alpha, bravo), visibleDays = 7, hourHeight = 35f)
        assertEquals(columnOf(bounds("Alpha")) * 0.25f, bounds("Bravo").left - bounds("Alpha").left, 1.5f)
    }

    @Test
    fun `zoomed out the day view splits the pair side by side`() {
        show(listOf(alpha, bravo), visibleDays = 1, hourHeight = 30f)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        assertEquals(a.width + 4f, b.left - a.left, 1.5f)
    }

    @Test
    fun `zoomed out the week view splits the pair side by side`() {
        // At 30dp an hour, 30 minutes is 15dp, less than Alpha's first title line.
        show(listOf(alpha, bravo), visibleDays = 7, hourHeight = 30f)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        assertEquals(a.width + 4f, b.left - a.left, 1.5f)
    }

    @Test
    fun `right to left the cascade indents from the start edge`() {
        show(listOf(alpha, bravo), visibleDays = 7, rtl = true)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        assertEquals("Bravo's right edge a quarter in from Alpha's", columnOf(a) * 0.25f, a.right - b.right, 1.5f)
        assertEquals(a.left, b.left, 1.5f)
    }

    @Test
    fun `a tap on the covered event's top opens it and a tap on the event on top opens that one`() {
        val calls = show(listOf(alpha, bravo), visibleDays = 7)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        rule.onRoot().performTouchInput { click(Offset(a.center.x, a.top + 8f)) }
        rule.onRoot().performTouchInput { click(b.center) }
        rule.waitForIdle()
        assertEquals(listOf("Alpha", "Bravo"), calls.opened)
    }

    @Test
    fun `a screen reader opens the covered event, a separate item from the one on top`() {
        val calls = show(listOf(alpha, bravo), visibleDays = 7)
        val items = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes()
            .map { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { t -> t.text } }
        assertTrue("separate items: $items", items.any { it.contains("Alpha") && !it.contains("Bravo") })
        assertTrue("separate items: $items", items.any { it.contains("Bravo") && !it.contains("Alpha") })
        rule.onNodeWithText("Alpha").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Alpha"), calls.opened)
    }

    @Test
    fun `a long-press drag from the covered event's top moves that event`() {
        val calls = show(listOf(alpha, bravo), visibleDays = 7)
        val a = bounds("Alpha")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(Offset(a.center.x, a.top + 8f))
            advanceEventTime(700)
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf("Alpha"), calls.rescheduled)
    }

    @Test
    fun `the week view draws 11sp titles`() {
        show(listOf(alpha), visibleDays = 7)
        assertEquals(11.sp, titleLayout("Alpha").layoutInput.style.fontSize)
    }

    @Test
    fun `the 3-day view keeps 12sp titles`() {
        show(listOf(alpha), visibleDays = 3)
        assertEquals(12.sp, titleLayout("Alpha").layoutInput.style.fontSize)
    }

    @Test
    fun `an event drawn on top is outlined in the background colour`() {
        show(listOf(alpha, bravo), visibleDays = 3)
        val a = bounds("Alpha")
        val b = bounds("Bravo")
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val outline = pixels[b.left.toInt(), (b.top + 20f).toInt()]
        val fill = pixels[(b.left + 4f).toInt(), (b.top + 20f).toInt()]
        val plainEdge = pixels[a.left.toInt(), (a.top + 10f).toInt()]
        assertTrue("outline $outline is light", outline.red > 0.9f && outline.green > 0.9f)
        assertTrue("fill $fill is the event colour", fill.blue > fill.red + 0.2f)
        assertTrue("an event on nothing has no outline: $plainEdge", plainEdge.blue > plainEdge.red + 0.2f)
    }

    @Test
    fun `a split's count stays inside its day column`() {
        val base = event(3, "Base", 9, durationMinutes = 120)
        val inner = (4L..6L).map { event(it, "Inner $it", 9, 30) }
        show(listOf(base) + inner, visibleDays = 7)
        val column = bounds("Base")
        // The 7-day badge shows "+1" and tells screen readers "+1 more".
        val badge = rule.onNodeWithContentDescription("+1 more").fetchSemanticsNode().boundsInRoot
        assertTrue("badge ends at ${badge.right}, column at ${column.right + 1f}", badge.right <= column.right + 1f)
    }

    @Test
    fun `a crowded split shows its count at the split, not the column's edge`() {
        // Charlie 9:00-13:00 holds three 10:00 events: they cascade one level and split two
        // ways, the third behind the count.
        val charlie = event(3, "Charlie", 9, durationMinutes = 240)
        val inner = (4L..6L).map { event(it, "Inner $it", 10) }
        show(listOf(charlie) + inner, visibleDays = 7)
        val c = bounds("Charlie")
        val badge = rule.onNodeWithContentDescription("+1 more").fetchSemanticsNode().boundsInRoot
        assertTrue("badge at ${badge.left}, split starts at ${c.left + columnOf(c) * 0.25f}", badge.left >= c.left + columnOf(c) * 0.25f - 1f)
    }

    private val longTitle = "Alpha planning with the whole product team and the guests"
    private val longAlpha = event(1, longTitle, 9, durationMinutes = 90)

    /** Returns the root y where the last drawn line of the title containing [title] ends. */
    private fun lastTitleLineBottom(title: String): Float {
        val node = rule.onAllNodes(hasText(title, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().first()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        return node.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
    }

    private fun assertTextStopsAbove(visibleDays: Int) {
        show(listOf(longAlpha, bravo), visibleDays)
        val a = rule.onAllNodes(hasText(longTitle, substring = true)).fetchSemanticsNodes().first().boundsInRoot
        val b = bounds("Bravo")
        assertTrue("Bravo cascades ($visibleDays days)", b.left > a.left + 1f)
        val bottom = lastTitleLineBottom(longTitle)
        assertTrue("Alpha's text ends at $bottom, Bravo starts at ${b.top} ($visibleDays days)", bottom <= b.top + 0.5f)
    }

    @Test
    fun `a covered event's text stops above the event on top in the week view`() = assertTextStopsAbove(visibleDays = 7)

    @Test
    fun `a covered event's text stops above the event on top in the day view`() = assertTextStopsAbove(visibleDays = 1)

    @Test
    fun `with nothing on top the same title wraps past where the other event would start`() {
        show(listOf(longAlpha), visibleDays = 7)
        val a = rule.onAllNodes(hasText(longTitle, substring = true)).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("wraps below 9:30", lastTitleLineBottom(longTitle) > a.top + 30f)
    }

    private fun firstColumnRight(): Float =
        rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.right + columnOf(bounds("Alpha"))

    /** Long-presses [title] 4px in from its start and 2px below its top, then moves by [by]. */
    private fun longPressDrag(title: String, by: Offset) {
        val start = bounds(title).let { Offset(it.left + 4f, it.top + 2f) }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(by / 2f)
            moveBy(by / 2f)
            up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    /** Drags [title] sideways so the finger ends [past] px beyond the first column's right edge. */
    private fun dragAcrossFirstColumnEdge(title: String, past: Float) {
        val fingerX = bounds(title).left + 4f
        longPressDrag(title, Offset(firstColumnRight() + past - fingerX, 0f))
    }

    private fun assertIndentedDragCrosses(visibleDays: Int, day: LocalDate) {
        val calls = show(listOf(event(1, "Alpha", 9, durationMinutes = 90, day = day), event(2, "Bravo", 9, 30, day = day)), visibleDays)
        assertTrue("Bravo is indented", bounds("Bravo").left > bounds("Alpha").left + 10f)
        dragAcrossFirstColumnEdge("Bravo", past = 3f)
        assertEquals("dropped on the next day ($visibleDays days)", listOf(day.plusDays(1)), calls.drops.map { it.first })
    }

    @Test
    fun `dragging an indented event across the column edge moves it to the next day`() =
        assertIndentedDragCrosses(visibleDays = 7, day = weekStart)

    @Test
    fun `dragging an indented event across the column edge in the 3-day view moves it to the next day`() =
        assertIndentedDragCrosses(visibleDays = 3, day = date)

    @Test
    fun `an indented event dragged short of the column edge stays on its day`() {
        val calls = show(listOf(event(1, "Alpha", 9, durationMinutes = 90, day = weekStart), event(2, "Bravo", 9, 30, day = weekStart)), visibleDays = 7)
        dragAcrossFirstColumnEdge("Bravo", past = -3f)
        assertEquals(listOf(weekStart), calls.drops.map { it.first })
    }

    @Test
    fun `dragging the right-hand event of a split across the column edge moves it to the next day`() {
        val calls = show(listOf(event(1, "Alpha", 9, day = weekStart), event(2, "Bravo", 9, day = weekStart)), visibleDays = 7)
        assertTrue("Bravo is the right half", bounds("Bravo").left > bounds("Alpha").right)
        // Alpha is half the column here, so the column's edge comes from Bravo's right edge,
        // which stops 3px short of it.
        val edge = bounds("Bravo").right + 3f
        longPressDrag("Bravo", Offset(edge + 3f - (bounds("Bravo").left + 4f), 0f))
        assertEquals(listOf(weekStart.plusDays(1)), calls.drops.map { it.first })
    }

    private fun assertStraightDownDrop(title: String) {
        val events = listOf(event(1, "Alpha", 9, durationMinutes = 90, day = weekStart), event(2, "Bravo", 9, 30, day = weekStart))
        val calls = show(events, visibleDays = 7)
        val event = events.single { it.title == title }
        val start = java.time.Instant.ofEpochMilli(event.startTs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        val startMinutes = start.hour * 60 + start.minute
        val durationMinutes = ((event.endTs - event.startTs) / 60000).toInt()
        longPressDrag(title, Offset(0f, 60f))
        // The drop puts the event's start where the finger is: its start, plus the 2px grab below
        // the block's top, plus the drag, at 1px a minute (60dp an hour at mdpi).
        val expected = WeekViewUtils.clampDragStartMinutes(WeekViewUtils.snapToQuarterHour(startMinutes + 2 + 60), durationMinutes)
        assertEquals(listOf(weekStart to expected), calls.drops)
    }

    @Test
    fun `a plain event dragged straight down keeps its day and moves by the drag`() = assertStraightDownDrop("Alpha")

    @Test
    fun `an indented event dragged straight down keeps its day and moves by the drag`() = assertStraightDownDrop("Bravo")
}
