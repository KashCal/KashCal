package org.onekash.kashcal.ui.components

import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * One rendered agenda entry. A multi-day event produces one item per day it
 * spans, carrying its [dayNumber] within [totalDays] so the card can show
 * "Day X of Y".
 */
internal data class AgendaDisplayItem(
    val displayEvent: DisplayEvent,
    val displayDay: Int,   // YYYYMMDD for this display entry
    val dayNumber: Int,    // 1, 2, 3... (which day of a multi-day event)
    val totalDays: Int     // total days in the event (1 for single-day)
)

/** A day's worth of agenda items under a single date header. */
internal data class AgendaDayGroup(
    val dayCode: Int,
    val items: List<AgendaDisplayItem>
)

/**
 * The full render model for the agenda LazyColumn: the ordered [groups] and a
 * map from each group's day code to the flat LazyColumn index of its header.
 *
 * The flat layout is, per group, one header item followed by its N card items,
 * so a group's header index is the count of all preceding headers and cards.
 * The LazyColumn must match this layout, with no items before the first header,
 * or [resolveScrollTargetIndex] lands on the wrong item.
 */
internal data class AgendaListModel(
    val groups: List<AgendaDayGroup>,
    val headerIndexByDayCode: Map<Int, Int>
)

/**
 * Expands [events] into the agenda render model: splits multi-day events per
 * day, keeps entries from today on, dedups by title, startTs and day, and groups
 * and sorts by day then start time. Pure, so it can run in a `remember`.
 */
internal fun buildAgendaListModel(
    events: List<DisplayEvent>,
    todayDayCode: Int
): AgendaListModel {
    val expandedItems = events.flatMap { displayEvent ->
        val isMultiDay = displayEvent.endDay > displayEvent.startDay
        if (isMultiDay) {
            val items = mutableListOf<AgendaDisplayItem>()
            var currentDay = displayEvent.startDay
            var dayNum = 1
            val total = Occurrence.calculateDaysBetween(displayEvent.startDay, displayEvent.endDay) + 1
            while (currentDay <= displayEvent.endDay) {
                items.add(AgendaDisplayItem(displayEvent, currentDay, dayNum, total))
                currentDay = Occurrence.incrementDayCode(currentDay)
                dayNum++
            }
            items
        } else {
            listOf(AgendaDisplayItem(displayEvent, displayEvent.startDay, 1, 1))
        }
    }.filter { item ->
        item.displayDay >= todayDayCode
    }.distinctBy { item ->
        "${item.displayEvent.title}-${item.displayEvent.startTs}-${item.displayDay}"
    }.sortedWith(
        compareBy(
            { it.displayDay },              // primary: by date
            { it.displayEvent.startTs }     // secondary: by original start time
        )
    )

    val groups = expandedItems
        .groupBy { it.displayDay }
        .map { (dayCode, items) -> AgendaDayGroup(dayCode, items) }
        .sortedBy { it.dayCode }

    // Flat index of each header: sum of (1 header + N cards) for preceding groups.
    val headerIndexByDayCode = LinkedHashMap<Int, Int>()
    var index = 0
    for (group in groups) {
        headerIndexByDayCode[group.dayCode] = index
        index += 1 + group.items.size
    }

    return AgendaListModel(groups, headerIndexByDayCode)
}

/**
 * Returns the LazyColumn item index to scroll to for [dayCode]: that day's
 * header, else the nearest following day's header (so tapping an empty day still
 * scrolls forward), else the last group's header. Returns -1 for an empty model;
 * the caller must skip scrolling then.
 */
internal fun resolveScrollTargetIndex(dayCode: Int, model: AgendaListModel): Int {
    if (model.groups.isEmpty()) return -1
    model.headerIndexByDayCode[dayCode]?.let { return it }
    val following = model.groups.firstOrNull { it.dayCode > dayCode }
    if (following != null) return model.headerIndexByDayCode.getValue(following.dayCode)
    return model.headerIndexByDayCode.getValue(model.groups.last().dayCode)
}
