package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.time.LocalDate

/**
 * Displays a single day column in the week view time grid.
 *
 * Shows timed events positioned based on their start time and duration.
 * Handles overlapping events by stacking (max 2 visible, then "+N more" badge).
 *
 * @param date The date this column represents
 * @param events List of (Event, Occurrence) pairs for this day
 * @param calendarColors Map of calendar ID to color
 * @param hourHeight Height of one hour in the grid
 * @param isToday True if this column is today
 * @param onEventClick Called when an event is tapped
 * @param onOverflowClick Called when "+N more" badge is tapped (with list of overflow events)
 * @param onLongPress Called when user long-presses on empty space (hour, minute snapped to 15-min intervals)
 * @param modifier Modifier for the column
 */
@Composable
fun DayColumn(
    date: LocalDate,
    events: List<Pair<Event, Occurrence>>,
    calendarColors: Map<Long, Int>,
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    isToday: Boolean = false,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    is24Hour: Boolean = false,
    onEventClick: (Event, Occurrence) -> Unit,
    onOverflowClick: (List<Pair<Event, Occurrence>>) -> Unit,
    onLongPress: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },  // (date, hour, minute)
    modifier: Modifier = Modifier
) {
    // Calculate positioned events
    val positionedEvents = remember(events, date) {
        val dayIndex = date.dayOfWeek.value % 7  // 0=Sunday
        WeekViewUtils.positionEventsForDay(events, dayIndex, hourHeight)
    }

    // Group by overlap to show only 2 + badge
    val groupedEvents = remember(positionedEvents) {
        groupOverlappingEvents(positionedEvents)
    }

    val todayBorderColor = MaterialTheme.colorScheme.primary
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val density = LocalDensity.current
    val hourHeightPx = with(density) { hourHeight.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (isToday) {
                    // Today uses a colored border (per spec)
                    Modifier.border(
                        width = 2.dp,
                        color = todayBorderColor,
                        shape = RectangleShape
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(date) {
                detectTapGestures(
                    onLongPress = { offset ->
                        // Calculate time from y offset
                        // Grid starts at START_HOUR (6am)
                        val minutesFromGridStart = (offset.y / hourHeightPx * 60).toInt()
                        val totalMinutes = WeekViewUtils.START_HOUR * 60 + minutesFromGridStart
                        val hour = (totalMinutes / 60).coerceIn(0, 23)
                        // Snap to 15-minute intervals
                        val minute = ((totalMinutes % 60) / 15) * 15
                        onLongPress(date, hour, minute)
                    }
                )
            }
    ) {
        val columnWidth = maxWidth

        // Render event groups
        groupedEvents.forEach { group ->
            val (visibleEvents, overflowCount) = WeekViewUtils.groupForDisplay(group)

            visibleEvents.forEachIndexed { index, positioned ->
                val color = calendarColors[positioned.event.calendarId]
                    ?: DEFAULT_EVENT_COLOR

                // Calculate position within the column
                val eventWidth = columnWidth * positioned.widthFraction
                val eventLeft = columnWidth * positioned.leftFraction

                EventBlock(
                    event = positioned.event,
                    occurrence = positioned.occurrence,
                    height = positioned.height,
                    color = color,
                    clampedStart = positioned.clampedStart,
                    clampedEnd = positioned.clampedEnd,
                    originalStartMinutes = positioned.originalStartMinutes,
                    originalEndMinutes = positioned.originalEndMinutes,
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
                    is24Hour = is24Hour,
                    onClick = { onEventClick(positioned.event, positioned.occurrence) },
                    modifier = Modifier
                        .offset(x = eventLeft, y = positioned.topOffset)
                        .width(eventWidth - 2.dp)  // 2dp gap between overlapping events
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
                            .map { it.event to it.occurrence }
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

    val sorted = events.sortedBy { it.originalStartMinutes }
    val groups = mutableListOf<MutableList<WeekViewUtils.PositionedEvent>>()

    for (event in sorted) {
        // Find existing group that overlaps with this event
        val overlappingGroup = groups.find { group ->
            group.any { other ->
                event.originalStartMinutes < other.originalEndMinutes &&
                    event.originalEndMinutes > other.originalStartMinutes
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

private const val DEFAULT_EVENT_COLOR = 0xFF6200EE.toInt()
