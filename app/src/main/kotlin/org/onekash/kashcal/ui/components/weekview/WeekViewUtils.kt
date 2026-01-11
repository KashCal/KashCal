package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Utility functions for the week view component.
 *
 * Handles:
 * - Week/date calculations
 * - Range formatting for header
 * - Time snapping for long-press
 * - Event clamping to visible range (6am-11pm)
 * - Event positioning and overlap detection
 */
object WeekViewUtils {

    // Time grid constants
    const val START_HOUR = 6     // 6 AM
    const val END_HOUR = 23      // 11 PM (grid shows up to 11pm)
    const val TOTAL_HOURS = END_HOUR - START_HOUR  // 17 hours
    const val MINUTES_PER_HOUR = 60
    const val SNAP_INTERVAL_MINUTES = 15

    // Visual constants
    val HOUR_HEIGHT = 60.dp
    val MIN_EVENT_HEIGHT = 20.dp
    val MAX_VISIBLE_OVERLAP = 2  // Show max 2 events stacked, rest in "+N more"

    // ==================== Week Calculations ====================

    /**
     * Get the start of the week (Sunday at midnight) for a given date.
     *
     * @param date The date to find the week start for
     * @return LocalDate representing Sunday of that week
     */
    fun getWeekStart(date: LocalDate): LocalDate {
        val dayOfWeek = date.dayOfWeek.value  // Monday=1, Sunday=7
        val daysToSubtract = if (dayOfWeek == 7) 0L else dayOfWeek.toLong()
        return date.minusDays(daysToSubtract)
    }

    /**
     * Get the start of the week for a given timestamp.
     *
     * @param timestampMs Timestamp in milliseconds
     * @param zoneId Timezone to use (default: system default)
     * @return Timestamp of Sunday at midnight in the given timezone
     */
    fun getWeekStartMs(timestampMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(timestampMs)
            .atZone(zoneId)
            .toLocalDate()
        val weekStart = getWeekStart(date)
        return weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Get the day index (0-6) within a week for a given timestamp.
     * Sunday = 0, Monday = 1, ..., Saturday = 6
     *
     * @param timestampMs Timestamp in milliseconds
     * @param weekStartMs Start of the week in milliseconds
     * @return Day index (0-6)
     */
    fun getDayIndex(timestampMs: Long, weekStartMs: Long): Int {
        val daysDiff = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(weekStartMs).atZone(ZoneId.systemDefault()).toLocalDate(),
            Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
        )
        return daysDiff.toInt().coerceIn(0, 6)
    }

    // ==================== Range Formatting ====================

    /**
     * Format a compact date range for the header (e.g., "Jan 6-8" or "Dec 30 - Jan 1, 2026").
     *
     * Rules:
     * - Same month: "Jan 6-8"
     * - Cross month (same year, current year): "Dec 30 - Jan 1"
     * - Cross month (different year OR not current year): "Dec 30 - Jan 1, 2026"
     *
     * @param startDate First day of visible range
     * @param endDate Last day of visible range
     * @return Formatted string for header
     */
    fun formatCompactRange(startDate: LocalDate, endDate: LocalDate): String {
        val now = LocalDate.now()
        val showYear = startDate.year != now.year || endDate.year != now.year

        val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

        return when {
            // Same month
            startDate.month == endDate.month && startDate.year == endDate.year -> {
                val month = startDate.format(monthFormatter)
                if (showYear) {
                    "$month ${startDate.dayOfMonth}-${endDate.dayOfMonth}, ${startDate.year}"
                } else {
                    "$month ${startDate.dayOfMonth}-${endDate.dayOfMonth}"
                }
            }
            // Cross month (same year)
            startDate.year == endDate.year -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                if (showYear) {
                    "$startMonth ${startDate.dayOfMonth} - $endMonth ${endDate.dayOfMonth}, ${startDate.year}"
                } else {
                    "$startMonth ${startDate.dayOfMonth} - $endMonth ${endDate.dayOfMonth}"
                }
            }
            // Cross year
            else -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth} - $endMonth ${endDate.dayOfMonth}, ${endDate.year}"
            }
        }
    }

    /**
     * Format a compact date range given timestamps and pager position.
     *
     * @param weekStartMs Start of the week in milliseconds
     * @param pagerPosition Current pager position (0-6, index of first visible day)
     * @param visibleDays Number of visible days (default: 3)
     * @return Formatted header string
     */
    fun formatHeaderRange(weekStartMs: Long, pagerPosition: Int, visibleDays: Int = 3): String {
        val weekStart = Instant.ofEpochMilli(weekStartMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val startDate = weekStart.plusDays(pagerPosition.toLong())
        val endDate = startDate.plusDays((visibleDays - 1).toLong())
        return formatCompactRange(startDate, endDate)
    }

    // ==================== Time Snapping ====================

    /**
     * Snap minutes to the nearest quarter hour (15-minute interval).
     *
     * @param minutes Minutes to snap
     * @return Snapped minutes (0, 15, 30, or 45)
     */
    fun snapToQuarterHour(minutes: Int): Int {
        return ((minutes + SNAP_INTERVAL_MINUTES / 2) / SNAP_INTERVAL_MINUTES) * SNAP_INTERVAL_MINUTES
    }

    /**
     * Calculate the time from a Y offset in the time grid.
     *
     * @param yOffset Y position in the grid (pixels)
     * @param hourHeightPx Height of one hour in pixels
     * @param snap Whether to snap to 15-minute intervals
     * @return Pair of (hour, minute)
     */
    fun offsetToTime(yOffset: Float, hourHeightPx: Float, snap: Boolean = true): Pair<Int, Int> {
        val totalMinutes = START_HOUR * MINUTES_PER_HOUR + (yOffset / hourHeightPx * MINUTES_PER_HOUR).toInt()
        var hour = totalMinutes / MINUTES_PER_HOUR
        var minute = totalMinutes % MINUTES_PER_HOUR

        if (snap) {
            minute = snapToQuarterHour(minute)
            if (minute >= MINUTES_PER_HOUR) {
                hour++
                minute = 0
            }
        }

        return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }

    // ==================== Event Clamping ====================

    /**
     * Result of clamping an event to the visible time range.
     */
    data class ClampResult(
        /** Display start time in minutes from midnight */
        val displayStartMinutes: Int,
        /** Display end time in minutes from midnight */
        val displayEndMinutes: Int,
        /** True if event starts before 6am (clamped) */
        val clampedStart: Boolean,
        /** True if event ends after 11pm (clamped) */
        val clampedEnd: Boolean,
        /** Original start time (for indicator text) */
        val originalStartMinutes: Int,
        /** Original end time (for indicator text) */
        val originalEndMinutes: Int
    )

    /**
     * Clamp event times to the visible range (6am-11pm).
     * Events starting before 6am are clamped to 6am with an indicator.
     * Events ending after 11pm are clamped to 11pm with an indicator.
     *
     * @param startMinutes Start time in minutes from midnight
     * @param endMinutes End time in minutes from midnight
     * @return ClampResult with display times and clamping indicators
     */
    fun clampToVisibleRange(startMinutes: Int, endMinutes: Int): ClampResult {
        val gridStartMinutes = START_HOUR * MINUTES_PER_HOUR  // 360 (6am)
        val gridEndMinutes = END_HOUR * MINUTES_PER_HOUR      // 1380 (11pm)

        return ClampResult(
            displayStartMinutes = startMinutes.coerceIn(gridStartMinutes, gridEndMinutes),
            displayEndMinutes = endMinutes.coerceIn(gridStartMinutes, gridEndMinutes),
            clampedStart = startMinutes < gridStartMinutes,
            clampedEnd = endMinutes > gridEndMinutes,
            originalStartMinutes = startMinutes,
            originalEndMinutes = endMinutes
        )
    }

    /**
     * Format time for clamped indicator (e.g., "5:30am").
     *
     * @param minutes Time in minutes from midnight
     * @return Formatted time string
     */
    fun formatClampIndicatorTime(minutes: Int): String {
        val hour = minutes / MINUTES_PER_HOUR
        val minute = minutes % MINUTES_PER_HOUR
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val amPm = if (hour < 12) "am" else "pm"
        return if (minute == 0) {
            "$hour12$amPm"
        } else {
            "$hour12:${minute.toString().padStart(2, '0')}$amPm"
        }
    }

    // ==================== Event Positioning ====================

    /**
     * Positioned event for rendering in the week view.
     */
    data class PositionedEvent(
        val event: Event,
        val occurrence: Occurrence,
        /** Top offset from grid start (relative to 6am) */
        val topOffset: Dp,
        /** Height of the event block */
        val height: Dp,
        /** Horizontal position as fraction (0.0 = left edge, 1.0 = right edge) */
        val leftFraction: Float,
        /** Width as fraction of column width */
        val widthFraction: Float,
        /** Index in overlap group (0 = leftmost) */
        val overlapIndex: Int,
        /** Total events in overlap group */
        val overlapTotal: Int,
        /** True if start time is clamped (show indicator) */
        val clampedStart: Boolean,
        /** True if end time is clamped (show indicator) */
        val clampedEnd: Boolean,
        /** Original start time for indicator */
        val originalStartMinutes: Int,
        /** Original end time for indicator */
        val originalEndMinutes: Int,
        /** Day index within week (0=Sunday) */
        val dayIndex: Int
    )

    /**
     * Calculate positions for events in a single day column.
     * Handles overlap detection and stacking.
     *
     * @param events List of (Event, Occurrence) pairs for the day
     * @param dayIndex Day index (0-6)
     * @param hourHeight Height of one hour
     * @return List of positioned events
     */
    fun positionEventsForDay(
        events: List<Pair<Event, Occurrence>>,
        dayIndex: Int,
        hourHeight: Dp = HOUR_HEIGHT
    ): List<PositionedEvent> {
        if (events.isEmpty()) return emptyList()

        // Sort by start time, then by duration (longer events first for better stacking)
        val sorted = events.sortedWith(compareBy(
            { it.second.startTs },
            { -(it.second.endTs - it.second.startTs) }  // Negative for descending duration
        ))

        // Convert to positioned events with overlap detection
        val positioned = mutableListOf<PositionedEvent>()
        val activeEvents = mutableListOf<PositionedEvent>()

        for ((event, occurrence) in sorted) {
            // Calculate time in minutes from midnight
            val startDateTime = Instant.ofEpochMilli(occurrence.startTs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            val endDateTime = Instant.ofEpochMilli(occurrence.endTs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()

            val startMinutes = startDateTime.hour * MINUTES_PER_HOUR + startDateTime.minute
            val endMinutes = endDateTime.hour * MINUTES_PER_HOUR + endDateTime.minute

            // Clamp to visible range
            val clampResult = clampToVisibleRange(startMinutes, endMinutes)

            // Skip if entirely outside visible range
            if (clampResult.displayEndMinutes <= clampResult.displayStartMinutes) continue

            // Calculate visual position
            val topMinutes = clampResult.displayStartMinutes - START_HOUR * MINUTES_PER_HOUR
            val durationMinutes = clampResult.displayEndMinutes - clampResult.displayStartMinutes

            val topOffset = (topMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp
            val height = maxOf(
                (durationMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp,
                MIN_EVENT_HEIGHT
            )

            // Remove events that have ended before this event starts
            activeEvents.removeAll { active ->
                val activeEndMinutes = active.originalEndMinutes
                activeEndMinutes <= startMinutes
            }

            // Count overlapping events (events that overlap with this one's time slot)
            val overlapIndex = activeEvents.size
            val overlapTotal = overlapIndex + 1  // Will be updated for all in group

            // Calculate horizontal position based on overlap
            val widthFraction = 1f / maxOf(overlapTotal, 1)
            val leftFraction = overlapIndex * widthFraction

            val positionedEvent = PositionedEvent(
                event = event,
                occurrence = occurrence,
                topOffset = topOffset,
                height = height,
                leftFraction = leftFraction,
                widthFraction = widthFraction,
                overlapIndex = overlapIndex,
                overlapTotal = overlapTotal,
                clampedStart = clampResult.clampedStart,
                clampedEnd = clampResult.clampedEnd,
                originalStartMinutes = startMinutes,
                originalEndMinutes = endMinutes,
                dayIndex = dayIndex
            )

            activeEvents.add(positionedEvent)
            positioned.add(positionedEvent)
        }

        // Update overlap totals for all events in each group
        return updateOverlapTotals(positioned)
    }

    /**
     * Update overlap totals after initial positioning.
     * Ensures all events in an overlap group know the total count.
     */
    private fun updateOverlapTotals(events: List<PositionedEvent>): List<PositionedEvent> {
        if (events.isEmpty()) return events

        // Group events that overlap each other
        val groups = mutableListOf<MutableList<PositionedEvent>>()

        for (event in events) {
            val startMinutes = event.originalStartMinutes
            val endMinutes = event.originalEndMinutes

            // Find existing group that this event overlaps with
            val overlappingGroup = groups.find { group ->
                group.any { other ->
                    startMinutes < other.originalEndMinutes && endMinutes > other.originalStartMinutes
                }
            }

            if (overlappingGroup != null) {
                overlappingGroup.add(event)
            } else {
                groups.add(mutableListOf(event))
            }
        }

        // Update all events with correct totals and positions
        return groups.flatMap { group ->
            val total = group.size
            group.mapIndexed { index, event ->
                event.copy(
                    overlapIndex = index,
                    overlapTotal = total,
                    leftFraction = index.toFloat() / total,
                    widthFraction = 1f / total
                )
            }
        }
    }

    /**
     * Group positioned events for display, separating visible events from overflow.
     *
     * @param events All positioned events for a time slot
     * @return Pair of (visible events, overflow count)
     */
    fun groupForDisplay(events: List<PositionedEvent>): Pair<List<PositionedEvent>, Int> {
        return if (events.size <= MAX_VISIBLE_OVERLAP) {
            events to 0
        } else {
            events.take(MAX_VISIBLE_OVERLAP) to (events.size - MAX_VISIBLE_OVERLAP)
        }
    }

    // ==================== Time Formatting ====================

    /**
     * Format a time range for display (e.g., "9:00am - 10:30am").
     *
     * @param startTs Start timestamp in milliseconds
     * @param endTs End timestamp in milliseconds
     * @return Formatted time range string
     */
    fun formatTimeRange(startTs: Long, endTs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("h:mma", Locale.getDefault())
        val startTime = Instant.ofEpochMilli(startTs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        val endTime = Instant.ofEpochMilli(endTs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()

        return "${startTime.format(formatter).lowercase()} - ${endTime.format(formatter).lowercase()}"
    }

    /**
     * Check if a date is today.
     */
    fun isToday(date: LocalDate): Boolean = date == LocalDate.now()

    /**
     * Check if a date is a weekend (Saturday or Sunday).
     */
    fun isWeekend(date: LocalDate): Boolean {
        val dayOfWeek = date.dayOfWeek.value
        return dayOfWeek == 6 || dayOfWeek == 7  // Saturday or Sunday
    }

    /**
     * Get the LocalDate for a day index in a week.
     *
     * @param weekStartMs Start of the week in milliseconds
     * @param dayIndex Day index (0=Sunday)
     * @return LocalDate for that day
     */
    fun getDateForDayIndex(weekStartMs: Long, dayIndex: Int): LocalDate {
        val weekStart = Instant.ofEpochMilli(weekStartMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return weekStart.plusDays(dayIndex.toLong())
    }

    /**
     * Format day header (e.g., "Mon 6").
     *
     * @param date The date to format
     * @return Formatted string with day name and day of month
     */
    fun formatDayHeader(date: LocalDate): String {
        val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
        return date.format(dayFormatter)
    }

    /**
     * Format individual date for week header (e.g., "Jan 4").
     * Shows year only if not current year.
     *
     * @param weekStartMs Start of the week in milliseconds
     * @param dayIndex Day index (0=Sunday, 1=Monday, ...)
     * @return Formatted date string (e.g., "Jan 4" or "Jan 4, 2027")
     */
    fun formatIndividualDate(weekStartMs: Long, dayIndex: Int): String {
        val date = getDateForDayIndex(weekStartMs, dayIndex)
        val now = LocalDate.now()
        val showYear = date.year != now.year
        val formatter = if (showYear) {
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        } else {
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        }
        return date.format(formatter)
    }
}
