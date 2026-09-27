package org.onekash.kashcal.ui.shared

/**
 * Packs column spans (inclusive [startCol]..[endCol]) greedily into at most [maxLanes]
 * non-overlapping lanes.
 *
 * Spans are sorted by start column, widest first within a column; each takes the first lane whose
 * last span ends before it starts, or opens a new lane while fewer than [maxLanes] exist. A span
 * that fits nowhere is left out of the result, and the caller decides how to surface it (for
 * example a per-column overflow badge).
 *
 * Shared by the week and day all-day strip
 * ([org.onekash.kashcal.ui.components.weekview.computeAllDaySpans]) and the full month grid
 * ([org.onekash.kashcal.ui.screens.monthfull.computeWeekSpans]) so their spanning bars place alike.
 */
internal fun <T> packSpansIntoLanes(
    spans: List<T>,
    maxLanes: Int,
    startCol: (T) -> Int,
    endCol: (T) -> Int,
): List<List<T>> {
    val sorted = spans.sortedWith(compareBy({ startCol(it) }, { -(endCol(it) - startCol(it)) }))
    val lanes = mutableListOf<MutableList<T>>()
    for (span in sorted) {
        val laneIndex = lanes.indexOfFirst { lane -> endCol(lane.last()) < startCol(span) }
        when {
            laneIndex >= 0 -> lanes[laneIndex].add(span)
            lanes.size < maxLanes -> lanes.add(mutableListOf(span))
            else -> { /* No lane left: the span stays unplaced. */ }
        }
    }
    return lanes
}
