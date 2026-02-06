package org.onekash.kashcal.ui.components.weekview

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Row showing overflow events (before 6am or after 11pm) for the visible week days.
 *
 * Features:
 * - Expandable section (collapsed by default)
 * - Shows event time and title
 * - Moon icon to indicate off-hours events
 * - Similar pattern to all-day events row
 *
 * @param label Row label (e.g., "Before 6am" or "After 11pm")
 * @param weekStartMs Start of the week in milliseconds
 * @param currentPage Current pager position (0-6)
 * @param overflowEvents Map of day index to list of overflow events
 * @param calendarColors Map of calendar ID to color
 * @param visibleDays Number of visible days (default: 3)
 * @param timeColumnWidth Width of the time labels column (for alignment)
 * @param onEventClick Called when an event is tapped
 * @param modifier Modifier for the row
 */
@Composable
fun OverflowEventsRow(
    label: String,
    weekStartMs: Long,
    currentPage: Int,
    overflowEvents: Map<Int, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    visibleDays: Int = 3,
    timeColumnWidth: Dp = 48.dp,
    timePattern: String = "h:mma",
    onEventClick: (Event, Occurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Check if there are any overflow events to show
    val hasAnyEvents = overflowEvents.values.any { it.isNotEmpty() }
    if (!hasAnyEvents) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Label column with moon icon
            Box(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .padding(end = 4.dp, top = 4.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = "🌙",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Overflow events for visible days
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = if (expanded) MAX_EXPANDED_HEIGHT else MAX_COLLAPSED_HEIGHT)
                    .animateContentSize()
            ) {
                repeat(visibleDays) { offset ->
                    val dayIndex = currentPage + offset
                    if (dayIndex in 0..6) {
                        val dayEvents = overflowEvents[dayIndex] ?: emptyList()

                        OverflowColumn(
                            events = dayEvents,
                            calendarColors = calendarColors,
                            expanded = expanded,
                            timePattern = timePattern,
                            onExpand = { expanded = true },
                            onEventClick = onEventClick,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // Empty space for out-of-range days
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Divider below overflow section
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Column showing overflow events for a single day.
 */
@Composable
private fun OverflowColumn(
    events: List<Pair<Event, Occurrence>>,
    calendarColors: Map<Long, Int>,
    expanded: Boolean,
    timePattern: String,
    onExpand: () -> Unit,
    onEventClick: (Event, Occurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) {
        Box(modifier = modifier.padding(4.dp))
        return
    }

    val visibleCount = if (expanded) events.size else MAX_VISIBLE_COLLAPSED
    val overflowCount = events.size - visibleCount

    Column(
        modifier = modifier
            .then(
                if (expanded) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Visible events
        events.take(visibleCount).forEach { (event, occurrence) ->
            val color = calendarColors[event.calendarId] ?: DEFAULT_COLOR

            OverflowEventChip(
                event = event,
                occurrence = occurrence,
                color = color,
                timePattern = timePattern,
                onClick = { onEventClick(event, occurrence) }
            )
        }

        // "+N more" button
        if (!expanded && overflowCount > 0) {
            Text(
                text = "+$overflowCount more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Single overflow event chip showing time and title.
 */
@Composable
private fun OverflowEventChip(
    event: Event,
    occurrence: Occurrence,
    color: Int,
    timePattern: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color(color)
    // Calculate luminance for text color
    val luminance = (0.299f * backgroundColor.red + 0.587f * backgroundColor.green + 0.114f * backgroundColor.blue)
    val textColor = if (luminance > 0.5f) Color.Black else Color.White

    val timeText = WeekViewUtils.formatOverflowTime(occurrence.startTs, timePattern)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Time
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.8f)
            )

            // Event title
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Constants
private val MAX_COLLAPSED_HEIGHT = 36.dp
private val MAX_EXPANDED_HEIGHT = 120.dp
private const val MAX_VISIBLE_COLLAPSED = 1
private const val DEFAULT_COLOR = 0xFF6200EE.toInt()
