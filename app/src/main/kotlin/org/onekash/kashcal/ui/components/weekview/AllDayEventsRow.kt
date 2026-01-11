package org.onekash.kashcal.ui.components.weekview

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence

/**
 * Row showing all-day events for the visible week days.
 *
 * Features:
 * - Expandable section (collapsed by default, max 120dp when expanded)
 * - Shows "+N more" when there are hidden events
 * - Scrollable within 120dp limit when expanded
 * - Syncs with time grid pager
 *
 * @param pagerState Pager state synced with time grid
 * @param weekStartMs Start of the week in milliseconds
 * @param allDayEvents Map of day index to list of all-day events
 * @param calendarColors Map of calendar ID to color
 * @param timeColumnWidth Width of the time labels column (for alignment)
 * @param onEventClick Called when an all-day event is tapped
 * @param modifier Modifier for the row
 */
@Composable
fun AllDayEventsRow(
    pagerState: PagerState,
    weekStartMs: Long,
    allDayEvents: Map<Int, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    timeColumnWidth: Dp = 48.dp,
    onEventClick: (Event, Occurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Check if there are any all-day events to show
    val hasAnyEvents = allDayEvents.values.any { it.isNotEmpty() }
    if (!hasAnyEvents) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // "All day" label column
            Box(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .padding(end = 8.dp, top = 4.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = "All day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // All-day events pager (syncs with time grid)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = if (expanded) MAX_EXPANDED_HEIGHT else MAX_COLLAPSED_HEIGHT)
                    .animateContentSize()
            ) {
                val columnWidth = maxWidth / 3  // Match TimeGrid's 3-day view

                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fixed(columnWidth),  // CRITICAL: Show 3 days
                    beyondViewportPageCount = 2,
                    userScrollEnabled = false  // Scrolling controlled by TimeGrid
                ) { dayIndex ->
                    val dayEvents = allDayEvents[dayIndex] ?: emptyList()

                    AllDayColumn(
                        events = dayEvents,
                        calendarColors = calendarColors,
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onEventClick = onEventClick,
                        modifier = Modifier.width(columnWidth)
                    )
                }
            }
        }

        // Divider below all-day section
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Column showing all-day events for a single day.
 */
@Composable
private fun AllDayColumn(
    events: List<Pair<Event, Occurrence>>,
    calendarColors: Map<Long, Int>,
    expanded: Boolean,
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
            val color = calendarColors[event.calendarId] ?: DEFAULT_ALL_DAY_COLOR

            AllDayEventChip(
                event = event,
                occurrence = occurrence,
                color = color,
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
 * Single all-day event chip.
 */
@Composable
private fun AllDayEventChip(
    event: Event,
    occurrence: Occurrence,
    color: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color(color).copy(alpha = 0.2f)
    val borderColor = Color(color)

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
            // Color indicator
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .background(borderColor, RoundedCornerShape(1.5.dp))
            )

            // Event title
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
private const val DEFAULT_ALL_DAY_COLOR = 0xFF6200EE.toInt()

/**
 * Static all-day events row (not paged, shows visible days based on currentPage).
 * More reliable sync than paged version.
 */
@Composable
fun StaticAllDayEventsRow(
    weekStartMs: Long,
    currentPage: Int,
    allDayEvents: Map<Int, List<Pair<Event, Occurrence>>>,
    calendarColors: Map<Long, Int>,
    visibleDays: Int = 3,
    timeColumnWidth: Dp = 48.dp,
    onEventClick: (Event, Occurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Check if there are any all-day events to show
    val hasAnyEvents = allDayEvents.values.any { it.isNotEmpty() }
    if (!hasAnyEvents) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // "All day" label column
            Box(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .padding(end = 8.dp, top = 4.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = "All day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // All-day events for visible days
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = if (expanded) MAX_EXPANDED_HEIGHT else MAX_COLLAPSED_HEIGHT)
                    .animateContentSize()
            ) {
                repeat(visibleDays) { offset ->
                    val dayIndex = currentPage + offset
                    if (dayIndex in 0..6) {
                        val dayEvents = allDayEvents[dayIndex] ?: emptyList()

                        AllDayColumn(
                            events = dayEvents,
                            calendarColors = calendarColors,
                            expanded = expanded,
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

        // Divider below all-day section
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
