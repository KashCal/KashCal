package org.onekash.kashcal.widget

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/**
 * Unit tests for [WidgetPreviewData] — the sample content the widget-picker previews
 * render instead of real events.
 *
 * The invariants here are what keep a preview from looking broken: day codes must be
 * derived from today (a hardcoded date would show a stale month), each event's
 * `startDay` must match the day it is filed under (otherwise the row renders a
 * multi-day continuation arrow instead of a time), and nothing may be flagged past or
 * cancelled (both render struck-through).
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPreviewDataTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun dayCodeOf(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    private fun allSampleTitles(context: Context): List<String> =
        (
            WidgetPreviewData.agendaEvents(context) +
                WidgetPreviewData.weekEvents(context).values.flatten() +
                WidgetPreviewData.monthEvents(context).values.flatten() +
                WidgetPreviewData.upcomingEvents(context).values.flatten()
            ).map { it.title }

    @Test
    fun `agenda samples are all filed under today`() {
        val today = dayCodeOf(LocalDate.now())
        val events = WidgetPreviewData.agendaEvents(context)

        assertTrue("expected more than one agenda sample", events.size > 1)
        events.forEach { assertEquals(today, it.startDay) }
    }

    @Test
    fun `agenda samples include an all-day event and timed events`() {
        val events = WidgetPreviewData.agendaEvents(context)

        assertTrue("expected an all-day sample", events.any { it.isAllDay })
        assertTrue("expected at least two timed samples", events.count { !it.isAllDay } >= 2)
    }

    @Test
    fun `week map is keyed by the seven day codes starting today`() {
        val expected = (0..6).map { dayCodeOf(LocalDate.now().plusDays(it.toLong())) }

        val week = WidgetPreviewData.weekEvents(context)

        assertEquals(expected.toSet(), week.keys)
    }

    @Test
    fun `week map has populated days and at least one empty day`() {
        val week = WidgetPreviewData.weekEvents(context)

        assertTrue("expected at least one populated day", week.any { it.value.isNotEmpty() })
        assertTrue("expected at least one empty day", week.any { it.value.isEmpty() })
    }

    @Test
    fun `every month sample lands on a cell the grid actually draws a dot on`() {
        // Being inside the grid's 42-cell range is not enough: the grid fades
        // adjacent-month cells without drawing dots, and drops trailing all-next-month
        // rows entirely, so a sample outside the displayed month is invisible.
        val today = LocalDate.now()

        val monthEvents = WidgetPreviewData.monthEvents(context)

        assertTrue("expected month samples", monthEvents.isNotEmpty())
        monthEvents.keys.forEach { dayCode ->
            assertEquals(
                "$dayCode is not in the displayed month, so no dot renders for it",
                today.year * 10000 + today.monthValue * 100,
                dayCode - dayCode % 100
            )
        }
    }

    @Test
    fun `month samples spread several dots across the month on every date of the year`() {
        // Sampling by offset from today used to collapse to a single dot near month end.
        // Walk a whole year of leap-year dates so a month-end regression can't hide behind
        // whatever today happens to be.
        var start = LocalDate.of(2024, 1, 1)
        while (start.year == 2024) {
            val inMonth = WidgetPreviewData.monthSampleDates(start)
            assertTrue(
                "only ${inMonth.size} dot(s) would render for a preview published on $start",
                inMonth.size >= 4
            )
            inMonth.forEach {
                assertEquals("sample $it is outside the month of $start", start.month, it.month)
            }
            start = start.plusDays(1)
        }
    }

    @Test
    fun `month grid is the current month`() {
        val now = LocalDate.now()

        val grid = WidgetPreviewData.monthGrid()

        assertEquals(now.year, grid.year)
        assertEquals(now.monthValue - 1, grid.month)
    }

    @Test
    fun `upcoming map skips empty days and spans more than one day`() {
        val upcoming = WidgetPreviewData.upcomingEvents(context)

        assertTrue("expected more than one day of upcoming samples", upcoming.size > 1)
        upcoming.forEach { (dayCode, events) ->
            assertTrue("day $dayCode should not be empty", events.isNotEmpty())
        }
    }

    @Test
    fun `upcoming map starts today and stays inside the widget horizon`() {
        val today = dayCodeOf(LocalDate.now())
        val lastInHorizon = dayCodeOf(LocalDate.now().plusDays((UPCOMING_HORIZON_DAYS - 1).toLong()))

        val upcoming = WidgetPreviewData.upcomingEvents(context)

        assertTrue("expected samples on today", upcoming.containsKey(today))
        upcoming.keys.forEach { dayCode ->
            assertTrue("$dayCode outside horizon $today..$lastInHorizon", dayCode in today..lastInHorizon)
        }
    }

    @Test
    fun `every sample event startDay equals the day code it is filed under`() {
        val maps = listOf(
            "week" to WidgetPreviewData.weekEvents(context),
            "month" to WidgetPreviewData.monthEvents(context),
            "upcoming" to WidgetPreviewData.upcomingEvents(context)
        )

        maps.forEach { (label, map) ->
            map.forEach { (dayCode, events) ->
                events.forEach { event ->
                    assertEquals(
                        "$label sample \"${event.title}\" filed under $dayCode but startDay is ${event.startDay}",
                        dayCode,
                        event.startDay
                    )
                }
            }
        }
    }

    @Test
    fun `no sample event is past or cancelled`() {
        val all = WidgetPreviewData.agendaEvents(context) +
            WidgetPreviewData.weekEvents(context).values.flatten() +
            WidgetPreviewData.monthEvents(context).values.flatten() +
            WidgetPreviewData.upcomingEvents(context).values.flatten()

        assertTrue("expected samples across all widgets", all.isNotEmpty())
        all.forEach { event ->
            assertFalse("\"${event.title}\" is flagged past", event.isPast)
            assertFalse("\"${event.title}\" is flagged cancelled", event.isCancelled)
        }
    }

    @Test
    fun `every sample event ends after it starts and carries a colour`() {
        val all = WidgetPreviewData.agendaEvents(context) +
            WidgetPreviewData.weekEvents(context).values.flatten() +
            WidgetPreviewData.upcomingEvents(context).values.flatten()

        all.forEach { event ->
            assertTrue("\"${event.title}\" ends before it starts", event.endTs > event.startTs)
            assertTrue("\"${event.title}\" has no calendar colour", event.calendarColor != 0)
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `sample titles are localized rather than hardcoded English`() {
        // The test class runs under a French configuration, so `context` resolves French
        // resources. Building the same samples through an explicitly English context gives
        // the source strings to compare against.
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )

        val frenchTitles = allSampleTitles(context)
        val englishTitles = allSampleTitles(english)

        assertTrue("expected sample titles", frenchTitles.isNotEmpty())
        assertEquals(
            "both configurations should yield the same number of samples",
            englishTitles.size,
            frenchTitles.size
        )
        // Guard the guard: prove the French resources really loaded, so the assertion
        // below can't pass merely because the configuration was ignored.
        assertEquals(
            "French configuration did not take effect",
            "Point d'équipe",
            context.getString(R.string.widget_preview_event_standup)
        )
        // A hardcoded literal renders identically under both configurations.
        val untranslated = frenchTitles.zip(englishTitles)
            .filter { (fr, en) -> fr == en }
            .map { it.second }
        assertTrue(
            "these sample titles are still the English source text: $untranslated",
            untranslated.isEmpty()
        )
    }

    @Test
    fun `the per-day cap leaves room for the busiest sample day`() {
        // Exceeding the cap makes the widget render a "+N more" overflow row instead of
        // the events themselves, which is not what a preview should advertise.
        val busiest = listOf(
            WidgetPreviewData.agendaEvents(context).size,
            WidgetPreviewData.weekEvents(context).values.maxOf { it.size },
            WidgetPreviewData.upcomingEvents(context).values.maxOf { it.size }
        ).max()

        assertTrue(
            "busiest sample day has $busiest events but the cap is " +
                "${WidgetPreviewData.MAX_EVENTS_PER_DAY}",
            busiest <= WidgetPreviewData.MAX_EVENTS_PER_DAY
        )
    }

    @Test
    fun `the time pattern follows the device 24-hour setting`() {
        val is24Hour = DateFormat.is24HourFormat(context)

        assertEquals(
            if (is24Hour) "HH:mm" else "h:mm a",
            WidgetPreviewData.timePattern(context)
        )
    }
}
