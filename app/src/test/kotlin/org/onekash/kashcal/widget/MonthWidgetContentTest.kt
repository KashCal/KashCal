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
    fun `formatMonthHeader omits year when same as current year`() {
        val result = formatMonthHeader(year = 2026, month0 = 3, currentYear = 2026) // April
        assertEquals("Apr", result)
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
        val result = formatMonthHeader(year = 2026, month0 = 11, currentYear = 2026) // Dec
        assertEquals("Dec", result)
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
