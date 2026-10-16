package org.onekash.kashcal.widget

import org.onekash.kashcal.widget.WidgetDataRepository.WidgetEvent

/**
 * Week-slot layout for the month widget's titles mode.
 *
 * A port of the in-app month view's
 * [org.onekash.kashcal.ui.screens.monthfull.computeMonthFullWeekRender] onto [WidgetEvent], as a
 * pure function: multi-day events become bars spanning their day columns in shared lanes,
 * single-day events fill the remaining per-cell slots, and what doesn't fit collapses into a
 * per-cell "+n more" slot. Unlike the app, a one-row week shows the top event instead of a "+n",
 * and trailing empty rows are dropped.
 *
 * Lanes win over cell events, as in the app: when bars fill a day column, that day's single-day
 * events are dropped silently; tapping the day still reaches them.
 */

/** A multi-day event bar covering [startCol]..[endCol] of a week row. */
internal data class MonthWidgetSpan(
    val event: WidgetEvent,
    val startCol: Int,
    val endCol: Int,
    /** Continues from the previous week: the leading edge stays flush, with no cap and no title. */
    val leftFlush: Boolean,
    /** Continues into the next week: the trailing edge stays flush, with no cap. */
    val rightFlush: Boolean,
)

/** One cell's worth of content inside a slot row. */
internal sealed interface MonthWidgetSlot {
    /** Nothing here; the cell background shows through. */
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
 * Computes the slot rows for one week of the month grid in titles mode.
 *
 * @param weekDayCodes the week's 7 day codes (YYYYMMDD), in column order
 * @param eventsByDay events by day code, as fetched for the whole grid range
 * @param maxSlots maximum slot rows below the day-number row; what doesn't fit collapses into
 *   [MonthWidgetSlot.Overflow]
 * @param maxEventsPerDay shared widget limit, including multi-day bars in each covered day
 */
internal fun computeMonthWidgetWeekRender(
    weekDayCodes: List<Int>,
    eventsByDay: Map<Int, List<WidgetEvent>>,
    maxSlots: Int,
    maxEventsPerDay: Int = Int.MAX_VALUE,
): MonthWidgetWeekRender {
    require(weekDayCodes.size == 7) { "weekDayCodes must have 7 entries" }
    if (maxSlots <= 0) return MonthWidgetWeekRender(emptyList())

    val eventLimit = maxEventsPerDay.coerceAtLeast(1)
    // The limit counts events, not the optional overflow marker. Never allocate more rows
    // than the available height/view budget, even when the user chooses fifteen events.
    val slotCount = minOf(maxSlots.toLong(), eventLimit.toLong() + 1).toInt()

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

    // 2. Clamp every span to the week. An event that overlaps the week lands on a valid 0..6
    //    range: startCol and endCol come from the same clamped dates as the guard.
    val spans = seen.values.mapNotNull { e ->
        if (e.endDay < weekStart || e.startDay > weekEnd) return@mapNotNull null
        val leftFlush = e.startDay < weekStart
        val rightFlush = e.endDay > weekEnd
        val startCol = if (leftFlush) 0 else weekDayCodes.indexOf(e.startDay)
        val endCol = if (rightFlush) 6 else weekDayCodes.indexOf(e.endDay)
        // After the overlap guard, indexOf misses only when the week list is inconsistent;
        // drop the span instead of drawing a bar from a bogus anchor.
        if (startCol < 0 || endCol < 0 || startCol > endCol) return@mapNotNull null
        MonthWidgetSpan(
            event = e,
            startCol = startCol,
            endCol = endCol,
            leftFlush = leftFlush,
            rightFlush = rightFlush,
        )
    }

    // 3. Place spans into lanes: earliest start first, longest first on ties, so short
    //    spans can share a freed lane behind a long one.
    val lanes = mutableListOf<MutableList<MonthWidgetSpan>>()
    for (span in spans.sortedWith(compareBy({ it.startCol }, { -(it.endCol - it.startCol) }))) {
        val laneIndex = lanes.indexOfFirst { lane -> lane.last().endCol < span.startCol }
        when {
            laneIndex >= 0 -> lanes[laneIndex].add(span)
            lanes.size < minOf(slotCount, eventLimit) -> lanes.add(mutableListOf(span))
            // The span overflows the lane budget and is dropped: at its start column every lane
            // is a bar, so there is no row for it or a "+n" marker (lanes win). It takes more
            // overlapping multi-day events in one week than rows.
            else -> Unit
        }
    }

    // 4. Bars occupy their lane's columns; cell events fill what remains.
    val grid: Array<Array<MonthWidgetSlot>> = Array(slotCount) { Array(7) { MonthWidgetSlot.Empty } }
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
        val freeSlots = (0 until slotCount).filter { grid[it][col] === MonthWidgetSlot.Empty }
        val shownBars = (0 until slotCount).count { grid[it][col] is MonthWidgetSlot.BarSegment }
        val totalBars = spans.count { col in it.startCol..it.endCol }
        val remainingLimit = (eventLimit - shownBars).coerceAtLeast(0)
        val possibleCells = minOf(cellEvents.size, freeSlots.size, remainingLimit)
        val hiddenAtCapacity = totalBars - shownBars + cellEvents.size - possibleCells
        val showOverflow = hiddenAtCapacity > 0 && freeSlots.isNotEmpty() && slotCount > 1
        val visibleCells = minOf(cellEvents.size, remainingLimit,
            (freeSlots.size - if (showOverflow) 1 else 0).coerceAtLeast(0))
        for (i in 0 until visibleCells) {
            grid[freeSlots[i]][col] = MonthWidgetSlot.CellEvent(cellEvents[i])
        }
        if (showOverflow) {
            grid[freeSlots.last()][col] = MonthWidgetSlot.Overflow(
                totalBars - shownBars + cellEvents.size - visibleCells
            )
        }
    }

    // 5. Drop trailing empty rows so short weeks stay compact. A week with no events has zero
    //    rows, and the caller renders nothing below the day numbers.
    val rows = grid.map { it.toList() }
    var last = rows.size - 1
    while (last >= 0 && rows[last].all { it === MonthWidgetSlot.Empty }) last--
    return MonthWidgetWeekRender(slots = if (last < 0) emptyList() else rows.subList(0, last + 1))
}

/** All-day busy = 0; all-day free = 1; timed = 2. Lower sorts first, as in the app. */
private fun eventOrderRank(event: WidgetEvent): Int = when {
    event.isAllDay && !event.isFree -> 0
    event.isAllDay && event.isFree -> 1
    else -> 2
}
