package org.onekash.kashcal.widget

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.model.MonthGrid
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
    // numbers below. In the default (English) test locale that is S M T W T F S; the repeats
    // (Sun/Sat both "S", Tue/Thu both "T") are disambiguated by column position, as in the
    // Material/Google Calendar month grid.
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

    // Full localized day names back the NARROW single-letter headers as accessibility labels,
    // so TalkBack still announces "Sunday"/"Monday" rather than ambiguous bare letters.
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
        // One gutter cell per rendered week — never the padded 6 rows if the month spans fewer.
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

    // ==================== buildAccessibilityDescription (dayCode overload) ====================

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

    // ==================== buildAccessibilityDescription (original) ====================

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
        // 19 (number) + 16 (row) + 1 (its leading gap) = 36dp minimum; below that the cell
        // falls back to dots rather than commit to a title row the number would clip away.
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
        // A cell that fits two rows at font-scale 1.0 fits none at 1.5: the scaled 16dp rows
        // (24dp each) plus the scaled 19dp number (28.5dp) no longer clear the 53dp cell, so the
        // layout backs off to dots instead of clipping a row off the bottom.
        assertEquals(2, maxEventRows(53f, fontScale = 1.0f))
        assertEquals(0, maxEventRows(53f, fontScale = 1.5f))
    }

    // ==================== minWidgetHeightForTitlesDp ====================

    @Test
    fun `minWidgetHeightForTitlesDp derives the one-row threshold from real element heights`() {
        // Header 40 + day-of-week 21 + 6 weeks * (19 number + 1 * (16 row + 1 gap)) = 277dp,
        // which is exactly the height a 6-week one-row grid renders at — so a widget past the
        // threshold fits its row with none clipped. This sits comfortably under the placed 4x4
        // default (304dp), so a freshly placed widget shows titles and only the smallest resizes fall to dots.
        assertEquals(277f, minWidgetHeightForTitlesDp(TITLES_MIN_ROWS), 0.001f)
    }

    @Test
    fun `minWidgetHeightForTitlesDp for two rows still matches the six-week two-row height`() {
        // Guards the derivation itself independent of TITLES_MIN_ROWS: 40 + 21 + 6 * (19 + 2*17).
        assertEquals(379f, minWidgetHeightForTitlesDp(2), 0.001f)
    }

    @Test
    fun `minWidgetHeightForTitlesDp needs more room for more rows`() {
        assertTrue(minWidgetHeightForTitlesDp(2) > minWidgetHeightForTitlesDp(1))
    }

    @Test
    fun `minWidgetHeightForTitlesDp rises with the font scale`() {
        // A larger system font grows the text, so titles need a taller widget before they fit —
        // the threshold tracks the font scale so a scaled-up widget shows dots until it is
        // genuinely tall enough for un-clipped titles.
        assertTrue(minWidgetHeightForTitlesDp(2, fontScale = 1.5f) > minWidgetHeightForTitlesDp(2, fontScale = 1.0f))
    }

    @Test
    fun `MAX_EVENT_ROWS stays small so the widget never exhausts its view-ID pool`() {
        // Each widget can allocate at most 500 views, and every slot row draws from that pool across
        // all 7 columns and 6 week rows — so the row count is the dominant multiplier and the budget
        // is capped by it, not by widget size. Three rows only fit once each event collapsed from a
        // Box+Text (two views) to a single Text; a fully-booked six-week month at three rows then
        // measures well inside the pool at every size. MonthWidgetTranslationTest measures the
        // worst-case count to hold this margin.
        assertEquals(3, MAX_EVENT_ROWS)
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
        // The narrow widget cell keeps every character for the title itself, so the whole
        // budget renders text: take(5) of "Design Review" is "Desig", no trailing "…".
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

    // ==================== deep-link target gating ====================

    @Test
    fun `isFirstCellEventInLane picks only the leading pill`() {
        val a = createWidgetEvent().copy(eventId = 1L)
        val b = createWidgetEvent().copy(eventId = 2L)
        val c = createWidgetEvent().copy(eventId = 3L)
        val row = listOf(
            MonthWidgetSlot.CellEvent(a),
            MonthWidgetSlot.CellEvent(b),
            MonthWidgetSlot.CellEvent(c),
        )
        // First pill in the lane carries the deep link.
        assertEquals(true, isFirstCellEventInLane(row, 0))
        // A pill preceded by another pill does not — this is the view-pool guard.
        assertEquals(false, isFirstCellEventInLane(row, 1))
        assertEquals(false, isFirstCellEventInLane(row, 2))
    }

    @Test
    fun `isFirstCellEventInLane ignores non-pill slots before the first pill`() {
        val span = MonthWidgetSpan(
            event = createWidgetEvent(),
            startCol = 0,
            endCol = 0,
            leftFlush = false,
            rightFlush = false,
        )
        val row = listOf(
            MonthWidgetSlot.BarSegment(span),
            MonthWidgetSlot.Overflow(3),
            MonthWidgetSlot.CellEvent(createWidgetEvent()),
        )
        // A leading bar/overflow are not CellEvents, so the pill at col 2 is still "first".
        assertEquals(true, isFirstCellEventInLane(row, 2))
    }

    @Test
    fun `isFirstBarSegmentInLane picks only the leading bar segment`() {
        val span = MonthWidgetSpan(
            event = createWidgetEvent(),
            startCol = 1,
            endCol = 2,
            leftFlush = false,
            rightFlush = false,
        )
        val row = listOf(
            MonthWidgetSlot.BarSegment(span),
            MonthWidgetSlot.BarSegment(span),
            MonthWidgetSlot.Empty,
        )
        // First segment deep-links to Quick View.
        assertEquals(true, isFirstBarSegmentInLane(row, 0))
        // A continuation segment is not the deep-link target, but it still opens the day
        // (both branches are clickable) — this only chooses which action the segment gets.
        assertEquals(false, isFirstBarSegmentInLane(row, 1))
    }

    // ==================== estimateMonthWidgetViewUnits (adaptive row budget) ====================

    private fun spanOf(startCol: Int, endCol: Int) = MonthWidgetSpan(
        event = createWidgetEvent(), startCol = startCol, endCol = endCol, leftFlush = false, rightFlush = false
    )

    private fun weekOf(vararg rows: List<MonthWidgetSlot>) = MonthWidgetWeekRender(rows.toList())

    @Test
    fun `estimateMonthWidgetViewUnits is base chrome for an empty grid`() {
        assertEquals(
            VIEW_UNITS_CHROME_BASE,
            estimateMonthWidgetViewUnits(emptyList(), showWeekNumbers = false, hasTodayInMonth = false)
        )
    }

    @Test
    fun `estimateMonthWidgetViewUnits adds per-week day cells, gutter, and today marker`() {
        val oneEmptyWeek = listOf(weekOf()) // a week that renders no slot rows
        val base = estimateMonthWidgetViewUnits(oneEmptyWeek, showWeekNumbers = false, hasTodayInMonth = false)
        assertEquals(VIEW_UNITS_CHROME_BASE + 7 * VIEW_UNITS_DAY_CELL, base)

        val withGutter = estimateMonthWidgetViewUnits(oneEmptyWeek, showWeekNumbers = true, hasTodayInMonth = false)
        assertEquals(base + VIEW_UNITS_WEEK_GUTTER, withGutter)

        val withToday = estimateMonthWidgetViewUnits(oneEmptyWeek, showWeekNumbers = false, hasTodayInMonth = true)
        assertEquals(base + VIEW_UNITS_TODAY_MARKER, withToday)
    }

    @Test
    fun `estimateMonthWidgetViewUnits weights a bars-or-overflow mix above equal-count pills`() {
        val pillsWeek = listOf(
            weekOf(listOf(MonthWidgetSlot.CellEvent(createWidgetEvent()), MonthWidgetSlot.CellEvent(createWidgetEvent()), MonthWidgetSlot.CellEvent(createWidgetEvent())))
        )
        val mixWeek = listOf(
            weekOf(listOf(MonthWidgetSlot.BarSegment(spanOf(0, 0)), MonthWidgetSlot.Overflow(2), MonthWidgetSlot.BarSegment(spanOf(2, 2))))
        )
        // Same element COUNT (3) and same chrome, but bars + overflow are the heavy elements —
        // this is the property the crash hinges on (a mix overflows where plain pills do not).
        val pills = estimateMonthWidgetViewUnits(pillsWeek, showWeekNumbers = false, hasTodayInMonth = false)
        val mix = estimateMonthWidgetViewUnits(mixWeek, showWeekNumbers = false, hasTodayInMonth = false)
        assertTrue("mix ($mix) must estimate higher than equal-count pills ($pills)", mix > pills)
    }

    @Test
    fun `estimateMonthWidgetViewUnits counts a merged bar run once, not per column`() {
        val span = spanOf(0, 1)
        val mergedRun = listOf(weekOf(listOf(MonthWidgetSlot.BarSegment(span), MonthWidgetSlot.BarSegment(span))))
        val twoDistinctBars = listOf(weekOf(listOf(MonthWidgetSlot.BarSegment(spanOf(0, 0)), MonthWidgetSlot.BarSegment(spanOf(1, 1)))))
        val merged = estimateMonthWidgetViewUnits(mergedRun, showWeekNumbers = false, hasTodayInMonth = false)
        val distinct = estimateMonthWidgetViewUnits(twoDistinctBars, showWeekNumbers = false, hasTodayInMonth = false)
        // The merged run is one SpanBar; the two distinct spans are two — exactly one bar apart.
        assertEquals(VIEW_UNITS_BAR, distinct - merged)
    }

    // ==================== chooseMonthWidgetRowCount (adaptive step-down) ====================

    /** Week w occupies day codes [w*7+1 .. w*7+7] — synthetic, strictly increasing, layout-valid. */
    private fun weekCodes(nWeeks: Int): List<List<Int>> = (0 until nWeeks).map { w -> (w * 7 + 1..w * 7 + 7).toList() }

    private fun pillOn(code: Int, i: Int) =
        createWidgetEvent().copy(eventId = code * 10L + i, occurrenceStartTs = i.toLong(), startDay = code, endDay = code)

    /** A two-day bar starting at [startCode]; placed in both buckets it spans, as the repository does. */
    private fun addBar(map: MutableMap<Int, MutableList<WidgetDataRepository.WidgetEvent>>, startCode: Int, id: Int) {
        val bar = createWidgetEvent().copy(
            eventId = 900_000L + id, occurrenceStartTs = id.toLong(), startDay = startCode, endDay = startCode + 1
        )
        map.getOrPut(startCode) { mutableListOf() }.add(bar)
        map.getOrPut(startCode + 1) { mutableListOf() }.add(bar)
    }

    /** Every cell packed with [perDay] pills, plus optional two-day bars at cols 0/2/4 of each week. */
    private fun densMonth(weeks: List<List<Int>>, perDay: Int, barsPerWeek: Boolean): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val map = mutableMapOf<Int, MutableList<WidgetDataRepository.WidgetEvent>>()
        weeks.forEach { codes ->
            codes.forEach { c -> repeat(perDay) { i -> map.getOrPut(c) { mutableListOf() }.add(pillOn(c, i)) } }
            if (barsPerWeek) listOf(0, 2, 4).forEachIndexed { k, sc -> addBar(map, codes[sc], codes.first() * 10 + k) }
        }
        return map.mapValues { it.value.toList() }
    }

    @Test
    fun `chooseMonthWidgetRowCount keeps full height for a sparse month`() {
        val weeks = weekCodes(6)
        val sparse = mapOf(weeks[0][1] to listOf(pillOn(weeks[0][1], 0)), weeks[2][3] to listOf(pillOn(weeks[2][3], 0)))
        assertEquals(3, chooseMonthWidgetRowCount(weeks, sparse, heightDerivedMax = 3, showWeekNumbers = true, hasTodayInMonth = false).rows)
    }

    @Test
    fun `chooseMonthWidgetRowCount steps a dense pills-plus-bars month down below full height`() {
        val weeks = weekCodes(6)
        val denseMix = densMonth(weeks, perDay = 3, barsPerWeek = true)
        val chosen = chooseMonthWidgetRowCount(weeks, denseMix, heightDerivedMax = 3, showWeekNumbers = true, hasTodayInMonth = false).rows
        // The 3-row layout of this mix overflows the pool; the chooser must drop below 3 but still
        // show at least one title row (dots is the last resort, not the first).
        assertTrue("expected a reduced row count in 1..2, got $chosen", chosen in 1..2)
    }

    @Test
    fun `chooseMonthWidgetRowCount pill-heavy full month keeps three rows`() {
        val weeks = weekCodes(6)
        val allPills = densMonth(weeks, perDay = 3, barsPerWeek = false)
        // A fully pill-packed month is measured-safe at 3 rows — pills are the cheap element.
        assertEquals(3, chooseMonthWidgetRowCount(weeks, allPills, heightDerivedMax = 3, showWeekNumbers = true, hasTodayInMonth = false).rows)
    }

    @Test
    fun `chooseMonthWidgetRowCount returns zero (dots) when nothing fits`() {
        // A real month is at most 6 weeks and always fits >= 1 title row, so this exercises the
        // defensive dots floor with an over-large grid whose fixed chrome alone exceeds the budget.
        val hugeGrid = weekCodes(30)
        val choice = chooseMonthWidgetRowCount(hugeGrid, emptyMap(), heightDerivedMax = 3, showWeekNumbers = true, hasTodayInMonth = false)
        assertEquals(0, choice.rows)
        assertTrue("dots fallback should carry no layouts", choice.weekRenders.isEmpty())
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
