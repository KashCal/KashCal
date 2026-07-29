package org.onekash.kashcal.widget

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Tests for [formatWidgetEventTimeRange] — the detailed-row time line that shows a
 * start-end range instead of just the start instant.
 *
 * Range formatting (dash glyph, shared am/pm collapse, RTL, digit shaping) is delegated
 * to Android's [android.text.format.DateUtils.formatDateRange], which follows the device's
 * system 12/24h setting and locale. These tests assert the branch logic and robust
 * properties of the output rather than an exact platform-formatted string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WidgetTimeRangeFormatterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        set24HourSetting("12")
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private fun set24HourSetting(value: String) {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, value)
    }

    private fun tsOf(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, mo, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun makeEvent(
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false,
        startDay: Int,
        isPast: Boolean = false,
        isCancelled: Boolean = false
    ) = WidgetDataRepository.WidgetEvent(
        eventId = 1L,
        occurrenceStartTs = startTs,
        title = "Test Event",
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        calendarColor = 0xFF0000FF.toInt(),
        isPast = isPast,
        isDeviceEvent = false,
        startDay = startDay,
        isCancelled = isCancelled
    )

    @Test
    fun `all-day event shows all-day text regardless of dayCode`() {
        val event = makeEvent(
            startTs = tsOf(2026, 4, 18, 0, 0),
            endTs = tsOf(2026, 4, 18, 23, 59),
            isAllDay = true,
            startDay = 20260418
        )
        assertEquals("All day", formatWidgetEventTimeRange(context, event, 20260418, "h:mm a", "All day"))
        assertEquals("All day", formatWidgetEventTimeRange(context, event, 20260419, "h:mm a", "All day"))
    }

    @Test
    fun `event continuing from a previous day shows continuation marker and end time`() {
        // Apr 18 10pm -> Apr 19 8am. Rendered on Apr 19 (a day it did NOT start on).
        val start = tsOf(2026, 4, 18, 22, 0)
        val end = tsOf(2026, 4, 19, 8, 0)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260419, "h:mm a", "All day")

        assertTrue("must carry the continuation marker: '$result'", result.contains("▸"))
        assertTrue("must show the end time: '$result'", result.contains("8:00"))
        assertFalse("must be single-line: '$result'", result.contains('\n'))
    }

    @Test
    fun `multi-day event on an interior day carries date context on its end time`() {
        // Apr 18 10pm -> Apr 22 8am. Rendered on Apr 20 — a day it neither starts
        // nor ends on. A bare "▸ 8:00 AM" would read as "ends today"; the end is
        // actually two days out, so a date token must accompany it.
        val start = tsOf(2026, 4, 18, 22, 0)
        val end = tsOf(2026, 4, 22, 8, 0)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260420, "h:mm a", "All day")

        assertTrue("must carry the continuation marker: '$result'", result.contains("▸"))
        // With FORMAT_ABBREV_ALL a zero-minute time renders as "8 AM" (no ":00"),
        // and newer ICU separates the meridiem with a narrow no-break space — so
        // assert on the hour digit and meridiem token rather than an exact string.
        assertTrue("must show the end hour: '$result'", result.contains("8"))
        assertTrue("must show the meridiem: '$result'", result.uppercase().contains("AM"))
        val hasDateContext = result.contains(Regex("""Apr|/|\d{1,2}[/.]"""))
        assertTrue("interior-day end must carry date context: '$result'", hasDateContext)
    }

    @Test
    fun `event continuing into the current day shows a bare end time without date`() {
        // Apr 18 10pm -> Apr 19 8am, rendered on Apr 19 (the day it ends). The end
        // is today, so no date token is needed — a bare time is unambiguous.
        val start = tsOf(2026, 4, 18, 22, 0)
        val end = tsOf(2026, 4, 19, 8, 0)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260419, "h:mm a", "All day")

        assertTrue("must carry the continuation marker: '$result'", result.contains("▸"))
        assertTrue("must show the end time: '$result'", result.contains("8:00"))
        assertFalse("end is today, no date token expected: '$result'", result.contains("Apr"))
    }

    @Test
    fun `same-day timed event shows a start-end range`() {
        // 9:30am -> 10:30am, same day.
        val start = tsOf(2026, 4, 18, 9, 30)
        val end = tsOf(2026, 4, 18, 10, 30)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260418, "h:mm a", "All day")

        assertTrue("must show start: '$result'", result.contains("9:30"))
        assertTrue("must show end: '$result'", result.contains("10:30"))
        assertFalse("must be single-line: '$result'", result.contains('\n'))
    }

    @Test
    fun `event that starts today but ends a future day is disambiguated with date context`() {
        // Apr 18 9am -> Apr 20 5pm, rendered on Apr 18 (the day it STARTS).
        // Without date context, FORMAT_SHOW_TIME alone would render two bare times
        // ("9:00 AM - 5:00 PM") that are actually two days apart — ambiguous.
        val start = tsOf(2026, 4, 18, 9, 0)
        val end = tsOf(2026, 4, 20, 17, 0)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260418, "h:mm a", "All day")

        assertFalse("must be single-line: '$result'", result.contains('\n'))
        // A date token (month name or numeric date) must appear so the two times
        // aren't mistaken for a same-day range.
        val hasDateContext = result.contains(Regex("""Apr|/|\d{1,2}[/.]"""))
        assertTrue("cross-day range must carry date context: '$result'", hasDateContext)
    }

    @Test
    fun `24-hour pattern produces 24-hour range output`() {
        // 2:30pm -> 3:30pm. With a 24h pattern this is 14:30 - 15:30, no am/pm token.
        val start = tsOf(2026, 4, 18, 14, 30)
        val end = tsOf(2026, 4, 18, 15, 30)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260418, "HH:mm", "All day")

        assertTrue("24h output must contain 14:30: '$result'", result.contains("14:30"))
        assertTrue("24h output must contain 15:30: '$result'", result.contains("15:30"))
        assertFalse("24h output must not contain AM/PM: '$result'", result.uppercase().contains("PM"))
    }

    @Test
    fun `resolved pattern drives the clock even when the device setting disagrees`() {
        // Device is on 12h (setUp pins TIME_12_24="12"), but the resolved pattern is
        // 24h — mirroring an in-app 24h override on a 12h device. The pattern must win
        // so the detailed row matches the compact row's clock, not the device setting.
        set24HourSetting("12")
        val start = tsOf(2026, 4, 18, 14, 30)
        val end = tsOf(2026, 4, 18, 15, 30)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418)

        val result = formatWidgetEventTimeRange(context, event, 20260418, "HH:mm", "All day")

        assertTrue("pattern-driven 24h output must contain 14:30: '$result'", result.contains("14:30"))
        assertFalse("pattern-driven 24h output must not contain AM/PM: '$result'", result.uppercase().contains("PM"))
    }

    @Test
    fun `past event still formats a range`() {
        val start = tsOf(2026, 4, 18, 9, 30)
        val end = tsOf(2026, 4, 18, 10, 30)
        val event = makeEvent(startTs = start, endTs = end, startDay = 20260418, isPast = true)

        val result = formatWidgetEventTimeRange(context, event, 20260418, "h:mm a", "All day")

        assertTrue("past event still shows start: '$result'", result.contains("9:30"))
        assertTrue("past event still shows end: '$result'", result.contains("10:30"))
    }
}
