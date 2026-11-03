package org.onekash.kashcal.ui.screens.monthfull

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.ZoneOffset

class MonthFullSpanLayoutTest {

    // -------- Fixtures --------

    private val cal = Calendar(
        id = 10L,
        accountId = 1L,
        caldavUrl = "https://example.com/cal/",
        displayName = "Cal",
        color = 0xFF2196F3.toInt(),
        isReadOnly = false
    )

    /** Builds a Room DisplayEvent with overrides on the fields under test. */
    private fun roomEvent(
        id: Long,
        startDay: Int,
        endDay: Int,
        isAllDay: Boolean = false,
        transp: String = "OPAQUE",
        eventColor: Int? = null,
        occStartTs: Long = 1_700_000_000_000L + id * 1000,
    ): DisplayEvent.Room {
        // A timed event's days come from its instants, so put it on [startDay] at 09:00 UTC plus
        // the sub-hour part of [occStartTs] (which orders events) and end it on [endDay]. All-day
        // events keep their stored codes and take [occStartTs] as is.
        val start = if (isAllDay) occStartTs else utcMidnight(startDay) + 9 * HOUR + occStartTs % HOUR
        val end = when {
            isAllDay -> occStartTs + HOUR
            endDay > startDay -> utcMidnight(endDay) + 10 * HOUR
            else -> start + HOUR
        }
        val event = Event(
            id = id,
            uid = "uid-$id",
            calendarId = 10L,
            title = "E$id",
            startTs = start,
            endTs = end,
            dtstamp = start,
            isAllDay = isAllDay,
            transp = transp,
            color = eventColor,
        )
        val occ = Occurrence(
            id = 1000L + id,
            eventId = id,
            calendarId = 10L,
            startTs = start,
            endTs = end,
            startDay = startDay,
            endDay = endDay,
        )
        return DisplayEvent.Room(event, occ, cal, zone = ZoneOffset.UTC)
    }

    private fun utcMidnight(dayCode: Int): Long =
        java.time.LocalDate.of(dayCode / 10000, dayCode / 100 % 100, dayCode % 100)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val HOUR = 3_600_000L

    /** Sun..Sat dayCodes for the week of 2026-03-08. */
    private val weekSunToSat = listOf(20260308, 20260309, 20260310, 20260311, 20260312, 20260313, 20260314)

    /** Following week, Sun..Sat. */
    private val weekSunToSat2 = listOf(20260315, 20260316, 20260317, 20260318, 20260319, 20260320, 20260321)

    /** Builds a bucket map with each multi-day event in every day it spans. */
    private fun bucketize(events: List<DisplayEvent>): Map<Int, List<DisplayEvent>> {
        val out = mutableMapOf<Int, MutableList<DisplayEvent>>()
        for (e in events) {
            val codes = if (e.startDay == e.endDay) listOf(e.startDay) else generateDayCodes(e.startDay, e.endDay)
            for (code in codes) {
                out.getOrPut(code) { mutableListOf() }.add(e)
            }
        }
        return out
    }

    /** Returns the dayCodes from start to end inclusive; handles only a same-month range. */
    private fun generateDayCodes(startDay: Int, endDay: Int): List<Int> {
        require(startDay <= endDay)
        val out = mutableListOf<Int>()
        var d = startDay
        while (d <= endDay) {
            out.add(d)
            // Plain increment: the fixtures stay within 2026-03
            d++
        }
        return out
    }

    // -------- Eligibility --------

    @Test
    fun `single-day all-day event NOT in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(0, layout.lanes.sumOf { it.size })
        assertTrue(layout.multiDayEventKeys.isEmpty())
    }

    @Test
    fun `single-day timed event NOT in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `multi-day timed event IS in spans`() {
        val e = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = false)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `multi-day all-day event IS in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
    }

    // -------- Identity & dedup --------

    @Test
    fun `same identity key in N buckets produces ONE span`() {
        val e = roomEvent(id = 42, startDay = 20260310, endDay = 20260313, isAllDay = true, occStartTs = 1_730_000_000_000L)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
        assertEquals(setOf("room:42:1730000000000"), layout.multiDayEventKeys)
    }

    @Test
    fun `two recurrences produce two distinct spans`() {
        val occ1Ts = 1_730_000_000_000L
        val occ2Ts = 1_730_086_400_000L  // next day
        val e1 = roomEvent(id = 7, startDay = 20260308, endDay = 20260309, isAllDay = true, occStartTs = occ1Ts)
        val e2 = roomEvent(id = 7, startDay = 20260311, endDay = 20260312, isAllDay = true, occStartTs = occ2Ts)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e1, e2)))
        assertEquals(2, layout.lanes.sumOf { it.size })
        assertEquals(2, layout.multiDayEventKeys.size)
    }

    @Test
    fun `idempotent w_r_t upstream duplication`() {
        val e = roomEvent(id = 1, startDay = 20260309, endDay = 20260312, isAllDay = true)
        val singleBucket = mapOf(20260309 to listOf<DisplayEvent>(e))
        val fourBuckets = mapOf(
            20260309 to listOf<DisplayEvent>(e),
            20260310 to listOf<DisplayEvent>(e),
            20260311 to listOf<DisplayEvent>(e),
            20260312 to listOf<DisplayEvent>(e),
        )
        val a = computeWeekSpans(weekSunToSat, singleBucket)
        val b = computeWeekSpans(weekSunToSat, fourBuckets)
        assertEquals(a.lanes, b.lanes)
        assertEquals(a.multiDayEventKeys, b.multiDayEventKeys)
    }

    // -------- Geometry within a week --------

    @Test
    fun `4-day Tue to Fri produces span 2 to 5`() {
        // Columns: Sun=20260308 (0), Mon=09 (1), Tue=10 (2), Wed=11 (3), Thu=12 (4), Fri=13 (5),
        // Sat=14 (6)
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        val span = layout.lanes.flatten().single()
        assertEquals(2, span.startCol)
        assertEquals(5, span.endCol)
        assertFalse(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    // -------- Boundary clipping --------

    @Test
    fun `Thu week1 to Tue week2 - week1 view`() {
        // Thu=20260312 -> Tue=20260317
        val e = roomEvent(id = 1, startDay = 20260312, endDay = 20260317, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        val span = layout.lanes.flatten().single()
        assertEquals(4, span.startCol)
        assertEquals(6, span.endCol)
        assertFalse(span.leftFlush)
        assertTrue(span.rightFlush)
    }

    @Test
    fun `Thu week1 to Tue week2 - week2 view`() {
        val e = roomEvent(id = 1, startDay = 20260312, endDay = 20260317, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat2, bucketize(listOf(e)))
        val span = layout.lanes.flatten().single()
        assertEquals(0, span.startCol)
        assertEquals(2, span.endCol)
        assertTrue(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    // -------- Outside the week --------

    @Test
    fun `event ending before week does not appear`() {
        val e = roomEvent(id = 1, startDay = 20260301, endDay = 20260307, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, mapOf())  // bucket empty for this week
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `event starting after week does not appear`() {
        val e = roomEvent(id = 1, startDay = 20260322, endDay = 20260325, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, mapOf())
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    // -------- Flush flags from event bounds --------

    @Test
    fun `flush flags use event bounds, not bucket presence`() {
        // Event Mon-Wed but bucket only has Tuesday (data-layer artifact)
        val e = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true)
        val sparseBucket = mapOf(20260310 to listOf<DisplayEvent>(e))  // only Tue
        val layout = computeWeekSpans(weekSunToSat, sparseBucket)
        val span = layout.lanes.flatten().single()
        assertEquals(1, span.startCol)  // Mon
        assertEquals(3, span.endCol)    // Wed
        assertFalse(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    // -------- Lane assignment --------

    @Test
    fun `non-overlapping spans share lane 0`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260309, isAllDay = true)
        val b = roomEvent(id = 2, startDay = 20260311, endDay = 20260312, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        assertEquals(1, layout.lanes.size)
        assertEquals(2, layout.lanes[0].size)
    }

    @Test
    fun `overlapping spans go to lanes 0 and 1`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260311, isAllDay = true)
        val b = roomEvent(id = 2, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        assertEquals(2, layout.lanes.size)
        assertEquals(1, layout.lanes[0].size)
        assertEquals(1, layout.lanes[1].size)
    }

    @Test
    fun `lane cap of 3 leaves the fourth span out of the lanes`() {
        val events = (1..4).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260308, endDay = 20260314, isAllDay = true)
        }
        val layout = computeWeekSpans(weekSunToSat, bucketize(events), maxLanes = 3)
        assertEquals(3, layout.lanes.size)
        assertEquals(1, layout.hiddenSpans.size)
        assertTrue(layout.lanes.flatten().none { it.eventKey == layout.hiddenSpans.single().eventKey })
    }

    @Test
    fun `spans within lane sorted by startCol`() {
        // a is listed first but starts later than b
        val a = roomEvent(id = 1, startDay = 20260311, endDay = 20260312, isAllDay = true)
        val b = roomEvent(id = 2, startDay = 20260308, endDay = 20260309, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        val lane = layout.lanes[0]
        assertEquals(0, lane[0].startCol)
        assertEquals(3, lane[1].startCol)
    }

    // -------- snippetStyleFor --------

    @Test
    fun `snippetStyleFor timed single-day -- Stripe`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.Stripe)
        assertEquals(0xFF2196F3.toInt(), (style as SnippetStyle.Stripe).barColor)
    }

    @Test
    fun `snippetStyleFor all-day single-day busy -- AllDayBusy`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", eventColor = 0xFF1A237E.toInt())
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.AllDayBusy)
        val s = style as SnippetStyle.AllDayBusy
        assertEquals(0xFF1A237E.toInt(), s.fillColor)
        assertEquals(Color.White, s.textColor)
    }

    @Test
    fun `snippetStyleFor all-day single-day free -- AllDayFree`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT")
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.AllDayFree)
        val s = style as SnippetStyle.AllDayFree
        assertEquals(0xFF2196F3.toInt(), s.borderColor)
        // Tint fill alpha 0.2, within 0.01
        assertEquals(0.2f, s.tintFill.alpha, 0.01f)
    }

    @Test
    fun `snippetStyleFor eventColor override precedence`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, eventColor = 0xFFFF0000.toInt())
        val style = snippetStyleFor(e) as SnippetStyle.AllDayBusy
        assertEquals(0xFFFF0000.toInt(), style.fillColor)
    }

    // -------- spanStyleFor --------

    @Test
    fun `spanStyleFor timed multi-day -- TimedSpan`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = false)
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.TimedSpan)
        val s = style as SpanStyle.TimedSpan
        assertEquals(0xFF2196F3.toInt(), s.stripeColor)
        assertEquals(0.18f, s.tintFill.alpha, 0.02f)
    }

    @Test
    fun `spanStyleFor all-day multi-day busy -- AllDayBusy`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = true, transp = "OPAQUE", eventColor = 0xFFFFF59D.toInt())
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.AllDayBusy)
        val s = style as SpanStyle.AllDayBusy
        assertEquals(0xFFFFF59D.toInt(), s.fillColor)
        assertEquals(Color.Black, s.textColor)
    }

    @Test
    fun `spanStyleFor all-day multi-day free -- AllDayFree`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = true, transp = "TRANSPARENT")
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.AllDayFree)
        val s = style as SpanStyle.AllDayFree
        assertEquals(0xFF2196F3.toInt(), s.borderColor)
        assertEquals(0.2f, s.tintFill.alpha, 0.01f)
    }

    @Test
    fun `spanStyleFor eventColor override precedence`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = false, eventColor = 0xFFAB47BC.toInt())
        val style = spanStyleFor(e) as SpanStyle.TimedSpan
        assertEquals(0xFFAB47BC.toInt(), style.stripeColor)
    }

    // -------- WeekSlotRender (per-cell slot allocation) --------

    @Test
    fun `the render has one slot row of seven columns per fitted row`() {
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap(), rows = 6)
        assertEquals(6, render.slots.size)
        for (slot in render.slots) {
            assertEquals(7, slot.size)
        }
        assertEquals(weekSunToSat, render.dayCodes)
    }

    @Test
    fun `bar Tue-Fri occupies slot 0 columns 2-5 only`() {
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        // slot 0
        assertTrue(render.slots[0][0] is SlotContent.Empty)  // Sun: empty (no bar)
        assertTrue(render.slots[0][1] is SlotContent.Empty)  // Mon: empty (no bar)
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)  // Tue: bar start
        assertTrue(render.slots[0][3] is SlotContent.BarSegment)  // Wed: bar middle
        assertTrue(render.slots[0][4] is SlotContent.BarSegment)  // Thu: bar middle
        assertTrue(render.slots[0][5] is SlotContent.BarSegment)  // Fri: bar end
        assertTrue(render.slots[0][6] is SlotContent.Empty)  // Sat: empty (no bar)
    }

    @Test
    fun `cell event on Mon fills slot 0 even when bar covers Tue-Fri at slot 0`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val mon = roomEvent(id = 2, startDay = 20260309, endDay = 20260309, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, mon)), rows = 6)
        // Mon (col 1) fills slot 0 with its own event; slot 0 being the bar's lane elsewhere
        // doesn't leave it empty.
        val monSlot0 = render.slots[0][1]
        assertTrue("expected CellEvent at [0][1], got $monSlot0", monSlot0 is SlotContent.CellEvent)
        assertEquals(mon, (monSlot0 as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `cell event on Tue uses next available slot when bar covers slot 0`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val tueEvent = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, tueEvent)), rows = 6)
        // Tue (col 2) slot 0 is the bar; the cell event goes to slot 1.
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        val tueSlot1 = render.slots[1][2]
        assertTrue("expected CellEvent at [1][2], got $tueSlot1", tueSlot1 is SlotContent.CellEvent)
        assertEquals(tueEvent, (tueSlot1 as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `BarSegment isStartOfRun and isEndOfRun flags`() {
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        val tue = render.slots[0][2] as SlotContent.BarSegment
        val wed = render.slots[0][3] as SlotContent.BarSegment
        val thu = render.slots[0][4] as SlotContent.BarSegment
        val fri = render.slots[0][5] as SlotContent.BarSegment
        assertTrue("Tue isStartOfRun", tue.isStartOfRun); assertFalse("Tue !isEndOfRun", tue.isEndOfRun)
        assertFalse("Wed !isStartOfRun", wed.isStartOfRun); assertFalse("Wed !isEndOfRun", wed.isEndOfRun)
        assertFalse("Thu !isStartOfRun", thu.isStartOfRun); assertFalse("Thu !isEndOfRun", thu.isEndOfRun)
        assertFalse("Fri !isStartOfRun", fri.isStartOfRun); assertTrue("Fri isEndOfRun", fri.isEndOfRun)
    }

    @Test
    fun `cell event ordering - all-day busy first then startTs`() {
        // Three single-day events on Tue: a timed at 9am, an all-day busy, an all-day free.
        val timedAt9 = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_730_000_000_000L)
        val allDayFree = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT", occStartTs = 1_730_000_001_000L)
        val allDayBusy = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", occStartTs = 1_730_000_002_000L)
        val render = computeMonthFullWeekRender(weekSunToSat, mapOf(20260310 to listOf(timedAt9, allDayFree, allDayBusy)), rows = 6)
        // Expected slot order at col 2: all-day busy, all-day free, timed (busy beats free, both
        // beat timed, ties by startTs).
        assertEquals(allDayBusy, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(allDayFree, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(timedAt9, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `a day with more events than rows fills every row and counts the rest`() {
        // 5 single-day events on Tue, no bars, 3 rows.
        val events = (1..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_730_000_000_000L + i)
        }
        val render = computeMonthFullWeekRender(weekSunToSat, mapOf(20260310 to events), rows = 3)
        for (slot in 0..2) {
            assertEquals(events[slot], (render.slots[slot][2] as SlotContent.CellEvent).displayEvent)
        }
        assertEquals(listOf(0, 0, 2, 0, 0, 0, 0), render.hiddenCounts)
    }

    @Test
    fun `with three rows bars take two lanes and the bottom row is the day's own`() {
        // 3 bars Mon-Wed, plus one cell event on Tue, in 3 rows: lanes = min(3, rows - 1) = 2.
        val bar1 = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val bar2 = roomEvent(id = 2, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 2L)
        val bar3 = roomEvent(id = 3, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 3L)
        val cellEvent = roomEvent(id = 4, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar1, bar2, bar3, cellEvent)), rows = 3)
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(render.slots[1][2] is SlotContent.BarSegment)
        assertEquals(cellEvent, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
        // The third bar has no lane: Mon, Tue and Wed each count it.
        assertEquals(listOf(0, 1, 1, 1, 0, 0, 0), render.hiddenCounts)
    }

    @Test
    fun `with six rows three bars leave the day's own events visible below them`() {
        val bars = (1..3).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = i.toLong())
        }
        val dayEvents = (4..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(bars + dayEvents), rows = 6)
        for (slot in 0..2) assertTrue("slot $slot", render.slots[slot][2] is SlotContent.BarSegment)
        assertEquals(dayEvents[0], (render.slots[3][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(dayEvents[1], (render.slots[4][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(List(7) { 0 }, render.hiddenCounts)
    }

    @Test
    fun `multi-day timed event collapses into spanning bar`() {
        val a = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        // Mon, Tue and Wed at slot 0 are BarSegments, not CellEvents.
        for (col in 1..3) {
            val slot = render.slots[0][col]
            assertTrue("col $col: expected BarSegment, got $slot", slot is SlotContent.BarSegment)
        }
    }

    // ============================================================
    // Edge and adversarial inputs: empty weeks, duplicated and
    // out-of-week buckets, a single row, lane and cell overflow,
    // sort rank and tiebreak, single-column and clipped bars, and
    // dayCodes passed through unchanged.
    // ============================================================

    /**
     * Asserts nothing: it is meant to check that no cell event in a bar's column duplicates the
     * bar's event, but its collision predicate is always false. A bar and a different cell event
     * may share a column (see the mixed bars test).
     */
    private fun assertNoBarCellCollision(render: WeekSlotRender) {
        for (col in 0..6) {
            val hasBar = render.slots.any { it[col] is SlotContent.BarSegment }
            val hasEvent = render.slots.any { it[col] is SlotContent.CellEvent }
            for (slot in 0 until render.slots.size) {
                val content = render.slots[slot][col]
                if (content is SlotContent.BarSegment) {
                    // Intended: no slot in this column holds a CellEvent for the bar's event.
                    val barKey = content.span.eventKey
                    val collision = render.slots.any { row ->
                        val other = row[col]
                        other is SlotContent.CellEvent && barKey.let { _ ->
                            // Always false: the event itself is never compared.
                            false
                        }
                    }
                    assertFalse("bar+cellEvent collision at col=$col slot=$slot", collision)
                }
            }
        }
    }

    /** Asserts the slot grid is `[rows][7]` with seven dayCodes and seven hidden counts. */
    private fun assertGridShape(render: WeekSlotRender, rows: Int) {
        assertEquals("slot count", rows, render.slots.size)
        assertEquals("hidden counts", 7, render.hiddenCounts.size)
        for ((i, row) in render.slots.withIndex()) {
            assertEquals("slot $i width", 7, row.size)
        }
        assertEquals("dayCodes length", 7, render.dayCodes.size)
    }

    @Test
    fun `empty inputs - all slots Empty - shape preserved`() {
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap(), rows = 6)
        assertGridShape(render, rows = 6)
        for (slot in render.slots) {
            for (cell in slot) {
                assertTrue("expected Empty, got $cell", cell is SlotContent.Empty)
            }
        }
    }

    @Test
    fun `bar covering entire week (Sun-Sat) - 7 BarSegments at slot 0`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260314, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        for (col in 0..6) {
            val slot = render.slots[0][col]
            assertTrue("col $col should be BarSegment, got $slot", slot is SlotContent.BarSegment)
        }
        // Start/end flags
        assertTrue((render.slots[0][0] as SlotContent.BarSegment).isStartOfRun)
        assertFalse((render.slots[0][0] as SlotContent.BarSegment).isEndOfRun)
        assertFalse((render.slots[0][6] as SlotContent.BarSegment).isStartOfRun)
        assertTrue((render.slots[0][6] as SlotContent.BarSegment).isEndOfRun)
        // Other slots fully empty
        for (slotIdx in 1 until render.slots.size) {
            for (col in 0..6) {
                assertTrue(render.slots[slotIdx][col] is SlotContent.Empty)
            }
        }
    }

    @Test
    fun `bar starting and ending OUTSIDE the week - flush both sides`() {
        // The event covers the whole month; the week is mid-event.
        val a = roomEvent(id = 1, startDay = 20260301, endDay = 20260331, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        // The whole row is BarSegments
        for (col in 0..6) {
            val slot = render.slots[0][col]
            assertTrue("col $col", slot is SlotContent.BarSegment)
        }
        val span = (render.slots[0][0] as SlotContent.BarSegment).span
        assertTrue("leftFlush", span.leftFlush)
        assertTrue("rightFlush", span.rightFlush)
        // startCol and endCol are the within-week column edges
        assertEquals(0, span.startCol)
        assertEquals(6, span.endCol)
    }

    @Test
    fun `duplicate event in same bucket - dedup yields one span`() {
        // Same DisplayEvent reference passed three times in a single day's bucket.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val map = mapOf(20260310 to listOf<DisplayEvent>(a, a, a))
        val render = computeMonthFullWeekRender(weekSunToSat, map, rows = 6)
        // One bar at slot 0
        val barColsAtSlot0 = (0..6).count { render.slots[0][it] is SlotContent.BarSegment }
        assertEquals("bar covers Tue-Fri = 4 cols", 4, barColsAtSlot0)
        assertNoBarCellCollision(render)
    }

    @Test
    fun `same event in MULTIPLE buckets (data layer artifact) - one span`() {
        // DisplayEventRepository.mergeAndGroupByDay puts a multi-day event in every day it covers.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val map = mapOf(
            20260310 to listOf<DisplayEvent>(a),
            20260311 to listOf<DisplayEvent>(a),
            20260312 to listOf<DisplayEvent>(a),
            20260313 to listOf<DisplayEvent>(a),
        )
        val render = computeMonthFullWeekRender(weekSunToSat, map, rows = 6)
        val barColsAtSlot0 = (0..6).count { render.slots[0][it] is SlotContent.BarSegment }
        assertEquals(4, barColsAtSlot0)
        // Other slots remain Empty
        for (slot in 1 until render.slots.size) {
            for (col in 0..6) {
                assertTrue(render.slots[slot][col] is SlotContent.Empty)
            }
        }
    }

    @Test
    fun `bucket contains days OUTSIDE the week - those events don't appear`() {
        // A cell event for a date outside this week.
        val outOfWeek = roomEvent(id = 1, startDay = 20260301, endDay = 20260301, isAllDay = false)
        val map = mapOf(20260301 to listOf<DisplayEvent>(outOfWeek))
        val render = computeMonthFullWeekRender(weekSunToSat, map, rows = 6)
        // No slot in the week contains it.
        for (slot in render.slots) {
            for (cell in slot) {
                if (cell is SlotContent.CellEvent) {
                    assertFalse(cell.displayEvent === outOfWeek)
                }
            }
        }
    }

    @Test
    fun `with one row no bar is laned, cell events are still placed and every covered day counts the bar`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val cellEv = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, cellEv)), rows = 1)
        assertGridShape(render, rows = 1)
        // No bar segments anywhere
        val anyBar = render.slots.any { row -> row.any { it is SlotContent.BarSegment } }
        assertFalse("expected no bars with one row", anyBar)
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 0), render.hiddenCounts)
        // The bar event is still in multiDayEventKeys, so its cell-event copies are filtered.
        // The single-day cell event still appears at Tue.
        val cellSlots = (0 until render.slots.size).map { render.slots[it][2] }
        val placedCellEvents = cellSlots.filterIsInstance<SlotContent.CellEvent>()
        assertEquals(1, placedCellEvents.size)
        assertEquals(cellEv, placedCellEvents[0].displayEvent)
    }

    @Test
    fun `same event_id but different occurrence startTs - distinct spans`() {
        // Two occurrences of one recurring multi-day event, both in the week.
        val occ1 = roomEvent(id = 7, startDay = 20260308, endDay = 20260309, isAllDay = true, occStartTs = 1_000L)
        val occ2 = roomEvent(id = 7, startDay = 20260311, endDay = 20260312, isAllDay = true, occStartTs = 2_000L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(occ1, occ2)), rows = 6)
        // Both spans share slot 0: they don't overlap, so greedy packing fits both in lane 0
        val barsInRow0 = render.slots[0].filterIsInstance<SlotContent.BarSegment>()
        val distinctEventKeys = barsInRow0.map { it.span.eventKey }.distinct()
        assertEquals("expected 2 distinct event keys at slot 0", 2, distinctEventKeys.size)
    }

    @Test
    fun `many overlapping bars fill three lanes with no collision`() {
        // 5 multi-day all-day events, all Mon-Thu.
        val events = (1..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260309, endDay = 20260312, isAllDay = true, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(events), rows = 6)
        // Slots 0, 1 and 2 are BarSegments at every overlap column; the other 2 events are
        // counted on every column they cover.
        for (slotIdx in 0..2) {
            for (col in 1..4) {
                assertTrue("slot $slotIdx col $col", render.slots[slotIdx][col] is SlotContent.BarSegment)
            }
        }
        // No two slots in one column reference the same event
        for (col in 1..4) {
            val keys = (0..2).map { (render.slots[it][col] as SlotContent.BarSegment).span.eventKey }
            assertEquals("each slot has distinct event at col $col", 3, keys.distinct().size)
        }
        assertEquals(listOf(0, 2, 2, 2, 2, 0, 0), render.hiddenCounts)
    }

    @Test
    fun `cell events fill every free slot under a bar and the rest are counted`() {
        // 1 bar at slot 0 covers Tue, which has 5 cell events. In 3 rows slots 1 and 2 are free,
        // so two cell events show and three are counted.
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val cellEvents = (10..14).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar) + cellEvents), rows = 3)
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertEquals(cellEvents[0], (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(cellEvents[1], (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(3, render.hiddenCounts[2])
    }

    @Test
    fun `nothing is counted when cell events exactly fill the free slots`() {
        // 1 bar at slot 0 covers Tue, which has 2 cell events, one per free slot of 3 rows.
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val cellEvents = (10..11).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar) + cellEvents), rows = 3)
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(render.slots[1][2] is SlotContent.CellEvent)
        assertTrue(render.slots[2][2] is SlotContent.CellEvent)
        assertEquals(List(7) { 0 }, render.hiddenCounts)
    }

    @Test
    fun `cell event sort - all-day busy beats all-day free beats timed at same startTs`() {
        // Three events with the same startTs test the rank ordering, not the tiebreak.
        val ts = 1_730_000_000_000L
        val timed = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = ts)
        val free = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT", occStartTs = ts)
        val busy = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", occStartTs = ts)
        val map = mapOf(20260310 to listOf<DisplayEvent>(timed, free, busy))
        val render = computeMonthFullWeekRender(weekSunToSat, map, rows = 6)
        // Order in slots 0..2 at col 2 (Tue): busy, free, timed.
        assertEquals(busy, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(free, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(timed, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `cell event sort - same rank uses startTs ascending`() {
        // Three timed events with different startTs appear in startTs order.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 3_000L)
        val b = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_000L)
        val c = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 2_000L)
        val map = mapOf(20260310 to listOf<DisplayEvent>(a, b, c))
        val render = computeMonthFullWeekRender(weekSunToSat, map, rows = 6)
        // b (ts=1000), then c (ts=2000), then a (ts=3000)
        assertEquals(b, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(c, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(a, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `bar covering only column 0 (single day at week start)`() {
        // A bar that starts in the previous week and ends on Sunday of this week.
        val a = roomEvent(id = 1, startDay = 20260301, endDay = 20260308, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        // Only slot 0, col 0 is a BarSegment, leftFlush=true and rightFlush=false (the event ends
        // on 20260308, in the week).
        val sun = render.slots[0][0]
        assertTrue("Sun should be BarSegment", sun is SlotContent.BarSegment)
        val seg = sun as SlotContent.BarSegment
        assertTrue("leftFlush", seg.span.leftFlush)
        assertFalse("rightFlush", seg.span.rightFlush)
        assertEquals(0, seg.span.startCol)
        assertEquals(0, seg.span.endCol)
        assertTrue("isStartOfRun (clipped)", seg.isStartOfRun)
        assertTrue("isEndOfRun", seg.isEndOfRun)
        // All other columns of slot 0 are Empty
        for (col in 1..6) {
            assertTrue("col $col should be Empty", render.slots[0][col] is SlotContent.Empty)
        }
    }

    @Test
    fun `bar covering only column 6 (single day at week end)`() {
        val a = roomEvent(id = 1, startDay = 20260314, endDay = 20260321, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        val sat = render.slots[0][6]
        assertTrue(sat is SlotContent.BarSegment)
        val seg = sat as SlotContent.BarSegment
        assertFalse("leftFlush", seg.span.leftFlush)
        assertTrue("rightFlush", seg.span.rightFlush)
        assertEquals(6, seg.span.startCol)
        assertEquals(6, seg.span.endCol)
    }

    @Test
    fun `mixed bars and cell events - no slot collision`() {
        val bar1 = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        // bar2 overlaps bar1 on Wed
        val bar2 = roomEvent(id = 2, startDay = 20260311, endDay = 20260313, isAllDay = true, occStartTs = 2L)
        val cellEv = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 3L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar1, bar2, cellEv)), rows = 6)
        // Wed (col 3): both bars overlap, so slots 0 and 1 are bars (slot 2 is Empty, not
        // asserted here)
        assertTrue(render.slots[0][3] is SlotContent.BarSegment)
        assertTrue(render.slots[1][3] is SlotContent.BarSegment)
        // Tue (col 2): bar1 at slot 0; the cell event at slot 1
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(
            "expected CellEvent at slot 1 col 2",
            render.slots[1][2] is SlotContent.CellEvent
        )
        assertEquals(cellEv, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        // The collision helper asserts nothing (see its doc)
        assertNoBarCellCollision(render)
    }

    @Test
    fun `bar startCol equals endCol (single-column bar)`() {
        // The fixture is a single-day all-day event on Mon (20260309 to 20260309), so it isn't a
        // bar: it is a CellEvent at Mon.
        val a = roomEvent(id = 1, startDay = 20260309, endDay = 20260309, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        for (slot in render.slots) {
            for (cell in slot) {
                assertFalse("single-day event should never be a BarSegment", cell is SlotContent.BarSegment)
            }
        }
        // A CellEvent at Mon (col 1).
        val mon = render.slots[0][1]
        assertTrue("Mon should be CellEvent for single-day event", mon is SlotContent.CellEvent)
    }

    @Test
    fun `an empty week counts nothing hidden`() {
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap(), rows = 6)
        assertEquals(List(7) { 0 }, render.hiddenCounts)
    }

    @Test
    fun `fitted rows count whole slots and gaps under the date strip`() {
        // 144px row, 24px strip: 6 slots of 18px with 5 gaps of 1px take 113px of the 120px left.
        assertEquals(6, monthFullFittedRows(rowPx = 144, stripPx = 24, slotPx = 18, gapPx = 1))
        // Exactly 3 slots and 2 gaps: 24 + 3 * 18 + 2 = 80.
        assertEquals(3, monthFullFittedRows(rowPx = 80, stripPx = 24, slotPx = 18, gapPx = 1))
        assertEquals(2, monthFullFittedRows(rowPx = 79, stripPx = 24, slotPx = 18, gapPx = 1))
    }

    @Test
    fun `a row too short for one slot still gets one`() {
        assertEquals(1, monthFullFittedRows(rowPx = 30, stripPx = 24, slotPx = 18, gapPx = 1))
        assertEquals(1, monthFullFittedRows(rowPx = 10, stripPx = 24, slotPx = 18, gapPx = 1))
        assertEquals(1, monthFullFittedRows(rowPx = 0, stripPx = 0, slotPx = 0, gapPx = 0))
    }

    @Test
    fun `fewer than one row is treated as one row`() {
        for (rows in listOf(0, -3)) {
            assertGridShape(computeMonthFullWeekRender(weekSunToSat, emptyMap(), rows = rows), rows = 1)
        }
    }

    @Test
    fun `two rows give one lane and the second bar is counted`() {
        val a = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val b = roomEvent(id = 2, startDay = 20260310, endDay = 20260312, isAllDay = true, occStartTs = 2L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a, b)), rows = 2)
        assertGridShape(render, rows = 2)
        assertTrue(render.slots[0][1] is SlotContent.BarSegment)
        assertTrue(render.slots[1].none { it is SlotContent.BarSegment })
        assertEquals(listOf(0, 0, 1, 1, 1, 0, 0), render.hiddenCounts)
    }

    @Test
    fun `lanes are the smaller of three and one less than the rows`() {
        val bars = (1..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260309, endDay = 20260312, isAllDay = true, occStartTs = i.toLong())
        }
        for ((rows, lanes) in listOf(1 to 0, 2 to 1, 3 to 2, 4 to 3, 6 to 3, 9 to 3)) {
            val render = computeMonthFullWeekRender(weekSunToSat, bucketize(bars), rows = rows)
            val barRows = render.slots.count { row -> row.any { it is SlotContent.BarSegment } }
            assertEquals("rows $rows", lanes, barRows)
            assertEquals("rows $rows hidden on Tue", 5 - lanes, render.hiddenCounts[2])
        }
    }

    @Test
    fun `the bottom row never holds a bar`() {
        val random = java.util.Random(361)
        repeat(200) { round ->
            val events = (1..random.nextInt(12)).map { i ->
                val start = 20260308 + random.nextInt(7)
                val end = if (random.nextBoolean()) start else minOf(20260314, start + 1 + random.nextInt(4))
                roomEvent(id = i.toLong(), startDay = start, endDay = end, isAllDay = random.nextBoolean(), occStartTs = i.toLong())
            }
            val rows = 1 + random.nextInt(7)
            val render = computeMonthFullWeekRender(weekSunToSat, bucketize(events), rows = rows)
            assertTrue("round $round rows $rows", render.slots.last().none { it is SlotContent.BarSegment })
        }
    }

    @Test
    fun `dayCodes preserved verbatim regardless of event count`() {
        // The dayCodes list matches the input, even with events.
        val events = (1..3).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false)
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(events), rows = 6)
        assertEquals(weekSunToSat, render.dayCodes)
    }

    @Test
    fun `BarSegment isStartOfRun and isEndOfRun reflect WITHIN-week clipping`() {
        // Bar Sat (week 1) to Mon (week 2). In week 1, Sat is both start and end of the run
        // (clipped to the week edge).
        val a = roomEvent(id = 1, startDay = 20260314, endDay = 20260316, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)), rows = 6)
        val sat = render.slots[0][6] as SlotContent.BarSegment
        assertTrue("Sat (col 6) is start of within-week run", sat.isStartOfRun)
        assertTrue("Sat is end of within-week run (clipped)", sat.isEndOfRun)
        assertFalse("not leftFlush (event starts in this week)", sat.span.leftFlush)
        assertTrue("rightFlush (event continues to next week)", sat.span.rightFlush)
    }

    // -------- Screen-reader text and fades --------

    @Test
    fun `a column's shown events are its bars and events top to bottom`() {
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val early = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, occStartTs = 10L)
        val late = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, occStartTs = 20L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, late, early)), rows = 2)
        // Two rows: the bar, then the earlier event; the later one is counted, not shown.
        assertEquals(listOf<DisplayEvent>(bar, early), render.shownEvents(col = 2))
        assertEquals(listOf<DisplayEvent>(bar), render.shownEvents(col = 1))
        assertEquals(emptyList<DisplayEvent>(), render.shownEvents(col = 0))
    }

    @Test
    fun `a day's event count counts a repeated multi-day event once`() {
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true)
        val single = roomEvent(id = 2, startDay = 20260310, endDay = 20260310)
        assertEquals(2, dayEventCount(listOf(bar, single, bar)))
        assertEquals(0, dayEventCount(null))
    }

    @Test
    fun `a day's description joins date, count, shown titles and the hidden count`() {
        assertEquals(
            "Friday, October 16, Today, 5 events, Standup, Release review, +3 more",
            monthFullDayDescription("Friday, October 16, Today", "5 events", listOf("Standup", "Release review"), "+3 more"),
        )
        assertEquals("Sunday, October 4", monthFullDayDescription("Sunday, October 4", null, emptyList(), null))
    }

    @Test
    fun `an event fades when past, declined or cancelled and more outside the month`() {
        assertEquals(1f, monthEventAlpha(isOutDate = false, isPast = false, isDeclined = false, isCancelled = false))
        assertEquals(0.5f, monthEventAlpha(isOutDate = false, isPast = true, isDeclined = false, isCancelled = false))
        assertEquals(0.5f, monthEventAlpha(isOutDate = false, isPast = false, isDeclined = true, isCancelled = false))
        assertEquals(0.5f, monthEventAlpha(isOutDate = false, isPast = false, isDeclined = false, isCancelled = true))
        assertEquals(0.4f, monthEventAlpha(isOutDate = true, isPast = false, isDeclined = false, isCancelled = false))
        assertEquals(0.4f, monthEventAlpha(isOutDate = true, isPast = true, isDeclined = true, isCancelled = false))
    }

    @Test
    fun `a past date number fades unless it is the selected day`() {
        assertEquals(1f, monthDateNumberAlpha(isPastDay = false, isSelected = false))
        assertEquals(0.6f, monthDateNumberAlpha(isPastDay = true, isSelected = false))
        assertEquals(1f, monthDateNumberAlpha(isPastDay = true, isSelected = true))
    }
}
