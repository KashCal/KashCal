package org.onekash.kashcal.ui.components.weekview

import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.shared.packSpansIntoLanes

/**
 * Layout model for the week/day all-day strip. Multi-day events (whether genuine
 * all-day events or timed events long enough to span more than one visible day)
 * are laid out as single spanning bars across the day columns they cover, instead
 * of being duplicated as independent chips in every day column — mirroring the
 * spanning-bar approach used by the full month grid ([org.onekash.kashcal.ui.screens.monthfull.MonthFullSpanLayout]).
 */

data class AllDaySpan(
    val displayEvent: DisplayEvent,
    val startCol: Int,
    val endCol: Int,
    /** True when the event actually started before the visible window (no left cap on the bar). */
    val leftFlush: Boolean,
    /** True when the event actually ends after the visible window (no right cap on the bar). */
    val rightFlush: Boolean,
)

internal data class AllDaySpanLayout(
    val lanes: List<List<AllDaySpan>>,
    val placedEventKeys: Set<String>,
)

sealed interface AllDaySlot {
    data object Empty : AllDaySlot
    data class BarSegment(val span: AllDaySpan) : AllDaySlot
    data class CellEvent(val event: DisplayEvent) : AllDaySlot
}

/**
 * Events for one day column that didn't fit in the grid's rows — surfaced as a
 * "+N" badge overlaid on that column (see [AllDayStripRender.overflowByColumn]),
 * rather than as a slot that would itself consume a row. A column can be fully
 * hidden this way (e.g. every row taken by spanning bars), so the badge must
 * never depend on a free row existing.
 */
data class ColumnOverflow(val count: Int, val events: List<DisplayEvent>)

data class AllDayStripRender(
    val slots: List<List<AllDaySlot>>, // [rowIndex][col]
    /** Per-column overflow, indexed like [slots]' columns; null = nothing hidden. */
    val overflowByColumn: List<ColumnOverflow?>,
)

/**
 * Packs multi-day events (startDay != endDay) that overlap [visibleDayCodes] into
 * non-overlapping lanes, greedily, up to [maxLanes]. Events beyond capacity are
 * left unplaced (their [AllDaySpanLayout.placedEventKeys] omits them) and fall
 * back to per-day cell treatment in [computeAllDayStripRender].
 */
internal fun computeAllDaySpans(
    visibleDayCodes: List<Int>,
    allDayEvents: List<DisplayEvent>,
    maxLanes: Int,
): AllDaySpanLayout {
    if (visibleDayCodes.isEmpty()) return AllDaySpanLayout(emptyList(), emptySet())
    val rangeStart = visibleDayCodes.first()
    val rangeEnd = visibleDayCodes.last()

    val seen = mutableMapOf<String, DisplayEvent>()
    for (e in allDayEvents) {
        if (e.startDay == e.endDay) continue
        if (e.endDay < rangeStart || e.startDay > rangeEnd) continue
        seen.putIfAbsent(e.stableKey, e)
    }

    val rawSpans = seen.values.map { e ->
        val leftFlush = e.startDay < rangeStart
        val rightFlush = e.endDay > rangeEnd
        val startCol = if (leftFlush) 0 else visibleDayCodes.indexOf(e.startDay)
        val endCol = if (rightFlush) visibleDayCodes.lastIndex else visibleDayCodes.indexOf(e.endDay)
        AllDaySpan(e, startCol, endCol, leftFlush, rightFlush)
    }

    val lanes = packSpansIntoLanes(rawSpans, maxLanes, startCol = { it.startCol }, endCol = { it.endCol })

    val placedKeys = lanes.flatten().map { it.displayEvent.stableKey }.toSet()
    return AllDaySpanLayout(lanes = lanes, placedEventKeys = placedKeys)
}

/**
 * Builds the full [rowIndex][col] render grid for the all-day strip: multi-day
 * spans occupy their lane's row across every column they cover, and each
 * column's remaining rows are filled with that day's single-day events (and any
 * multi-day events that didn't fit in a lane). Whatever doesn't fit — whether
 * because a column ran out of free rows, or every row in a column is taken by
 * spanning bars — is reported per column in [AllDayStripRender.overflowByColumn]
 * rather than claiming a row of its own, so a "+N" indicator is never lost even
 * when a column has zero free rows.
 */
fun computeAllDayStripRender(
    visibleDayCodes: List<Int>,
    allDayEvents: List<DisplayEvent>,
    maxRows: Int,
): AllDayStripRender {
    val numCols = visibleDayCodes.size
    if (numCols == 0 || maxRows == 0) return AllDayStripRender(emptyList(), emptyList())

    val layout = computeAllDaySpans(visibleDayCodes, allDayEvents, maxRows)
    val grid: Array<Array<AllDaySlot>> = Array(maxRows) { Array(numCols) { AllDaySlot.Empty } }

    for ((laneIndex, lane) in layout.lanes.withIndex()) {
        for (span in lane) {
            for (col in span.startCol..span.endCol) {
                grid[laneIndex][col] = AllDaySlot.BarSegment(span)
            }
        }
    }

    val overflowByColumn = arrayOfNulls<ColumnOverflow>(numCols)
    for (col in 0 until numCols) {
        val dayCode = visibleDayCodes[col]
        val allEventsForDay = allDayEvents
            .filter { it.startDay <= dayCode && it.endDay >= dayCode }
            .sortedBy { it.startTs }
        val columnEvents = allEventsForDay.filter { it.stableKey !in layout.placedEventKeys }

        val freeSlots = (0 until maxRows).filter { grid[it][col] === AllDaySlot.Empty }
        val visibleCount = minOf(columnEvents.size, freeSlots.size)
        for (i in 0 until visibleCount) {
            grid[freeSlots[i]][col] = AllDaySlot.CellEvent(columnEvents[i])
        }
        val hiddenEvents = columnEvents.drop(visibleCount)
        if (hiddenEvents.isNotEmpty()) {
            overflowByColumn[col] = ColumnOverflow(hiddenEvents.size, hiddenEvents)
        }
    }

    return AllDayStripRender(
        slots = grid.map { it.toList() },
        overflowByColumn = overflowByColumn.toList(),
    )
}
