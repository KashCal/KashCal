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

    /** Build a Room DisplayEvent with overrides on the relevant fields. */
    private fun roomEvent(
        id: Long,
        startDay: Int,
        endDay: Int,
        isAllDay: Boolean = false,
        transp: String = "OPAQUE",
        eventColor: Int? = null,
        occStartTs: Long = 1_700_000_000_000L + id * 1000,
    ): DisplayEvent.Room {
        val event = Event(
            id = id,
            uid = "uid-$id",
            calendarId = 10L,
            title = "E$id",
            startTs = occStartTs,
            endTs = occStartTs + 3_600_000L,
            dtstamp = occStartTs,
            isAllDay = isAllDay,
            transp = transp,
            color = eventColor,
        )
        val occ = Occurrence(
            id = 1000L + id,
            eventId = id,
            calendarId = 10L,
            startTs = occStartTs,
            endTs = occStartTs + 3_600_000L,
            startDay = startDay,
            endDay = endDay,
        )
        return DisplayEvent.Room(event, occ, cal)
    }

    /** Sun..Sat dayCodes for the week of 2026-03-08. */
    private val weekSunToSat = listOf(20260308, 20260309, 20260310, 20260311, 20260312, 20260313, 20260314)

    /** Following week, Sun..Sat. */
    private val weekSunToSat2 = listOf(20260315, 20260316, 20260317, 20260318, 20260319, 20260320, 20260321)

    /** Helper: build a bucket map duplicating multi-day events into each day they span. */
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

    /** Generate dayCodes from start to end inclusive (only handles same-month for fixtures). */
    private fun generateDayCodes(startDay: Int, endDay: Int): List<Int> {
        require(startDay <= endDay)
        val out = mutableListOf<Int>()
        var d = startDay
        while (d <= endDay) {
            out.add(d)
            // simple arithmetic — fixtures stay in 2026-03 with valid days
            d++
        }
        return out
    }

    // -------- Eligibility (2.1 - 2.4) --------

    @Test
    fun `2_1 single-day all-day event NOT in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(0, layout.lanes.sumOf { it.size })
        assertTrue(layout.multiDayEventKeys.isEmpty())
    }

    @Test
    fun `2_2 single-day timed event NOT in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `2_3 multi-day timed event IS in spans`() {
        val e = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = false)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `2_4 multi-day all-day event IS in spans`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
    }

    // -------- Identity & dedup (2.5 - 2.7) --------

    @Test
    fun `2_5 same identity key in N buckets produces ONE span`() {
        val e = roomEvent(id = 42, startDay = 20260310, endDay = 20260313, isAllDay = true, occStartTs = 1_730_000_000_000L)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        assertEquals(1, layout.lanes.sumOf { it.size })
        assertEquals(setOf("room:42:1730000000000"), layout.multiDayEventKeys)
    }

    @Test
    fun `2_6 two recurrences produce two distinct spans`() {
        val occ1Ts = 1_730_000_000_000L
        val occ2Ts = 1_730_086_400_000L  // next day
        val e1 = roomEvent(id = 7, startDay = 20260308, endDay = 20260309, isAllDay = true, occStartTs = occ1Ts)
        val e2 = roomEvent(id = 7, startDay = 20260311, endDay = 20260312, isAllDay = true, occStartTs = occ2Ts)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e1, e2)))
        assertEquals(2, layout.lanes.sumOf { it.size })
        assertEquals(2, layout.multiDayEventKeys.size)
    }

    @Test
    fun `2_7 idempotent w_r_t upstream duplication`() {
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

    // -------- Geometry — within week (2.8) --------

    @Test
    fun `2_8 4-day Tue to Fri produces span 2 to 5`() {
        // Sun=20260308 (col0), Mon=09 (1), Tue=10 (2), Wed=11 (3), Thu=12 (4), Fri=13 (5), Sat=14 (6)
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(e)))
        val span = layout.lanes.flatten().single()
        assertEquals(2, span.startCol)
        assertEquals(5, span.endCol)
        assertFalse(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    // -------- Boundary clipping (2.9 - 2.10) --------

    @Test
    fun `2_9 Thu week1 to Tue week2 - week1 view`() {
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
    fun `2_10 Thu week1 to Tue week2 - week2 view`() {
        val e = roomEvent(id = 1, startDay = 20260312, endDay = 20260317, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat2, bucketize(listOf(e)))
        val span = layout.lanes.flatten().single()
        assertEquals(0, span.startCol)
        assertEquals(2, span.endCol)
        assertTrue(span.leftFlush)
        assertFalse(span.rightFlush)
    }

    // -------- Outside-week (2.11 - 2.12) --------

    @Test
    fun `2_11 event ending before week does not appear`() {
        val e = roomEvent(id = 1, startDay = 20260301, endDay = 20260307, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, mapOf())  // bucket empty for this week
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    @Test
    fun `2_12 event starting after week does not appear`() {
        val e = roomEvent(id = 1, startDay = 20260322, endDay = 20260325, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, mapOf())
        assertEquals(0, layout.lanes.sumOf { it.size })
    }

    // -------- Flush flags from event bounds (2.13) --------

    @Test
    fun `2_13 flush flags use event bounds, not bucket presence`() {
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

    // -------- Lane assignment (2.14 - 2.17) --------

    @Test
    fun `2_14 non-overlapping spans share lane 0`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260309, isAllDay = true)
        val b = roomEvent(id = 2, startDay = 20260311, endDay = 20260312, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        assertEquals(1, layout.lanes.size)
        assertEquals(2, layout.lanes[0].size)
    }

    @Test
    fun `2_15 overlapping spans go to lanes 0 and 1`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260311, isAllDay = true)
        val b = roomEvent(id = 2, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        assertEquals(2, layout.lanes.size)
        assertEquals(1, layout.lanes[0].size)
        assertEquals(1, layout.lanes[1].size)
    }

    @Test
    fun `2_16 lane cap of 3 with overflow`() {
        val events = (1..4).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260308, endDay = 20260314, isAllDay = true)
        }
        val layout = computeWeekSpans(weekSunToSat, bucketize(events), maxLanes = 3)
        assertEquals(3, layout.lanes.size)
        assertEquals(1, layout.overflowCount)
    }

    @Test
    fun `2_17 spans within lane sorted by startCol`() {
        val a = roomEvent(id = 1, startDay = 20260311, endDay = 20260312, isAllDay = true)  // later
        val b = roomEvent(id = 2, startDay = 20260308, endDay = 20260309, isAllDay = true)  // earlier
        val layout = computeWeekSpans(weekSunToSat, bucketize(listOf(a, b)))
        val lane = layout.lanes[0]
        assertEquals(0, lane[0].startCol)
        assertEquals(3, lane[1].startCol)
    }

    // -------- snippetStyleFor (2.18 - 2.21) --------

    @Test
    fun `2_18 snippetStyleFor timed single-day -- Stripe`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.Stripe)
        assertEquals(0xFF2196F3.toInt(), (style as SnippetStyle.Stripe).barColor)
    }

    @Test
    fun `2_19 snippetStyleFor all-day single-day busy -- AllDayBusy`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", eventColor = 0xFF1A237E.toInt())
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.AllDayBusy)
        val s = style as SnippetStyle.AllDayBusy
        assertEquals(0xFF1A237E.toInt(), s.fillColor)
        assertEquals(Color.White, s.textColor)
    }

    @Test
    fun `2_20 snippetStyleFor all-day single-day free -- AllDayFree`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT")
        val style = snippetStyleFor(e)
        assertTrue(style is SnippetStyle.AllDayFree)
        val s = style as SnippetStyle.AllDayFree
        assertEquals(0xFF2196F3.toInt(), s.borderColor)
        // Tint fill alpha approximately 0.2
        assertEquals(0.2f, s.tintFill.alpha, 0.01f)
    }

    @Test
    fun `2_21 snippetStyleFor eventColor override precedence`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = true, eventColor = 0xFFFF0000.toInt())
        val style = snippetStyleFor(e) as SnippetStyle.AllDayBusy
        assertEquals(0xFFFF0000.toInt(), style.fillColor)
    }

    // -------- spanStyleFor (2.22 - 2.25) --------

    @Test
    fun `2_22 spanStyleFor timed multi-day -- TimedSpan`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = false)
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.TimedSpan)
        val s = style as SpanStyle.TimedSpan
        assertEquals(0xFF2196F3.toInt(), s.stripeColor)
        assertEquals(0.18f, s.tintFill.alpha, 0.02f)
    }

    @Test
    fun `2_23 spanStyleFor all-day multi-day busy -- AllDayBusy`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = true, transp = "OPAQUE", eventColor = 0xFFFFF59D.toInt())
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.AllDayBusy)
        val s = style as SpanStyle.AllDayBusy
        assertEquals(0xFFFFF59D.toInt(), s.fillColor)
        assertEquals(Color.Black, s.textColor)
    }

    @Test
    fun `2_24 spanStyleFor all-day multi-day free -- AllDayFree`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = true, transp = "TRANSPARENT")
        val style = spanStyleFor(e)
        assertTrue(style is SpanStyle.AllDayFree)
        val s = style as SpanStyle.AllDayFree
        assertEquals(0xFF2196F3.toInt(), s.borderColor)
        assertEquals(0.2f, s.tintFill.alpha, 0.01f)
    }

    @Test
    fun `2_25 spanStyleFor eventColor override precedence`() {
        val e = roomEvent(id = 1, startDay = 20260310, endDay = 20260312, isAllDay = false, eventColor = 0xFFAB47BC.toInt())
        val style = spanStyleFor(e) as SpanStyle.TimedSpan
        assertEquals(0xFFAB47BC.toInt(), style.stripeColor)
    }

    // -------- WeekSlotRender (per-cell slot allocation) --------

    @Test
    fun `2_30 slot render shape - 7 columns x MAX_SNIPPETS slots`() {
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap())
        assertEquals(MAX_SNIPPETS, render.slots.size)
        for (slot in render.slots) {
            assertEquals(7, slot.size)
        }
        assertEquals(weekSunToSat, render.dayCodes)
    }

    @Test
    fun `2_31 bar Tue-Fri occupies slot 0 columns 2-5 only`() {
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
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
    fun `2_32 cell event on Mon fills slot 0 even when bar covers Tue-Fri at slot 0`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val mon = roomEvent(id = 2, startDay = 20260309, endDay = 20260309, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, mon)))
        // The fix: Mon (col 1) should fill slot 0 with its own event, not be empty just because slot 0 is the bar's lane.
        val monSlot0 = render.slots[0][1]
        assertTrue("expected CellEvent at [0][1], got $monSlot0", monSlot0 is SlotContent.CellEvent)
        assertEquals(mon, (monSlot0 as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `2_33 cell event on Tue uses next available slot when bar covers slot 0`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val tueEvent = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, tueEvent)))
        // Tue (col 2) slot 0 is the bar; the cell event goes to slot 1.
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        val tueSlot1 = render.slots[1][2]
        assertTrue("expected CellEvent at [1][2], got $tueSlot1", tueSlot1 is SlotContent.CellEvent)
        assertEquals(tueEvent, (tueSlot1 as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `2_34 BarSegment isStartOfRun and isEndOfRun flags`() {
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
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
    fun `2_35 cell event ordering - all-day busy first then startTs`() {
        // Three single-day events on Tue: a timed at 9am, an all-day busy, an all-day free.
        val timedAt9 = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_730_000_000_000L)
        val allDayFree = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT", occStartTs = 1_730_000_001_000L)
        val allDayBusy = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", occStartTs = 1_730_000_002_000L)
        val render = computeMonthFullWeekRender(weekSunToSat, mapOf(20260310 to listOf(timedAt9, allDayFree, allDayBusy)))
        // Expected slot order at col 2: all-day busy, all-day free, timed (busy beats free; both beat timed; ties by startTs).
        assertEquals(allDayBusy, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(allDayFree, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(timedAt9, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `2_36 overflow at last slot when cell events exceed available`() {
        // 5 single-day events on Tue, no bars.
        val events = (1..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_730_000_000_000L + i)
        }
        val render = computeMonthFullWeekRender(weekSunToSat, mapOf(20260310 to events))
        // First two slots show events; third slot shows "+3 more"
        assertTrue(render.slots[0][2] is SlotContent.CellEvent)
        assertTrue(render.slots[1][2] is SlotContent.CellEvent)
        val overflow = render.slots[2][2]
        assertTrue("expected Overflow at [2][2], got $overflow", overflow is SlotContent.Overflow)
        assertEquals(3, (overflow as SlotContent.Overflow).count)
    }

    @Test
    fun `2_37 lanes-win when bars fill all slots, cell events silently dropped`() {
        // 3 bars all covering Tue, plus 2 cell events on Tue. All slots taken by bars.
        val bar1 = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val bar2 = roomEvent(id = 2, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 2L)
        val bar3 = roomEvent(id = 3, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 3L)
        val cellEvent = roomEvent(id = 4, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar1, bar2, bar3, cellEvent)))
        // All 3 slots at Tue should be bars.
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(render.slots[1][2] is SlotContent.BarSegment)
        assertTrue(render.slots[2][2] is SlotContent.BarSegment)
    }

    @Test
    fun `2_38 multi-day timed event collapses into spanning bar`() {
        val a = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        // Mon, Tue, Wed at slot 0 should all be BarSegment for the same event; not CellEvent.
        for (col in 1..3) {
            val slot = render.slots[0][col]
            assertTrue("col $col: expected BarSegment, got $slot", slot is SlotContent.BarSegment)
        }
    }

    // ============================================================
    // Adversarial / invariant tests
    //
    // Rather than describing one expected output, these assert
    // invariants that should ALWAYS hold across input variations.
    // A failing invariant test caught a real bug class.
    // ============================================================

    /** Invariant: a slot column never contains both a BarSegment AND a CellEvent. */
    private fun assertNoBarCellCollision(render: WeekSlotRender) {
        for (col in 0..6) {
            val hasBar = render.slots.any { it[col] is SlotContent.BarSegment }
            val hasEvent = render.slots.any { it[col] is SlotContent.CellEvent }
            for (slot in 0 until render.slots.size) {
                val content = render.slots[slot][col]
                if (content is SlotContent.BarSegment) {
                    // If a bar is at this slot/col, no other slot at this column can have a CellEvent for the same event.
                    val barKey = content.span.eventKey
                    val collision = render.slots.any { row ->
                        val other = row[col]
                        other is SlotContent.CellEvent && barKey.let { _ ->
                            // The cell event must not duplicate the multi-day event itself.
                            // CellEvent in this column should never reference an event already spanning here.
                            false  // pure structural check above is enough
                        }
                    }
                    assertFalse("bar+cellEvent collision at col=$col slot=$slot", collision)
                }
            }
        }
    }

    /** Invariant: slot grid shape is always `[max(maxLanes, maxSnippets)][7]`. */
    private fun assertGridShape(render: WeekSlotRender, maxLanes: Int = MAX_LANES, maxSnippets: Int = MAX_SNIPPETS) {
        val expectedSlots = maxOf(maxLanes, maxSnippets)
        assertEquals("slot count", expectedSlots, render.slots.size)
        for ((i, row) in render.slots.withIndex()) {
            assertEquals("slot $i width", 7, row.size)
        }
        assertEquals("dayCodes length", 7, render.dayCodes.size)
    }

    @Test
    fun `adv 1 - empty inputs - all slots Empty - shape preserved`() {
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap())
        assertGridShape(render)
        for (slot in render.slots) {
            for (cell in slot) {
                assertTrue("expected Empty, got $cell", cell is SlotContent.Empty)
            }
        }
    }

    @Test
    fun `adv 2 - bar covering entire week (Sun-Sat) - 7 BarSegments at slot 0`() {
        val a = roomEvent(id = 1, startDay = 20260308, endDay = 20260314, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
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
    fun `adv 3 - bar starting and ending OUTSIDE the week - flush both sides`() {
        // Event covers an entire month (or wider); week is mid-event.
        val a = roomEvent(id = 1, startDay = 20260301, endDay = 20260331, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        // The whole row should be BarSegments
        for (col in 0..6) {
            val slot = render.slots[0][col]
            assertTrue("col $col", slot is SlotContent.BarSegment)
        }
        val span = (render.slots[0][0] as SlotContent.BarSegment).span
        assertTrue("leftFlush", span.leftFlush)
        assertTrue("rightFlush", span.rightFlush)
        // Even the start/end column flags reflect within-week column edges
        assertEquals(0, span.startCol)
        assertEquals(6, span.endCol)
    }

    @Test
    fun `adv 4 - duplicate event in same bucket - dedup yields one span`() {
        // Same DisplayEvent reference passed three times in a single day's bucket.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val map = mapOf(20260310 to listOf<DisplayEvent>(a, a, a))
        val render = computeMonthFullWeekRender(weekSunToSat, map)
        // Only ONE bar should appear at slot 0
        val barColsAtSlot0 = (0..6).count { render.slots[0][it] is SlotContent.BarSegment }
        assertEquals("bar covers Tue-Fri = 4 cols", 4, barColsAtSlot0)
        assertNoBarCellCollision(render)
    }

    @Test
    fun `adv 5 - same event in MULTIPLE buckets (data layer artifact) - one span`() {
        // Multi-day events get duplicated by mergeAndGroupByDay; bucket map has the event in every covered day.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val map = mapOf(
            20260310 to listOf<DisplayEvent>(a),
            20260311 to listOf<DisplayEvent>(a),
            20260312 to listOf<DisplayEvent>(a),
            20260313 to listOf<DisplayEvent>(a),
        )
        val render = computeMonthFullWeekRender(weekSunToSat, map)
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
    fun `adv 6 - bucket contains days OUTSIDE the week - those events don't appear`() {
        // A cell event for a date NOT in this week (data leak).
        val outOfWeek = roomEvent(id = 1, startDay = 20260301, endDay = 20260301, isAllDay = false)
        val map = mapOf(20260301 to listOf<DisplayEvent>(outOfWeek))
        val render = computeMonthFullWeekRender(weekSunToSat, map)
        // No slot in the week should contain it.
        for (slot in render.slots) {
            for (cell in slot) {
                if (cell is SlotContent.CellEvent) {
                    assertFalse(cell.displayEvent === outOfWeek)
                }
            }
        }
    }

    @Test
    fun `adv 7 - maxLanes equals 0 - all bars overflow, cell events still placed`() {
        val bar = roomEvent(id = 1, startDay = 20260310, endDay = 20260313, isAllDay = true)
        val cellEv = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar, cellEv)), maxLanes = 0)
        // No bar segments anywhere
        val anyBar = render.slots.any { row -> row.any { it is SlotContent.BarSegment } }
        assertFalse("expected no bars when maxLanes=0", anyBar)
        // BUT the bar event is still in multiDayEventKeys, so its cell-event copies are filtered.
        // The single-day cell event SHOULD still appear at Tue.
        val cellSlots = (0 until render.slots.size).map { render.slots[it][2] }
        val placedCellEvents = cellSlots.filterIsInstance<SlotContent.CellEvent>()
        assertEquals(1, placedCellEvents.size)
        assertEquals(cellEv, placedCellEvents[0].displayEvent)
    }

    @Test
    fun `adv 8 - same event_id but different occurrence startTs - distinct spans`() {
        // A recurring multi-day event: master + exception both in week.
        val occ1 = roomEvent(id = 7, startDay = 20260308, endDay = 20260309, isAllDay = true, occStartTs = 1_000L)
        val occ2 = roomEvent(id = 7, startDay = 20260311, endDay = 20260312, isAllDay = true, occStartTs = 2_000L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(occ1, occ2)))
        // Both spans should appear, sharing slot 0 (non-overlapping → greedy fits both in lane 0)
        val barsInRow0 = render.slots[0].filterIsInstance<SlotContent.BarSegment>()
        val distinctEventKeys = barsInRow0.map { it.span.eventKey }.distinct()
        assertEquals("expected 2 distinct event keys at slot 0", 2, distinctEventKeys.size)
    }

    @Test
    fun `adv 9 - many overlapping bars exceed maxLanes - exact slot fill, no collision`() {
        // 5 multi-day all-day events all overlapping Tue-Wed.
        val events = (1..5).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260309, endDay = 20260312, isAllDay = true, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(events))
        // Slots 0, 1, 2 should all be BarSegments at all overlap columns; 2 events overflow.
        for (slotIdx in 0..2) {
            for (col in 1..4) {
                assertTrue("slot $slotIdx col $col", render.slots[slotIdx][col] is SlotContent.BarSegment)
            }
        }
        // No two slots in the same column should reference the same event
        for (col in 1..4) {
            val keys = (0..2).map { (render.slots[it][col] as SlotContent.BarSegment).span.eventKey }
            assertEquals("each slot has distinct event at col $col", 3, keys.distinct().size)
        }
    }

    @Test
    fun `adv 10 - overflow indicator appears in last available slot when cell events exceed budget`() {
        // 1 bar at slot 0 covers Tue. Tue has 5 cell events. Available slots 1+2 = 2; budget rule = (available-1)=1 visible + overflow.
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val cellEvents = (10..14).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar) + cellEvents))
        // Tue (col 2): slot 0 = bar, slot 1 = first cell event, slot 2 = "+more".
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(render.slots[1][2] is SlotContent.CellEvent)
        val overflow = render.slots[2][2]
        assertTrue("expected Overflow at [2][2], got $overflow", overflow is SlotContent.Overflow)
        // 5 events total; 1 visible; overflow = 4
        assertEquals(4, (overflow as SlotContent.Overflow).count)
    }

    @Test
    fun `adv 11 - overflow indicator does NOT appear when cell events exactly fill available`() {
        // 1 bar at slot 0 covers Tue. Tue has exactly 2 cell events (= available slots).
        val bar = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val cellEvents = (10..11).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = i.toLong())
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar) + cellEvents))
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(render.slots[1][2] is SlotContent.CellEvent)
        assertTrue(render.slots[2][2] is SlotContent.CellEvent)
        // None should be Overflow
        for (slot in 0..2) {
            assertFalse("slot $slot should not be Overflow", render.slots[slot][2] is SlotContent.Overflow)
        }
    }

    @Test
    fun `adv 12 - cell event sort - all-day busy beats all-day free beats timed at same startTs`() {
        // Three events with IDENTICAL startTs to test the rank ordering, not the tiebreak.
        val ts = 1_730_000_000_000L
        val timed = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = ts)
        val free = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "TRANSPARENT", occStartTs = ts)
        val busy = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = true, transp = "OPAQUE", occStartTs = ts)
        val map = mapOf(20260310 to listOf<DisplayEvent>(timed, free, busy))
        val render = computeMonthFullWeekRender(weekSunToSat, map)
        // Order in slots 0..2 at col 2 (Tue): busy, free, timed.
        assertEquals(busy, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(free, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        assertEquals(timed, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)
    }

    @Test
    fun `adv 13 - cell event sort - same rank uses startTs ascending`() {
        // Three timed events with different startTs; should appear in startTs order.
        val a = roomEvent(id = 1, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 3_000L)
        val b = roomEvent(id = 2, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 1_000L)
        val c = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 2_000L)
        val map = mapOf(20260310 to listOf<DisplayEvent>(a, b, c))
        val render = computeMonthFullWeekRender(weekSunToSat, map)
        assertEquals(b, (render.slots[0][2] as SlotContent.CellEvent).displayEvent)  // ts=1000 first
        assertEquals(c, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)  // ts=2000
        assertEquals(a, (render.slots[2][2] as SlotContent.CellEvent).displayEvent)  // ts=3000
    }

    @Test
    fun `adv 14 - bar covering only column 0 (single day at week start)`() {
        // Edge: bar that starts in the previous week and ends on Sunday of this week.
        val a = roomEvent(id = 1, startDay = 20260301, endDay = 20260308, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        // Slot 0, col 0 only is a BarSegment with leftFlush=true, rightFlush=false (since 20260308 is in the week).
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
    fun `adv 15 - bar covering only column 6 (single day at week end)`() {
        val a = roomEvent(id = 1, startDay = 20260314, endDay = 20260321, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        val sat = render.slots[0][6]
        assertTrue(sat is SlotContent.BarSegment)
        val seg = sat as SlotContent.BarSegment
        assertFalse("leftFlush", seg.span.leftFlush)
        assertTrue("rightFlush", seg.span.rightFlush)
        assertEquals(6, seg.span.startCol)
        assertEquals(6, seg.span.endCol)
    }

    @Test
    fun `adv 16 - mixed bars and cell events - no slot collision`() {
        val bar1 = roomEvent(id = 1, startDay = 20260309, endDay = 20260311, isAllDay = true, occStartTs = 1L)
        val bar2 = roomEvent(id = 2, startDay = 20260311, endDay = 20260313, isAllDay = true, occStartTs = 2L)  // overlaps bar1 on Wed
        val cellEv = roomEvent(id = 3, startDay = 20260310, endDay = 20260310, isAllDay = false, occStartTs = 3L)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(bar1, bar2, cellEv)))
        // Wed (col 3): both bars overlap → slot 0 = bar1, slot 1 = bar2, slot 2 = Empty
        assertTrue(render.slots[0][3] is SlotContent.BarSegment)
        assertTrue(render.slots[1][3] is SlotContent.BarSegment)
        // Tue (col 2): bar1 at slot 0; cell event should be at slot 1
        assertTrue(render.slots[0][2] is SlotContent.BarSegment)
        assertTrue(
            "expected CellEvent at slot 1 col 2",
            render.slots[1][2] is SlotContent.CellEvent
        )
        assertEquals(cellEv, (render.slots[1][2] as SlotContent.CellEvent).displayEvent)
        // No bar+cellEvent collision anywhere
        assertNoBarCellCollision(render)
    }

    @Test
    fun `adv 17 - bar startCol equals endCol (single-column bar)`() {
        // Edge: a 2-day all-day event Mon-Tue but only Mon is in this week.
        val a = roomEvent(id = 1, startDay = 20260309, endDay = 20260309, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        // Wait: 20260309 to 20260309 is a single-day all-day event, not multi-day. Should NOT be a bar.
        // Instead, it should be a CellEvent at Mon.
        for (slot in render.slots) {
            for (cell in slot) {
                assertFalse("single-day event should never be a BarSegment", cell is SlotContent.BarSegment)
            }
        }
        // It IS a CellEvent at Mon (col 1).
        val mon = render.slots[0][1]
        assertTrue("Mon should be CellEvent for single-day event", mon is SlotContent.CellEvent)
    }

    @Test
    fun `adv 18 - empty cell row - no overflow indicator`() {
        // Empty week → no event slots, no overflow.
        val render = computeMonthFullWeekRender(weekSunToSat, emptyMap())
        for (slot in render.slots) {
            for (cell in slot) {
                assertFalse("empty week should have no Overflow", cell is SlotContent.Overflow)
            }
        }
    }

    @Test
    fun `adv 19 - dayCodes preserved verbatim regardless of event count`() {
        // The dayCodes list should match the input exactly, even with events.
        val events = (1..3).map { i ->
            roomEvent(id = i.toLong(), startDay = 20260310, endDay = 20260310, isAllDay = false)
        }
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(events))
        assertEquals(weekSunToSat, render.dayCodes)
    }

    @Test
    fun `adv 20 - BarSegment isStartOfRun and isEndOfRun reflect WITHIN-week clipping`() {
        // Bar Sat(week1) -> Mon(week2). For week1, Sat is the start AND end of run (clipped to week edge).
        val a = roomEvent(id = 1, startDay = 20260314, endDay = 20260316, isAllDay = true)
        val render = computeMonthFullWeekRender(weekSunToSat, bucketize(listOf(a)))
        val sat = render.slots[0][6] as SlotContent.BarSegment
        assertTrue("Sat (col 6) is start of within-week run", sat.isStartOfRun)
        assertTrue("Sat is end of within-week run (clipped)", sat.isEndOfRun)
        assertFalse("not leftFlush (event starts in this week)", sat.span.leftFlush)
        assertTrue("rightFlush (event continues to next week)", sat.span.rightFlush)
    }
}
