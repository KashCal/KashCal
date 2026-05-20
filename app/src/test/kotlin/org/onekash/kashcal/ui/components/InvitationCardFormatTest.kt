package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.widget.formatUpcomingDayHeader
import org.robolectric.RobolectricTestRunner

/**
 * Pins the relative-day-label policy used by [InvitationCard] (today /
 * tomorrow / weekday). The card delegates to [formatUpcomingDayHeader],
 * which uses Android's locale-aware date pattern resolver and therefore
 * needs Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class InvitationCardFormatTest {

    @Test
    fun `today returns todayLabel`() {
        val today = 20260519
        val tomorrow = 20260520
        val result = formatUpcomingDayHeader(
            dayCode = today,
            todayDayCode = today,
            tomorrowDayCode = tomorrow,
            todayLabel = "Today",
            tomorrowLabel = "Tomorrow"
        )
        assertEquals("Today", result)
    }

    @Test
    fun `tomorrow returns tomorrowLabel`() {
        val today = 20260519
        val tomorrow = 20260520
        val result = formatUpcomingDayHeader(
            dayCode = tomorrow,
            todayDayCode = today,
            tomorrowDayCode = tomorrow,
            todayLabel = "Today",
            tomorrowLabel = "Tomorrow"
        )
        assertEquals("Tomorrow", result)
    }

    @Test
    fun `multi-day-out returns formatted day name`() {
        val today = 20260519
        val tomorrow = 20260520
        val target = 20260522
        val result = formatUpcomingDayHeader(
            dayCode = target,
            todayDayCode = today,
            tomorrowDayCode = tomorrow,
            todayLabel = "Today",
            tomorrowLabel = "Tomorrow"
        )
        assertEquals(false, result.equals("Today", ignoreCase = true))
        assertEquals(false, result.equals("Tomorrow", ignoreCase = true))
    }
}
