package org.onekash.kashcal.domain.share

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text of the share-card date chip: a single-day chip (big numeral beside a stacked month and
 * day of week) or a multi-day chip on one line ("MAY 31 – JUN 03"). A range squeezed into the
 * three-part single-day shape makes a heavy, unbalanced header.
 *
 * [org.onekash.kashcal.util.DateTimeUtils.formatEventDate] and
 * [org.onekash.kashcal.util.DateTimeUtils.formatEventDateShort] return one composed string
 * ("Thu, Dec 25"), so they can't supply the separately styled parts.
 */
sealed class DateChipText {
    /** Single-day chip: a big numeral with month and day of week stacked to its right. */
    data class Single(
        val numeral: String,
        val monthLabel: String,
        val dayOfWeekLabel: String,
    ) : DateChipText()

    /**
     * Multi-day chip: one label such as "MAY 31 – JUN 03", "MAY 05 – 08" or "DEC 30 – JAN 02".
     * It has no day of week; the body subtitle carries that ([DateChipFormatter.formatDowRange]).
     */
    data class Range(val label: String) : DateChipText()
}

object DateChipFormatter {

    /** Range separator: an en dash (U+2013), not a hyphen. */
    private const val EN_DASH = "–"

    /**
     * Formats [timestampMs], read in [zone], as a single-day chip.
     *
     * The numeral is "dd", zero-padded so "06" lines up with two-digit days. Month ("MMM") and
     * day of week ("EEE") are formatted in [locale] and uppercased with [Locale.ROOT], which
     * avoids the Turkish dotted/dotless i mapping.
     */
    fun format(timestampMs: Long, zone: ZoneId, locale: Locale): DateChipText.Single {
        val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        return formatSingle(date, locale)
    }

    /**
     * Formats a multi-day range as a one-line chip label. In en-US: "MAY 05 – 08" within a
     * month, "MAY 31 – JUN 03" across months, "DEC 30 – JAN 02" across years (no year shown).
     *
     * Returns a [DateChipText.Single] when [startMs] and [endMs] fall on the same day in [zone],
     * so the caller doesn't branch.
     */
    fun formatRange(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        locale: Locale,
    ): DateChipText {
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        if (startDate == endDate) {
            return formatSingle(startDate, locale)
        }

        val startDay = DateTimeFormatter.ofPattern("dd", locale).format(startDate)
        val endDay = DateTimeFormatter.ofPattern("dd", locale).format(endDate)
        val startMonth = DateTimeFormatter.ofPattern("MMM", locale).format(startDate)
            .uppercase(Locale.ROOT)
        val endMonth = DateTimeFormatter.ofPattern("MMM", locale).format(endDate)
            .uppercase(Locale.ROOT)

        // Within one month the month is shown once; across months both are shown.
        val label = if (startMonth == endMonth) {
            "$startMonth $startDay $EN_DASH $endDay"
        } else {
            "$startMonth $startDay $EN_DASH $endMonth $endDay"
        }
        return DateChipText.Range(label)
    }

    /**
     * Formats the day-of-week range for a multi-day event's body subtitle, e.g. "Sun – Wed", or
     * one day name when both fall on the same day in [zone].
     *
     * Kept in the locale's own case, unlike the uppercase chip, so it reads as body text.
     */
    fun formatDowRange(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        locale: Locale,
    ): String {
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val startDow = DateTimeFormatter.ofPattern("EEE", locale).format(startDate)
        val endDow = DateTimeFormatter.ofPattern("EEE", locale).format(endDate)
        if (startDate == endDate) return startDow
        return "$startDow $EN_DASH $endDow"
    }

    private fun formatSingle(date: LocalDate, locale: Locale): DateChipText.Single {
        val numeral = DateTimeFormatter.ofPattern("dd", locale).format(date)
        val month = DateTimeFormatter.ofPattern("MMM", locale).format(date)
        val dow = DateTimeFormatter.ofPattern("EEE", locale).format(date)
        return DateChipText.Single(
            numeral = numeral,
            monthLabel = month.uppercase(Locale.ROOT),
            dayOfWeekLabel = dow.uppercase(Locale.ROOT),
        )
    }
}
