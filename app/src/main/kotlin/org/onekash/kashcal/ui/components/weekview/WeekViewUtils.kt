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
 * Holds the day, 3-day and week views' date math, header formatting, scroll and zoom offsets,
 * time snapping, event layout with overlap packing, and drag-to-reschedule targets.
 */
object WeekViewUtils {

    // Every view's timed grid spans the full 24 hours.
    const val START_HOUR = 0
    const val END_HOUR = 24
    const val TOTAL_HOURS = END_HOUR - START_HOUR
    const val MINUTES_PER_HOUR = 60
    const val SNAP_INTERVAL_MINUTES = 15

    /**
     * Hour the timed grid scrolls to on first composition when neither an in-session position
     * nor a persisted scroll time exists, so it opens near waking hours instead of midnight.
     * See [resolveInitialScrollPx] and #188.
     */
    const val DEFAULT_SCROLL_START_HOUR = 6

    // Visual constants
    val HOUR_HEIGHT = 60.dp
    val MIN_EVENT_HEIGHT = 20.dp

    /**
     * Width of the timed grid's hour-label column. The day headers and the Day view's pinned
     * week strip start after this width so they line up with the day columns.
     */
    val TIME_COLUMN_WIDTH = 48.dp
    const val MIN_HOUR_HEIGHT_DP = 30f
    const val MAX_HOUR_HEIGHT_DP = 150f
    const val MAX_VISIBLE_OVERLAP = 2  // Slots shown; the rest go in the "+N more" badge

    // All-day strip rows shown per day, collapsed (the default) and expanded.
    const val MAX_ALLDAY_ROWS_COLLAPSED = 1
    const val MAX_ALLDAY_ROWS_EXPANDED = 3

    // Day pager (DAY and THREE_DAYS). HorizontalPager is lazy, so an Int.MAX_VALUE page count
    // costs nothing and scrolls pseudo-infinitely.
    const val TOTAL_DAY_PAGES = Int.MAX_VALUE
    const val CENTER_DAY_PAGE = TOTAL_DAY_PAGES / 2

    // Week pager (WEEK): one page per week, about 500 weeks each direction.
    const val TOTAL_WEEK_PAGES = 1000
    const val CENTER_WEEK_PAGE = TOTAL_WEEK_PAGES / 2

    // ==================== Day Pager Functions ====================

    /** Returns the date of day-pager [page]; [CENTER_DAY_PAGE] is today. */
    fun pageToDate(page: Int): LocalDate {
        val today = LocalDate.now()
        val dayOffset = page.toLong() - CENTER_DAY_PAGE.toLong()
        return today.plusDays(dayOffset)
    }

    /** Returns the day-pager page of [date]; the inverse of [pageToDate]. */
    fun dateToPage(date: LocalDate): Int {
        val today = LocalDate.now()
        val dayOffset = ChronoUnit.DAYS.between(today, date)
        return (CENTER_DAY_PAGE.toLong() + dayOffset).toInt()
    }

    /**
     * Returns whether [pagerPosition] is a settled day-scale page, safe to pass to [pageToDate].
     *
     * The day and week pagers share one stored position on different scales: day pages sit
     * near [CENTER_DAY_PAGE] (about 1.07e9), week pages in 0..[TOTAL_WEEK_PAGES]. The default 0
     * and a week page left over from a WEEK to DAY switch would read as dates millions of years
     * off. A week page can't exceed [TOTAL_WEEK_PAGES], and a day page that low would be about
     * 2.9M years before today.
     */
    fun isSettledDayPage(pagerPosition: Int): Boolean = pagerPosition > TOTAL_WEEK_PAGES

    /** Returns the inclusive first and last date of [visibleDays] days from page [currentPage]. */
    fun getVisibleDateRange(currentPage: Int, visibleDays: Int = 3): Pair<LocalDate, LocalDate> {
        val startDate = pageToDate(currentPage)
        val endDate = startDate.plusDays((visibleDays - 1).toLong())
        return startDate to endDate
    }

    /**
     * Returns the inclusive date range to load events for: the [visibleDays] days from day-pager
     * [currentPage] plus [bufferDays] on each side, so a swipe lands on loaded days.
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
     * Returns the first day of week-pager [weekPage]; [CENTER_WEEK_PAGE] is the week holding
     * [referenceDate]. [firstDayOfWeek] is read as in [getWeekStart].
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
     * Returns the week-pager page of the week holding [date]; the inverse of
     * [weekPageToStartDate].
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
     * Formats the 7 days of week-pager [weekPage] as a range:
     * - Same month: "Mar 9 - 15, 2026"
     * - Cross month, same year: "Mar 30 - Apr 5, 2026"
     * - Cross year, both years shown: "Dec 29, 2025 - Jan 4, 2026"
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
            // Cross year: both years
            else -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth}, ${startDate.year} - $endMonth ${endDate.dayOfMonth}, ${endDate.year}"
            }
        }
    }

    /** Returns the start of [date] in the system zone, in epoch ms. */
    fun dateToEpochMs(date: LocalDate): Long {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Returns the system-zone date of [epochMs]. */
    fun epochMsToDate(epochMs: Long): LocalDate {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    // ==================== Week Calculations ====================

    /**
     * Returns the first day of the week holding [date].
     *
     * @param firstDayOfWeek `Calendar.SUNDAY`, `MONDAY` or `SATURDAY`, or 0 for the locale default;
     *   any other value counts as SUNDAY ([DateTimeUtils.getDayOfWeekOffset])
     */
    fun getWeekStart(date: LocalDate, firstDayOfWeek: Int = Calendar.SUNDAY): LocalDate {
        val daysToSubtract = DateTimeUtils.getDayOfWeekOffset(date, firstDayOfWeek)
        return date.minusDays(daysToSubtract.toLong())
    }

    /** Returns the week start of [timestampMs] at midnight in [zoneId], in epoch ms. */
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
     * Returns how many days [timestampMs] falls after [weekStartMs], by system-zone dates,
     * clamped to 0..6. Index 0 is whichever weekday the week starts on.
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
     * Formats [startDate]..[endDate] as a compact range. The year is appended when either date
     * is outside the current year:
     * - Same month: "Jan 6-8", or "Jan 6-8, 2027"
     * - Cross month, same year: "Jan 30 - Feb 1", or "Jan 30 - Feb 1, 2027"
     * - Cross year, both years always shown: "Dec 30, 2025 - Jan 1, 2026"
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
            // Cross year: both years
            else -> {
                val startMonth = startDate.format(monthFormatter)
                val endMonth = endDate.format(monthFormatter)
                "$startMonth ${startDate.dayOfMonth}, ${startDate.year} - $endMonth ${endDate.dayOfMonth}, ${endDate.year}"
            }
        }
    }

    /**
     * Formats [date] as the locale's abbreviated month and year ("Apr 2026"), for the top-bar
     * title in the month, agenda, week and 3-day views. The abbreviation keeps the label short
     * enough to fit alongside the logo and navigation controls.
     */
    fun formatMonthYear(date: LocalDate): String {
        return DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMM"), Locale.getDefault())
            .format(date)
    }

    /**
     * Formats [prefix] followed by the locale-aware week-of-year of [date]. The caller passes a
     * localized string resource as [prefix], which keeps this Composable-free. No space is added,
     * so en-US reads "W21"; a locale whose prefix needs trailing whitespace must include it in the
     * resource value.
     */
    fun formatWeekLabel(date: LocalDate, firstDayOfWeek: Int = 0, prefix: String): String {
        val weekFields = DateTimeUtils.getLocaleWeekFields(firstDayOfWeek)
        val weekNumber = date.get(weekFields.weekOfWeekBasedYear())
        return "$prefix$weekNumber"
    }

    // ==================== Scroll Defaults ====================

    /**
     * Returns the initial scroll offset in px for the timed grid.
     *
     * [androidx.compose.foundation.rememberScrollState] uses `rememberSaveable` with no keys, so
     * its `initial` is read once per composition lifetime. This gives a useful start on cold
     * launch without overriding a scroll the user made this session.
     *
     * @param savedPosition the ViewModel's cached pixel offset. Above 0 means the user scrolled
     *   this session, and it is returned as is. 0 and negatives count as not scrolled because,
     *   after the debounced onScrollPositionChange settles, any real scroll has moved past pixel 0,
     *   so only a positive value proves one.
     * @param hourHeightDp the current, pinch-zoomable hour-row height, clamped to
     *   [MIN_HOUR_HEIGHT_DP]..[MAX_HOUR_HEIGHT_DP] by the caller.
     * @param density `LocalDensity.current.density`.
     * @param savedMinutes the persisted clock time (minutes from midnight, 0..1439) restored
     *   across restarts; below 0 means never saved. Stored as minutes, not pixels, so a zoom
     *   change between sessions still lands on the same time. Read only when [savedPosition]
     *   isn't positive.
     * @param defaultHour hour (0..23) to scroll to when neither position is saved.
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
            // Fresh install or never scrolled: land on the default hour.
            else -> (defaultHour * hourHeightDp * density).toInt()
        }
    }

    /**
     * Converts a vertical scroll offset in px to minutes from midnight, clamped to 0..1439, so the
     * scroll position persists as a zoom-independent clock time. A non-positive [hourHeightPx]
     * returns 0 instead of dividing by zero.
     */
    fun pixelsToMinutesOfDay(pixels: Float, hourHeightPx: Float): Int {
        if (hourHeightPx <= 0f) return 0
        val minutes = (pixels / hourHeightPx * MINUTES_PER_HOUR).toInt()
        return minutes.coerceIn(0, 24 * MINUTES_PER_HOUR - 1)
    }

    /**
     * Converts minutes from midnight to a vertical scroll offset in px at [hourHeightPx]; the
     * inverse of [pixelsToMinutesOfDay] at a fixed zoom.
     */
    fun minutesOfDayToPixels(minutesOfDay: Int, hourHeightPx: Float): Int {
        return (minutesOfDay.toFloat() / MINUTES_PER_HOUR * hourHeightPx).toInt()
    }

    /**
     * Returns the scroll offset in px that keeps the clock time at the viewport's vertical center
     * fixed while a pinch-zoom changes the hour-row height.
     *
     * The center time comes from the pre-zoom geometry, is re-projected onto the new hour height,
     * then clamped to the scrollable range. The clamp must use the post-zoom content height
     * (`newHourHeightPx * totalHours`): zooming in grows the grid, and clamping to the smaller
     * pre-zoom range would park the current-time line and every event away from center. A
     * non-positive [oldHourHeightPx] returns [currentScrollPx] unchanged.
     *
     * @param viewportHeightPx height of the visible grid viewport, above 0.
     * @param totalHours hours the grid renders ([TOTAL_HOURS]).
     * @param panYPx vertical pan from the same two-finger gesture, folded in before the clamp so
     *   the result always stays in range. An upward pan must not push the target past the
     *   post-zoom max, or the caller's wait for the grid to reach this offset never completes.
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
     * Returns the hour at the top of the visible grid, clamped to [gridStartHour]..23, for
     * seeding a new event's start time.
     *
     * Uses the same `savedPosition > 0` test as [resolveInitialScrollPx]. Otherwise it returns
     * [defaultHour] unclamped, which ignores the persisted scroll time [resolveInitialScrollPx]
     * restores, so after a restart it can differ from the hour the grid lands on.
     *
     * @param savedPosition the ViewModel's cached pixel offset; above 0 means the user scrolled.
     * @param hourHeightPx the current hour-row height in px (dp * density).
     * @param gridStartHour first hour the grid renders, [START_HOUR] by default.
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
     * Rounds [minutes] to the nearest multiple of [SNAP_INTERVAL_MINUTES]. 53 gives 60, so a
     * caller snapping minutes within an hour must carry into the next hour.
     */
    fun snapToQuarterHour(minutes: Int): Int {
        return ((minutes + SNAP_INTERVAL_MINUTES / 2) / SNAP_INTERVAL_MINUTES) * SNAP_INTERVAL_MINUTES
    }

    /**
     * Returns the (hour, minute) at [yOffset] px in a grid that starts at [startHour], snapped to
     * the quarter hour when [snap]. The hour is clamped to 0..23 and the minute to 0..59.
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

    /** Formats [hour] (0-23) as a grid label: "6a", "12p" in 12-hour, "06", "12" in 24-hour. */
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

    /** An event's layout window in minutes from midnight, used for overlap detection. */
    private data class EventTimeSpan(
        val startMinutes: Int,
        val endMinutes: Int
    ) {
        /**
         * Returns whether the spans overlap. Spans that only touch (one ends at 10:00, the other
         * starts at 10:00) don't, so they can stack vertically in the same slot.
         */
        fun overlapsWith(other: EventTimeSpan): Boolean {
            return startMinutes < other.endMinutes && endMinutes > other.startMinutes
        }
    }

    /** A layout column whose events never overlap, so they stack vertically. */
    private class LayoutSlot(val slotIndex: Int) {
        private val spans = mutableListOf<EventTimeSpan>()

        fun canAccommodate(span: EventTimeSpan): Boolean {
            return spans.none { it.overlapsWith(span) }
        }

        fun place(span: EventTimeSpan) {
            spans.add(span)
        }
    }

    /**
     * An event's placement in one day column.
     *
     * @property overlapIndex the event's slot; [groupForDisplay] hides slots past the cap.
     * @property overlapTotal slots in the event's overlap cluster, including hidden ones.
     * @property startMinutes start of the layout window, clipped to the column's date.
     * @property endMinutes end of the layout window, clipped to the column's date. Short events
     *   stretch it toward the rendered minimum height, up to the cap [positionEventsForDay] sets.
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
     * Positions [events] in the day column for [date]:
     * 1. Sort by start time, then duration, longer first.
     * 2. Place each event in the leftmost slot it doesn't overlap.
     * 3. Group transitively overlapping events into clusters.
     * 4. Split the column width evenly among a cluster's slots, up to [maxVisibleOverlap].
     *
     * Parts of an event on other dates are clipped off. An event whose clipped window falls
     * outside [startHour]..[endHour] is dropped.
     *
     * @param dayIndex copied into each [PositionedEvent].
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

        // Step 1: sort by start time, then by duration, longer first for better stacking.
        val sorted = events.sortedWith(compareBy(
            { it.startTs },
            { -(it.endTs - it.startTs) }
        ))

        // An event shorter than MIN_EVENT_HEIGHT renders at that height, so a thinner layout
        // window would pack two such events into one slot and draw them on top of each other.
        // Every event gets a window at least as tall as its rendered block, up to the cap
        // below. This also keeps zero-duration and sub-minute events, which the grid-clamp
        // guard below would drop; off-grid events still clamp to a point and drop.
        //
        // The window is capped at its default-zoom size. Zoomed out, a floored block covers
        // more minutes, but matching that would force back-to-back meetings (two 30-min events
        // at min zoom) into half-width columns, a worse read than a few px of block overlap
        // that zooming in or the Agenda or Day view resolves. Zooming in still shrinks the
        // window, since it stays below the cap.
        val defaultMinHeightMinutes = (MIN_EVENT_HEIGHT.value / HOUR_HEIGHT.value * MINUTES_PER_HOUR).toInt()
        val minHeightMinutes = (MIN_EVENT_HEIGHT.value / hourHeight.value * MINUTES_PER_HOUR)
            .toInt().coerceIn(1, defaultMinHeightMinutes)

        // Step 2: convert to time spans, clamping cross-midnight events to this date.
        val timeSpans = sorted.map { displayEvent ->
            val start = Instant.ofEpochMilli(displayEvent.startTs).atZone(ZoneId.systemDefault())
            val end = Instant.ofEpochMilli(displayEvent.endTs).atZone(ZoneId.systemDefault())
            val eventStartDate = start.toLocalDate()
            val eventEndDate = end.toLocalDate()

            val startMinutes = if (eventStartDate < date) 0
                else start.hour * MINUTES_PER_HOUR + start.minute

            val rawEndMinutes = if (eventEndDate > date) END_HOUR * MINUTES_PER_HOUR
                else end.hour * MINUTES_PER_HOUR + end.minute

            // Stretch short events to the rendered height, except a multi-day event that began
            // on a prior date and ends at exactly 00:00 today: that zero-length sliver must
            // collapse and drop off this day. Same-day short, zero-length or sub-minute events
            // aren't slivers and do stretch.
            val isMidnightSliver = eventStartDate < date && rawEndMinutes == startMinutes
            val endMinutes = if (!isMidnightSliver && rawEndMinutes - startMinutes < minHeightMinutes) {
                startMinutes + minHeightMinutes
            } else {
                rawEndMinutes
            }

            EventTimeSpan(startMinutes = startMinutes, endMinutes = endMinutes)
        }

        // Step 3: assign each event to a layout slot.
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

        // Step 4: find clusters of transitively overlapping events.
        val clusters = findConnectedClusters(timeSpans)

        // Step 5: build positioned events with their layout fractions.
        return sorted.mapIndexedNotNull { i, displayEvent ->
            val span = timeSpans[i]
            val slotIndex = slotAssignments[i]

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
     * Returns clusters of transitively overlapping spans, as sets of indices into [spans]. A and C
     * share a cluster when some B overlaps both, even if A and C don't overlap: A(9-10),
     * B(9:30-11), C(10-11) form one cluster.
     */
    private fun findConnectedClusters(spans: List<EventTimeSpan>): List<Set<Int>> {
        val clusters = mutableListOf<MutableSet<Int>>()

        for (i in spans.indices) {
            val overlappingClusters = clusters.filter { cluster ->
                cluster.any { j -> spans[i].overlapsWith(spans[j]) }
            }

            when (overlappingClusters.size) {
                0 -> {
                    clusters.add(mutableSetOf(i))
                }
                1 -> {
                    overlappingClusters[0].add(i)
                }
                else -> {
                    // The event bridges clusters: merge them.
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
     * Splits one overlap group into the events to draw and the count for the overflow badge.
     *
     * Filters by slot (`overlapIndex`), not list order, which keeps the layout
     * [positionEventsForDay] computed: every event in a slot within the cap renders, even when a
     * long event connects them into one cluster (#175).
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
     * Formats [startTs]..[endTs] in the system zone, lowercased: "9:00am - 10:30am" or
     * "09:00 - 10:30".
     *
     * @param timePattern a `DateTimeFormatter` pattern, "h:mma" for 12-hour or "HH:mm" for 24-hour
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

    /** Formats [ts] like one end of [formatTimeRange]: "9:00am" or "09:00". */
    fun formatTime(ts: Long, timePattern: String = "h:mma"): String {
        val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
        val time = Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        return time.format(formatter).lowercase()
    }

    /** Returns whether [date] is today in the system zone. */
    fun isToday(date: LocalDate): Boolean = date == LocalDate.now()

    /** Returns whether [date] is a Saturday or Sunday, whatever the locale's weekend. */
    fun isWeekend(date: LocalDate): Boolean {
        val dayOfWeek = date.dayOfWeek.value
        return dayOfWeek == 6 || dayOfWeek == 7  // Saturday or Sunday
    }

    /** Returns the system-zone date [dayIndex] days after the date of [weekStartMs]. */
    fun getDateForDayIndex(weekStartMs: Long, dayIndex: Int): LocalDate {
        val weekStart = Instant.ofEpochMilli(weekStartMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return weekStart.plusDays(dayIndex.toLong())
    }

    /** Formats [date] with the locale's pattern for short weekday and day of month. */
    fun formatDayHeader(date: LocalDate): String {
        val dayFormatter = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEd"), Locale.getDefault())
        return date.format(dayFormatter)
    }

    /**
     * Formats the date [dayIndex] days after the date of [weekStartMs] as month and day ("Jan 4"),
     * adding the year only outside the current year ("Jan 4, 2027").
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
     * Returns whether the all-day expand chevron has anything to do: true only when at least one
     * day in [perDayCounts] has more all-day events than [MAX_ALLDAY_ROWS_COLLAPSED].
     */
    fun anyAllDayColumnHasOverflowWhenCollapsed(perDayCounts: List<Int>): Boolean =
        perDayCounts.any { it > MAX_ALLDAY_ROWS_COLLAPSED }
}
