package org.onekash.kashcal.ui.screens.monthfull

import androidx.compose.ui.graphics.Color
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.shared.contrastForegroundOn

// ============================================================
// Spanning bars across a week row
// ============================================================

data class WeekSpan(
    val eventKey: String,
    val displayEvent: DisplayEvent,
    val startCol: Int,
    val endCol: Int,
    val leftFlush: Boolean,
    val rightFlush: Boolean,
)

data class WeekSpanLayout(
    val lanes: List<List<WeekSpan>>,
    val multiDayEventKeys: Set<String>,
    val overflowCount: Int,
)

const val MAX_LANES = 3

fun computeWeekSpans(
    weekDayCodes: List<Int>,
    eventsByDayCode: Map<Int, List<DisplayEvent>>,
    maxLanes: Int = MAX_LANES,
): WeekSpanLayout {
    require(weekDayCodes.size == 7) { "weekDayCodes must have 7 entries" }
    val weekStart = weekDayCodes.first()
    val weekEnd = weekDayCodes.last()

    val seen = mutableMapOf<String, DisplayEvent>()
    for ((_, events) in eventsByDayCode) {
        for (e in events) {
            if (e.startDay == e.endDay) continue
            if (e.endDay < weekStart || e.startDay > weekEnd) continue
            seen.putIfAbsent(e.spanIdentityKey(), e)
        }
    }

    val rawSpans = seen.map { (key, e) ->
        val leftFlush = e.startDay < weekStart
        val rightFlush = e.endDay > weekEnd
        val startCol = if (leftFlush) 0 else weekDayCodes.indexOf(e.startDay)
        val endCol = if (rightFlush) 6 else weekDayCodes.indexOf(e.endDay)
        WeekSpan(
            eventKey = key,
            displayEvent = e,
            startCol = startCol,
            endCol = endCol,
            leftFlush = leftFlush,
            rightFlush = rightFlush,
        )
    }

    val sortedForPlacement = rawSpans.sortedWith(
        compareBy({ it.startCol }, { -(it.endCol - it.startCol) })
    )

    val lanes = mutableListOf<MutableList<WeekSpan>>()
    var overflow = 0
    for (span in sortedForPlacement) {
        val laneIndex = lanes.indexOfFirst { lane ->
            lane.last().endCol < span.startCol
        }
        when {
            laneIndex >= 0 -> lanes[laneIndex].add(span)
            lanes.size < maxLanes -> lanes.add(mutableListOf(span))
            else -> overflow++
        }
    }

    return WeekSpanLayout(
        lanes = lanes,
        multiDayEventKeys = seen.keys,
        overflowCount = overflow,
    )
}

// ============================================================
// Snippet style — for SINGLE-DAY events inside a cell
// ============================================================

sealed interface SnippetStyle {
    data class Stripe(val barColor: Int) : SnippetStyle
    data class AllDayBusy(val fillColor: Int, val textColor: Color) : SnippetStyle
    data class AllDayFree(val borderColor: Int, val tintFill: Color) : SnippetStyle
}

fun snippetStyleFor(displayEvent: DisplayEvent): SnippetStyle {
    val argb = displayEvent.eventColor ?: displayEvent.calendarColor
    return when {
        !displayEvent.isAllDay -> SnippetStyle.Stripe(argb)
        displayEvent.isFree -> SnippetStyle.AllDayFree(
            borderColor = argb,
            tintFill = Color(argb).copy(alpha = ALL_DAY_FREE_TINT_ALPHA),
        )
        else -> SnippetStyle.AllDayBusy(
            fillColor = argb,
            textColor = contrastForegroundOn(Color(argb)),
        )
    }
}

// ============================================================
// Span style — for MULTI-DAY events as spanning bars
// ============================================================

sealed interface SpanStyle {
    data class AllDayBusy(val fillColor: Int, val textColor: Color) : SpanStyle
    data class AllDayFree(val borderColor: Int, val tintFill: Color) : SpanStyle
    data class TimedSpan(val stripeColor: Int, val tintFill: Color) : SpanStyle
}

fun spanStyleFor(displayEvent: DisplayEvent): SpanStyle {
    val argb = displayEvent.eventColor ?: displayEvent.calendarColor
    return when {
        !displayEvent.isAllDay -> SpanStyle.TimedSpan(
            stripeColor = argb,
            tintFill = Color(argb).copy(alpha = TIMED_SPAN_TINT_ALPHA),
        )
        displayEvent.isFree -> SpanStyle.AllDayFree(
            borderColor = argb,
            tintFill = Color(argb).copy(alpha = ALL_DAY_FREE_TINT_ALPHA),
        )
        else -> SpanStyle.AllDayBusy(
            fillColor = argb,
            textColor = contrastForegroundOn(Color(argb)),
        )
    }
}

private const val ALL_DAY_FREE_TINT_ALPHA = 0.2f
private const val TIMED_SPAN_TINT_ALPHA = 0.18f

// ============================================================
// Per-cell slot rendering
// ============================================================

const val MAX_SNIPPETS = 3

sealed interface SlotContent {
    data object Empty : SlotContent
    data class BarSegment(
        val span: WeekSpan,
        val isStartOfRun: Boolean,
        val isEndOfRun: Boolean,
    ) : SlotContent
    data class CellEvent(val displayEvent: DisplayEvent) : SlotContent
    data class Overflow(val count: Int) : SlotContent
}

data class WeekSlotRender(
    val dayCodes: List<Int>,
    val slots: List<List<SlotContent>>, // [slotIndex][col]
)

fun computeMonthFullWeekRender(
    weekDayCodes: List<Int>,
    eventsByDayCode: Map<Int, List<DisplayEvent>>,
    maxLanes: Int = MAX_LANES,
    maxSnippets: Int = MAX_SNIPPETS,
): WeekSlotRender {
    val numSlots = maxOf(maxLanes, maxSnippets)
    val layout = computeWeekSpans(weekDayCodes, eventsByDayCode, maxLanes)

    // [slotIndex][col]
    val grid: Array<Array<SlotContent>> = Array(numSlots) { Array(7) { SlotContent.Empty } }

    // 1. Place bars in their lane index (slot index).
    for ((laneIndex, lane) in layout.lanes.withIndex()) {
        for (span in lane) {
            for (col in span.startCol..span.endCol) {
                grid[laneIndex][col] = SlotContent.BarSegment(
                    span = span,
                    isStartOfRun = col == span.startCol,
                    isEndOfRun = col == span.endCol,
                )
            }
        }
    }

    // 2. For each column, fill remaining slots with cell-only events sorted by
    //    (all-day busy first, then by startTs).
    for (col in 0..6) {
        val dayCode = weekDayCodes[col]
        val cellEvents = eventsByDayCode[dayCode].orEmpty()
            .filter { it.spanIdentityKey() !in layout.multiDayEventKeys }
            .sortedWith(
                compareBy<DisplayEvent> { eventOrderRank(it) }.thenBy { it.startTs }
            )

        val freeSlots = (0 until numSlots).filter { grid[it][col] === SlotContent.Empty }
        val available = freeSlots.size
        if (cellEvents.size <= available) {
            for ((i, event) in cellEvents.withIndex()) {
                grid[freeSlots[i]][col] = SlotContent.CellEvent(event)
            }
        } else {
            val visibleCount = (available - 1).coerceAtLeast(0)
            for (i in 0 until visibleCount) {
                grid[freeSlots[i]][col] = SlotContent.CellEvent(cellEvents[i])
            }
            if (available > 0) {
                val overflowCount = cellEvents.size - visibleCount
                grid[freeSlots[available - 1]][col] = SlotContent.Overflow(overflowCount)
            }
            // If available == 0, cell events are silently dropped (lanes-win policy).
        }
    }

    return WeekSlotRender(
        dayCodes = weekDayCodes,
        slots = grid.map { it.toList() },
    )
}

/** All-day busy = 0; all-day free = 1; timed = 2. Lower = sorted first. */
private fun eventOrderRank(event: DisplayEvent): Int = when {
    event.isAllDay && !event.isFree -> 0
    event.isAllDay && event.isFree -> 1
    else -> 2
}

// ============================================================
// Identity key
// ============================================================

internal fun DisplayEvent.spanIdentityKey(): String = when (this) {
    is DisplayEvent.Room -> "room:${event.id}:${occurrence.startTs}"
    is DisplayEvent.Device -> "device:${instance.eventId}:${instance.startTs}"
}
