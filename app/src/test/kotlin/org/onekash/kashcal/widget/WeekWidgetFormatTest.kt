package org.onekash.kashcal.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
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

    // ==================== formatWidgetEventTime ====================

    private fun makeEvent(
        startTs: Long = 1000L,
        endTs: Long = 2000L,
        isAllDay: Boolean = false,
        startDay: Int = 0
    ) = WidgetDataRepository.WidgetEvent(
        eventId = 1L,
        occurrenceStartTs = startTs,
        title = "Test Event",
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        calendarColor = 0xFF0000FF.toInt(),
        isPast = false,
        isDeviceEvent = false,
        startDay = startDay
    )

    @Test
    fun `formatWidgetEventTime shows time on event start day`() {
        val zone = ZoneId.systemDefault()
        val startTs = LocalDateTime.of(2026, 4, 18, 22, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val event = makeEvent(startTs = startTs, endTs = startTs + 4 * 3600_000, startDay = 20260418)
        assertEquals("10:00pm", formatWidgetEventTime(event, 20260418, "h:mma", "All day"))
    }

    @Test
    fun `formatWidgetEventTime shows continuation marker on non-start day`() {
        val zone = ZoneId.systemDefault()
        val startTs = LocalDateTime.of(2026, 4, 18, 22, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val event = makeEvent(startTs = startTs, endTs = startTs + 4 * 3600_000, startDay = 20260418)
        assertEquals("\u25B8", formatWidgetEventTime(event, 20260419, "h:mma", "All day"))
    }

    @Test
    fun `formatWidgetEventTime shows all-day text regardless of dayCode`() {
        val event = makeEvent(isAllDay = true, startDay = 20260418)
        assertEquals("All day", formatWidgetEventTime(event, 20260419, "h:mma", "All day"))
        assertEquals("All day", formatWidgetEventTime(event, 20260418, "h:mma", "All day"))
    }

    @Test
    fun `formatWidgetEventTime shows continuation marker for middle day of multi-day event`() {
        val zone = ZoneId.systemDefault()
        val startTs = LocalDateTime.of(2026, 4, 17, 20, 0)
            .atZone(zone).toInstant().toEpochMilli()
        // 3-day event: Apr 17 8pm -> Apr 19 8am
        val event = makeEvent(startTs = startTs, endTs = startTs + 36 * 3600_000, startDay = 20260417)
        assertEquals("\u25B8", formatWidgetEventTime(event, 20260418, "h:mma", "All day"))
    }

    // ============ single-line contract (time column renders with maxLines=1) ============
    // The widget time column is a fixed-width Text rendered single-line. These
    // guard the invariant the layout depends on: the label is always a compact,
    // newline-free token, so timed values, the continuation marker, and the
    // all-day label all occupy exactly one line in the column.

    @Test
    fun `formatWidgetEventTime never contains a newline`() {
        val zone = ZoneId.systemDefault()
        // Widest 12-hour case: 12:30 PM with a space before the meridiem.
        val noonish = LocalDateTime.of(2026, 4, 18, 12, 30)
            .atZone(zone).toInstant().toEpochMilli()
        val timed = makeEvent(startTs = noonish, endTs = noonish + 3600_000, startDay = 20260418)
        val allDay = makeEvent(isAllDay = true, startDay = 20260418)

        val samples = listOf(
            formatWidgetEventTime(timed, 20260418, "h:mm a", "All day"),   // "12:30 pm"
            formatWidgetEventTime(timed, 20260418, "HH:mm", "All day"),    // "12:30" (24h)
            formatWidgetEventTime(timed, 20260419, "h:mm a", "All day"),   // continuation marker
            formatWidgetEventTime(allDay, 20260418, "h:mm a", "All day"),  // "All day"
        )
        samples.forEach { label ->
            assertEquals("time label must be single-line: '$label'", false, label.contains('\n'))
        }
    }
}
