package org.onekash.kashcal.widget

import org.onekash.kashcal.widget.WidgetDataRepository.WidgetEvent

/**
 * Week-slot layout for the month widget's titles mode.
 *
 * A pure port of the in-app month view's [org.onekash.kashcal.ui.screens.monthfull.computeMonthFullWeekRender]
 * onto [WidgetEvent]: multi-day events become continuous bars that span their day columns in
 * a shared lane grid, single-day events fill the remaining per-cell slots, and whatever does
 * not fit collapses into a per-cell "+n more" overflow slot.
 *
 * Lanes win over cell events: when a day column is fully occupied by spanning bars, that
 * day's single-day events are dropped silently (they remain reachable by tapping the day) —
 * the same policy the in-app month view applies.
 */

/** A multi-day event bar covering [startCol]..[endCol] of a week row. */
internal data class MonthWidgetSpan(
    val event: WidgetEvent,
    val startCol: Int,
    val endCol: Int,
    /** Continues from the previous week — the bar's leading edge stays flush (no cap, no title). */
    val leftFlush: Boolean,
    /** Continues into the next week — the bar's trailing edge stays flush (no cap). */
    val rightFlush: Boolean,
)

/** One cell's worth of content inside a slot row. */
internal sealed interface MonthWidgetSlot {
    /** Nothing here — the cell background shows through. */
    data object Empty : MonthWidgetSlot

    /** Part of a multi-day bar; a run of consecutive segments forms one continuous bar. */
    data class BarSegment(val span: MonthWidgetSpan) : MonthWidgetSlot

    /** A single-day event snippet (timed stripe or all-day chip). */
    data class CellEvent(val event: WidgetEvent) : MonthWidgetSlot

    /** "+n more" marker for events that did not fit this cell. */
    data class Overflow(val count: Int) : MonthWidgetSlot
}

/**
 * The slot rows of one week: [slots] is indexed [slotIndex][column] with 7 columns.
 * Only non-empty rows are returned.
 */
internal data class MonthWidgetWeekRender(
    val slots: List<List<MonthWidgetSlot>>
)

/**
 * Compute the slot rows for one week of the month grid in titles mode.
 *
 * @param weekDayCodes the 7 day codes (YYYYMMDD) of the week, in column order
 * @param eventsByDay events grouped by day code (as fetched for the whole grid range)
 * @param maxSlots maximum number of slot rows below the day-number row; what does not fit
 *   collapses into [MonthWidgetSlot.Overflow]
 */
internal fun computeMonthWidgetWeekRender(
    weekDayCodes: List<Int>,
    eventsByDay: Map<Int, List<WidgetEvent>>,
    maxSlots: Int
): MonthWidgetWeekRender {
    require(weekDayCodes.size == 7) { "weekDayCodes must have 7 entries" }
    if (maxSlots <= 0) return MonthWidgetWeekRender(emptyList())

    val weekStart = weekDayCodes.first()
    val weekEnd = weekDayCodes.last()

    // 1. Collect the week's multi-day events, deduped across day buckets. Only this week's
    //    own day buckets are scanned: the fetch range covers the whole month grid, and an
    //    event that never touches this week must never leak into the row as a flush bar.
    val seen = LinkedHashMap<String, WidgetEvent>()
    for (dayCode in weekDayCodes) {
        for (e in eventsByDay[dayCode].orEmpty()) {
            if (!e.isMultiDay) continue
            seen.putIfAbsent(e.spanKey, e)
        }
    }

    // 2. Clamp every span to the week. The guards make the columns total: an event that
    //    overlaps the week always lands on a valid [0..6] range — startCol/endCol are
    //    derived from the same clamped dates, so they can never disagree with the guard.
    val spans = seen.values.mapNotNull { e ->
        if (e.endDay < weekStart || e.startDay > weekEnd) return@mapNotNull null
        val leftFlush = e.startDay < weekStart
        val rightFlush = e.endDay > weekEnd
        val startCol = if (leftFlush) 0 else weekDayCodes.indexOf(e.startDay)
        val endCol = if (rightFlush) 6 else weekDayCodes.indexOf(e.endDay)
        // With the overlap guard above, indexOf can only miss when the week list itself is
        // inconsistent — drop rather than draw a bar from a bogus anchor.
        if (startCol < 0 || endCol < 0 || startCol > endCol) return@mapNotNull null
        MonthWidgetSpan(
            event = e,
            startCol = startCol,
            endCol = endCol,
            leftFlush = leftFlush,
            rightFlush = rightFlush,
        )
    }

    // 2. Place spans into lanes: earliest start first, longest first on ties, so short
    //    spans can share a freed lane behind a long one.
    val lanes = mutableListOf<MutableList<MonthWidgetSpan>>()
    for (span in spans.sortedWith(compareBy({ it.startCol }, { -(it.endCol - it.startCol) }))) {
        val laneIndex = lanes.indexOfFirst { lane -> lane.last().endCol < span.startCol }
        when {
            laneIndex >= 0 -> lanes[laneIndex].add(span)
            lanes.size < maxSlots -> lanes.add(mutableListOf(span))
            // Span overflows the lane budget and is dropped. At its start column every lane
            // is already a bar, so there is no free row for it or a "+n" marker (lanes-win
            // policy). Rare — needs more overlapping multi-day events in one week than rows.
            else -> Unit
        }
    }

    // 3. Bars occupy their lane (slot) columns; cell events fill what remains.
    val grid: Array<Array<MonthWidgetSlot>> = Array(maxSlots) { Array(7) { MonthWidgetSlot.Empty } }
    for ((laneIndex, lane) in lanes.withIndex()) {
        for (span in lane) {
            for (col in span.startCol..span.endCol) {
                grid[laneIndex][col] = MonthWidgetSlot.BarSegment(span)
            }
        }
    }

    for (col in 0..6) {
        val cellEvents = eventsByDay[weekDayCodes[col]].orEmpty()
            .filter { !it.isMultiDay }
            .sortedWith(compareBy<WidgetEvent> { eventOrderRank(it) }.thenBy { it.startTs })

        val freeSlots = (0 until maxSlots).filter { grid[it][col] === MonthWidgetSlot.Empty }
        when {
            cellEvents.size <= freeSlots.size -> {
                for ((i, event) in cellEvents.withIndex()) {
                    grid[freeSlots[i]][col] = MonthWidgetSlot.CellEvent(event)
                }
            }
            freeSlots.isEmpty() -> {
                // Column fully bar-occupied: this cell's single-day events — and any
                // multi-day span that overflowed the lane budget and would have started here
                // — drop with no "+n", because every row is a bar (lanes-win policy leaves no
                // slot for a marker).
            }
            maxSlots == 1 -> {
                // Only one row for the whole week: reserving it for a "+n" marker would leave the
                // cell showing a bare count with no event name at all — worse than the dots this row
                // replaced. Show the top event's title instead and hide the rest silently, the way
                // the dots fallback caps at three without spelling out the overflow.
                grid[freeSlots[0]][col] = MonthWidgetSlot.CellEvent(cellEvents[0])
            }
            else -> {
                // More events than free slots: show what fits, collapse the rest into a
                // "+n" marker in the LAST free slot — matching the in-app month view. With
                // exactly one free slot (a bar occupies the others) the visible count is zero, so
                // the marker still reports the day's real count alongside that bar's context.
                val visibleCount = (freeSlots.size - 1).coerceAtLeast(0)
                for (i in 0 until visibleCount) {
                    grid[freeSlots[i]][col] = MonthWidgetSlot.CellEvent(cellEvents[i])
                }
                grid[freeSlots[freeSlots.size - 1]][col] =
                    MonthWidgetSlot.Overflow(cellEvents.size - visibleCount)
            }
        }
    }

    // 4. Drop trailing all-empty rows so short weeks stay compact. A week with no events at
    //    all collapses to zero rows — the caller renders nothing below the day numbers.
    val rows = grid.map { it.toList() }
    var last = rows.size - 1
    while (last >= 0 && rows[last].all { it === MonthWidgetSlot.Empty }) last--
    return MonthWidgetWeekRender(slots = if (last < 0) emptyList() else rows.subList(0, last + 1))
}

/** All-day busy = 0; all-day free = 1; timed = 2. Lower sorts first — same as the app. */
private fun eventOrderRank(event: WidgetEvent): Int = when {
    event.isAllDay && !event.isFree -> 0
    event.isAllDay && event.isFree -> 1
    else -> 2
}
