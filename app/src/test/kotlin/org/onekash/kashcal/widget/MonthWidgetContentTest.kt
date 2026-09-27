package org.onekash.kashcal.widget

import android.content.res.Resources
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasNoClickAction
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.MonthGrid
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthWidgetContentTest {

    private lateinit var originalLocale: Locale
    private lateinit var resources: Resources

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // ==================== extractDotColors ====================

    @Test
    fun `extractDotColors returns empty list for no events`() {
        assertEquals(emptyList<Int>(), extractDotColors(emptyList()))
    }

    @Test
    fun `extractDotColors returns single color for one event`() {
        val events = listOf(createWidgetEvent(calendarColor = 0xFF0000))
        assertEquals(listOf(0xFF0000), extractDotColors(events))
    }

    @Test
    fun `extractDotColors returns unique colors from multiple events`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), extractDotColors(events))
    }

    @Test
    fun `extractDotColors caps at maxDots default 3`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF),
            createWidgetEvent(calendarColor = 0xFFFF00),
            createWidgetEvent(calendarColor = 0xFF00FF)
        )
        assertEquals(3, extractDotColors(events).size)
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), extractDotColors(events))
    }

    @Test
    fun `extractDotColors deduplicates same color`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00), extractDotColors(events))
    }

    @Test
    fun `extractDotColors with custom maxDots`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00), extractDotColors(events, maxDots = 2))
    }

    // ==================== getDayOfWeekHeaders ====================

    // Headers use CLDR NARROW (single letter) so they render at the same size as the day
    // numbers below. In the English test locale that is S M T W T F S; the repeats (Sun/Sat
    // both "S", Tue/Thu both "T") are told apart by column position.
    @Test
    fun `getDayOfWeekHeaders Sunday start returns single-letter names Sunday first`() {
        val headers = getDayOfWeekHeaders(Calendar.SUNDAY)
        assertEquals(7, headers.size)
        assertEquals("S", headers[0]) // Sunday
        assertEquals("M", headers[1]) // Monday
        assertEquals("S", headers[6]) // Saturday
    }

    @Test
    fun `getDayOfWeekHeaders Monday start returns single-letter names Monday first`() {
        val headers = getDayOfWeekHeaders(Calendar.MONDAY)
        assertEquals(7, headers.size)
        assertEquals("M", headers[0]) // Monday
        assertEquals("T", headers[1]) // Tuesday
        assertEquals("S", headers[6]) // Sunday
    }

    // Full localized day names are the accessibility labels behind the single-letter headers,
    // so TalkBack announces "Sunday" or "Monday", not an ambiguous letter.
    @Test
    fun `dayOfWeekAccessibilityLabels Sunday start returns full names Sunday first`() {
        val labels = dayOfWeekAccessibilityLabels(Calendar.SUNDAY)
        assertEquals(7, labels.size)
        assertEquals("Sunday", labels[0])
        assertEquals("Monday", labels[1])
        assertEquals("Saturday", labels[6])
    }

    @Test
    fun `dayOfWeekAccessibilityLabels Monday start returns full names Monday first`() {
        val labels = dayOfWeekAccessibilityLabels(Calendar.MONDAY)
        assertEquals(7, labels.size)
        assertEquals("Monday", labels[0])
        assertEquals("Tuesday", labels[1])
        assertEquals("Sunday", labels[6])
    }

    // ==================== weekNumberGutterLabels ====================

    @Test
    fun `weekNumberGutterLabels is empty when the setting is off`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.MONDAY) // January 2026
        assertEquals(emptyList<String>(), weekNumberGutterLabels(grid, showWeekNumbers = false))
    }

    @Test
    fun `weekNumberGutterLabels has one label per visible week when on`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.MONDAY)
        val labels = weekNumberGutterLabels(grid, showWeekNumbers = true)
        // One gutter cell per rendered week, never the padded 6 rows when the month spans fewer.
        assertEquals(visibleWeeks(grid).size, labels.size)
    }

    @Test
    fun `weekNumberGutterLabels reads each visible week's first-cell week number`() {
        val grid = MonthGrid.compute(2026, 0, Calendar.MONDAY)
        val expected = visibleWeeks(grid).map { it.first().weekNumber.toString() }
        assertEquals(expected, weekNumberGutterLabels(grid, showWeekNumbers = true))
    }

    // ==================== formatMonthHeader ====================

    @Test
    fun `formatMonthHeader omits year and uses full name when same as current year`() {
        val result = formatMonthHeader(year = 2026, month0 = 3, currentYear = 2026) // April
        assertEquals("April", result)
    }

    @Test
    fun `formatMonthHeader includes year when different from current year`() {
        val result = formatMonthHeader(year = 2025, month0 = 8, currentYear = 2026) // Sep 2025
        assertEquals("Sep 2025", result)
    }

    @Test
    fun `formatMonthHeader handles January correctly`() {
        val result = formatMonthHeader(year = 2027, month0 = 0, currentYear = 2026) // Jan 2027
        assertEquals("Jan 2027", result)
    }

    @Test
    fun `formatMonthHeader handles December current year`() {
        val result = formatMonthHeader(year = 2026, month0 = 11, currentYear = 2026) // December
        assertEquals("December", result)
    }

    // ==================== buildAccessibilityDescription (dayCode) ====================

    @Test
    fun `buildAccessibilityDescription dayCode overload for InDate previous month`() {
        // Feb 28 dayCode when viewing March grid
        val desc = buildAccessibilityDescription(resources, 20260228, 0)
        assertEquals("February 28, no events", desc)
    }

    @Test
    fun `buildAccessibilityDescription dayCode overload for OutDate next month`() {
        // April 1 dayCode when viewing March grid
        val desc = buildAccessibilityDescription(resources, 20260401, 2)
        assertEquals("April 1, 2 events", desc)
    }

    @Test
    fun `buildAccessibilityDescription dayCode overload for year boundary`() {
        // January 2 dayCode when viewing December 2025 grid
        val desc = buildAccessibilityDescription(resources, 20260102, 1)
        assertEquals("January 2, 1 event", desc)
    }

    // ==================== buildAccessibilityDescription (year, month, day) ====================

    @Test
    fun `buildAccessibilityDescription singular event`() {
        val desc = buildAccessibilityDescription(resources, 2026, 2, 15, 1) // March (0-indexed)
        assertEquals("March 15, 1 event", desc)
    }

    @Test
    fun `buildAccessibilityDescription plural events`() {
        val desc = buildAccessibilityDescription(resources, 2026, 2, 15, 3) // March
        assertEquals("March 15, 3 events", desc)
    }

    @Test
    fun `buildAccessibilityDescription zero events`() {
        val desc = buildAccessibilityDescription(resources, 2026, 2, 15, 0)
        assertEquals("March 15, no events", desc)
    }

    // ==================== maxEventRows ====================

    @Test
    fun `maxEventRows returns 0 when not even one row fits below the day number`() {
        // 19 (number) + 16 (row) + 1 (its leading gap) = 36dp minimum; below that it returns 0,
        // not a row the number would clip.
        assertEquals(0, maxEventRows(35f))
    }

    @Test
    fun `maxEventRows fits exactly one row at the minimum height`() {
        assertEquals(1, maxEventRows(36f))
    }

    @Test
    fun `maxEventRows fits two rows once the second row and its gap clear the number`() {
        // 19 (number) + 2 * (16 row + 1 gap) = 53dp. Every slot row pays its leading gap, so
        // the second row costs a full 17dp, not 16.
        assertEquals(2, maxEventRows(53f))
    }

    @Test
    fun `maxEventRows caps at MAX_EVENT_ROWS on tall cells`() {
        assertEquals(MAX_EVENT_ROWS, maxEventRows(200f))
    }

    @Test
    fun `maxEventRows fits fewer rows at a larger font scale`() {
        // A cell that fits two rows at font scale 1.0 fits none at 1.5: the scaled number
        // (28.5dp) plus one scaled row and its gap (24 + 1dp) overrun the 53dp cell, so it
        // returns 0, not a row clipped off the bottom.
        assertEquals(2, maxEventRows(53f, fontScale = 1.0f))
        assertEquals(0, maxEventRows(53f, fontScale = 1.5f))
    }

    // ==================== minWidgetHeightForTitlesDp ====================

    @Test
    fun `minWidgetHeightForTitlesDp derives the one-row threshold from real element heights`() {
        // Header 40 + day-of-week 21 + 6 weeks * (19 number + 1 * (16 row + 1 gap)) = 277dp,
        // the height a 6-week one-row grid renders at, so a widget at the threshold fits its row
        // unclipped. It is under the placed 4x4 default (304dp), so a newly placed widget shows
        // titles and only the smallest resizes fall to dots.
        assertEquals(277f, minWidgetHeightForTitlesDp(TITLES_MIN_ROWS), 0.001f)
    }

    @Test
    fun `minWidgetHeightForTitlesDp for two rows still matches the six-week two-row height`() {
        // Checks the formula apart from TITLES_MIN_ROWS: 40 + 21 + 6 * (19 + 2*17).
        assertEquals(379f, minWidgetHeightForTitlesDp(2), 0.001f)
    }

    @Test
    fun `minWidgetHeightForTitlesDp needs more room for more rows`() {
        assertTrue(minWidgetHeightForTitlesDp(2) > minWidgetHeightForTitlesDp(1))
    }

    @Test
    fun `minWidgetHeightForTitlesDp rises with the font scale`() {
        // A larger system font grows the text, so titles need a taller widget; a scaled-up
        // widget shows dots until it is tall enough for unclipped titles.
        assertTrue(minWidgetHeightForTitlesDp(2, fontScale = 1.5f) > minWidgetHeightForTitlesDp(2, fontScale = 1.0f))
    }

    @Test
    fun `MAX_EVENT_ROWS stays small so the widget never exhausts its view-ID pool`() {
        // Glance translates each widget size from a pool of 500 view IDs; six weeks at three rows
        // plus the header come to about 464 and a fourth row would overflow (cost breakdown on
        // [MAX_EVENT_ROWS]). `MonthWidgetTranslationTest` checks the bound through Glance.
        assertEquals(3, MAX_EVENT_ROWS)
    }

    // ==================== weekColumnWidthDp ====================

    @Test
    fun `weekColumnWidthDp fills the week row exactly so event runs line up with day tap targets`() {
        // Event runs use fixed column widths while day numbers and day tap targets split the week
        // row into equal weights. They line up only if seven columns plus the gutter are the full
        // width; any extra inset shifts every run left of its day, so a tap near a run's edge opens
        // the neighbouring day.
        for (width in listOf(250f, 400f, 617.5f)) {
            for (weekNumbers in listOf(false, true)) {
                val gutter = if (weekNumbers) WEEK_NUMBER_GUTTER_WIDTH_DP else 0
                assertEquals(width, 7 * weekColumnWidthDp(width, weekNumbers) + gutter, 0.001f)
            }
        }
    }

    // ==================== maxTitleChars ====================

    @Test
    fun `maxTitleChars estimates characters from cell width`() {
        // (50 - 8) / 6 = 7
        assertEquals(7, maxTitleChars(50f))
    }

    @Test
    fun `maxTitleChars never drops below 4`() {
        assertEquals(4, maxTitleChars(20f))
    }

    // ==================== truncateTitle ====================

    @Test
    fun `truncateTitle keeps titles that fit`() {
        assertEquals("Gym", truncateTitle("Gym", 7))
    }

    @Test
    fun `truncateTitle clips to whole characters with no ellipsis`() {
        // The narrow cell spends every character on the title: take(5) of "Design Review" is
        // "Desig", no trailing "…".
        assertEquals("Desig", truncateTitle("Design Review", 5))
    }

    @Test
    fun `truncateTitle trims a trailing space left at the clip boundary`() {
        // take(8) of "Project X" is "Project " -> trimEnd -> "Project" (never ends on a blank
        // glyph); "Team sync" takes "Team syn" which has no trailing space to trim.
        assertEquals("Project", truncateTitle("Project X", 8))
        assertEquals("Team syn", truncateTitle("Team sync", 8))
    }

    @Test
    fun `truncateTitle returns the title untouched for degenerate budgets`() {
        assertEquals("Gym", truncateTitle("Gym", 0))
    }

    // ==================== eventActionParameters ====================

    @Test
    fun `eventActionParameters carries the Quick View deep link`() {
        val event = createWidgetEvent().copy(
            eventId = 42L,
            occurrenceStartTs = 1_700_000_000_000L,
            isDeviceEvent = false
        )
        val params = eventActionParameters(event)
        assertEquals(
            ACTION_SHOW_EVENT,
            params[androidx.glance.action.ActionParameters.Key<String>(EXTRA_ACTION)]
        )
        assertEquals(
            42L,
            params[androidx.glance.action.ActionParameters.Key<Long>(EXTRA_EVENT_ID)]
        )
        assertEquals(
            1_700_000_000_000L,
            params[androidx.glance.action.ActionParameters.Key<Long>(EXTRA_OCCURRENCE_TS)]
        )
        assertEquals(
            false,
            params[androidx.glance.action.ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT)]
        )
    }

    @Test
    fun `eventActionParameters flags device events for the device quick view`() {
        val event = createWidgetEvent().copy(isDeviceEvent = true)
        val params = eventActionParameters(event)
        assertEquals(
            true,
            params[androidx.glance.action.ActionParameters.Key<Boolean>(EXTRA_IS_DEVICE_EVENT)]
        )
    }

    // ==================== titles-mode tap targets (composition) ====================

    /**
     * May 2026 at a three-row size, today on the 13th. Week of May 10: a bar on the 10th-11th, day
     * 12 with five events (two pills and a "+3"), day 13 with one pill, and a second bar on the
     * 15th-16th, all sharing the first lane. So that lane holds the first bar, the first pill, a
     * second pill and a second bar. April 26, an adjacent-month day in the first week, has one
     * event too.
     */
    private fun tapFixture(): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        fun ev(id: Long, title: String, start: Int, end: Int = start) = createWidgetEvent().copy(
            eventId = id, occurrenceStartTs = id, title = title, startDay = start, endDay = end
        )
        val barA = ev(100, "BarA", 20260510, 20260511)
        val barB = ev(101, "BarB", 20260515, 20260516)
        val day12 = listOf("P12a", "P12b", "P12c", "P12d", "P12e").mapIndexed { i, t -> ev(200L + i, t, 20260512) }
        return mapOf(
            20260426 to listOf(ev(50, "Apr26", 20260426)),
            20260510 to listOf(barA), 20260511 to listOf(barA),
            20260512 to day12,
            20260513 to listOf(ev(300, "P13", 20260513)),
            20260515 to listOf(barB), 20260516 to listOf(barB),
        )
    }

    private fun dayParams(dayCode: Int): ActionParameters = actionParametersOf(
        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_DATE,
        ActionParameters.Key<Int>(EXTRA_DAY_CODE) to dayCode
    )

    private fun renderTapFixture(block: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            setAppWidgetSize(DpSize(400.dp, 600.dp))
            val grid = MonthGrid.compute(2026, 4, Calendar.SUNDAY)
            provideComposable {
                MonthWidgetContent(
                    monthGrid = grid, monthEvents = tapFixture(), monthOffset = 0,
                    targetYear = 2026, targetMonth0 = 4, firstDayOfWeek = Calendar.SUNDAY,
                    today = LocalDate.of(2026, 5, 13)
                )
            }
            block()
        }

    @Test
    fun `day numbers and plus-n markers are not inside any tap target`() = renderTapFixture {
        // "+3" appears only at three rows (two rows would show "+4"), so its presence also shows
        // the dense month renders at full height.
        val plusThree = resources.getString(R.string.status_more_events_compact, 3)
        onAllNodes(hasTextEqualTo(plusThree)).assertCountEquals(1)
        // Taps on them fall through to the day's own tap target underneath.
        onAllNodes(hasClickAction() and hasAnyDescendant(hasTextEqualTo("12"))).assertCountEquals(0)
        onAllNodes(hasClickAction() and hasAnyDescendant(hasTextEqualTo(plusThree))).assertCountEquals(0)
    }

    @Test
    fun `each day has exactly one open-day tap target carrying its description`() = renderTapFixture {
        val targets = onAllNodes(hasStartActivityClickAction<MainActivity>(dayParams(20260512)))
        targets.assertCountEquals(1)
        targets[0].assert(hasContentDescriptionEqualTo(buildAccessibilityDescription(resources, 20260512, 5)))
        // Adjacent-month days announce no events even when they have some.
        val adjacent = onAllNodes(hasStartActivityClickAction<MainActivity>(dayParams(20260426)))
        adjacent.assertCountEquals(1)
        adjacent[0].assert(hasContentDescriptionEqualTo(buildAccessibilityDescription(resources, 20260426, 0)))
    }

    @Test
    fun `each day description is announced by exactly one node`() = renderTapFixture {
        // A second node with the same description makes TalkBack read the day twice when swiping.
        onAllNodes(hasContentDescriptionEqualTo(buildAccessibilityDescription(resources, 20260512, 5))).assertCountEquals(1)
        onAllNodes(hasContentDescriptionEqualTo(buildAccessibilityDescription(resources, 20260513, 1))).assertCountEquals(1)
        // The day number reads as its plain number; hasContentDescription("") matches any.
        onNode(hasTextEqualTo("12")).assert(hasContentDescription("").not())
        // Today's number sits inside its marker; nothing containing it has a description.
        onAllNodes(hasAnyDescendant(hasTextEqualTo("13")) and hasContentDescription("")).assertCountEquals(0)
    }

    @Test
    fun `only the first pill and first bar in a row open their event`() = renderTapFixture {
        val fixture = tapFixture()
        onAllNodes(hasStartActivityClickAction<MainActivity>(eventActionParameters(fixture.getValue(20260512).first()))).assertCountEquals(1)
        onAllNodes(hasStartActivityClickAction<MainActivity>(eventActionParameters(fixture.getValue(20260510).single()))).assertCountEquals(1)
        for (title in listOf("P13", "BarB")) {
            onNode(hasTextEqualTo(title)).assert(hasNoClickAction())
            onAllNodes(hasClickAction() and hasAnyDescendant(hasTextEqualTo(title))).assertCountEquals(0)
        }
    }

    @Test
    fun `blank space and plus-n markers add no tap targets of their own`() = renderTapFixture {
        // Every tap target in the widget, counted: the header's 4 (previous, title, next, add), one
        // per day for the 6 visible weeks, and the deep links (BarA and P12a in the first lane,
        // P12b in the second, the April 26 pill). A tappable blank, "+n" or non-first pill/bar
        // would add more.
        onAllNodes(hasClickAction()).assertCountEquals(4 + 7 * 6 + 4)
    }

    // ==================== slotRowRuns (bounded slot-row layout) ====================

    private fun span(startCol: Int, endCol: Int, id: Long = 1L) = MonthWidgetSpan(
        event = createWidgetEvent().copy(eventId = id), startCol = startCol, endCol = endCol, leftFlush = false, rightFlush = false
    )

    private fun pill(id: Long) = MonthWidgetSlot.CellEvent(createWidgetEvent().copy(eventId = id))

    private val empty = MonthWidgetSlot.Empty

    private fun assertCoversSevenColumns(runs: List<SlotRun>) {
        var col = 0
        runs.forEach { run ->
            assertEquals("run $run must start where the previous one ended", col, run.startCol)
            assertTrue("run $run must have a positive width", run.width > 0)
            col += run.width
        }
        assertEquals("runs must cover exactly seven columns", 7, col)
    }

    @Test
    fun `slotRowRuns merges an all-empty row into one blank`() {
        val runs = slotRowRuns(List(7) { empty })
        assertEquals(listOf(SlotRun.Blank(startCol = 0, width = 7)), runs)
    }

    @Test
    fun `slotRowRuns merges consecutive empties between pills`() {
        val runs = slotRowRuns(listOf(empty, empty, pill(1), empty, empty, empty, pill(2)))
        assertCoversSevenColumns(runs)
        assertEquals(listOf(SlotRun.Blank::class, SlotRun.Pill::class, SlotRun.Blank::class, SlotRun.Pill::class), runs.map { it::class })
        assertEquals(2, (runs[0] as SlotRun.Blank).width)
        assertEquals(3, (runs[2] as SlotRun.Blank).width)
    }

    @Test
    fun `slotRowRuns merges a multi-column bar into one run`() {
        val bar = span(1, 3)
        val seg = MonthWidgetSlot.BarSegment(bar)
        val runs = slotRowRuns(listOf(empty, seg, seg, seg, empty, empty, empty))
        assertCoversSevenColumns(runs)
        val barRun = runs.single { it is SlotRun.Bar } as SlotRun.Bar
        assertEquals(1, barRun.startCol)
        assertEquals(3, barRun.width)
        assertTrue(barRun.span === bar)
    }

    @Test
    fun `slotRowRuns deep-links only the first pill and the first bar`() {
        val a = span(0, 1, id = 10)
        val b = span(4, 5, id = 11)
        val row = listOf(
            MonthWidgetSlot.BarSegment(a), MonthWidgetSlot.BarSegment(a), pill(1), pill(2),
            MonthWidgetSlot.BarSegment(b), MonthWidgetSlot.BarSegment(b), pill(3)
        )
        val runs = slotRowRuns(row)
        assertCoversSevenColumns(runs)
        assertEquals(listOf(true, false), runs.filterIsInstance<SlotRun.Bar>().map { it.deepLink })
        assertEquals(listOf(true, false, false), runs.filterIsInstance<SlotRun.Pill>().map { it.deepLink })
    }

    @Test
    fun `slotRowRuns never gives an overflow marker an action and caps a row at seven runs`() {
        val row = listOf(pill(1), MonthWidgetSlot.Overflow(2), MonthWidgetSlot.Overflow(3), pill(4),
            MonthWidgetSlot.Overflow(5), pill(6), MonthWidgetSlot.Overflow(7))
        val runs = slotRowRuns(row)
        assertCoversSevenColumns(runs)
        assertEquals(7, runs.size)
        assertEquals(listOf(2, 3, 5, 7), runs.filterIsInstance<SlotRun.Overflow>().map { it.count })
        assertEquals(1, runs.count { (it as? SlotRun.Pill)?.deepLink == true })
    }

    @Test
    fun `slotRowRuns does not merge adjacent spans that are equal but distinct`() {
        // Merging follows span identity, which is how the week layout marks one bar's columns.
        // Two separate bars with identical fields stay two bars.
        val first = span(0, 0, id = 5)
        val second = span(0, 0, id = 5)
        assertTrue(first == second && first !== second)
        val runs = slotRowRuns(listOf(MonthWidgetSlot.BarSegment(first), MonthWidgetSlot.BarSegment(second), empty, empty, empty, empty, empty))
        assertEquals(2, runs.count { it is SlotRun.Bar })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `slotRowRuns rejects a row that is not seven columns`() {
        slotRowRuns(List(6) { empty })
    }

    private fun createWidgetEvent(
        calendarColor: Int = 0xFF2196F3.toInt()
    ): WidgetDataRepository.WidgetEvent {
        return WidgetDataRepository.WidgetEvent(
            eventId = 1L,
            occurrenceStartTs = 1000L,
            title = "Test",
            startTs = 1000L,
            endTs = 2000L,
            isAllDay = false,
            calendarColor = calendarColor,
            isPast = false,
            isDeviceEvent = false,
            startDay = 0
        )
    }
}
