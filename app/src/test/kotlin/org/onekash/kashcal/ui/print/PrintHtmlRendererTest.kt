package org.onekash.kashcal.ui.print

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.PlannerTestEvents.room
import org.onekash.kashcal.domain.planner.PlannerTestEvents.roomAllDay
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Tests [renderPrintHtml]: a self-contained, script-free document whose week page is sized by
 * the printed page, with every user or server text escaped. Set the Gradle property
 * `print.dumpDir` to write sample documents for checking in a browser's print preview.
 */
class PrintHtmlRendererTest {

    private lateinit var savedZone: TimeZone
    private val ny = ZoneId.of("America/New_York")
    private val sunday = LocalDate.of(2026, 10, 25)

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ny))
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedZone)

    private fun strings(
        languageTag: String = "en-US",
        rtl: Boolean = false,
        pageOf: String = "Page %1\$s of %2\$s",
        printedOn: String = "Printed Oct 26, 2026",
    ) = PrintStrings(
        languageTag = languageTag,
        rtl = rtl,
        documentTitle = "KashCal – Week of Oct 25",
        weekRange = "Oct 25 – 31, 2026",
        weekLabel = "Week",
        detailsLabel = "Details",
        calendarsSummary = "2 of 7 calendars",
        allDay = "All day",
        repeats = "Repeats",
        declined = "Declined",
        cancelled = "Cancelled",
        freeDay = "Free day",
        numbersNote = "Numbers match the details, which list every event in full.",
        timesShownIn = "Times shown in Eastern Time",
        printedOn = printedOn,
        pageOf = pageOf,
    )

    private fun formats(pattern: String = "h:mm a") = PrintFormats(
        locale = Locale.US,
        timePattern = pattern,
        weekdayPattern = "EEE",
        shortDatePattern = "MMM d",
        dayHeaderPattern = "EEE, MMM d",
        allDayLabel = "All day",
        join = { a, b -> "$a–$b" },
        moved = { "was $it" },
    )

    private val work = PrintCalendar(PrintCalendarKey.Room(1L), "Work", 0xFF1E88E5.toInt())

    private fun week(
        events: List<DisplayEvent>,
        grid: Boolean = true,
        details: Boolean = true,
        calendars: List<PrintCalendar> = listOf(work),
        pattern: String = "h:mm a",
        title: (DisplayEvent) -> String = { it.title },
    ) = buildPrintWeek(
        events = events,
        weekStart = sunday,
        includeGrid = grid,
        includeDetails = details,
        showMultiDayTimedInAllDayStrip = false,
        roomSeriesAllDayById = emptyMap(),
        calendars = calendars,
        formats = formats(pattern),
        title = title,
    )

    private fun timed(id: Long, day: Int, h: Int, m: Int, eh: Int, em: Int, title: String = "Event $id", location: String? = null) =
        room(
            id = id,
            startTs = at(sunday.plusDays(day.toLong()), h, m, zone = ny),
            endTs = at(sunday.plusDays(day.toLong()), eh, em, zone = ny),
            zone = ny,
            title = title,
        ).let { if (location == null) it else it.copy(event = it.event.copy(location = location)) }

    private val lunch get() = timed(1, 1, 12, 0, 13, 0, title = "Lunch", location = "Café")

    // ==================== Document ====================

    @Test
    fun `the document is self-contained with a strict CSP and the job name as title`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("""<html lang="en-US" dir="ltr">"""))
        assertTrue(html.contains("<title>KashCal – Week of Oct 25</title>"))
        assertTrue(
            html.contains(
                """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">"""
            )
        )
    }

    @Test
    fun `the document has no script, no URL and no page size`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        assertFalse(html.contains("<script", ignoreCase = true))
        assertFalse(html.contains("http://") || html.contains("https://"))
        assertFalse(html.contains("url("))
        assertFalse(Regex("""@page\s*\{[^}]*\bsize\s*:""").containsMatchIn(html))
    }

    @Test
    fun `a right-to-left language sets the document direction`() {
        assertTrue(renderPrintHtml(week(listOf(lunch)), strings(languageTag = "ar", rtl = true)).contains("""<html lang="ar" dir="rtl">"""))
    }

    @Test
    fun `user and server text is escaped everywhere`() {
        val hostile = timed(1, 1, 9, 0, 10, 0, title = "<img src=x onerror=alert(1)>", location = "\"Hall\" & 'B'")
        val calendar = PrintCalendar(PrintCalendarKey.Room(1L), "<b>Cal</b>", 1)
        val html = renderPrintHtml(week(listOf(hostile), calendars = listOf(calendar)), strings())

        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<b>Cal</b>"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("&quot;Hall&quot; &amp; &#39;B&#39;"))
        assertTrue(html.contains("&lt;b&gt;Cal&lt;/b&gt;"))
        assertTrue(html.contains("""<span class="t" dir="auto">&lt;img"""))
    }

    // ==================== Week page ====================

    @Test
    fun `the week page is sized by the printed page`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        assertTrue(html.contains("height: 100vh"))
        assertTrue(html.contains("container-type: size"))
        assertTrue(html.contains("100cqh"))
        assertTrue(html.contains("round(down,"))
        assertTrue(html.contains("1lh"))
        assertTrue(html.contains("print-color-adjust: exact"))
        // A crowded all-day band can't squeeze the timed grid away.
        assertTrue(html.contains("min-height: 55vh"))
        assertTrue(html.contains("-webkit-print-color-adjust: exact"))
    }

    @Test
    fun `a block is placed from the model's minutes, span and cascade`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        // 12:00-13:00 in an 08:00-18:00 grid.
        assertTrue(html.contains("""<div class="cols" style="--span:600;--hours:10">"""))
        assertTrue(html.contains("--s:240;--d:60;--t:60;"))
        assertTrue(html.contains("inset-inline-start:calc(0.0% + 0.3mm);width:calc(100.0% - 0.6mm);"))
        assertTrue(html.contains("z-index:1"))
    }

    @Test
    fun `weekend headings, continuation marks and hour labels are written`() {
        val fado = room(
            id = 2, startTs = at(sunday.plusDays(5), 23, 0, zone = ny),
            endTs = at(sunday.plusDays(6), 1, 30, zone = ny), zone = ny, title = "Fado",
        )
        val html = renderPrintHtml(week(listOf(fado)), strings())
        assertTrue(html.contains("""<div class="dh weekend">Sat, Oct 31</div>"""))
        assertTrue(html.contains("""<div class="dh">Mon, Oct 26</div>"""))
        assertTrue(html.contains("Fado ▸"))
        assertTrue(html.contains("◂ Fado"))
        assertTrue(html.contains(">1 am</span>"))
    }

    @Test
    fun `continuation marks mirror in a right-to-left document`() {
        val fado = room(
            id = 2, startTs = at(sunday.plusDays(5), 23, 0, zone = ny),
            endTs = at(sunday.plusDays(6), 1, 30, zone = ny), zone = ny, title = "Fado",
        )
        val html = renderPrintHtml(week(listOf(fado)), strings(languageTag = "ar", rtl = true))
        assertTrue(html.contains("Fado ◂"))
        assertTrue(html.contains("▸ Fado"))
    }

    @Test
    fun `numbers appear in grid and details only when both pages print`() {
        val both = renderPrintHtml(week(listOf(lunch)), strings())
        assertEquals(2, Regex("""<span class="n">1</span>""").findAll(both).count())
        assertTrue(both.contains("Numbers match the details"))

        val gridOnly = renderPrintHtml(week(listOf(lunch), details = false), strings())
        assertFalse(gridOnly.contains("""class="n""""))
        assertFalse(gridOnly.contains("Numbers match the details"))
        assertFalse(gridOnly.contains("""<div class="details">"""))
        assertTrue(gridOnly.contains("""<div class="cols""""))

        val detailsOnly = renderPrintHtml(week(listOf(lunch), grid = false), strings())
        assertFalse(detailsOnly.contains("""class="n""""))
        assertFalse(detailsOnly.contains("""<div class="cols""""))
        assertTrue(detailsOnly.contains("""<div class="details">"""))
    }

    // ==================== Details page ====================

    @Test
    fun `details use three columns in landscape, two in portrait, and never split an entry`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        assertTrue(html.contains(".details { columns: 3;"))
        assertTrue(html.contains("@media print and (orientation: portrait) { .details { columns: 2 } }"))
        assertTrue(Regex("""\.item \{[^}]*break-inside: avoid""").containsMatchIn(html))
        assertTrue(Regex("""\.details h2 \{[^}]*break-after: avoid""").containsMatchIn(html))
    }

    @Test
    fun `a detail entry carries its fields, tags and a free day placeholder`() {
        val declined = timed(3, 2, 12, 0, 13, 0, title = "Talk").let { it.copy(isDeclinedByMe = true) }
        val cancelled = timed(4, 3, 9, 0, 9, 15, title = "Standup").let { it.copy(event = it.event.copy(status = "CANCELLED")) }
        val html = renderPrintHtml(week(listOf(lunch, declined, cancelled)), strings())

        assertTrue(html.contains("""<div class="item">"""))
        assertTrue(html.contains("""<div class="item declined">"""))
        assertTrue(html.contains("""<div class="item cancelled">"""))
        assertTrue(html.contains("""<span class="tag">Declined</span>"""))
        assertTrue(html.contains("""<span class="tag">Cancelled</span>"""))
        assertTrue(html.contains("""<span dir="auto">Café</span>"""))
        assertTrue(html.contains("12–1 pm"))
        // Sunday has no events.
        assertTrue(html.contains("""<div class="free">Free day</div>"""))
    }

    @Test
    fun `the legend lists only the printed calendars and the subtitle the count`() {
        val html = renderPrintHtml(week(listOf(lunch), calendars = listOf(work)), strings())
        assertTrue(html.contains(">Work</span>"))
        assertFalse(html.contains("Unprinted"))
        assertTrue(html.contains("2 of 7 calendars"))
    }

    // ==================== Page boxes ====================

    @Test
    fun `the page counter takes the translation's placeholder order`() {
        val html = renderPrintHtml(week(listOf(lunch)), strings())
        assertTrue(html.contains("""content: "Page " counter(page) " of " counter(pages);"""))

        val reordered = renderPrintHtml(week(listOf(lunch)), strings(pageOf = "%2\$s pages, page %1\$s"))
        assertTrue(reordered.contains("""content: counter(pages) " pages, page " counter(page);"""))
    }

    @Test
    fun `text in CSS content is CSS-escaped`() {
        val html = renderPrintHtml(
            week(listOf(lunch)),
            strings(pageOf = "Seite \"%1\$s\" \\ %2\$s", printedOn = "Gedruckt \"heute\""),
        )
        assertTrue(html.contains("""content: "Seite \"" counter(page) "\" \\ " counter(pages);"""))
        assertTrue(html.contains("""content: "Gedruckt \"heute\"";"""))
    }

    // ==================== Content rules ====================

    @Test
    fun `descriptions never print, a contact event prints its age title`() {
        val note = timed(5, 1, 15, 0, 15, 10, title = "Call pharmacy").let { it.copy(event = it.event.copy(description = "SECRET-NOTE")) }
        val birthday = roomAllDay(6, sunday.plusDays(2), zone = ny).let {
            it.copy(event = it.event.copy(title = "Alex", description = "birthYear:2021"))
        }
        val html = renderPrintHtml(
            week(listOf(note, birthday), title = { if (it.title == "Alex") "Alex's 5th Birthday" else it.title }),
            strings(),
        )
        assertFalse(html.contains("SECRET-NOTE"))
        assertFalse(html.contains("birthYear"))
        assertTrue(html.contains("Alex&#39;s 5th Birthday"))
    }

    // ==================== Samples for browser checks ====================

    @Test
    fun `write sample documents when print dumpDir is set`() {
        val dir = System.getProperty("print.dumpDir") ?: return
        File(dir).mkdirs()
        val samples = PrintSamples(ny, sunday)
        File(dir, "sample-12h-sunday.html").writeText(
            renderPrintHtml(week(samples.normalWeek(), calendars = samples.calendars, title = samples::title), strings())
        )
        File(dir, "sample-grid-only.html").writeText(
            renderPrintHtml(week(samples.normalWeek(), details = false, calendars = samples.calendars, title = samples::title), strings())
        )
        File(dir, "sample-details-only.html").writeText(
            renderPrintHtml(week(samples.normalWeek(), grid = false, calendars = samples.calendars, title = samples::title), strings())
        )
        File(dir, "sample-two-calendars.html").writeText(
            renderPrintHtml(
                week(samples.normalWeek().filter { it.calendarName in setOf("Work", "Family") },
                    calendars = samples.calendars.filter { it.name in setOf("Work", "Family") }, title = samples::title),
                strings(),
            )
        )
        val monday = sunday.plusDays(1)
        val mondayWeek = buildPrintWeek(
            events = samples.midnightWeek(monday), weekStart = monday, includeGrid = true, includeDetails = true,
            showMultiDayTimedInAllDayStrip = false, roomSeriesAllDayById = emptyMap(), calendars = samples.calendars,
            formats = formats("HH:mm"), title = samples::title,
        )
        File(dir, "sample-24h-monday-midnight.html").writeText(
            renderPrintHtml(mondayWeek, strings().copy(weekRange = "Oct 26 – Nov 1, 2026"))
        )
    }
}
