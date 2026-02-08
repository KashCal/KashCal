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
import java.util.Calendar
import java.util.Locale
import org.onekash.kashcal.util.DateTimeUtils

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

    // Infinite day pager constants
    // Using large page count for pseudo-infinite scrolling
    // HorizontalPager is lazy - large pageCount costs nothing
    const val TOTAL_DAY_PAGES = Int.MAX_VALUE
    const val CENTER_DAY_PAGE = TOTAL_DAY_PAGES / 2
    const val VISIBLE_DAYS = 3  // 3-day view

    // ==================== Day Pager Functions ====================

    /**
     * Convert a pager page index to an absolute date.
     * CENTER_DAY_PAGE corresponds to today.
     *
     * @param page The pager page index
     * @return LocalDate for that page
     */
    fun pageToDate(page: Int): LocalDate {
        val today = LocalDate.now()
        val dayOffset = page.toLong() - CENTER_DAY_PAGE.toLong()
        return today.plusDays(dayOffset)
    }

    /**
     * Convert an absolute date to a pager page index.
     * Today corresponds to CENTER_DAY_PAGE.
     *
     * @param date The date to convert
     * @return Page index for that date
     */
    fun dateToPage(date: LocalDate): Int {
        val today = LocalDate.now()
        val dayOffset = ChronoUnit.DAYS.between(today, date)
        return (CENTER_DAY_PAGE.toLong() + dayOffset).toInt()
    }

    /**
     * Get the date range for currently visible days.
     *
     * @param currentPage The current (leftmost) visible page
     * @param visibleDays Number of visible days (default: 3)
     * @return Pair of (startDate, endDate) inclusive
     */
    fun getVisibleDateRange(currentPage: Int, visibleDays: Int = VISIBLE_DAYS): Pair<LocalDate, LocalDate> {
        val startDate = pageToDate(currentPage)
        val endDate = startDate.plusDays((visibleDays - 1).toLong())
        return startDate to endDate
    }

    /**
     * Get the date range for event loading (visible + buffer).
     * Loads extra days to ensure smooth scrolling.
     *
     * @param currentPage The current (leftmost) visible page
     * @param visibleDays Number of visible days
     * @param bufferDays Days to load before and after visible range
     * @return Pair of (startDate, endDate) inclusive
     */
    fun getLoadingDateRange(
        currentPage: Int,
        visibleDays: Int = VISIBLE_DAYS,
        bufferDays: Int = 7
    ): Pair<LocalDate, LocalDate> {
        val startDate = pageToDate(currentPage).minusDays(bufferDays.toLong())
        val endDate = pageToDate(currentPage).plusDays((visibleDays - 1 + bufferDays).toLong())
        return startDate to endDate
    }

    /**
     * Convert LocalDate to epoch milliseconds at start of day in system timezone.
     */
    fun dateToEpochMs(date: LocalDate): Long {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * Convert epoch milliseconds to LocalDate in system timezone.
     */
    fun epochMsToDate(epochMs: Long): LocalDate {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /**
     * Time slot classification for events.
     * Events are categorized based on their relationship to the visible grid (6am-11pm).
     */
    enum class TimeSlot {
        /** Event occurs entirely before 6am */
        EARLY,
        /** Event overlaps with the visible grid (6am-11pm) */
        NORMAL,
        /** Event occurs entirely after 11pm */
        LATE
    }

    /**
     * Classify an event based on its time relative to the visible grid.
     *
     * - EARLY: Event ends at or before 6am
     * - LATE: Event starts at or after 11pm
     * - NORMAL: Event overlaps with 6am-11pm range
     *
     * @param occurrence The occurrence to classify
     * @return TimeSlot indicating where the event belongs
     */
    fun classifyTimeSlot(occurrence: Occurrence): TimeSlot {
        val startTime = Instant.ofEpochMilli(occurrence.startTs)
            .atZone(ZoneId.systemDefault())
        val endTime = Instant.ofEpochMilli(occurrence.endTs)
            .atZone(ZoneId.systemDefault())

        val startMinutes = startTime.hour * MINUTES_PER_HOUR + startTime.minute
        val endMinutes = endTime.hour * MINUTES_PER_HOUR + endTime.minute

        val gridStartMinutes = START_HOUR * MINUTES_PER_HOUR  // 360 (6am)
        val gridEndMinutes = END_HOUR * MINUTES_PER_HOUR      // 1380 (11pm)

        return when {
            // Event ends at or before 6am - it's an early event
            endMinutes <= gridStartMinutes -> TimeSlot.EARLY
            // Event starts at or after 11pm - it's a late event
            startMinutes >= gridEndMinutes -> TimeSlot.LATE
            // Event overlaps with the grid
            else -> TimeSlot.NORMAL
        }
    }

    /**
     * Format time for overflow row display (e.g., "5:30am" or "05:30").
     *
     * @param timestampMs Event start timestamp
     * @param timePattern DateTimeFormatter pattern (e.g., "h:mma" for 12h, "HH:mm" for 24h)
     */
    fun formatOverflowTime(timestampMs: Long, timePattern: String = "h:mma"): String {
        val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(formatter)
            .lowercase(Locale.getDefault())
    }

    // ==================== Week Calculations ====================

    /**
     * Get the start of the week for a given date.
     *
     * @param date The date to find the week start for
     * @param firstDayOfWeek User's preferred first day of week (Calendar.SUNDAY, etc.)
     * @return LocalDate representing the first day of that week
     */
    fun getWeekStart(date: LocalDate, firstDayOfWeek: Int = Calendar.SUNDAY): LocalDate {
        val daysToSubtract = DateTimeUtils.getDayOfWeekOffset(date, firstDayOfWeek)
        return date.minusDays(daysToSubtract.toLong())
    }

    /**
     * Get the start of the week for a given timestamp.
     *
     * @param timestampMs Timestamp in milliseconds
     * @param zoneId Timezone to use (default: system default)
     * @param firstDayOfWeek User's preferred first day of week (Calendar.SUNDAY, etc.)
     * @return Timestamp of the first day of the week at midnight in the given timezone
     */
    fun getWeekStartMs(
        timestampMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        firstDayOfWeek: Int = Calendar.SUNDAY
    ): Long {
        val date = Instant.ofEpochMilli(timestampMs)
            .atZone(zoneId)
            .toLocalDate()
        val weekStart = getWeekStart(date, firstDayOfWeek)
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
     * Format the month and year for the 3-day view header (e.g., "February 2026").
     * Always includes year for clarity.
     *
     * Uses pageToDate() to convert the absolute infinite pager page to a date,
     * which correctly handles the CENTER_DAY_PAGE offset.
     *
     * @param pagerPosition Current pager page (absolute index in infinite pager)
     * @return Formatted month/year string
     */
    fun formatMonthYear(pagerPosition: Int): String {
        val centerDate = pageToDate(pagerPosition + 1) // center of 3-day window
        val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        return centerDate.format(formatter)
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
     * Format hour for time grid labels (e.g., "6a", "12p" or "06", "12").
     *
     * @param hour Hour (0-23)
     * @param is24Hour True for 24-hour format (e.g., "06"), false for 12-hour (e.g., "6a")
     * @return Formatted hour string
     */
    fun formatHourLabel(hour: Int, is24Hour: Boolean = false): String {
        return if (is24Hour) {
            String.format(Locale.getDefault(), "%02d", hour)
        } else {
            when {
                hour == 0 -> "12a"
                hour < 12 -> "${hour}a"
                hour == 12 -> "12p"
                else -> "${hour - 12}p"
            }
        }
    }

    /**
     * Format time for clamped indicator (e.g., "5:30am" or "05:30").
     *
     * @param minutes Time in minutes from midnight
     * @param is24Hour True for 24-hour format (e.g., "05:30"), false for 12-hour (e.g., "5:30am")
     * @return Formatted time string
     */
    fun formatClampIndicatorTime(minutes: Int, is24Hour: Boolean = false): String {
        val hour = minutes / MINUTES_PER_HOUR
        val minute = minutes % MINUTES_PER_HOUR

        return if (is24Hour) {
            String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        } else {
            val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            val amPm = if (hour < 12) "am" else "pm"
            if (minute == 0) {
                "$hour12$amPm"
            } else {
                "$hour12:${minute.toString().padStart(2, '0')}$amPm"
            }
        }
    }

    // ==================== Event Positioning ====================

    /**
     * Time span for an event in minutes from midnight.
     * Used for overlap detection during layout.
     */
    private data class EventTimeSpan(
        val startMinutes: Int,
        val endMinutes: Int
    ) {
        /**
         * Check if this time span overlaps with another.
         * Events that touch at exact boundaries (one ends at 10:00, other starts at 10:00)
         * are NOT considered overlapping - they can stack vertically in the same slot.
         */
        fun overlapsWith(other: EventTimeSpan): Boolean {
            return startMinutes < other.endMinutes && endMinutes > other.startMinutes
        }
    }

    /**
     * A vertical layout slot that holds non-overlapping events.
     * Events in the same slot are stacked vertically without horizontal overlap.
     */
    private class LayoutSlot(val slotIndex: Int) {
        private val spans = mutableListOf<EventTimeSpan>()

        /**
         * Check if an event can fit in this slot (no overlap with existing events).
         */
        fun canAccommodate(span: EventTimeSpan): Boolean {
            return spans.none { it.overlapsWith(span) }
        }

        /**
         * Place an event in this slot.
         */
        fun place(span: EventTimeSpan) {
            spans.add(span)
        }
    }

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
     * Uses a Layout Slot algorithm for consistent overlap handling:
     * 1. Sort events by start time, then duration (longer first)
     * 2. Place each event in the leftmost available slot
     * 3. Group transitively overlapping events into clusters
     * 4. Calculate width based on slots used in each cluster
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

        // Step 1: Sort by start time, then by duration (longer events first for better stacking)
        val sorted = events.sortedWith(compareBy(
            { it.second.startTs },
            { -(it.second.endTs - it.second.startTs) }
        ))

        // Step 2: Convert to time spans
        val timeSpans = sorted.map { (_, occ) ->
            val start = Instant.ofEpochMilli(occ.startTs).atZone(ZoneId.systemDefault())
            val end = Instant.ofEpochMilli(occ.endTs).atZone(ZoneId.systemDefault())
            EventTimeSpan(
                startMinutes = start.hour * MINUTES_PER_HOUR + start.minute,
                endMinutes = end.hour * MINUTES_PER_HOUR + end.minute
            )
        }

        // Step 3: Assign each event to a layout slot
        val slots = mutableListOf<LayoutSlot>()
        val slotAssignments = IntArray(sorted.size)

        for (i in sorted.indices) {
            val span = timeSpans[i]
            val availableSlot = slots.firstOrNull { it.canAccommodate(span) }

            if (availableSlot != null) {
                availableSlot.place(span)
                slotAssignments[i] = availableSlot.slotIndex
            } else {
                val newSlot = LayoutSlot(slots.size)
                newSlot.place(span)
                slots.add(newSlot)
                slotAssignments[i] = newSlot.slotIndex
            }
        }

        // Step 4: Find connected event clusters (events that transitively overlap)
        val clusters = findConnectedClusters(timeSpans)

        // Step 5: Build positioned events with correct layout fractions
        return sorted.mapIndexedNotNull { i, (event, occurrence) ->
            val span = timeSpans[i]
            val slotIndex = slotAssignments[i]

            // Find cluster containing this event
            val cluster = clusters.first { i in it }
            val slotsInCluster = cluster.map { slotAssignments[it] }.toSet().size

            // Calculate layout fractions based on slots used in this cluster
            val widthFraction = 1f / slotsInCluster
            val leftFraction = slotIndex * widthFraction

            // Clamp to visible range
            val clampResult = clampToVisibleRange(span.startMinutes, span.endMinutes)

            // Skip if entirely outside visible range
            if (clampResult.displayEndMinutes <= clampResult.displayStartMinutes) return@mapIndexedNotNull null

            // Calculate visual position
            val topMinutes = clampResult.displayStartMinutes - START_HOUR * MINUTES_PER_HOUR
            val durationMinutes = clampResult.displayEndMinutes - clampResult.displayStartMinutes

            val topOffset = (topMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp
            val height = maxOf(
                (durationMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp,
                MIN_EVENT_HEIGHT
            )

            PositionedEvent(
                event = event,
                occurrence = occurrence,
                topOffset = topOffset,
                height = height,
                leftFraction = leftFraction,
                widthFraction = widthFraction,
                overlapIndex = slotIndex,
                overlapTotal = slotsInCluster,
                clampedStart = clampResult.clampedStart,
                clampedEnd = clampResult.clampedEnd,
                originalStartMinutes = span.startMinutes,
                originalEndMinutes = span.endMinutes,
                dayIndex = dayIndex
            )
        }
    }

    /**
     * Find clusters of events that transitively overlap.
     * Events A and C are in the same cluster if there's an event B
     * that overlaps both, even if A and C don't directly overlap.
     *
     * Example: A(9-10), B(9:30-11), C(10-11)
     * - A overlaps B, B overlaps C → All in one cluster {A, B, C}
     *
     * @param spans List of event time spans
     * @return List of clusters, where each cluster is a set of event indices
     */
    private fun findConnectedClusters(spans: List<EventTimeSpan>): List<Set<Int>> {
        val clusters = mutableListOf<MutableSet<Int>>()

        for (i in spans.indices) {
            // Find all existing clusters this event overlaps with
            val overlappingClusters = clusters.filter { cluster ->
                cluster.any { j -> spans[i].overlapsWith(spans[j]) }
            }

            when (overlappingClusters.size) {
                0 -> {
                    // No overlap - create new cluster
                    clusters.add(mutableSetOf(i))
                }
                1 -> {
                    // Overlaps one cluster - add to it
                    overlappingClusters[0].add(i)
                }
                else -> {
                    // Overlaps multiple clusters - merge them all
                    val merged = mutableSetOf(i)
                    for (cluster in overlappingClusters) {
                        merged.addAll(cluster)
                    }
                    clusters.removeAll(overlappingClusters.toSet())
                    clusters.add(merged)
                }
            }
        }

        return clusters
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
     * Format a time range for display (e.g., "9:00am - 10:30am" or "09:00 - 10:30").
     *
     * @param startTs Start timestamp in milliseconds
     * @param endTs End timestamp in milliseconds
     * @param timePattern DateTimeFormatter pattern (e.g., "h:mma" for 12h, "HH:mm" for 24h)
     * @return Formatted time range string
     */
    fun formatTimeRange(startTs: Long, endTs: Long, timePattern: String = "h:mma"): String {
        val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
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
