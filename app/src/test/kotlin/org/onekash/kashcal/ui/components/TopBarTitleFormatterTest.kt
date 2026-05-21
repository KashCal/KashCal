package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TopBarTitleFormatterTest {

    private val weekSuffixTemplate = "%s · %s"
    private val agendaLabel = "Agenda"
    private val yearLabel = "Year"

    @Test
    fun `format MONTH returns abbreviated month and year`() {
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.MONTH,
            viewingYear = 2026,
            viewingMonth = 4, // May (0-indexed)
            weekViewPagerPosition = 0,
            firstDayOfWeek = Calendar.SUNDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
        )
        assertTrue("Should contain abbreviated May, got: $result", result.contains("May"))
        assertTrue("Should contain year 2026, got: $result", result.contains("2026"))
    }

    @Test
    fun `format MONTH_FULL matches MONTH format`() {
        val month = TopBarTitleFormatter.format(
            ViewMode.MONTH, 2026, 8, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        val monthFull = TopBarTitleFormatter.format(
            ViewMode.MONTH_FULL, 2026, 8, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        assertEquals(month, monthFull)
    }

    @Test
    fun `format AGENDA returns the static agenda label`() {
        val result = TopBarTitleFormatter.format(
            ViewMode.AGENDA, 2026, 8, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        assertEquals(agendaLabel, result)
    }

    @Test
    fun `format YEAR returns the static year label`() {
        val result = TopBarTitleFormatter.format(
            ViewMode.YEAR, 2026, 0, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        assertEquals(yearLabel, result)
    }

    @Test
    fun `format WEEK combines month-year with W-week suffix`() {
        val centerPage = WeekViewUtils.CENTER_WEEK_PAGE
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.WEEK,
            viewingYear = 2026,
            viewingMonth = 0,
            weekViewPagerPosition = centerPage,
            firstDayOfWeek = Calendar.MONDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
        )
        assertTrue("Should contain ' · W' separator with no extra space, got: $result", result.contains(" · W"))
        assertFalse("Should not have space between W and digit, got: $result", result.contains("W "))
        assertTrue("Should match 'Mmm yyyy · W##', got: $result", result.matches(Regex("\\w+ \\d{4} · W\\d+")))
    }

    @Test
    fun `format DAY returns weekday with abbreviated month and day`() {
        val centerPage = WeekViewUtils.CENTER_DAY_PAGE
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.DAY,
            viewingYear = 2026,
            viewingMonth = 0,
            weekViewPagerPosition = centerPage,
            firstDayOfWeek = Calendar.SUNDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
        )
        // Weekday name should appear in some form (3-letter abbrev)
        assertFalse("Should not be empty", result.isEmpty())
        assertTrue(
            "Should contain a 3-letter weekday abbrev, got: $result",
            Regex("\\b(Sun|Mon|Tue|Wed|Thu|Fri|Sat)\\b").containsMatchIn(result),
        )
    }

    @Test
    fun `format DAY drops year when displayed date is current year`() {
        val centerPage = WeekViewUtils.CENTER_DAY_PAGE
        val displayed = WeekViewUtils.pageToDate(centerPage)
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.DAY,
            viewingYear = displayed.year,
            viewingMonth = 0,
            weekViewPagerPosition = centerPage,
            firstDayOfWeek = Calendar.SUNDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
            today = displayed,
        )
        assertFalse("Should NOT contain the year, got: $result", result.contains(displayed.year.toString()))
    }

    @Test
    fun `format DAY keeps year when displayed date is not current year`() {
        val centerPage = WeekViewUtils.CENTER_DAY_PAGE
        val displayed = WeekViewUtils.pageToDate(centerPage)
        val nextYear = displayed.plusYears(1)
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.DAY,
            viewingYear = displayed.year,
            viewingMonth = 0,
            weekViewPagerPosition = centerPage,
            firstDayOfWeek = Calendar.SUNDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
            today = nextYear,
        )
        assertTrue("Should contain the displayed year, got: $result", result.contains(displayed.year.toString()))
    }

    @Test
    fun `format THREE_DAYS returns center-date month-year`() {
        val centerPage = WeekViewUtils.CENTER_DAY_PAGE
        val result = TopBarTitleFormatter.format(
            viewMode = ViewMode.THREE_DAYS,
            viewingYear = 2026,
            viewingMonth = 0,
            weekViewPagerPosition = centerPage,
            firstDayOfWeek = Calendar.SUNDAY,
            weekPrefix = "W",
            weekSuffixTemplate = weekSuffixTemplate,
            agendaLabel = agendaLabel,
            yearLabel = yearLabel,
            locale = Locale.US,
        )
        assertTrue("Should match 'Mmm yyyy', got: $result", result.matches(Regex("\\w+ \\d{4}")))
    }

    @Test
    fun `format INSIGHTS returns empty string`() {
        val result = TopBarTitleFormatter.format(
            ViewMode.INSIGHTS, 2026, 4, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        assertEquals("", result)
    }

    @Test
    fun `format MONTH abbreviates month not full name`() {
        val result = TopBarTitleFormatter.format(
            ViewMode.MONTH, 2026, 3, 0, Calendar.SUNDAY, "W", weekSuffixTemplate, agendaLabel, yearLabel, Locale.US,
        )
        // April → Apr (abbreviated)
        assertTrue("Should contain Apr, got: $result", result.contains("Apr"))
        assertFalse("Should not contain full April, got: $result", result.contains("April"))
    }
}
