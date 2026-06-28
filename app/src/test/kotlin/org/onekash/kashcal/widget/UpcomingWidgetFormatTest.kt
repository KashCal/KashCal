package org.onekash.kashcal.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class UpcomingWidgetFormatTest {

    private lateinit var originalLocale: Locale
    private val todayLabel = "Today"
    private val tomorrowLabel = "Tomorrow"
    private val withDateTemplate = "%1\$s (%2\$s)"

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatUpcomingDayHeader appends date in brackets after today label`() {
        // 20260428 is Tuesday. Today/Tomorrow headers now carry the date so
        // they are as informative as the Week widget's headers (issue #253).
        val result = formatUpcomingDayHeader(
            dayCode = 20260428,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Today (Tue, Apr 28)", result)
    }

    @Test
    fun `formatUpcomingDayHeader without template returns plain relative label`() {
        // Back-compat: callers that omit the template (e.g. the invitation card)
        // get the bare "Today"/"Tomorrow" label, unchanged.
        val today = formatUpcomingDayHeader(
            dayCode = 20260428,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(todayLabel, today)
    }

    @Test
    fun `formatUpcomingDayHeader appends date in brackets after tomorrow label`() {
        // 20260429 is Wednesday.
        val result = formatUpcomingDayHeader(
            dayCode = 20260429,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Tomorrow (Wed, Apr 29)", result)
    }

    @Test
    fun `tomorrowDayCodeOf crosses month boundary correctly`() {
        // Integer +1 on 20260430 would give 20260431 (invalid). Must produce 20260501.
        assertEquals(20260501, tomorrowDayCodeOf(20260430))
    }

    @Test
    fun `tomorrowDayCodeOf crosses year boundary correctly`() {
        // Dec 31 -> Jan 1 of next year.
        assertEquals(20270101, tomorrowDayCodeOf(20261231))
    }

    @Test
    fun `formatUpcomingDayHeader returns tomorrow with date when tomorrow crosses month boundary`() {
        // 20260501 is Friday.
        val result = formatUpcomingDayHeader(
            dayCode = 20260501,
            todayDayCode = 20260430,
            tomorrowDayCode = tomorrowDayCodeOf(20260430),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Tomorrow (Fri, May 1)", result)
    }

    @Test
    fun `formatUpcomingDayHeader returns tomorrow with date when tomorrow crosses year boundary`() {
        // 20270101 is Friday.
        val result = formatUpcomingDayHeader(
            dayCode = 20270101,
            todayDayCode = 20261231,
            tomorrowDayCode = tomorrowDayCodeOf(20261231),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Tomorrow (Fri, Jan 1)", result)
    }

    @Test
    fun `formatUpcomingDayHeader returns weekday and date for day in same month`() {
        // 20260501 is Friday.
        val result = formatUpcomingDayHeader(
            dayCode = 20260501,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Fri, May 1", result)
    }

    @Test
    fun `formatUpcomingDayHeader returns weekday and date for day far in future`() {
        // 20260527 is Wednesday — exercises the "far future" branch of the formatter.
        // (Input is far beyond the widget's 10-day horizon; formatter is horizon-agnostic.)
        val result = formatUpcomingDayHeader(
            dayCode = 20260527,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel,
            withDateTemplate = withDateTemplate
        )
        assertEquals("Wed, May 27", result)
    }

    @Test
    fun `formatUpcomingDayHeader is unambiguous across months`() {
        // Two days in different months must render differently.
        val tomorrow = tomorrowDayCodeOf(20260428)
        val apr30 = formatUpcomingDayHeader(20260430, 20260428, tomorrow, todayLabel, tomorrowLabel, withDateTemplate)
        val may30 = formatUpcomingDayHeader(20260530, 20260428, tomorrow, todayLabel, tomorrowLabel, withDateTemplate)
        assertNotEquals(apr30, may30)
    }
}
