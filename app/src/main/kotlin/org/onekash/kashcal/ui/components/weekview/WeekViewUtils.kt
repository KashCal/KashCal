package org.onekash.kashcal.ui.components.weekview

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.MAX_HOUR_HEIGHT_DP
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.MIN_HOUR_HEIGHT_DP
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.START_HOUR
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.positionEventsForDay
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.resolveInitialScrollPx
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils.weekPageToStartDate
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale

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

    /**
     * Hour the week/3-day grid scrolls to on first composition when no in-session
     * scroll position has been saved. Lands the user near typical wake/work hours
     * instead of midnight. See [resolveInitialScrollPx] and issue #188.
     */
    const val DEFAULT_SCROLL_START_HOUR = 6

    // Visual constants
    val HOUR_HEIGHT = 60.dp
    val MIN_EVENT_HEIGHT = 20.dp
    const val MIN_HOUR_HEIGHT_DP = 30f
    const val MAX_HOUR_HEIGHT_DP = 150f
    const val MAX_VISIBLE_OVERLAP = 2  // Show max 2 events stacked, rest in "+N more"

    // All-day strip: rows shown per day when collapsed (today's default) vs expanded.
    const val MAX_ALLDAY_ROWS_COLLAPSED = 1
    const val MAX_ALLDAY_ROWS_EXPANDED = 3

    // Infinite day pager constants (THREE_DAYS mode)
    // Using large page count for pseudo-infinite scrolling
    // HorizontalPager is lazy - large pageCount costs nothing
    const val TOTAL_DAY_PAGES = Int.MAX_VALUE
    const val CENTER_DAY_PAGE = TOTAL_DAY_PAGES / 2

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
     * Whether [pagerPosition] is a settled day-scale page (safe to feed to
     * [pageToDate]).
     *
     * The DAY/3-DAY and WEEK pagers share one stored position but use different
     * scales: day pages sit near [CENTER_DAY_PAGE] (~1.07e9) while week pages are
     * in 0..[TOTAL_WEEK_PAGES]. The default (0) and a stale week page left over
     * from a WEEK->DAY switch are therefore both far below any real day page, and
     * interpreting them as day pages yields absurd dates millions of years off.
     * Anything above [TOTAL_WEEK_PAGES] can only be a day-scale page: reaching it
     * as a week page is impossible, and reaching it as a day page ~2.9M years
     * before today isn't either.
     */
    fun isSettledDayPage(pagerPosition: Int): Boolean = pagerPosition > TOTAL_WEEK_PAGES

    /**
     * Get the date range for currently visible days.
     *
     * @param currentPage The current (leftmost) visible page
     * @param visibleDays Number of visible days (default: 3)
     * @return Pair of (startDate, endDate) inclusive
     */
    fun getVisibleDateRange(currentPage: Int, visibleDays: Int = 3): Pair<LocalDate, LocalDate> {
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
        visibleDays: Int = 3,
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
     * Format a date as "Apr 2026" with abbreviated month name. Used by the
     * top-bar title in month/agenda/week/3-day views; abbreviation keeps the
     * label short enough to fit alongside the logo and W## suffix.
     */
    fun formatMonthYear(date: LocalDate): String {
        return DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMM"), Locale.getDefault())
            .format(date)
    }

    /**
     * Format a week label as "${prefix}N" where N is the locale-aware week-of-year
     * for [date]. The [prefix] is passed in by the caller so the host can supply a
     * localized stringResource — keeps the formatter Composable-free. No space
     * between prefix and number so the en-US output reads "W21" rather than "W 21";
     * locales whose translated prefix needs trailing whitespace must include it in
     * the resource value.
     */
    fun formatWeekLabel(date: LocalDate, firstDayOfWeek: Int = 0, prefix: String): String {
        val weekFields = DateTimeUtils.getLocaleWeekFields(firstDayOfWeek)
        val weekNumber = date.get(weekFields.weekOfWeekBasedYear())
        return "$prefix$weekNumber"
    }

    // ==================== Scroll Defaults ====================

    /**
     * Resolve the initial scroll position (in pixels) for the week/3-day time grid.
     *
     * First-composition-only default: Compose's [androidx.compose.foundation.rememberScrollState]
     * uses `rememberSaveable` internally with no keys, so `initial` is read once per
     * composition lifetime. This function provides a useful starting pixel on cold launch
     * without overriding any in-session scroll the user has already made.
     *
     * @param savedPosition Cached pixel offset from the ViewModel. Values > 0 indicate the
     *   user has scrolled this session and we honor their position verbatim. Non-positive
     *   values (0 or unexpected negatives) are treated as "not yet scrolled" and fall into
     *   the default-hour branch. The gate is `> 0` rather than `>= 0` because a positive
     *   value is the only unambiguous signal of a user-initiated scroll: after the debounced
     *   onScrollPositionChange settles, any real scroll has already moved past pixel 0.
     * @param hourHeightDp Current hour-row height in dp (pinch-zoomable at runtime, clamped
     *   to [MIN_HOUR_HEIGHT_DP]..[MAX_HOUR_HEIGHT_DP] by the caller).
     * @param density Display density factor from `LocalDensity.current.density`.
     * @param savedMinutes Persisted clock time (minutes from midnight, 0..1439) restored across
     *   app restarts. `< 0` means "never saved" (fresh install) and falls through to [defaultHour].
     *   Stored as clock minutes rather than pixels so a zoom change between sessions still lands on
     *   the same time. Only consulted when there is no in-session [savedPosition].
     * @param defaultHour Target hour (0..23) to scroll to when no saved position exists.
     */
    fun resolveInitialScrollPx(
        savedPosition: Int,
        hourHeightDp: Float,
        density: Float,
        savedMinutes: Int = -1,
        defaultHour: Int = DEFAULT_SCROLL_START_HOUR
    ): Int {
        return when {
            // In-session scroll wins: the user has already moved the grid this session.
            savedPosition > 0 -> savedPosition
            // Cold-launch restore from a persisted clock time.
            savedMinutes >= 0 -> minutesOfDayToPixels(savedMinutes, hourHeightDp * density)
            // Fresh install / never scrolled: land on the default hour.
            else -> (defaultHour * hourHeightDp * density).toInt()
        }
    }

    /**
     * Convert a vertical scroll offset (pixels) to a clock time in minutes from midnight,
     * clamped to a valid time-of-day (0..1439). Used to persist the scroll position as
     * zoom-independent clock time. A non-positive [hourHeightPx] returns 0 rather than
     * dividing by zero.
     */
    fun pixelsToMinutesOfDay(pixels: Float, hourHeightPx: Float): Int {
        if (hourHeightPx <= 0f) return 0
        val minutes = (pixels / hourHeightPx * MINUTES_PER_HOUR).toInt()
        return minutes.coerceIn(0, 24 * MINUTES_PER_HOUR - 1)
    }

    /**
     * Convert a clock time (minutes from midnight) to a vertical scroll offset in pixels
     * at the given hour-row height. Inverse of [pixelsToMinutesOfDay] at a fixed zoom.
     */
    fun minutesOfDayToPixels(minutesOfDay: Int, hourHeightPx: Float): Int {
        return (minutesOfDay.toFloat() / MINUTES_PER_HOUR * hourHeightPx).toInt()
    }

    /**
     * Compute the vertical scroll offset (px) that keeps the clock time at the viewport's
     * vertical center fixed while the hour-row height changes during a pinch-zoom.
     *
     * The center time is derived from the pre-zoom geometry, re-projected onto the new
     * hour-row height, then clamped to the scrollable range. Clamping must use the
     * POST-zoom content height (`newHourHeightPx * totalHours`): when zooming in the grid
     * grows taller, so a target that is valid once the grid re-measures gets wrongly
     * clamped if measured against the smaller pre-zoom range — parking the current-time
     * line and every event away from center.
     *
     * @param currentScrollPx Scroll offset before the zoom step.
     * @param viewportHeightPx Height of the visible grid viewport in px (> 0).
     * @param oldHourHeightPx Hour-row height in px before the zoom step (> 0).
     * @param newHourHeightPx Hour-row height in px after the zoom step (> 0).
     * @param totalHours Number of hours rendered by the grid ([TOTAL_HOURS]).
     * @param panYPx Vertical pan (px) applied by the same two-finger gesture, folded in
     *   before the clamp so the returned offset always stays within the scrollable range —
     *   an upward pan must not push the target past the post-zoom max, or the caller's
     *   "wait for the grid to reach this offset" step could never complete.
     */
    fun resolveZoomScrollPx(
        currentScrollPx: Float,
        viewportHeightPx: Float,
        oldHourHeightPx: Float,
        newHourHeightPx: Float,
        totalHours: Int = TOTAL_HOURS,
        panYPx: Float = 0f
    ): Float {
        if (oldHourHeightPx <= 0f) return currentScrollPx
        val viewportCenterTime = (currentScrollPx + viewportHeightPx / 2f) / oldHourHeightPx
        val target = viewportCenterTime * newHourHeightPx - viewportHeightPx / 2f - panYPx
        val contentHeightPx = newHourHeightPx * totalHours
        val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
        return target.coerceIn(0f, maxScroll)
    }

    /**
     * Resolve the hour currently at the top of the visible grid, for callers that
     * need to seed a new event's start time (e.g. the "+" FAB).
     *
     * Sibling of [resolveInitialScrollPx]: uses the same `savedPosition > 0` sentinel
     * so that on cold launch — before the user has scrolled and before the debounced
     * onScrollPositionChange has written back — the FAB picks the same default hour
     * that the grid is visually landing on.
     *
     * @param savedPosition Cached pixel offset from the ViewModel. Values > 0 mean
     *   the user has scrolled; non-positive values fall into the default-hour branch.
     * @param hourHeightPx Current hour-row height in pixels (dp * density).
     * @param gridStartHour First hour rendered by the grid (typically [START_HOUR] 0).
     * @param defaultHour Hour to use when no saved position exists.
     */
    fun resolveVisibleStartHour(
        savedPosition: Int,
        hourHeightPx: Float,
        gridStartHour: Int = START_HOUR,
        defaultHour: Int = DEFAULT_SCROLL_START_HOUR
    ): Int {
        return if (savedPosition > 0) {
            ((savedPosition / hourHeightPx).toInt() + gridStartHour).coerceIn(gridStartHour, 23)
        } else {
            defaultHour
        }
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
        endHour: Int = END_HOUR,
        maxVisibleOverlap: Int = MAX_VISIBLE_OVERLAP,
    ): List<PositionedEvent> {
        if (events.isEmpty()) return emptyList()

        // Step 1: Sort by start time, then by duration (longer events first for better stacking)
        val sorted = events.sortedWith(compareBy(
            { it.startTs },
            { -(it.endTs - it.startTs) }
        ))

        // Events shorter than MIN_EVENT_HEIGHT render floored to that height, but
        // their true (sub-floor) layout window is thinner than the block drawn on
        // screen — so overlap detection would pack them into one slot and draw them
        // on top of each other. Give every such event a layout window at least as
        // tall as the rendered block, so packing matches what the user sees. This
        // also rescues zero-duration (point-in-time) and sub-minute events, whose
        // raw span would otherwise collapse to nothing and be dropped by the
        // grid-clamp guard below. Off-grid events still clamp to a point and drop.
        //
        // Cap the window at its default-zoom size: zooming out makes a floored block
        // cover more real minutes, but inflating the overlap window to match would
        // force back-to-back meetings (e.g. two 30-min events at min zoom) into
        // half-width columns — a worse read than a few px of block overlap, which
        // the user resolves by zooming in or switching to Agenda/Day view. Zooming
        // in still shrinks the window (more accurate packing) since it stays below
        // the cap.
        val defaultMinHeightMinutes = (MIN_EVENT_HEIGHT.value / HOUR_HEIGHT.value * MINUTES_PER_HOUR).toInt()
        val minHeightMinutes = (MIN_EVENT_HEIGHT.value / hourHeight.value * MINUTES_PER_HOUR)
            .toInt().coerceIn(1, defaultMinHeightMinutes)

        // Step 2: Convert to time spans (clamp cross-midnight events to day boundaries)
        val timeSpans = sorted.map { displayEvent ->
            val start = Instant.ofEpochMilli(displayEvent.startTs).atZone(ZoneId.systemDefault())
            val end = Instant.ofEpochMilli(displayEvent.endTs).atZone(ZoneId.systemDefault())
            val eventStartDate = start.toLocalDate()
            val eventEndDate = end.toLocalDate()

            val startMinutes = if (eventStartDate < date) 0
                else start.hour * MINUTES_PER_HOUR + start.minute

            val rawEndMinutes = if (eventEndDate > date) END_HOUR * MINUTES_PER_HOUR
                else end.hour * MINUTES_PER_HOUR + end.minute

            // Bump the layout window up to the rendered height for events shorter
            // than the floor. But a multi-day event whose end-day portion is a
            // zero-length sliver at midnight (it began on a prior day and ends at
            // exactly 00:00 today) must still collapse and drop off that day — so
            // exclude that case. Same-day short/zero/sub-minute events are NOT
            // slivers and do get bumped.
            val isMidnightSliver = eventStartDate < date && rawEndMinutes == startMinutes
            val endMinutes = if (!isMidnightSliver && rawEndMinutes - startMinutes < minHeightMinutes) {
                startMinutes + minHeightMinutes
            } else {
                rawEndMinutes
            }

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

            // Visible events split the column evenly. Slots beyond the cap collapse
            // into the overflow badge in groupForDisplay; reserving width for them
            // would leave empty space on the right (issue #256).
            val effectiveSlots = minOf(slotsInCluster, maxVisibleOverlap)
            val widthFraction = 1f / effectiveSlots
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
     * Filters by slot assignment (`overlapIndex`) rather than list order. This
     * preserves the layout computed by [positionEventsForDay] — events packed
     * into slots within the cap all render, even when a long event transitively
     * connects them into one cluster (see issue #175).
     *
     * @param events All positioned events for a time slot
     * @return Pair of (visible events, overflow count)
     */
    fun groupForDisplay(
        events: List<PositionedEvent>,
        maxVisibleOverlap: Int = MAX_VISIBLE_OVERLAP
    ): Pair<List<PositionedEvent>, Int> {
        val (visible, overflow) = events.partition { it.overlapIndex < maxVisibleOverlap }
        return visible to overflow.size
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

    // ==================== All-Day Strip Expand/Collapse ====================

    /**
     * How many all-day rows to render for a day with [count] events.
     *
     * Collapsed keeps today's behavior (at most one row); expanded fills up to
     * [MAX_ALLDAY_ROWS_EXPANDED] adaptively, so a day with two events shows two,
     * a day with one shows one, and a day with three or more shows three.
     */
    fun allDayVisibleRows(count: Int, expanded: Boolean): Int {
        val cap = if (expanded) MAX_ALLDAY_ROWS_EXPANDED else MAX_ALLDAY_ROWS_COLLAPSED
        return count.coerceAtMost(cap)
    }

    /**
     * Events beyond the visible rows, surfaced as the "+N more" badge that opens
     * the overflow sheet. Zero when everything fits.
     */
    fun allDayOverflowCount(count: Int, expanded: Boolean): Int =
        (count - allDayVisibleRows(count, expanded)).coerceAtLeast(0)

    /**
     * Whether the expand/collapse chevron is meaningful for the current window:
     * true only when at least one visible day has more all-day events than the
     * collapsed cap can show. Otherwise there is nothing to expand.
     */
    fun anyAllDayColumnHasOverflowWhenCollapsed(perDayCounts: List<Int>): Boolean =
        perDayCounts.any { it > MAX_ALLDAY_ROWS_COLLAPSED }
}
