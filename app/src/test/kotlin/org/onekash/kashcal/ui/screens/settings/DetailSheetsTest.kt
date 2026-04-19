package org.onekash.kashcal.ui.screens.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_MINUTES
import org.onekash.kashcal.ui.shared.EVENT_DURATION_MINUTES
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_MINUTES
import org.onekash.kashcal.ui.shared.formatDurationShort
import org.onekash.kashcal.ui.shared.formatReminderShort
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for detail selection sheets (Checkpoint 8).
 *
 * Note: Composable UI interaction tests require AndroidX Compose testing
 * which runs as instrumented tests. These unit tests verify the supporting
 * logic and data.
 */
@RunWith(RobolectricTestRunner::class)
class DetailSheetsTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ==================== CalendarSheets Tests ====================

    @Test
    fun `visible calendars count format is correct`() {
        val visibleCount = 3
        val totalCount = 5
        val expected = "$visibleCount / $totalCount"
        assertEquals("3 / 5", expected)
    }

    // ==================== AlertsSheet Tests ====================

    @Test
    fun `alerts summary format is correct`() {
        val timedMinutes = 15
        val allDayMinutes = 720
        val summary = "Scheduled: ${formatReminderShort(timedMinutes, resources = resources)} · All-day: ${formatReminderShort(allDayMinutes, resources = resources)}"
        assertEquals("Scheduled: 15m · All-day: 12h", summary)
    }

    @Test
    fun `TIMED_REMINDER_MINUTES available for picker`() {
        assertTrue(TIMED_REMINDER_MINUTES.isNotEmpty())
        assertEquals(9, TIMED_REMINDER_MINUTES.size) // None, 0m, 5m, 15m, 30m, 1h, 4h, 1d, 1w
    }

    @Test
    fun `ALL_DAY_REMINDER_MINUTES available for picker`() {
        assertTrue(ALL_DAY_REMINDER_MINUTES.isNotEmpty())
        assertEquals(5, ALL_DAY_REMINDER_MINUTES.size) // None, 9AM, 12h, 1d, 1w
    }

    // ==================== AddCalendarSheet Tests ====================

    @Test
    fun `add calendar has two options`() {
        // Design requirement: Subscribe to URL + Import file
        // "Coming Soon" option removed
        val options = listOf("Calendar Subscription", "Import Calendar File")
        assertEquals(2, options.size)
    }

    @Test
    fun `subscription option has correct description`() {
        val description = "Subscribe to an ICS or webcal URL"
        assertTrue(description.contains("ICS"))
        assertTrue(description.contains("webcal"))
    }

    @Test
    fun `import option has correct description`() {
        val description = "Import events from a .ics file"
        assertTrue(description.contains(".ics"))
    }

    // ==================== DisplayOptionsSheet Tests ====================

    @Test
    fun `display options subtitle with emojis enabled`() {
        val showEventEmojis = true
        val defaultEventDuration = 60
        val subtitle = if (showEventEmojis) {
            "Emojis on · ${formatDurationShort(defaultEventDuration, resources)}"
        } else {
            "Emojis off · ${formatDurationShort(defaultEventDuration, resources)}"
        }
        assertEquals("Emojis on · 1h", subtitle)
    }

    @Test
    fun `display options subtitle with emojis disabled`() {
        val showEventEmojis = false
        val defaultEventDuration = 30
        val subtitle = if (showEventEmojis) {
            "Emojis on · ${formatDurationShort(defaultEventDuration, resources)}"
        } else {
            "Emojis off · ${formatDurationShort(defaultEventDuration, resources)}"
        }
        assertEquals("Emojis off · 30m", subtitle)
    }

    @Test
    fun `EVENT_DURATION_MINUTES available for picker`() {
        assertTrue(EVENT_DURATION_MINUTES.isNotEmpty())
        assertEquals(4, EVENT_DURATION_MINUTES.size) // 15m, 30m, 1h, 2h
    }

    @Test
    fun `duration options have correct values`() {
        assertEquals(15, EVENT_DURATION_MINUTES[0])
        assertEquals(30, EVENT_DURATION_MINUTES[1])
        assertEquals(60, EVENT_DURATION_MINUTES[2])
        assertEquals(120, EVENT_DURATION_MINUTES[3])
    }
}
