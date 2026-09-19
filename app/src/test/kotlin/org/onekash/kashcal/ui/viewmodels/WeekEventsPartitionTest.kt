package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.weekview.allDayDisplayEvent
import org.onekash.kashcal.ui.components.weekview.multiDayDisplayEvent
import org.onekash.kashcal.ui.components.weekview.roomDisplayEvent
import java.time.LocalDate

/**
 * Unit tests for [WeekEventsUiState.fromEvents], the routing that decides whether
 * a multi-day *timed* event is shown in the timed grid or lifted into the all-day
 * strip. The partition must stay mutually exclusive and exhaustive: every input
 * event lands in exactly one of the two output lists regardless of the toggle.
 */
class WeekEventsPartitionTest {

    private val day = LocalDate.of(2026, 3, 2)

    private fun idsOf(events: List<DisplayEvent>) =
        events.map { (it as DisplayEvent.Room).event.id }.toSet()

    @Test
    fun `single-day timed event stays in the timed grid regardless of the toggle`() {
        val e = roomDisplayEvent(1, "meeting", day, hour = 10)
        for (flag in listOf(true, false)) {
            val state = WeekEventsUiState.fromEvents(listOf(e), showMultiDayTimedInAllDayStrip = flag)
            assertEquals("flag=$flag", setOf(1L), idsOf(state.timedEvents))
            assertTrue("flag=$flag", state.allDayEvents.isEmpty())
        }
    }

    @Test
    fun `multi-day timed event moves to the all-day strip when the toggle is on`() {
        val e = multiDayDisplayEvent(1, "overnight trip", day, day.plusDays(2), allDay = false)
        val state = WeekEventsUiState.fromEvents(listOf(e), showMultiDayTimedInAllDayStrip = true)
        assertEquals(setOf(1L), idsOf(state.allDayEvents))
        assertTrue(state.timedEvents.isEmpty())
    }

    @Test
    fun `multi-day timed event stays in the timed grid when the toggle is off`() {
        val e = multiDayDisplayEvent(1, "overnight trip", day, day.plusDays(2), allDay = false)
        val state = WeekEventsUiState.fromEvents(listOf(e), showMultiDayTimedInAllDayStrip = false)
        assertEquals(setOf(1L), idsOf(state.timedEvents))
        assertTrue(state.allDayEvents.isEmpty())
    }

    @Test
    fun `all-day events always go to the all-day strip whatever the toggle`() {
        val single = allDayDisplayEvent(1, "holiday", day)
        val multi = multiDayDisplayEvent(2, "vacation", day, day.plusDays(3), allDay = true)
        for (flag in listOf(true, false)) {
            val state = WeekEventsUiState.fromEvents(listOf(single, multi), showMultiDayTimedInAllDayStrip = flag)
            assertEquals("flag=$flag", setOf(1L, 2L), idsOf(state.allDayEvents))
            assertTrue("flag=$flag", state.timedEvents.isEmpty())
        }
    }

    @Test
    fun `partition is mutually exclusive and exhaustive for a mixed set`() {
        val events = listOf(
            roomDisplayEvent(1, "single timed", day, hour = 9),
            multiDayDisplayEvent(2, "multi timed", day, day.plusDays(1), allDay = false),
            allDayDisplayEvent(3, "single all-day", day),
            multiDayDisplayEvent(4, "multi all-day", day, day.plusDays(2), allDay = true),
        )
        for (flag in listOf(true, false)) {
            val state = WeekEventsUiState.fromEvents(events, showMultiDayTimedInAllDayStrip = flag)
            val timed = idsOf(state.timedEvents)
            val allDay = idsOf(state.allDayEvents)
            assertTrue("no event in both lists (flag=$flag)", timed.intersect(allDay).isEmpty())
            assertEquals("every event placed exactly once (flag=$flag)", setOf(1L, 2L, 3L, 4L), timed + allDay)
        }
    }
}
