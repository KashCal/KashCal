package org.onekash.kashcal.ui.screens.monthfull

import androidx.compose.ui.graphics.Color
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.DEEMPHASIZED_ALPHA
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.ui.shared.packSpansIntoLanes

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
    /** Spans that got no lane; each counts in the hidden count of every column it covers. */
    val hiddenSpans: List<WeekSpan>,
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

    val lanes = packSpansIntoLanes(rawSpans, maxLanes, startCol = { it.startCol }, endCol = { it.endCol })
    val laned = lanes.flatten().mapTo(HashSet()) { it.eventKey }

    return WeekSpanLayout(
        lanes = lanes,
        multiDayEventKeys = seen.keys,
        hiddenSpans = rawSpans.filter { it.eventKey !in laned },
    )
}

// ============================================================
// Snippet style for single-day events inside a cell
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
// Span style for multi-day events drawn as spanning bars
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

/**
 * Returns how many one-line slots of [slotPx], [gapPx] apart, fit under a [stripPx] date strip
 * in a week row [rowPx] tall, and never fewer than 1: the caller clips a slot that doesn't fit.
 */
fun monthFullFittedRows(rowPx: Int, stripPx: Int, slotPx: Int, gapPx: Int): Int {
    val pitch = (slotPx + gapPx).coerceAtLeast(1)
    return ((rowPx - stripPx + gapPx) / pitch).coerceAtLeast(1)
}

sealed interface SlotContent {
    data object Empty : SlotContent
    data class BarSegment(
        val span: WeekSpan,
        val isStartOfRun: Boolean,
        val isEndOfRun: Boolean,
    ) : SlotContent
    data class CellEvent(val displayEvent: DisplayEvent) : SlotContent
}

data class WeekSlotRender(
    val dayCodes: List<Int>,
    val slots: List<List<SlotContent>>, // [slotIndex][col]
    /** Per column, the events the slots don't show: single-day events and unlaned bars. */
    val hiddenCounts: List<Int>,
)

/**
 * Lays out one week row as [rows] one-line slots per day (fewer than 1 counts as 1).
 *
 * Multi-day bars take the top lanes, at most [MAX_LANES] and at most [rows] - 1, so
 * the bottom slot of every day is never a bar. Each day's single-day events then fill its free
 * slots in [eventOrderRank] order, then by startTs. What doesn't fit, including a bar left
 * without a lane, is counted in [WeekSlotRender.hiddenCounts] for every day it covers; no slot
 * is held back for the count.
 */
fun computeMonthFullWeekRender(
    weekDayCodes: List<Int>,
    eventsByDayCode: Map<Int, List<DisplayEvent>>,
    rows: Int,
): WeekSlotRender {
    val numSlots = rows.coerceAtLeast(1)
    val layout = computeWeekSpans(weekDayCodes, eventsByDayCode, maxLanes = minOf(MAX_LANES, numSlots - 1))

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

    val hidden = IntArray(7)
    for (span in layout.hiddenSpans) {
        for (col in span.startCol..span.endCol) hidden[col]++
    }

    // 2. Fill each column's free slots with its single-day events.
    for (col in 0..6) {
        val dayCode = weekDayCodes[col]
        val cellEvents = eventsByDayCode[dayCode].orEmpty()
            .filter { it.spanIdentityKey() !in layout.multiDayEventKeys }
            .sortedWith(
                compareBy<DisplayEvent> { eventOrderRank(it) }.thenBy { it.startTs }
            )

        val freeSlots = (0 until numSlots).filter { grid[it][col] === SlotContent.Empty }
        val shown = minOf(cellEvents.size, freeSlots.size)
        for (i in 0 until shown) {
            grid[freeSlots[i]][col] = SlotContent.CellEvent(cellEvents[i])
        }
        hidden[col] += cellEvents.size - shown
    }

    return WeekSlotRender(
        dayCodes = weekDayCodes,
        slots = grid.map { it.toList() },
        hiddenCounts = hidden.toList(),
    )
}

/** Returns the events [col] shows, top to bottom: its laned bars and its placed events. */
fun WeekSlotRender.shownEvents(col: Int): List<DisplayEvent> = slots.mapNotNull { row ->
    when (val slot = row[col]) {
        is SlotContent.BarSegment -> slot.span.displayEvent
        is SlotContent.CellEvent -> slot.displayEvent
        SlotContent.Empty -> null
    }
}

/** Returns how many distinct events a day's bucket holds, keyed as [computeWeekSpans] keys bars. */
fun dayEventCount(events: List<DisplayEvent>?): Int = events.orEmpty().distinctBy { it.spanIdentityKey() }.size

/**
 * Returns what a screen reader says for one day cell: [datePrefix] (its date, and "Today"),
 * [countLabel], the [titles] it shows in order, then [moreLabel] for what it hides, joined with
 * ", "; a null label is left out.
 */
fun monthFullDayDescription(datePrefix: String, countLabel: String?, titles: List<String>, moreLabel: String?): String =
    (listOfNotNull(datePrefix, countLabel) + titles + listOfNotNull(moreLabel)).joinToString(", ")

/** Returns an event row's alpha: 0.4 on a day outside the month, else [declinedCardAlpha]'s. */
fun monthEventAlpha(isOutDate: Boolean, isPast: Boolean, isDeclined: Boolean, isCancelled: Boolean): Float =
    if (isOutDate) OUT_OF_MONTH_EVENT_ALPHA else declinedCardAlpha(isPast, isDeclined, isCancelled)

/**
 * Returns a date number's alpha: [DEEMPHASIZED_ALPHA] for a past day unless it is the selected
 * one, else 1.
 */
fun monthDateNumberAlpha(isPastDay: Boolean, isSelected: Boolean): Float =
    if (isPastDay && !isSelected) DEEMPHASIZED_ALPHA else 1f

private const val OUT_OF_MONTH_EVENT_ALPHA = 0.4f

/** Ranks all-day busy 0, all-day free 1, timed 2; lower sorts first. */
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
