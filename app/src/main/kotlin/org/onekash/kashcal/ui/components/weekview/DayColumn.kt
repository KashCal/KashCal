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
 * Handles overlapping events by stacking up to [maxVisibleOverlap] side-by-side, then "+N more" badge.
 *
 * @param date The date this column represents
 * @param events List of DisplayEvent for this day
 * @param hourHeight Height of one hour in the grid
 * @param isToday True if this column is today
 * @param maxVisibleOverlap Max number of overlapping events shown side-by-side before overflow badge
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
    maxVisibleOverlap: Int = WeekViewUtils.MAX_VISIBLE_OVERLAP,
    onEventClick: (DisplayEvent) -> Unit,
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },  // (date, hour, minute)
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Calculate positioned events
    val endHour = WeekViewUtils.END_HOUR
    val positionedEvents = remember(events, date, startHour, hourHeight, maxVisibleOverlap) {
        val dayIndex = date.dayOfWeek.value % 7  // 0=Sunday
        WeekViewUtils.positionEventsForDay(
            events, date, dayIndex, hourHeight, startHour, endHour, maxVisibleOverlap
        )
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
                        onTap = { offset ->
                            val (hour, minute) = WeekViewUtils.offsetToTime(offset.y, hourHeightPx, startHour = startHour)
                            onEmptyTap(date, hour, minute)
                        }
                    )
                }
        )

        // Render event groups
        groupedEvents.forEach { group ->
            val (visibleEvents, overflowCount) = WeekViewUtils.groupForDisplay(group, maxVisibleOverlap)

            visibleEvents.forEach { positioned ->
                // Keyed on the event's stable identity so that when the day
                // re-sorts after a reschedule, Compose moves each EventBlock
                // node (and its long-lived gesture pointerInput coroutine) with
                // its event instead of reusing the node by position and rebinding
                // it to a different event — which would fire the wrong event's
                // tap callback.
                key(positioned.displayEvent.stableKey) {
                    // Calculate position within the column
                    // Rendered size of the block, gutters already subtracted, so the
                    // width passed to EventBlock is the one it actually gets.
                    val eventWidth = columnWidth * positioned.widthFraction - 4.dp
                    val eventLeft = columnWidth * positioned.leftFraction + 1.dp

                    EventBlock(
                        displayEvent = positioned.displayEvent,
                        height = positioned.height,
                        width = eventWidth,
                        showEventEmojis = showEventEmojis,
                        timePattern = timePattern,
                        onClick = { onEventClick(positioned.displayEvent) },
                        modifier = Modifier
                            .offset(x = eventLeft, y = positioned.topOffset)
                            .width(eventWidth)
                    )
                }
            }

            // Show overflow badge if more than 2 events overlap
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
