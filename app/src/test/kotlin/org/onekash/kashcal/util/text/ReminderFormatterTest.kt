package org.onekash.kashcal.util.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReminderFormatterTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ========== formatDuration (ISO) ==========

    @Test
    fun `formatDuration - formats minutes`() {
        assertEquals("15 min before", formatDuration("-PT15M", resources))
        assertEquals("30 min before", formatDuration("-PT30M", resources))
        assertEquals("1 min before", formatDuration("-PT1M", resources))
    }

    @Test
    fun `formatDuration - formats hours`() {
        assertEquals("1 hour before", formatDuration("-PT1H", resources))
        assertEquals("2 hours before", formatDuration("-PT2H", resources))
    }

    @Test
    fun `formatDuration - formats days`() {
        assertEquals("1 day before", formatDuration("-P1D", resources))
        assertEquals("2 days before", formatDuration("-P2D", resources))
        assertEquals("7 days before", formatDuration("-P7D", resources))
    }

    @Test
    fun `formatDuration - formats combined durations`() {
        assertEquals("1 hour 30 min before", formatDuration("-PT1H30M", resources))
        assertEquals("1 day 2 hours before", formatDuration("-P1DT2H", resources))
    }

    @Test
    fun `formatDuration - handles positive duration (after)`() {
        assertEquals("30 min after", formatDuration("PT30M", resources))
        assertEquals("1 hour after", formatDuration("PT1H", resources))
    }

    @Test
    fun `formatDuration - handles zero duration`() {
        assertEquals("At time of event", formatDuration("PT0M", resources))
        assertEquals("At time of event", formatDuration("P0D", resources))
    }

    @Test
    fun `formatDuration - returns null for invalid duration`() {
        assertNull(formatDuration("", resources))
        assertNull(formatDuration("invalid", resources))
        assertNull(formatDuration("15 minutes", resources))
    }

    // ========== formatRemindersForDisplay ==========

    @Test
    fun `formatRemindersForDisplay - formats list`() {
        val reminders = listOf("-PT15M", "-P1D")
        assertEquals("15 min before, 1 day before", formatRemindersForDisplay(reminders, resources))
    }

    @Test
    fun `formatRemindersForDisplay - returns null for empty`() {
        assertNull(formatRemindersForDisplay(null, resources))
        assertNull(formatRemindersForDisplay(emptyList(), resources))
    }

    @Test
    fun `formatRemindersForDisplay - skips invalid entries`() {
        val reminders = listOf("-PT15M", "invalid", "-PT1H")
        assertEquals("15 min before, 1 hour before", formatRemindersForDisplay(reminders, resources))
    }

    // ========== formatRemindersFromMinutes ==========

    @Test
    fun `formatRemindersFromMinutes with 15 returns 15 min before`() {
        assertEquals("15 min before", formatRemindersFromMinutes(listOf(15), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 60 returns 1 hour before`() {
        assertEquals("1 hour before", formatRemindersFromMinutes(listOf(60), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 120 returns 2 hours before`() {
        assertEquals("2 hours before", formatRemindersFromMinutes(listOf(120), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 1440 returns 1 day before`() {
        assertEquals("1 day before", formatRemindersFromMinutes(listOf(1440), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 2880 returns 2 days before`() {
        assertEquals("2 days before", formatRemindersFromMinutes(listOf(2880), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with multiple returns comma-separated sorted`() {
        assertEquals(
            "15 min before, 1 hour before, 1 day before",
            formatRemindersFromMinutes(listOf(60, 15, 1440), resources)
        )
    }

    @Test
    fun `formatRemindersFromMinutes with empty list returns null`() {
        assertNull(formatRemindersFromMinutes(emptyList(), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 0 returns At time of event`() {
        assertEquals("At time of event", formatRemindersFromMinutes(listOf(0), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 1 returns 1 min before`() {
        assertEquals("1 min before", formatRemindersFromMinutes(listOf(1), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with 90 returns 1 hour 30 min before`() {
        assertEquals("1 hour 30 min before", formatRemindersFromMinutes(listOf(90), resources))
    }

    @Test
    fun `formatRemindersFromMinutes with negative value coerces to 0`() {
        assertEquals("At time of event", formatRemindersFromMinutes(listOf(-15), resources))
    }
}
