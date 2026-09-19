package org.onekash.kashcal.ui.shared

/**
 * Greedily packs column-spans (anything with an inclusive [startCol]..[endCol]
 * range) into up to [maxLanes] non-overlapping lanes: sorted widest-first at
 * each start column, then each span claims the first lane whose last span ends
 * before it starts, or a new lane if capacity allows. Spans beyond [maxLanes]
 * are left out of the result entirely — callers decide how to surface them
 * (e.g. a per-column overflow badge).
 *
 * Shared by the week/day all-day strip ([org.onekash.kashcal.ui.components.weekview.computeAllDaySpans])
 * and the full month grid ([org.onekash.kashcal.ui.screens.monthfull.computeWeekSpans]),
 * which both need the identical placement algorithm for their spanning-bar rows.
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
            else -> { /* Beyond lane capacity — left unplaced; caller decides how to surface it. */ }
        }
    }
    return lanes
}
