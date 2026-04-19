package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
 * - Time snapping for event creation
 * - Event positioning and overlap detection
 */
object WeekViewUtils {

    // Time grid constants (full 24-hour grid for all views)
    const val START_HOUR = 0
    const val END_HOUR = 24
    const val TOTAL_HOURS = END_HOUR - START_HOUR  // 24 hours
    const val MINUTES_PER_HOUR = 60
    const val SNAP_INTERVAL_MINUTES = 15

    // Visual constants
    val HOUR_HEIGHT = 60.dp
    val MIN_EVENT_HEIGHT = 20.dp
    const val MIN_HOUR_HEIGHT_DP = 30f
    const val MAX_HOUR_HEIGHT_DP = 150f
    const val MAX_VISIBLE_OVERLAP = 2  // Show max 2 events stacked, rest in "+N more"

    // Infinite day pager constants (THREE_DAYS mode)
    // Using large page count for pseudo-infinite scrolling
    // HorizontalPager is lazy - large pageCount costs nothing
    const val TOTAL_DAY_PAGES = Int.MAX_VALUE
    const val CENTER_DAY_PAGE = TOTAL_DAY_PAGES / 2
    const val VISIBLE_DAYS = 3  // 3-day view

    // Week pager constants (WEEK mode — 1 page = 1 week)
    const val TOTAL_WEEK_PAGES = 1000  // ~500 weeks each direction
    const val CENTER_WEEK_PAGE = TOTAL_WEEK_PAGES / 2

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

    // ==================== Week Pager Functions ====================

    /**
     * Convert a week pager page index to the start date of that week.
     * CENTER_WEEK_PAGE corresponds to the current week.
     *
     * @param weekPage The week pager page index
     * @param firstDayOfWeek User's preferred first day of week (Calendar.SUNDAY, etc.)
     * @param referenceDate Reference date for "today" (default: now; injectable for tests)
     * @return LocalDate for the first day of that week
     */
    fun weekPageToStartDate(
        weekPage: Int,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        referenceDate: LocalDate = LocalDate.now()
    ): LocalDate {
        val weekOffset = (weekPage - CENTER_WEEK_PAGE).toLong()
        val currentWeekStart = getWeekStart(referenceDate, firstDayOfWeek)
        return currentWeekStart.plusWeeks(weekOffset)
    }

    /**
     * Convert a date to a week pager page index.
     * Inverse of [weekPageToStartDate].
     *
     * @param date The date to convert (any day in the target week)
     * @param firstDayOfWeek User's preferred first day of week (Calendar.SUNDAY, etc.)
     * @param referenceDate Reference date for "today" (default: now; injectable for tests)
     * @return Week pager page index
     */
    fun dateToWeekPage(
        date: LocalDate,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        referenceDate: LocalDate = LocalDate.now()
    ): Int {
        val currentWeekStart = getWeekStart(referenceDate, firstDayOfWeek)
        val targetWeekStart = getWeekStart(date, firstDayOfWeek)
        val weekOffset = ChronoUnit.WEEKS.between(currentWeekStart, targetWeekStart)
        return (CENTER_WEEK_PAGE + weekOffset).toInt()
    }

    /**
     * Format a week range for the header (e.g., "Mar 9 - 15, 2026").
     *
     * Rules:
     * - Same month: "Mar 9 - 15, 2026"
     * - Cross month, same year: "Mar 30 - Apr 5, 2026"
     * - Cross year: "Dec 29, 2025 - Jan 4, 2026" (shows both years)
     *
     * @param weekPage The week pager page index
     * @param firstDayOfWeek User's preferred first day of week
     * @param referenceDate Reference date for "today" (injectable for tests)
     * @return Formatted week range string
     */
    fun formatWeekRange(
        weekPage: Int,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        referenceDate: LocalDate = LocalDate.now()
    ): String {
        val startDate = weekPageToStartDate(weekPage, firstDayOfWeek, referenceDate)
        val endDate = startDate.plusDays(6)

        val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

        return when {
            // Same month
            startDate.month == endDate.month && startDate.year == endDate.year -> {
                val month = startDate.format(monthFormatter)
                "$month ${startDate.dayOfMonth} - ${endDate.dayOfMonth}, ${startDate.year}"
            }
            // Cross month, same year
            startDate.year == endDate.year -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth} - $endMonth ${endDate.dayOfMonth}, ${startDate.year}"
            }
            // Cross year — show both years for clarity
            else -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth}, ${startDate.year} - $endMonth ${endDate.dayOfMonth}, ${endDate.year}"
            }
        }
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
            // Cross year — show both years for clarity
            else -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth}, ${startDate.year} - $endMonth ${endDate.dayOfMonth}, ${endDate.year}"
            }
        }
    }

    /**
     * Format a date as "Apr 2026 (W16)" with locale-aware week numbering.
     * Used for week view where all 7 days share the same week number.
     *
     * @param date The date to format
     * @param firstDayOfWeek User's first-day-of-week preference (Calendar.SUNDAY/MONDAY/SATURDAY, or 0 for system default)
     * @return Formatted month/year + week number string
     */
    fun formatMonthYearWithWeek(date: LocalDate, firstDayOfWeek: Int = 0): String {
        val monthYear = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMM"), Locale.getDefault())
            .format(date)
        val weekFields = DateTimeUtils.getLocaleWeekFields(firstDayOfWeek)
        val weekNumber = date.get(weekFields.weekOfWeekBasedYear())
        return "$monthYear (W$weekNumber)"
    }

    /**
     * Format a date as "Apr 2026" without week number.
     * Used for 3-day view where the window can straddle week boundaries.
     */
    fun formatMonthYear(date: LocalDate): String {
        return DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMM"), Locale.getDefault())
            .format(date)
    }

    /**
     * Format the 3-day view header from a pager position.
     * Uses the center date of the 3-day window, without week number.
     */
    fun formatThreeDayHeader(pagerPosition: Int): String {
        val centerDate = pageToDate(pagerPosition + 1)
        return formatMonthYear(centerDate)
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
    fun offsetToTime(yOffset: Float, hourHeightPx: Float, snap: Boolean = true, startHour: Int = START_HOUR): Pair<Int, Int> {
        val totalMinutes = startHour * MINUTES_PER_HOUR + (yOffset / hourHeightPx * MINUTES_PER_HOUR).toInt()
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
        val displayEvent: DisplayEvent,
        val topOffset: Dp,
        val height: Dp,
        val leftFraction: Float,
        val widthFraction: Float,
        val overlapIndex: Int,
        val overlapTotal: Int,
        val startMinutes: Int,
        val endMinutes: Int,
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
     * @param events List of DisplayEvent for the day
     * @param dayIndex Day index (0-6)
     * @param hourHeight Height of one hour
     * @return List of positioned events
     */
    fun positionEventsForDay(
        events: List<DisplayEvent>,
        date: LocalDate,
        dayIndex: Int,
        hourHeight: Dp = HOUR_HEIGHT,
        startHour: Int = START_HOUR,
        endHour: Int = END_HOUR
    ): List<PositionedEvent> {
        if (events.isEmpty()) return emptyList()

        // Step 1: Sort by start time, then by duration (longer events first for better stacking)
        val sorted = events.sortedWith(compareBy(
            { it.startTs },
            { -(it.endTs - it.startTs) }
        ))

        // Step 2: Convert to time spans (clamp cross-midnight events to day boundaries)
        val timeSpans = sorted.map { displayEvent ->
            val start = Instant.ofEpochMilli(displayEvent.startTs).atZone(ZoneId.systemDefault())
            val end = Instant.ofEpochMilli(displayEvent.endTs).atZone(ZoneId.systemDefault())
            val eventStartDate = start.toLocalDate()
            val eventEndDate = end.toLocalDate()

            val startMinutes = if (eventStartDate < date) 0
                else start.hour * MINUTES_PER_HOUR + start.minute

            val endMinutes = if (eventEndDate > date) END_HOUR * MINUTES_PER_HOUR
                else end.hour * MINUTES_PER_HOUR + end.minute

            EventTimeSpan(startMinutes = startMinutes, endMinutes = endMinutes)
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
        return sorted.mapIndexedNotNull { i, displayEvent ->
            val span = timeSpans[i]
            val slotIndex = slotAssignments[i]

            // Find cluster containing this event
            val cluster = clusters.first { i in it }
            val slotsInCluster = cluster.map { slotAssignments[it] }.toSet().size

            // Calculate layout fractions based on slots used in this cluster
            val widthFraction = 1f / slotsInCluster
            val leftFraction = slotIndex * widthFraction

            val gridStartMinutes = startHour * MINUTES_PER_HOUR
            val gridEndMinutes = endHour * MINUTES_PER_HOUR
            val displayStart = span.startMinutes.coerceIn(gridStartMinutes, gridEndMinutes)
            val displayEnd = span.endMinutes.coerceIn(gridStartMinutes, gridEndMinutes)

            if (displayEnd <= displayStart) return@mapIndexedNotNull null

            val topMinutes = displayStart - gridStartMinutes
            val durationMinutes = displayEnd - displayStart

            val topOffset = (topMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp
            val height = maxOf(
                (durationMinutes.toFloat() / MINUTES_PER_HOUR * hourHeight.value).dp,
                MIN_EVENT_HEIGHT
            )

            PositionedEvent(
                displayEvent = displayEvent,
                topOffset = topOffset,
                height = height,
                leftFraction = leftFraction,
                widthFraction = widthFraction,
                overlapIndex = slotIndex,
                overlapTotal = slotsInCluster,
                startMinutes = span.startMinutes,
                endMinutes = span.endMinutes,
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
        val dayFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEd"), Locale.getDefault())
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
            DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd"), Locale.getDefault())
        } else {
            DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("MMMd"), Locale.getDefault())
        }
        return date.format(formatter)
    }

    // ==================== Drag-to-Reschedule ====================

    data class DragState(
        val isDragging: Boolean = false,
        val draggedEvent: DisplayEvent? = null,
        val originalDate: LocalDate = LocalDate.MIN,
        val originalStartMinutes: Int = 0,
        val currentOffsetX: Float = 0f,
        val currentOffsetY: Float = 0f,
        val targetDate: LocalDate? = null,
        val targetStartMinutes: Int = 0,
        val eventHeight: Dp = 0.dp,
        val durationMinutes: Int = 0
    ) {
        companion object {
            val Idle = DragState()
        }
    }

    fun minutesToTimeLabel(totalMinutes: Int, is24Hour: Boolean): String {
        val h = totalMinutes / MINUTES_PER_HOUR
        val m = totalMinutes % MINUTES_PER_HOUR
        return if (is24Hour) {
            "$h:${String.format(Locale.getDefault(), "%02d", m)}"
        } else {
            val period = if (h < 12) "AM" else "PM"
            val displayHour = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            "$displayHour:${String.format(Locale.getDefault(), "%02d", m)} $period"
        }
    }

    fun calculateDragTarget(
        fingerX: Float,
        fingerY: Float,
        columnWidth: Float,
        visibleDates: List<LocalDate>,
        hourHeightPx: Float,
        scrollOffsetPx: Int,
        startHour: Int = START_HOUR
    ): Pair<LocalDate, Int> {
        val columnIndex = (fingerX / columnWidth).toInt().coerceIn(0, visibleDates.size - 1)
        val date = visibleDates[columnIndex]

        val absoluteY = fingerY + scrollOffsetPx
        val totalMinutesRaw = startHour * MINUTES_PER_HOUR + (absoluteY / hourHeightPx * MINUTES_PER_HOUR).toInt()
        val snappedMinutes = snapToQuarterHour(totalMinutesRaw.coerceAtLeast(0))
            .coerceIn(0, END_HOUR * MINUTES_PER_HOUR - 1)

        return date to snappedMinutes
    }

    fun clampDragStartMinutes(startMinutes: Int, durationMinutes: Int): Int {
        val maxStart = END_HOUR * MINUTES_PER_HOUR - 1 - durationMinutes
        return startMinutes.coerceIn(0, maxStart.coerceAtLeast(0))
    }

    fun calculateNewTimestamps(
        targetDate: LocalDate,
        targetStartMinutes: Int,
        durationMinutes: Int
    ): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val startTime = LocalTime.of(
            targetStartMinutes / MINUTES_PER_HOUR,
            targetStartMinutes % MINUTES_PER_HOUR
        )
        val startTs = targetDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val endTs = startTs + durationMinutes.toLong() * 60 * 1000
        return startTs to endTs
    }
}
