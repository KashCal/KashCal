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
    fun `formatUpcomingDayHeader returns todayLabel when dayCode equals todayDayCode`() {
        val result = formatUpcomingDayHeader(
            dayCode = 20260428,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(todayLabel, result)
    }

    @Test
    fun `formatUpcomingDayHeader returns tomorrowLabel when dayCode is LocalDate_plusDays 1`() {
        val result = formatUpcomingDayHeader(
            dayCode = 20260429,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, result)
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
    fun `formatUpcomingDayHeader returns tomorrowLabel when tomorrow crosses month boundary`() {
        val result = formatUpcomingDayHeader(
            dayCode = 20260501,
            todayDayCode = 20260430,
            tomorrowDayCode = tomorrowDayCodeOf(20260430),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, result)
    }

    @Test
    fun `formatUpcomingDayHeader returns tomorrowLabel when tomorrow crosses year boundary`() {
        val result = formatUpcomingDayHeader(
            dayCode = 20270101,
            todayDayCode = 20261231,
            tomorrowDayCode = tomorrowDayCodeOf(20261231),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
        )
        assertEquals(tomorrowLabel, result)
    }

    @Test
    fun `formatUpcomingDayHeader returns weekday and date for day in same month`() {
        // 20260501 is Friday.
        val result = formatUpcomingDayHeader(
            dayCode = 20260501,
            todayDayCode = 20260428,
            tomorrowDayCode = tomorrowDayCodeOf(20260428),
            todayLabel = todayLabel,
            tomorrowLabel = tomorrowLabel
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
            tomorrowLabel = tomorrowLabel
        )
        assertEquals("Wed, May 27", result)
    }

    @Test
    fun `formatUpcomingDayHeader is unambiguous across months`() {
        // Two days in different months must render differently.
        val tomorrow = tomorrowDayCodeOf(20260428)
        val apr30 = formatUpcomingDayHeader(20260430, 20260428, tomorrow, todayLabel, tomorrowLabel)
        val may30 = formatUpcomingDayHeader(20260530, 20260428, tomorrow, todayLabel, tomorrowLabel)
        assertNotEquals(apr30, may30)
    }
}
