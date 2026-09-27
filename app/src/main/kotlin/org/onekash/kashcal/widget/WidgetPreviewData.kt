package org.onekash.kashcal.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.MonthGrid
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sample content for the widget-picker previews.
 *
 * Previews render outside a normal widget session. Nothing here touches the repository, the
 * database or DataStore: the picker should render at once, possibly before the user has any
 * events, so the samples come only from [LocalDate.now] and string resources.
 *
 * The samples are correct as of the moment previews are published, and no later: the system
 * keeps the rasterized image and nothing here re-renders it. [WidgetPreviewRegistrar.publishStamp]
 * keeps them from going stale.
 *
 * Two invariants keep the previews rendering right:
 * - `startDay` on each event must equal the day code it is filed under, or the row shows a
 *   multi-day continuation glyph instead of a start time.
 * - `isPast` and `isCancelled` stay false, or rows render dimmed and struck through.
 */
internal object WidgetPreviewData {

    /** [MonthGrid.compute] reads the first weekday from the locale when given 0. */
    const val LOCALE_FIRST_DAY_OF_WEEK = 0

    /** Leaves room for the busiest sample day without triggering the overflow row. */
    const val MAX_EVENTS_PER_DAY = 5

    /** Distinct colors so preview rows don't all share one calendar's pill. */
    private const val COLOR_BLUE = 0xFF2196F3.toInt()
    private const val COLOR_GREEN = 0xFF43A047.toInt()
    private const val COLOR_ORANGE = 0xFFF57C00.toInt()
    private const val COLOR_PURPLE = 0xFF7E57C2.toInt()

    /**
     * Returns the time pattern for the device's 12/24-hour setting. The real widgets let the
     * stored time-format preference override it; a preview keeps to the device setting so it
     * needs no DataStore read on the picker path.
     */
    fun timePattern(context: Context): String = DateTimeUtils.getTimePattern(
        preference = DateTimeUtils.TimeFormatPreference.SYSTEM,
        is24HourDevice = DateFormat.is24HourFormat(context)
    )

    /**
     * Returns the current month's grid, the real month widget's default view. The first day of
     * the week comes from the device locale: previews are rasterized once at publish time, so a
     * snapshot of the stored preference would go stale anyway.
     */
    fun monthGrid(): MonthGrid {
        val today = LocalDate.now()
        return MonthGrid.compute(today.year, today.monthValue - 1, LOCALE_FIRST_DAY_OF_WEEK)
    }

    /** A plausible today: two timed events plus an all-day one. */
    fun agendaEvents(context: Context): List<WidgetDataRepository.WidgetEvent> {
        val today = LocalDate.now()
        return listOf(
            allDay(context, R.string.widget_preview_event_birthday, today, COLOR_PURPLE, id = 1),
            timed(context, R.string.widget_preview_event_standup, today, 9, 30, 45, COLOR_BLUE, id = 2),
            timed(context, R.string.widget_preview_event_review, today, 14, 0, 60, COLOR_ORANGE, id = 3)
        )
    }

    /**
     * Returns seven days from today. Every day is a key because the week widget renders a
     * header per day; some days are empty so the preview also shows a quiet day.
     */
    fun weekEvents(context: Context): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val today = LocalDate.now()
        var nextId = 10L
        return (0..6).associate { offset ->
            val date = today.plusDays(offset.toLong())
            val events = when (offset) {
                0 -> listOf(
                    timed(context, R.string.widget_preview_event_standup, date, 9, 30, 45, COLOR_BLUE, nextId++),
                    timed(context, R.string.widget_preview_event_lunch, date, 12, 30, 45, COLOR_GREEN, nextId++)
                )
                1 -> listOf(
                    timed(context, R.string.widget_preview_event_review, date, 14, 0, 60, COLOR_ORANGE, nextId++)
                )
                3 -> listOf(
                    timed(context, R.string.widget_preview_event_gym, date, 18, 0, 60, COLOR_GREEN, nextId++)
                )
                5 -> listOf(
                    allDay(context, R.string.widget_preview_event_trip, date, COLOR_PURPLE, nextId++)
                )
                else -> emptyList()
            }
            dayCodeOf(date) to events
        }
    }

    /** Days of the month that get a sample event, spread out to read as a busy-ish month. */
    private val MONTH_SAMPLE_DAYS = listOf(3, 8, 12, 17, 22, 26)

    /**
     * Returns the dates that get a sample event, all inside [inMonthOf]'s own month.
     *
     * Anchored to days of the month, not offsets from today: the grid drops trailing
     * all-next-month rows, and in dots mode draws no dots on adjacent-month cells, so a sample
     * that spilled into the next month could silently vanish. Offsets from today spill for the
     * last stretch of every month.
     *
     * Takes the reference date as a parameter so a test can walk a full year of them.
     */
    fun monthSampleDates(inMonthOf: LocalDate): List<LocalDate> = MONTH_SAMPLE_DAYS
        .filter { it <= inMonthOf.lengthOfMonth() }
        .map { inMonthOf.withDayOfMonth(it) }

    /** Returns sample events for the month grid, spread across the current month. */
    fun monthEvents(context: Context): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        var nextId = 30L

        return monthSampleDates(LocalDate.now()).associate { date ->
            dayCodeOf(date) to listOf(
                timed(context, R.string.widget_preview_event_call, date, 11, 0, 30, COLOR_BLUE, nextId++)
            )
        }
    }

    /**
     * Returns sample events on four days within the Upcoming widget's horizon. Empty days have
     * no key, since the Upcoming widget skips empty days.
     */
    fun upcomingEvents(context: Context): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val today = LocalDate.now()
        var nextId = 50L
        val plan = listOf(
            0L to listOf(
                Sample(R.string.widget_preview_event_standup, 9, 30, 45, COLOR_BLUE),
                Sample(R.string.widget_preview_event_lunch, 12, 30, 45, COLOR_GREEN)
            ),
            1L to listOf(Sample(R.string.widget_preview_event_review, 14, 0, 60, COLOR_ORANGE)),
            3L to listOf(Sample(R.string.widget_preview_event_dentist, 10, 15, 45, COLOR_PURPLE)),
            6L to listOf(Sample(R.string.widget_preview_event_gym, 18, 0, 60, COLOR_GREEN))
        )

        return plan
            .filter { (offset, _) -> offset < UPCOMING_HORIZON_DAYS }
            .associate { (offset, samples) ->
                val date = today.plusDays(offset)
                dayCodeOf(date) to samples.map { s ->
                    timed(context, s.titleRes, date, s.hour, s.minute, s.durationMinutes, s.color, nextId++)
                }
            }
    }

    private data class Sample(
        @StringRes val titleRes: Int,
        val hour: Int,
        val minute: Int,
        val durationMinutes: Int,
        val color: Int
    )

    private fun timed(
        context: Context,
        @StringRes titleRes: Int,
        date: LocalDate,
        hour: Int,
        minute: Int,
        durationMinutes: Int,
        color: Int,
        id: Long
    ): WidgetDataRepository.WidgetEvent {
        val startTs = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WidgetDataRepository.WidgetEvent(
            eventId = id,
            occurrenceStartTs = startTs,
            title = context.getString(titleRes),
            startTs = startTs,
            endTs = startTs + durationMinutes * 60_000L,
            isAllDay = false,
            calendarColor = color,
            isPast = false,
            isDeviceEvent = false,
            startDay = dayCodeOf(date),
            isCancelled = false
        )
    }

    private fun allDay(
        context: Context,
        @StringRes titleRes: Int,
        date: LocalDate,
        color: Int,
        id: Long
    ): WidgetDataRepository.WidgetEvent {
        val startTs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTs = date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WidgetDataRepository.WidgetEvent(
            eventId = id,
            occurrenceStartTs = startTs,
            title = context.getString(titleRes),
            startTs = startTs,
            endTs = endTs,
            isAllDay = true,
            calendarColor = color,
            isPast = false,
            isDeviceEvent = false,
            startDay = dayCodeOf(date),
            isCancelled = false
        )
    }

    fun dayCodeOf(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
}
