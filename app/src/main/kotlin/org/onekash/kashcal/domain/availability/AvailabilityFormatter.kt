package org.onekash.kashcal.domain.availability

import android.content.Context
import org.onekash.kashcal.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * Converts a list of [FreeBlock]s to a plain-text availability summary suitable
 * for Intent.ACTION_SEND / EXTRA_TEXT.
 *
 * Working hours are passed as minutes from midnight (0..1440). 1440 represents
 * end-of-day and is rendered specially because LocalTime cannot encode 24:00.
 *
 * All output strings come from string resources; day-of-week labels use the
 * caller-supplied [Locale] short style. Time formatting follows the 12h/24h
 * preference passed in by the caller.
 */
class AvailabilityFormatter @Inject constructor() {

    fun format(
        blocks: List<FreeBlock>,
        startDay: LocalDate,
        days: Int,
        workStartMin: Int,
        workEndMin: Int,
        locale: Locale,
        is24Hour: Boolean,
        context: Context
    ): String {
        val footer = context.getString(R.string.share_from_kashcal_footer)
        if (blocks.isEmpty()) {
            return context.getString(R.string.share_availability_empty) + "\n\n" + footer
        }

        val dateFormatter = DateTimeFormatter.ofPattern("MMM d", locale)

        val header = context.resources.getQuantityString(
            R.plurals.share_availability_header,
            days,
            days,
            formatMinutes(workStartMin, is24Hour, locale),
            formatMinutes(workEndMin, is24Hour, locale)
        )

        val separator = context.getString(R.string.share_availability_block_separator)

        val grouped = blocks
            .groupBy { it.day }
            .toSortedMap()

        val dayLines = grouped.map { (day, dayBlocks) ->
            val dow = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val date = day.format(dateFormatter)
            val ranges = dayBlocks
                .sortedBy { it.start }
                .joinToString(separator) { block ->
                    context.getString(
                        R.string.share_availability_time_range,
                        formatLocalTime(block.start, is24Hour, locale),
                        formatLocalTime(block.end, is24Hour, locale)
                    )
                }
            context.getString(
                R.string.share_availability_day_line,
                "$dow $date",
                ranges
            )
        }

        return buildString {
            append(header)
            append("\n\n")
            append(dayLines.joinToString("\n"))
            append("\n\n")
            append(footer)
        }
    }

    private fun formatLocalTime(time: LocalTime, is24Hour: Boolean, locale: Locale): String {
        // FreeBlockFinder uses LocalTime.MAX as the end-of-day sentinel because
        // LocalTime can't represent 24:00. Render it explicitly so the user
        // doesn't see a deceptive "11:59 PM" / "23:59".
        val effectiveMinutes = if (time == LocalTime.MAX) 24 * 60 else time.hour * 60 + time.minute
        return formatMinutes(effectiveMinutes, is24Hour, locale)
    }

    private fun formatMinutes(minutes: Int, is24Hour: Boolean, locale: Locale): String {
        if (minutes >= 24 * 60) {
            return if (is24Hour) "24:00" else "12:00 AM"
        }
        val safe = minutes.coerceIn(0, 24 * 60 - 1)
        val hour = safe / 60
        val minute = safe % 60
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale)
            .format(LocalTime.of(hour, minute))
    }
}
