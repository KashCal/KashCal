package org.onekash.kashcal.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WeekWidgetFormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // ==================== formatWeekHeaderRange ====================

    @Test
    fun `formatWeekHeaderRange same month shows month once`() {
        // March 7 - March 13 -> "March 7 – 13"
        assertEquals("March 7 \u2013 13", formatWeekHeaderRange(20260307, 20260313))
    }

    @Test
    fun `formatWeekHeaderRange cross month shows both months`() {
        // March 28 - April 3 -> "March 28 – April 3"
        assertEquals("March 28 \u2013 April 3", formatWeekHeaderRange(20260328, 20260403))
    }

    @Test
    fun `formatWeekHeaderRange cross year shows both months`() {
        // December 29 - January 4 -> "December 29 – January 4"
        assertEquals("December 29 \u2013 January 4", formatWeekHeaderRange(20261229, 20270104))
    }

    @Test
    fun `formatWeekHeaderRange single day`() {
        // Edge case: same day -> "March 7 – 7"
        assertEquals("March 7 \u2013 7", formatWeekHeaderRange(20260307, 20260307))
    }

    // ==================== formatDayHeaderText ====================

    @Test
    fun `formatDayHeaderText returns full day name with day number`() {
        // 20260307 is Saturday
        assertEquals("Saturday 7", formatDayHeaderText(20260307))
    }

    @Test
    fun `formatDayHeaderText handles longest day name`() {
        // 20260311 is Wednesday
        assertEquals("Wednesday 11", formatDayHeaderText(20260311))
    }

    @Test
    fun `formatDayHeaderText handles single digit day`() {
        // 20260302 is Monday
        assertEquals("Monday 2", formatDayHeaderText(20260302))
    }
}
