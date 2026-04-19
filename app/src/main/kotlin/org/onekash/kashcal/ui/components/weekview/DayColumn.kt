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
 * Displays a single day column in the week view time grid.
 *
 * Shows timed events positioned based on their start time and duration.
 * Handles overlapping events by stacking (max 2 visible, then "+N more" badge).
 *
 * @param date The date this column represents
 * @param events List of DisplayEvent for this day
 * @param hourHeight Height of one hour in the grid
 * @param isToday True if this column is today
 * @param onEventClick Called when an event is tapped
 * @param onOverflowClick Called when "+N more" badge is tapped (with list of overflow events)
 * @param onEmptyTap Called when user taps on empty space (hour, minute snapped to 15-min intervals)
 * @param modifier Modifier for the column
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
    // Calculate positioned events
    val endHour = WeekViewUtils.END_HOUR
    val positionedEvents = remember(events, date, startHour, hourHeight) {
        val dayIndex = date.dayOfWeek.value % 7  // 0=Sunday
        WeekViewUtils.positionEventsForDay(events, date, dayIndex, hourHeight, startHour, endHour)
    }

    // Group by overlap to show only 2 + badge
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

        // Background tap target — below events in z-order so event taps hit EventBlock first
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(date, startHour, hourHeight) {
                    detectTapGestures(
                        onDoubleTap = { },
                        onTap = { offset ->
                            val (hour, minute) = WeekViewUtils.offsetToTime(offset.y, hourHeightPx, startHour = startHour)
                            onEmptyTap(date, hour, minute)
                        }
                    )
                }
        )

        // Render event groups
        groupedEvents.forEach { group ->
            val (visibleEvents, overflowCount) = WeekViewUtils.groupForDisplay(group)

            visibleEvents.forEachIndexed { index, positioned ->
                // Calculate position within the column
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

            // Show overflow badge if more than 2 events overlap
            if (overflowCount > 0 && visibleEvents.isNotEmpty()) {
                val firstVisible = visibleEvents.first()
                val badgeTop = firstVisible.topOffset + firstVisible.height - 16.dp

                OverflowBadge(
                    count = overflowCount,
                    onClick = {
                        val overflowEvents = group.drop(WeekViewUtils.MAX_VISIBLE_OVERLAP)
                            .map { it.displayEvent }
                        onOverflowClick(overflowEvents)
                    },
                    modifier = Modifier
                        .offset(y = badgeTop)
                        .padding(start = 2.dp)
                )
            }
        }

        // Vertical day separator at the right edge
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
 * Groups overlapping events together.
 * Events in the same group have overlapping time ranges.
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
