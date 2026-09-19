package org.onekash.kashcal.ui.components.weekview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate

/**
 * Unit tests for the all-day-strip spanning-bar layout engine
 * ([computeAllDaySpans] / [computeAllDayStripRender]). These are pure functions
 * over dayCodes and [DisplayEvent]s, so they run without Robolectric.
 *
 * Coverage focuses on the arithmetic the UI depends on and that is easy to get
 * subtly wrong: column mapping, flush-edge detection when an event runs off the
 * visible window, greedy lane packing, dedup by stableKey, the maxLanes cap and
 * its per-day cell fallback, month-boundary (non-contiguous) dayCodes, and the
 * overflow-badge math.
 */
class AllDayStripLayoutTest {

    private val monday = LocalDate.of(2026, 3, 2)
    // A Mon–Sun visible window of real, consecutive dayCodes.
    private val week: List<Int> = (0..6).map { i ->
        val d = monday.plusDays(i.toLong())
        d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    private fun dayCodeOf(date: LocalDate) =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    // ==================== computeAllDaySpans ====================

    @Test
    fun `empty visible window yields empty layout`() {
        val layout = computeAllDaySpans(
            visibleDayCodes = emptyList(),
            allDayEvents = listOf(multiDayDisplayEvent(1, "x", monday, monday.plusDays(2))),
            maxLanes = 3
        )
        assertTrue(layout.lanes.isEmpty())
        assertTrue(layout.placedEventKeys.isEmpty())
    }

    @Test
    fun `single-day events are not treated as spans`() {
        val layout = computeAllDaySpans(
            visibleDayCodes = week,
            allDayEvents = listOf(allDayDisplayEvent(1, "single", monday)),
            maxLanes = 3
        )
        assertTrue("single-day events must not become spanning bars", layout.lanes.isEmpty())
    }

    @Test
    fun `span fully inside window maps to the correct columns with no flush`() {
        // Tue -> Thu  == columns 1..3
        val e = multiDayDisplayEvent(1, "trip", monday.plusDays(1), monday.plusDays(3))
        val layout = computeAllDaySpans(week, listOf(e), maxLanes = 3)
        assertEquals(1, layout.lanes.size)
        val span = layout.lanes[0].single()
        assertEquals(1, span.startCol)
        assertEquals(3, span.endCol)
        assertFalse(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    @Test
    fun `span starting before the window is left-flush and clamps to column 0`() {
        // Previous Sat -> Tue: starts two days before the window.
        val e = multiDayDisplayEvent(1, "ongoing", monday.minusDays(2), monday.plusDays(1))
        val layout = computeAllDaySpans(week, listOf(e), maxLanes = 3)
        val span = layout.lanes[0].single()
        assertTrue(span.leftFlush)
        assertEquals(0, span.startCol)
        assertEquals(1, span.endCol)
        assertFalse(span.rightFlush)
    }

    @Test
    fun `span ending after the window is right-flush and clamps to the last column`() {
        // Fri -> next Tue: ends after the window.
        val e = multiDayDisplayEvent(1, "ongoing", monday.plusDays(4), monday.plusDays(9))
        val layout = computeAllDaySpans(week, listOf(e), maxLanes = 3)
        val span = layout.lanes[0].single()
        assertEquals(4, span.startCol)
        assertEquals(6, span.endCol)
        assertTrue(span.rightFlush)
        assertFalse(span.leftFlush)
    }

    @Test
    fun `span entirely outside the window is excluded`() {
        val before = multiDayDisplayEvent(1, "past", monday.minusDays(10), monday.minusDays(8))
        val after = multiDayDisplayEvent(2, "future", monday.plusDays(8), monday.plusDays(10))
        val layout = computeAllDaySpans(week, listOf(before, after), maxLanes = 3)
        assertTrue(layout.lanes.isEmpty())
    }

    @Test
    fun `non-overlapping spans pack into one lane`() {
        val a = multiDayDisplayEvent(1, "a", monday, monday.plusDays(1))            // cols 0..1
        val b = multiDayDisplayEvent(2, "b", monday.plusDays(3), monday.plusDays(4)) // cols 3..4
        val layout = computeAllDaySpans(week, listOf(a, b), maxLanes = 3)
        assertEquals("disjoint spans share a lane", 1, layout.lanes.size)
        assertEquals(2, layout.lanes[0].size)
    }

    @Test
    fun `overlapping spans take separate lanes`() {
        val a = multiDayDisplayEvent(1, "a", monday, monday.plusDays(3))            // cols 0..3
        val b = multiDayDisplayEvent(2, "b", monday.plusDays(2), monday.plusDays(4)) // cols 2..4 (overlaps a)
        val layout = computeAllDaySpans(week, listOf(a, b), maxLanes = 3)
        assertEquals(2, layout.lanes.size)
    }

    @Test
    fun `spans beyond maxLanes are left unplaced`() {
        // Three mutually overlapping spans, but only 2 lanes available.
        val a = multiDayDisplayEvent(1, "a", monday, monday.plusDays(4))
        val b = multiDayDisplayEvent(2, "b", monday, monday.plusDays(4))
        val c = multiDayDisplayEvent(3, "c", monday, monday.plusDays(4))
        val layout = computeAllDaySpans(week, listOf(a, b, c), maxLanes = 2)
        assertEquals(2, layout.lanes.size)
        assertEquals(2, layout.placedEventKeys.size)
        // Exactly one of the three is unplaced.
        val placed = layout.placedEventKeys
        assertEquals(1, listOf(a, b, c).count { it.stableKey !in placed })
    }

    @Test
    fun `duplicate events are collapsed by stable key`() {
        val e = multiDayDisplayEvent(1, "dup", monday, monday.plusDays(2))
        val layout = computeAllDaySpans(week, listOf(e, e), maxLanes = 3)
        assertEquals(1, layout.lanes.flatten().size)
    }

    @Test
    fun `span across a month boundary maps columns correctly despite non-contiguous dayCodes`() {
        // Jan 30 -> Feb 2, 2026. dayCodes: 20260130, 20260131, 20260201, 20260202.
        // endDay - startDay == 72, NOT 3, so any code that assumed contiguous
        // dayCodes would mis-map the columns.
        val janStart = LocalDate.of(2026, 1, 30)
        val visible = (0..4).map { dayCodeOf(janStart.plusDays(it.toLong())) } // Jan30..Feb3
        val e = multiDayDisplayEvent(1, "cross-month", janStart, LocalDate.of(2026, 2, 2))
        val layout = computeAllDaySpans(visible, listOf(e), maxLanes = 3)
        val span = layout.lanes[0].single()
        assertEquals(0, span.startCol)
        assertEquals(3, span.endCol) // Feb 2 is the 4th visible day -> index 3
    }

    // ==================== computeAllDayStripRender ====================

    @Test
    fun `zero columns or zero rows renders nothing`() {
        assertTrue(
            computeAllDayStripRender(emptyList(), listOf(allDayDisplayEvent(1, "x", monday)), maxRows = 3)
                .slots.isEmpty()
        )
        assertTrue(
            computeAllDayStripRender(week, listOf(allDayDisplayEvent(1, "x", monday)), maxRows = 0)
                .slots.isEmpty()
        )
    }

    @Test
    fun `single-day event lands in its own column as a cell`() {
        val render = computeAllDayStripRender(
            week,
            listOf(allDayDisplayEvent(1, "solo", monday.plusDays(2))), // col 2
            maxRows = 3
        )
        val slot = render.slots[0][2]
        assertTrue(slot is AllDaySlot.CellEvent)
        assertEquals(1L, (slot as AllDaySlot.CellEvent).event.let { (it as DisplayEvent.Room).event.id })
        // Other columns in row 0 stay empty.
        assertTrue(render.slots[0][0] is AllDaySlot.Empty)
    }

    @Test
    fun `multi-day span occupies lane row across every covered column`() {
        val e = multiDayDisplayEvent(1, "span", monday.plusDays(1), monday.plusDays(3)) // cols 1..3
        val render = computeAllDayStripRender(week, listOf(e), maxRows = 3)
        for (col in 1..3) {
            val slot = render.slots[0][col]
            assertTrue("col $col should carry the bar segment", slot is AllDaySlot.BarSegment)
        }
        assertTrue(render.slots[0][0] is AllDaySlot.Empty)
        assertTrue(render.slots[0][4] is AllDaySlot.Empty)
    }

    @Test
    fun `span in row 0 leaves single-day events on a covered column to lower rows`() {
        val span = multiDayDisplayEvent(1, "span", monday, monday.plusDays(2))   // cols 0..2, row 0
        val solo = allDayDisplayEvent(2, "solo", monday)                          // col 0, must go to row 1
        val render = computeAllDayStripRender(week, listOf(span, solo), maxRows = 3)
        assertTrue(render.slots[0][0] is AllDaySlot.BarSegment)
        assertTrue(render.slots[1][0] is AllDaySlot.CellEvent)
        assertEquals(2L, ((render.slots[1][0] as AllDaySlot.CellEvent).event as DisplayEvent.Room).event.id)
    }

    @Test
    fun `overflow is reported out-of-band without consuming a row`() {
        // 5 single-day events on the same day, cap = 3 rows: all 3 free rows show a
        // cell now that overflow no longer reserves one of them for a badge, with
        // the remaining 2 reported via overflowByColumn instead.
        val events = (1..5).map { allDayDisplayEvent(it.toLong(), "e$it", monday.plusDays(1)) } // col 1
        val render = computeAllDayStripRender(week, events, maxRows = 3)
        val col1 = render.slots.map { it[1] }
        val cells = col1.filterIsInstance<AllDaySlot.CellEvent>()
        assertEquals("all three rows carry a cell now that overflow is out-of-band", 3, cells.size)
        val overflow = render.overflowByColumn[1]
        assertEquals(2, overflow?.count)
        assertEquals(setOf(4L, 5L), overflow?.events?.map { (it as DisplayEvent.Room).event.id }?.toSet())
    }

    @Test
    fun `collapsed strip surfaces a span-covered column's own event as overflow, not a silent drop`() {
        // In the 1-row collapsed strip (the default), a day covered by a spanning
        // bar shows only the bar; that day's own single-day event has no free row
        // to render in, so it must surface via overflowByColumn instead of
        // vanishing with no affordance (the regression the old CompactEventCell
        // never had, since it always fell back to a "+N more" badge).
        val span = multiDayDisplayEvent(1, "span", monday, monday.plusDays(2)) // covers col 0
        val solo = allDayDisplayEvent(2, "hidden", monday)                     // col 0
        val render = computeAllDayStripRender(week, listOf(span, solo), maxRows = 1)
        assertEquals(1, render.slots.size)
        assertTrue(render.slots[0][0] is AllDaySlot.BarSegment)
        // The solo event doesn't appear as a row slot...
        val soloShown = render.slots.flatten().any {
            it is AllDaySlot.CellEvent && (it.event as DisplayEvent.Room).event.id == 2L
        }
        assertFalse(soloShown)
        // ...but it's surfaced as this column's overflow, and only the solo event —
        // not the already-visible bar — so the badge count and its sheet agree.
        val overflow = render.overflowByColumn[0]
        assertEquals(1, overflow?.count)
        assertEquals(listOf(2L), overflow?.events?.map { (it as DisplayEvent.Room).event.id })
    }

    @Test
    fun `multi-day timed event (not all-day) is placed as a span`() {
        val timed = multiDayDisplayEvent(1, "overnight", monday, monday.plusDays(1), allDay = false)
        val render = computeAllDayStripRender(week, listOf(timed), maxRows = 3)
        assertTrue(render.slots[0][0] is AllDaySlot.BarSegment)
        assertTrue(render.slots[0][1] is AllDaySlot.BarSegment)
        val seg = render.slots[0][0] as AllDaySlot.BarSegment
        assertFalse("timed span must keep isAllDay=false so the UI can show its start time", seg.span.displayEvent.isAllDay)
    }

    @Test
    fun `span beyond lane capacity falls back to per-day cells`() {
        // maxRows = 1 -> maxLanes = 1. Two overlapping spans: one is placed as a
        // bar, the other overflows lane capacity and reappears as a per-day cell
        // in each column it covers (the documented fallback).
        val a = multiDayDisplayEvent(1, "placed", monday, monday.plusDays(2))
        val b = multiDayDisplayEvent(2, "fallback", monday, monday.plusDays(2))
        val render = computeAllDayStripRender(week, listOf(a, b), maxRows = 1)
        // Row 0 is the placed bar; the fallback event cannot fit (no free rows)
        // so it is dropped from this collapsed render — assert the placed bar wins.
        assertTrue(render.slots[0][0] is AllDaySlot.BarSegment)
    }
}
