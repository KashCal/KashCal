package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.LocalDate

/**
 * Displays one day column of the time grid, placing timed events by start time and duration.
 *
 * Overlapping events sit side by side in up to [maxVisibleOverlap] slots; events in later slots
 * go behind a "+N more" badge.
 *
 * @param maxVisibleOverlap side-by-side slots shown before the badge
 * @param onOverflowClick called with the group's hidden events when the badge is tapped
 * @param onEmptyTap called with the date, hour and minute of a tap on empty space, the minute
 *   rounded to the nearest 15
 */
@Composable
fun DayColumn(
    date: LocalDate,
    events: List<DisplayEvent>,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    isToday: Boolean = false,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    startHour: Int = WeekViewUtils.START_HOUR,
    maxVisibleOverlap: Int = WeekViewUtils.MAX_VISIBLE_OVERLAP,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },  // (date, hour, minute)
    onEventDragStart: ((DisplayEvent, Offset) -> Unit)? = null,
    onEventDrag: ((Offset) -> Unit)? = null,
    onEventDragEnd: (() -> Unit)? = null,
    onEventDragCancel: (() -> Unit)? = null,
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier
) {
    val endHour = WeekViewUtils.END_HOUR
    val positionedEvents = remember(events, date, startHour, hourHeight, maxVisibleOverlap) {
        val dayIndex = date.dayOfWeek.value % 7  // 0=Sunday
        WeekViewUtils.positionEventsForDay(
            events, date, dayIndex, hourHeight, startHour, endHour, maxVisibleOverlap
        )
    }

    // Each group shows the events in its first maxVisibleOverlap slots, plus a badge.
    val groupedEvents = remember(positionedEvents) {
        groupOverlappingEvents(positionedEvents)
    }

    val todayBorderColor = MaterialTheme.colorScheme.primary
    val dropTargetColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (isDropTarget) {
                    Modifier.background(dropTargetColor)
                } else {
                    Modifier
                }
            )
            .then(
                if (isToday) {
                    Modifier.border(
                        width = 2.dp,
                        color = todayBorderColor,
                        shape = RectangleShape
                    )
                } else {
                    Modifier
                }
            )
    ) {
        val columnWidth = maxWidth

        // Background tap target, below the events in z-order so an event tap hits EventBlock.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(date, startHour, hourHeight) {
                    detectTapGestures(
                        onTap = { offset ->
                            val (hour, minute) = WeekViewUtils.offsetToTime(offset.y, hourHeightPx, startHour = startHour)
                            onEmptyTap(date, hour, minute)
                        }
                    )
                }
        )

        groupedEvents.forEach { group ->
            val (visibleEvents, overflowCount) = WeekViewUtils.groupForDisplay(group, maxVisibleOverlap)

            visibleEvents.forEach { positioned ->
                // Key each EventBlock on its event's stableKey so that when the day re-sorts
                // after a reschedule, Compose moves the node and its long-lived pointerInput
                // coroutine with its event. Keyed by position, the node would be rebound to
                // another event and fire that event's tap and drag callbacks.
                key(positioned.displayEvent.stableKey) {
                    val eventWidth = columnWidth * positioned.widthFraction
                    val eventLeft = columnWidth * positioned.leftFraction

                    val isDraggable = onEventDragStart != null &&
                        !positioned.displayEvent.isReadOnly &&
                        !positioned.displayEvent.isAllDay

                    EventBlock(
                        displayEvent = positioned.displayEvent,
                        height = positioned.height,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        onClick = { onEventClick(positioned.displayEvent) },
                        isDraggable = isDraggable,
                        onDragStart = if (isDraggable) { offset ->
                            onEventDragStart?.invoke(positioned.displayEvent, offset)
                        } else null,
                        onDrag = onEventDrag,
                        onDragEnd = onEventDragEnd,
                        onDragCancel = onEventDragCancel,
                        modifier = Modifier
                            .offset(x = eventLeft, y = positioned.topOffset)
                            .width(eventWidth - 2.dp)
                            .padding(horizontal = 1.dp)
                    )
                }
            }

            // Badge when some of the group's events sit in a slot past the cap.
            if (overflowCount > 0 && visibleEvents.isNotEmpty()) {
                val firstVisible = visibleEvents.first()
                val badgeTop = firstVisible.topOffset + firstVisible.height - 16.dp

                OverflowBadge(
                    count = overflowCount,
                    onClick = {
                        val overflowEvents = group
                            .filter { it.overlapIndex >= maxVisibleOverlap }
                            .map { it.displayEvent }
                        onOverflowClick(overflowEvents)
                    },
                    modifier = Modifier
                        .offset(y = badgeTop)
                        .padding(start = 2.dp)
                )
            }
        }

        // Day separator at the right edge.
        VerticalDivider(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            thickness = 0.5.dp,
            color = dividerColor
        )
    }
}

/**
 * Groups events by start time, adding each to the first group holding an event it overlaps.
 */
private fun groupOverlappingEvents(
    events: List<WeekViewUtils.PositionedEvent>
): List<List<WeekViewUtils.PositionedEvent>> {
    if (events.isEmpty()) return emptyList()

    val sorted = events.sortedBy { it.startMinutes }
    val groups = mutableListOf<MutableList<WeekViewUtils.PositionedEvent>>()

    for (event in sorted) {
        val overlappingGroup = groups.find { group ->
            group.any { other ->
                event.startMinutes < other.endMinutes &&
                    event.endMinutes > other.startMinutes
            }
        }

        if (overlappingGroup != null) {
            overlappingGroup.add(event)
        } else {
            groups.add(mutableListOf(event))
        }
    }

    return groups
}
