package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.ui.components.DEEMPHASIZED_ALPHA
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.Calendar as JavaCalendar

/**
 * Drives the real [FullHeightMonthGrid] the way the month view shows it: day cells sized from
 * the grid's height, rows fitted to each cell, titles cut at the edge, a corner count of what
 * doesn't fit, taps anywhere in a cell, and nothing drawn outside its week row at large font
 * sizes and zoom.
 *
 * The drawn text is hidden from screen readers but stays in the semantics tree, so text is read
 * from the unmerged tree; cells are found by their test tags. At w360dp-h720dp-mdpi a 5-week
 * month gives 144px week rows, a 24px date strip and 18px slots 1px apart: 6 rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class FullHeightMonthGridTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneId.of("America/New_York")
    private lateinit var savedZone: TimeZone

    private val busyDay = LocalDate.of(2026, 10, 14)

    @Before
    fun setup() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedZone)

    private fun timed(id: Long, date: LocalDate, hour: Int, title: String, declined: Boolean = false, status: String = "CONFIRMED") =
        PlannerTestEvents.room(
            id = id, startTs = at(date, hour, zone = zone), endTs = at(date, hour, zone = zone) + PlannerTestEvents.HOUR,
            zone = zone, title = title, declined = declined, status = status,
        )

    private fun allDay(id: Long, date: LocalDate, days: Int, title: String, color: Int? = null) =
        PlannerTestEvents.roomAllDay(id, date, zone, days = days)
            .let { it.copy(event = it.event.copy(title = title, color = color)) }

    private fun busyEvents(count: Int = 8) =
        (0 until count).map { timed(100L + it, busyDay, 8 + it, "Busy event number $it with a long title") }

    private fun mapOf(events: List<DisplayEvent>, year: Int, month0: Int): ImmutableMap<Int, ImmutableList<DisplayEvent>> {
        val first = LocalDate.of(year, month0 + 1, 1)
        val codes = generateSequence(first.minusDays(14)) { it.plusDays(1) }
            .takeWhile { it.isBefore(first.plusMonths(1).plusDays(14)) }
            .map { PlannerTestEvents.dayCode(it) }.toList()
        return PlannerTestEvents.bucketByDay(codes, events)
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.toImmutableList() }
            .toImmutableMap()
    }

    /** Shows the grid filling the screen (or [gridHeight]) and returns the days it opened. */
    private fun show(
        events: List<DisplayEvent> = emptyList(),
        year: Int = 2026,
        month0: Int = JavaCalendar.OCTOBER,
        fontScale: Float = 1f,
        zoom: Float = 1f,
        rtl: Boolean = false,
        gridHeight: Dp? = null,
        unbounded: Boolean = false,
        holder: MutableState<List<DisplayEvent>>? = null,
        today: LocalDate = LocalDate.of(2026, 10, 16),
    ): MutableList<LocalDate> {
        val opened = mutableListOf<LocalDate>()
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then
                    DeviceConfigurationOverride.LayoutDirection(
                        if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                    )
            ) {
                MaterialTheme {
                    val column = when {
                        unbounded -> Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        gridHeight != null -> Modifier.fillMaxWidth().height(gridHeight)
                        else -> Modifier.fillMaxSize()
                    }
                    Column(modifier = column) {
                        FullHeightMonthGrid(
                            year = year,
                            month = month0,
                            selectedDate = 0L,
                            monthEventsMap = mapOf(holder?.value ?: events, year, month0),
                            onDayTap = { _, ms -> opened += Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() },
                            firstDayOfWeekPref = JavaCalendar.SUNDAY,
                            showWeekNumbers = false,
                            showEventEmojis = false,
                            nowMs = at(today, 12, zone = zone),
                            todayDayCode = PlannerTestEvents.dayCode(today),
                            zoom = zoom,
                            modifier = if (unbounded) Modifier else Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return opened
    }

    private fun cellTag(date: LocalDate) = "month_cell_${PlannerTestEvents.dayCode(date)}"
    private fun cell(date: LocalDate): Rect = rule.onNodeWithTag(cellTag(date)).fetchSemanticsNode().boundsInRoot

    private fun allCells(): List<Rect> =
        rule.onAllNodes(SemanticsMatcher("month cell") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("month_cell_") == true })
            .fetchSemanticsNodes().map { it.boundsInRoot }

    private fun placedTexts(): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private fun textOf(node: SemanticsNode) = node.config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The drawn top-left of [node], before any clipping by its parents. */
    private fun origin(node: SemanticsNode) = node.positionInRoot

    /** Placed texts whose unclipped origin sits inside [date]'s cell. */
    private fun textsIn(date: LocalDate): List<SemanticsNode> {
        val bounds = cell(date)
        return placedTexts().filter {
            val o = origin(it)
            o.x + 1 >= bounds.left && o.x + 1 <= bounds.right && o.y + 1 >= bounds.top && o.y + 1 <= bounds.bottom
        }
    }

    private fun titlesIn(date: LocalDate, prefix: String) = textsIn(date).filter { textOf(it).startsWith(prefix) }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun `a busy day shows as many rows as its cell fits, more than three`() {
        show(busyEvents())
        assertEquals(6, titlesIn(busyDay, "Busy event").size)
    }

    @Test
    fun `zooming out fits more rows`() {
        // At 0.85 a slot is 15px and the strip 20px: 7 rows.
        show(busyEvents(), zoom = 0.85f)
        assertEquals(7, titlesIn(busyDay, "Busy event").size)
    }

    @Test
    fun `the largest zoom fits three rows`() {
        // At 1.6 a slot is 29px and the strip 38px: 3 rows.
        show(busyEvents(), zoom = 1.6f)
        assertEquals(3, titlesIn(busyDay, "Busy event").size)
    }

    @Test
    fun `a long title is one line cut at the edge with no ellipsis`() {
        val title = "Soccer game at field three, bring orange slices and the folding chairs"
        show(listOf(timed(1, busyDay.plusDays(1), 9, title)))
        val node = titlesIn(busyDay.plusDays(1), "Soccer").single()
        val layout = layoutOf(node)
        assertEquals(1, layout.lineCount)
        assertFalse("ellipsized", layout.isLineEllipsized(0))
        val width = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue("line $width is wider than its cell ${cell(busyDay.plusDays(1)).width}", width > cell(busyDay.plusDays(1)).width)
    }

    @Test
    fun `a right to left title in a left to right layout shows its first letter`() {
        val title = "שיעור פסנתר ארוך מאוד עם הרבה מילים נוספות שלא נכנסות"
        val day = busyDay.plusDays(1)
        show(listOf(timed(1, day, 9, title)))
        val node = placedTexts().single { textOf(it) == title }
        val first = layoutOf(node).getBoundingBox(0)
        val bounds = cell(day)
        val firstLeft = node.positionInRoot.x + first.left
        val firstRight = node.positionInRoot.x + first.right
        assertTrue("first letter $firstLeft..$firstRight outside cell $bounds", firstLeft >= bounds.left - 0.5f && firstRight <= bounds.right + 0.5f)
    }

    @Test
    fun `a full day counts the rest in its bottom-end corner, level with the last row`() {
        show(busyEvents())
        val count = textsIn(busyDay).single { textOf(it) == "+2" }
        val bounds = cell(busyDay)
        val countBox = count.boundsInRoot
        assertTrue("count ends ${bounds.right - countBox.right}px before the cell's end", bounds.right - countBox.right <= 3f)
        val lastRow = titlesIn(busyDay, "Busy event").maxBy { it.boundsInRoot.top }.boundsInRoot
        assertTrue("count $countBox beside last row $lastRow", countBox.center.y in lastRow.top..lastRow.bottom)
    }

    @Test
    fun `the row beside the count is narrower and the rows above keep their width`() {
        show(busyEvents())
        val rows = titlesIn(busyDay, "Busy event").sortedBy { it.boundsInRoot.top }.map { it.boundsInRoot.width }
        assertEquals(6, rows.size)
        assertTrue("rows above share one width: $rows", rows.dropLast(1).distinct().size == 1)
        assertTrue("last row ${rows.last()} narrower than ${rows.first()}", rows.last() < rows.first() - 4f)
    }

    @Test
    fun `nothing hidden shows no count`() {
        show(busyEvents(count = 6))
        assertTrue(textsIn(busyDay).none { textOf(it).startsWith("+") })
        assertEquals(6, titlesIn(busyDay, "Busy event").size)
    }

    @Test
    fun `a bar left out of the lanes counts on every day it covers`() {
        val start = LocalDate.of(2026, 10, 20)
        show((0 until 4).map { allDay(10L + it, start, days = 4, title = "Trip $it") })
        for (offset in 0..3) {
            val day = start.plusDays(offset.toLong())
            assertEquals("count on $day", listOf("+1"), textsIn(day).map { textOf(it) }.filter { it.startsWith("+") })
        }
        assertTrue(textsIn(start.minusDays(1)).none { textOf(it).startsWith("+") })
    }

    @Test
    fun `three bars over a day leave that day's own events visible`() {
        val thu = LocalDate.of(2026, 10, 8)
        show(
            listOf(
                allDay(1, thu.minusDays(2), days = 4, title = "Lisbon trip"),
                allDay(2, thu.minusDays(1), days = 2, title = "Conference"),
                allDay(3, thu, days = 3, title = "Mom visiting"),
                timed(4, thu, 15, "Dentist checkup"),
            )
        )
        assertEquals(1, titlesIn(thu, "Dentist").size)
        assertTrue(textsIn(thu).none { textOf(it).startsWith("+") })
    }

    @Test
    fun `a tap below the rows opens that day`() {
        val day = LocalDate.of(2026, 10, 27)
        val opened = show(listOf(timed(1, day, 9, "Flu shot")))
        val bounds = cell(day)
        rule.onRoot().performTouchInput { click(Offset(bounds.center.x, bounds.bottom - 4f)) }
        rule.waitForIdle()
        assertEquals(listOf(day), opened)
    }

    @Test
    fun `a tap on a bar opens the day under the finger`() = assertBarTapOpens(rtl = false)

    @Test
    fun `right to left a tap on a bar opens the day under the finger`() = assertBarTapOpens(rtl = true)

    private fun assertBarTapOpens(rtl: Boolean) {
        val start = LocalDate.of(2026, 10, 6)
        val opened = show(listOf(allDay(1, start, days = 4, title = "Lisbon trip")), rtl = rtl)
        val target = start.plusDays(2)
        val bar = placedTexts().single { textOf(it) == "Lisbon trip" }.boundsInRoot
        val bounds = cell(target)
        rule.onRoot().performTouchInput { click(Offset(bounds.center.x, bar.center.y)) }
        rule.waitForIdle()
        assertEquals(listOf(target), opened)
    }

    @Test
    fun `a day that loses events while shown keeps working`() {
        val holder = mutableStateOf<List<DisplayEvent>>(busyEvents())
        show(holder = holder)
        assertEquals(6, titlesIn(busyDay, "Busy event").size)
        holder.value = busyEvents(count = 1)
        rule.waitForIdle()
        assertEquals(1, titlesIn(busyDay, "Busy event").size)
        assertTrue(textsIn(busyDay).none { textOf(it).startsWith("+") })
    }

    @Test
    fun `inside a vertical scroll the grid lays out without crashing`() {
        // The weighted week rows get no height there, so nothing is drawn, but every cell exists.
        show(busyEvents(), unbounded = true)
        rule.onNodeWithTag(cellTag(busyDay)).assertExists()
    }

    // -------- One screen-reader item per day --------

    private fun busyDescription(todayWord: Boolean) = buildList {
        add("Wednesday, October 14")
        if (todayWord) add("Today")
        add("8 events")
        for (n in 0 until 6) add("Busy event number $n with a long title")
        add("+2 more")
    }.joinToString(", ")

    @Test
    fun `a day reads as one item with its date, count, shown titles and hidden count`() {
        show(busyEvents())
        rule.onNodeWithTag(cellTag(busyDay)).assertContentDescriptionEquals(busyDescription(todayWord = false))
    }

    @Test
    fun `today's cell says Today`() {
        show(busyEvents(), today = busyDay)
        rule.onNodeWithTag(cellTag(busyDay)).assertContentDescriptionEquals(busyDescription(todayWord = true))
    }

    @Test
    fun `an empty day reads its date only`() {
        show(busyEvents())
        rule.onNodeWithTag(cellTag(LocalDate.of(2026, 10, 4))).assertContentDescriptionEquals("Sunday, October 4")
    }

    @Test
    fun `a declined event's title says so and a bar is read on every day it covers`() {
        val day = LocalDate.of(2026, 10, 21)
        show(listOf(allDay(1, day.minusDays(1), days = 3, title = "Offsite"), timed(2, day, 10, "Review", declined = true)))
        rule.onNodeWithTag(cellTag(day)).assertContentDescriptionEquals("Wednesday, October 21, 2 events, Offsite, Review, declined")
        rule.onNodeWithTag(cellTag(day.minusDays(1))).assertContentDescriptionEquals("Tuesday, October 20, 1 event, Offsite")
    }

    @Test
    fun `a cancelled event's title says so`() {
        val day = LocalDate.of(2026, 10, 22)
        show(listOf(timed(1, day, 10, "Choir", status = "CANCELLED")))
        rule.onNodeWithTag(cellTag(day)).assertContentDescriptionEquals("Thursday, October 22, 1 event, Choir, cancelled")
    }

    @Test
    fun `a cell's click tells a screen reader it opens the day`() {
        show(busyEvents())
        val action = rule.onNodeWithTag(cellTag(busyDay)).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("Show this day's events", action.label)
    }

    @Test
    fun `every drawn title, date and count is hidden from screen readers`() {
        show(busyEvents() + allDay(1, LocalDate.of(2026, 10, 20), days = 3, title = "Offsite"))
        val texts = placedTexts()
        assertTrue("texts drawn", texts.size > 30)
        val shown = texts.filterNot { it.config.contains(SemanticsProperties.HideFromAccessibility) }.map { textOf(it) }
        assertEquals(emptyList<String>(), shown)
    }

    // -------- Past fade --------

    /**
     * Returns the colour at the inside start edge of the chip titled [title], clear of its text.
     */
    private fun chipEdgeColor(title: String): androidx.compose.ui.graphics.Color {
        val chip = placedTexts().single { textOf(it) == title }.boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        return pixels[(chip.left - 2).toInt(), chip.center.y.toInt()]
    }

    @Test
    fun `a past event fades and a coming one doesn't`() {
        val red = 0xFFFF0000.toInt()
        show(
            listOf(
                allDay(1, LocalDate.of(2026, 10, 13), days = 1, title = "Past", color = red),
                allDay(2, LocalDate.of(2026, 10, 20), days = 1, title = "Coming", color = red),
            )
        )
        val past = chipEdgeColor("Past")
        val coming = chipEdgeColor("Coming")
        assertTrue("coming chip is full red: $coming", coming.red > 0.9f && coming.green < 0.1f)
        // Red at DEEMPHASIZED_ALPHA over the white surface: green and blue are
        // 1 - DEEMPHASIZED_ALPHA.
        val wantGreen = 1 - DEEMPHASIZED_ALPHA
        assertTrue(
            "past chip is red at DEEMPHASIZED_ALPHA on the surface: $past",
            past.red > 0.9f && past.green in (wantGreen - 0.05f)..(wantGreen + 0.05f),
        )
    }

    // -------- Nothing outside its week row --------

    /** Asserts every drawn text, unclipped, ends inside the week row it starts in. */
    private fun assertEveryRowFitsWhole(label: String) {
        val cells = allCells()
        assertTrue("$label: cells found", cells.size >= 28)
        for (node in placedTexts()) {
            val o = origin(node)
            val home = cells.firstOrNull { o.x + 1 in it.left..it.right && o.y + 1 in it.top..it.bottom } ?: continue
            assertTrue(
                "$label: '${textOf(node)}' ends at ${o.y + node.size.height}, past its week row ${home.bottom}",
                o.y + node.size.height <= home.bottom + 0.5f,
            )
        }
    }

    private fun monthMix(year: Int, month0: Int): List<DisplayEvent> {
        val first = LocalDate.of(year, month0 + 1, 1)
        val busy = first.plusDays(9)
        return (0 until 8).map { timed(200L + it, busy, 8 + it, "Mix event $it with a long title") } +
            allDay(300, first.plusDays(3), days = 3, title = "A long trip away from home") +
            allDay(301, first.plusDays(4), days = 2, title = "Conference")
    }

    @Test
    fun `no row is cut in a 4-week month at the default font`() =
        show(monthMix(2026, JavaCalendar.FEBRUARY), month0 = JavaCalendar.FEBRUARY).let { assertEveryRowFitsWhole("feb") }

    @Test
    fun `no row is cut in a 5-week month at font scale 1_3`() =
        show(monthMix(2026, JavaCalendar.OCTOBER), fontScale = 1.3f).let { assertEveryRowFitsWhole("oct 1.3") }

    @Test
    fun `no row is cut in a 6-week month at font scale 2`() =
        show(monthMix(2026, JavaCalendar.AUGUST), month0 = JavaCalendar.AUGUST, fontScale = 2f).let { assertEveryRowFitsWhole("aug 2") }

    @Test
    fun `no row is cut in a 6-week month at font scale 2 with the largest zoom`() =
        show(monthMix(2026, JavaCalendar.AUGUST), month0 = JavaCalendar.AUGUST, fontScale = 2f, zoom = 1.6f)
            .let { assertEveryRowFitsWhole("aug 2 x1.6") }

    @Test
    fun `no row is cut right to left at the smallest zoom`() =
        show(monthMix(2026, JavaCalendar.OCTOBER), rtl = true, zoom = 0.85f).let { assertEveryRowFitsWhole("oct rtl") }

    @Test
    fun `no row is cut in a 4-week month right to left at font scale 2 with the largest zoom`() =
        show(monthMix(2026, JavaCalendar.FEBRUARY), month0 = JavaCalendar.FEBRUARY, rtl = true, fontScale = 2f, zoom = 1.6f)
            .let { assertEveryRowFitsWhole("feb rtl 2 x1.6") }

    /**
     * Asserts a row too tall for its week paints nothing into the next week: the slot keeps its
     * full height from the row's top, so only the week row's clip stops its letters. The check
     * looks for dark pixels at the top of the next week's date strip, above its number. Removing
     * that clip fails the landscape case; the short-week case checks the same outcome at another
     * size.
     */
    private fun assertClippedAtWeekEdge(fontScale: Float, zoom: Float, gridHeight: Dp?) {
        val chipDay = LocalDate.of(2026, 8, 2)
        show(
            listOf(timed(1, chipDay, 9, "WgjpqyWgjpqy")),
            month0 = JavaCalendar.AUGUST, fontScale = fontScale, zoom = zoom, gridHeight = gridHeight,
        )
        val chip = placedTexts().single { textOf(it) == "WgjpqyWgjpqy" }
        val week = cell(chipDay)
        assertTrue(
            "the case overflows: chip text ends at ${origin(chip).y + chip.size.height}, week ends ${week.bottom}",
            origin(chip).y + chip.size.height > week.bottom + 4f,
        )
        val below = cell(chipDay.plusDays(7))
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val dark = mutableListOf<String>()
        for (x in (below.left + 3).toInt()..(below.right - 3).toInt()) {
            for (y in (below.top + 1).toInt()..(below.top + 6).toInt()) {
                val c = pixels[x, y]
                if (c.red + c.green + c.blue < 1.5f) dark += "($x,$y)"
            }
        }
        assertTrue("letters painted into the next week at $dark", dark.isEmpty())
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land-mdpi")
    fun `in landscape at font scale 2 the single row is cut at the week edge`() =
        assertClippedAtWeekEdge(fontScale = 2f, zoom = 1.3f, gridHeight = null)

    @Test
    fun `at font scale 2 with the largest zoom in short weeks the row is cut at the week edge`() =
        assertClippedAtWeekEdge(fontScale = 2f, zoom = 1.6f, gridHeight = (95 * 6).dp)
}
