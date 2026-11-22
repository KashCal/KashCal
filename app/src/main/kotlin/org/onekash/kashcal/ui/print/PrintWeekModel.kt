package org.onekash.kashcal.ui.print

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.isStripItem
import org.onekash.kashcal.domain.model.overlapsWindow
import org.onekash.kashcal.domain.planner.isChangedOccurrence
import org.onekash.kashcal.domain.planner.movedCue
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.screens.planner.movedCueLabel
import org.onekash.kashcal.ui.screens.planner.plannerTime
import org.onekash.kashcal.ui.screens.planner.plannerTimeRange
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Identifies a printed calendar; Room and device ids are separate id spaces. */
sealed interface PrintCalendarKey {
    data class Room(val id: Long) : PrintCalendarKey
    data class Device(val id: Long) : PrintCalendarKey
}

/** A calendar on the printout, named as the print sheet names it. */
data class PrintCalendar(val key: PrintCalendarKey, val name: String, val color: Int)

/**
 * The resolved formats and labels the model writes with. The patterns are localized by the
 * caller (DateTimeUtils.localizedPattern needs Android), so the model stays a pure function.
 *
 * @property timePattern KashCal's resolved time pattern ("h:mm a" or "HH:mm").
 * @property weekdayPattern a short weekday ("EEE").
 * @property shortDatePattern a short month and day ("MMMd").
 * @property dayHeaderPattern a day column heading ("EEEMMMd").
 * @property allDayLabel the label of an all-day event.
 * @property join puts the two ends of a range together, in the language's range joiner.
 * @property moved writes where a moved occurrence was ("was Thu") from the original's text.
 */
data class PrintFormats(
    val locale: Locale,
    val timePattern: String,
    val weekdayPattern: String,
    val shortDatePattern: String,
    val dayHeaderPattern: String,
    val allDayLabel: String,
    val join: (start: String, end: String) -> String,
    val moved: (original: String) -> String,
)

/** The printed week: everything the HTML needs, already written out. */
data class PrintWeek(
    val days: List<PrintDay>,
    /** First hour the grid shows, 0..23. */
    val hourStart: Int,
    /** Hour the grid ends at, exclusive, 1..24. */
    val hourEnd: Int,
    /** Labels for the hour lines inside the grid, hourStart + 1 until hourEnd. */
    val hourLabels: List<Pair<Int, String>>,
    val includeGrid: Boolean,
    val includeDetails: Boolean,
    /** True when both pages print, so grid blocks and detail entries carry matching numbers. */
    val numbered: Boolean,
    /** The ticked calendars, in sheet order, for the legend. */
    val calendars: List<PrintCalendar>,
)

/** One day of the printed week. [details] is empty on a free day. */
data class PrintDay(
    val date: LocalDate,
    val heading: String,
    val isWeekend: Boolean,
    val stripItems: List<PrintStripItem>,
    val parts: List<PrintGridPart>,
    val details: List<PrintDetail>,
)

/** An all-day band entry on one day: an all-day event or a long multi-day one. */
data class PrintStripItem(
    val number: Int?,
    val title: String,
    val color: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
    val declined: Boolean,
    val cancelled: Boolean,
)

/**
 * A timed event's block in one day column, placed as the Week view places it
 * ([WeekViewUtils.positionEventsForDay]). Minutes count from the column's midnight.
 *
 * @property textEndMinute where the block's text must stop: the start of the first block drawn
 *   on top of it, or [endMinute].
 * @property leftFraction the block's start within the column.
 * @property widthFraction the block's width within the column: to the column's end, or a share
 *   of it when its cascade level splits side by side.
 * @property depth the cascade level; a deeper block draws on top.
 */
data class PrintGridPart(
    val number: Int?,
    val title: String,
    val timeText: String,
    val color: Int,
    val startMinute: Int,
    val endMinute: Int,
    val textEndMinute: Int,
    val leftFraction: Float,
    val widthFraction: Float,
    val depth: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
    val declined: Boolean,
    val cancelled: Boolean,
)

/**
 * One event on the details page. There is no notes field: the printout never carries an event's
 * description.
 *
 * @property whenNote a second line under [whenText], the all-day label for a multi-day all-day
 *   event.
 */
data class PrintDetail(
    val number: Int?,
    val whenText: String,
    val whenNote: String?,
    val title: String,
    val calendarName: String,
    val calendarColor: Int,
    val location: String?,
    val repeats: Boolean,
    val movedText: String?,
    val declined: Boolean,
    val cancelled: Boolean,
)

/** Grid height on an A4 landscape page, the size the cascade's decisions are made for. */
private const val GRID_HEIGHT_MM = 150f
private const val MM_PER_DP = 0.15875f

/**
 * One printed block line (7 pt at 1.18 line height); a cascaded block leaves the block it covers
 * this much.
 */
private const val BLOCK_LINE_MM = 7f * 1.18f * 0.3528f

private const val DEFAULT_HOUR_START = 8
private const val DEFAULT_HOUR_END = 18
private const val MINUTES_PER_DAY = 24 * 60
private const val MIN_BLOCK_MINUTES = 30
private const val HOUR_MS = 3_600_000L

/**
 * Builds the printed week from [events] for the 7 days from [weekStart], in the system zone
 * (the zone [WeekViewUtils.positionEventsForDay] uses, so grid and details agree).
 *
 * Events with no time in the week are dropped ([overlapsWindow]). [DisplayEvent.isStripItem]
 * decides between the all-day band and the grid, as in the Week view. The grid's hours run from
 * 08:00 to 18:00, widened to every timed event's start and end on the week's days, with room for
 * a short block below its start. Each event is numbered, in the order the details list it, only
 * when both pages are included.
 *
 * @param calendars the ticked calendars in sheet order; an event takes its calendar's name and
 *   colour from here, falling back to [DisplayEvent.calendarName] and
 *   [DisplayEvent.calendarColor].
 * @param title the printed title of an event, as the app shows it.
 */
fun buildPrintWeek(
    events: List<DisplayEvent>,
    weekStart: LocalDate,
    includeGrid: Boolean,
    includeDetails: Boolean,
    showMultiDayTimedInAllDayStrip: Boolean,
    roomSeriesAllDayById: Map<Long, Boolean>,
    calendars: List<PrintCalendar>,
    formats: PrintFormats,
    title: (DisplayEvent) -> String,
): PrintWeek {
    val zone = ZoneId.systemDefault()
    val dates = (0L until 7L).map { weekStart.plusDays(it) }
    val dayCodes = dates.map { DayPagerUtils.localDateToDayCode(it) }
    val dayStarts = (dates + weekStart.plusDays(7)).map { it.atStartOfDay(zone).toInstant().toEpochMilli() }

    val inWeek = events.filter { it.overlapsWindow(dayStarts.first(), dayStarts.last(), dayCodes.first(), dayCodes.last()) }
    val (strip, grid) = inWeek.partition { it.isStripItem(showMultiDayTimedInAllDayStrip) }
    val stripKeys = strip.map { it.stableKey }.toSet()

    // Worked out once per event: sorting and the per-day passes read them many times.
    val titles = inWeek.associate { it.stableKey to title(it) }
    val listDay = inWeek.associate { event ->
        val day = if (event.isAllDay) dayCodes.indexOf(event.startDay) else dates.indexOf(localDate(event.startTs, zone))
        event.stableKey to day.coerceAtLeast(0)
    }
    val windows = grid.associate { event -> event.stableKey to dates.indices.map { d -> event.minutesOn(d, dayStarts, zone) } }

    val ordered = inWeek.sortedWith(
        compareBy<DisplayEvent> { listDay.getValue(it.stableKey) }
            .thenBy { if (it.stableKey in stripKeys) 0 else 1 }
            .thenBy { if (it.isAllDay) it.startDay.toLong() else it.startTs }
            .thenByDescending { if (it.isAllDay) it.endDay.toLong() else it.endTs - it.startTs }
            .thenBy { titles.getValue(it.stableKey) }
    )
    val orderedStrip = ordered.filter { it.stableKey in stripKeys }
    val numbered = includeGrid && includeDetails
    val numbers: Map<String, Int> =
        if (numbered) ordered.mapIndexed { i, e -> e.stableKey to i + 1 }.toMap() else emptyMap()

    // The span comes from the raw times clipped to each day, before positioning: the
    // positioned ends are stretched by a minimum height that depends on the span itself. A
    // short block still needs room, so every start counts at least half an hour.
    val allWindows = windows.values.flatten().filterNotNull()
    val hourStart = minOf(DEFAULT_HOUR_START, allWindows.minOfOrNull { it.first }?.div(60) ?: DEFAULT_HOUR_START)
    val hourEnd = maxOf(
        DEFAULT_HOUR_END,
        allWindows.maxOfOrNull { (maxOf(it.second, it.first + MIN_BLOCK_MINUTES) + 59) / 60 } ?: DEFAULT_HOUR_END,
    ).coerceAtMost(24)
    val hourHeight: Dp = (GRID_HEIGHT_MM / (hourEnd - hourStart) / MM_PER_DP).dp
    val cascade = WeekViewUtils.OverlapLayout.Cascade(minOffset = (BLOCK_LINE_MM / MM_PER_DP).dp)

    val calendarsByKey = calendars.associateBy { it.key }
    val weekday = DateTimeFormatter.ofPattern(formats.weekdayPattern, formats.locale)
    val shortDate = DateTimeFormatter.ofPattern(formats.shortDatePattern, formats.locale)
    val dayHeader = DateTimeFormatter.ofPattern(formats.dayHeaderPattern, formats.locale)
    // Weekday names only when both ends are in the printed week, where each names one day;
    // otherwise both ends as dates, so a week-long range never reads "Sun–Sun".
    val lastDate = dates.last()
    val endNames: (LocalDate, LocalDate) -> Pair<String, String> = { from, to ->
        val inWeek = from >= weekStart && to <= lastDate
        val f = if (inWeek) weekday else shortDate
        f.format(from) to f.format(to)
    }
    val timeTexts = grid.associate { it.stableKey to timeText(it, zone, formats, endNames) }

    val days = dates.mapIndexed { d, date ->
        val dayCode = dayCodes[d]
        val stripItems = orderedStrip
            .filter { it.stripCovers(dayCode, dayStarts[d], dayStarts[d + 1]) }
            .map { event ->
                PrintStripItem(
                    number = numbers[event.stableKey],
                    title = titles.getValue(event.stableKey),
                    color = event.eventColor ?: event.calendarColor,
                    continuesBefore = event.startsBefore(dayCode, dayStarts[d]),
                    continuesAfter = event.endsAfter(dayCode, dayStarts[d + 1]),
                    declined = event.isDeclinedByMe,
                    cancelled = event.isCancelled,
                )
            }
        val positioned = WeekViewUtils.positionEventsForDay(
            events = grid.filter { windows.getValue(it.stableKey)[d] != null },
            date = date,
            dayIndex = d,
            hourHeight = hourHeight,
            startHour = 0,
            endHour = 24,
            maxVisibleOverlap = Int.MAX_VALUE,
            overlapLayout = cascade,
        )
        val parts = positioned.map { p ->
            val event = p.displayEvent
            val end = p.endMinutes.coerceAtMost(MINUTES_PER_DAY)
            val textMinutes = (p.textHeight.value / hourHeight.value * 60f).toInt()
            PrintGridPart(
                number = numbers[event.stableKey],
                title = titles.getValue(event.stableKey),
                timeText = timeTexts.getValue(event.stableKey),
                color = event.eventColor ?: event.calendarColor,
                startMinute = p.startMinutes,
                endMinute = end,
                textEndMinute = (p.startMinutes + textMinutes).coerceIn(p.startMinutes, end),
                leftFraction = p.leftFraction,
                widthFraction = p.widthFraction,
                depth = p.cascadeDepth,
                continuesBefore = event.startTs < dayStarts[d],
                continuesAfter = event.endTs > dayStarts[d + 1],
                declined = event.isDeclinedByMe,
                cancelled = event.isCancelled,
            )
        }
        val details = ordered.filter { listDay.getValue(it.stableKey) == d }.map { event ->
            val calendar = calendarsByKey[event.calendarKey()]
            val multiDayAllDay = event.isAllDay && event.endDay != event.startDay
            PrintDetail(
                number = numbers[event.stableKey],
                whenText = when {
                    multiDayAllDay -> endNames(
                        DayPagerUtils.dayCodeToLocalDate(event.startDay),
                        DayPagerUtils.dayCodeToLocalDate(event.endDay),
                    ).let { (from, to) -> formats.join(from, to) }
                    event.isAllDay -> formats.allDayLabel
                    else -> timeTexts[event.stableKey] ?: timeText(event, zone, formats, endNames)
                },
                whenNote = if (multiDayAllDay) formats.allDayLabel else null,
                title = titles.getValue(event.stableKey),
                calendarName = calendar?.name ?: event.calendarName,
                calendarColor = calendar?.color ?: event.calendarColor,
                location = event.location?.takeIf { it.isNotBlank() },
                repeats = event.hasRrule || event.isChangedOccurrence(),
                movedText = movedCue(event, roomSeriesAllDayById, dayCodes, zone)?.let { cue ->
                    formats.moved(movedCueLabel(cue, formats.timePattern, formats.locale, weekday, shortDate, zone))
                },
                declined = event.isDeclinedByMe,
                cancelled = event.isCancelled,
            )
        }
        PrintDay(
            date = date,
            heading = dayHeader.format(date),
            isWeekend = WeekViewUtils.isWeekend(date),
            stripItems = stripItems,
            parts = parts,
            details = details,
        )
    }

    // A clock time, not an instant on a real day: on a spring-forward week start the gap hour
    // would otherwise read as the hour after it.
    val hourLabels = (hourStart + 1 until hourEnd).map { hour ->
        hour to plannerTime(hour * HOUR_MS, formats.timePattern, formats.locale, ZoneOffset.UTC)
    }

    return PrintWeek(
        days = days,
        hourStart = hourStart,
        hourEnd = hourEnd,
        hourLabels = hourLabels,
        includeGrid = includeGrid,
        includeDetails = includeDetails,
        numbered = numbered,
        calendars = calendars,
    )
}

/**
 * Writes a timed or long multi-day event's time: a range within one day (09:00 to the next
 * midnight counts as one, [DateTimeUtils.spansMultipleDays]), or both ends' day names and times
 * when it spans days.
 */
private fun timeText(
    event: DisplayEvent,
    zone: ZoneId,
    formats: PrintFormats,
    endNames: (LocalDate, LocalDate) -> Pair<String, String>,
): String {
    if (!DateTimeUtils.spansMultipleDays(event.startTs, event.endTs, isAllDay = false, localZone = zone)) {
        return plannerTimeRange(event.startTs, event.endTs, formats.timePattern, formats.join, formats.locale, zone)
    }
    val (from, to) = endNames(localDate(event.startTs, zone), localDate(event.endTs, zone))
    return formats.join(
        "$from ${plannerTime(event.startTs, formats.timePattern, formats.locale, zone)}",
        "$to ${plannerTime(event.endTs, formats.timePattern, formats.locale, zone)}",
    )
}

private fun zoned(ts: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ts).atZone(zone)

private fun localDate(ts: Long, zone: ZoneId): LocalDate = zoned(ts, zone).toLocalDate()

private fun DisplayEvent.calendarKey(): PrintCalendarKey = when (this) {
    is DisplayEvent.Room -> PrintCalendarKey.Room(occurrence.calendarId)
    is DisplayEvent.Device -> PrintCalendarKey.Device(instance.calendarId)
}

/**
 * Returns the event's minutes on week day [d] (clipped to that day, from its midnight), or null
 * when it has no time there ([overlapsWindow]).
 */
private fun DisplayEvent.minutesOn(d: Int, dayStarts: List<Long>, zone: ZoneId): Pair<Int, Int>? {
    val dayStart = dayStarts[d]
    val dayEnd = dayStarts[d + 1]
    // Grid events are timed, so the day codes play no part.
    if (!overlapsWindow(dayStart, dayEnd, 0, 0)) return null
    val startMinute = if (startTs <= dayStart) 0 else zoned(startTs, zone).let { it.hour * 60 + it.minute }
    val endMinute = if (endTs >= dayEnd) MINUTES_PER_DAY else zoned(endTs, zone).let { it.hour * 60 + it.minute }
    return startMinute to maxOf(endMinute, startMinute)
}

/** Returns true when a band entry shows on [dayCode]: by day code if all-day, by time if not. */
private fun DisplayEvent.stripCovers(dayCode: Int, dayStart: Long, dayEnd: Long): Boolean =
    if (isAllDay) dayCode in startDay..endDay else endTs > dayStart && startTs < dayEnd

private fun DisplayEvent.startsBefore(dayCode: Int, dayStart: Long): Boolean =
    if (isAllDay) startDay < dayCode else startTs < dayStart

private fun DisplayEvent.endsAfter(dayCode: Int, dayEnd: Long): Boolean =
    if (isAllDay) endDay > dayCode else endTs > dayEnd
