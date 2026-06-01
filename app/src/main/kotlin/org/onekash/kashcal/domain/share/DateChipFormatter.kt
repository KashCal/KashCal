package org.onekash.kashcal.domain.share

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date chip on the share-card. Two distinct shapes — a single-day chip
 * with a big numeral + stacked month/dow, and a multi-day range chip
 * rendered as a single horizontal string ("MAY 31 – JUN 3") because
 * trying to cram a range into the 3-element single-day shape produced a
 * heavy, unbalanced header.
 *
 * Sibling formatter audit:
 *  - [org.onekash.kashcal.util.DateTimeUtils.formatEventDate] returns a single
 *    composed string ("Thu, Dec 25"). Cannot reuse — we need three separate
 *    parts styled independently for the single-day case.
 *  - [org.onekash.kashcal.util.DateTimeUtils.formatEventDateShort] same constraint.
 */
sealed class DateChipText {
    /** Single-day chip: big numeral + stacked month/dow on the right. */
    data class Single(
        val numeral: String,
        val monthLabel: String,
        val dayOfWeekLabel: String,
    ) : DateChipText()

    /**
     * Multi-day chip: one horizontal label ("MAY 31 – JUN 03" / "MAY 05 – 08"
     * / "DEC 30 – JAN 02"). Rendered at 18sp / weight 700 / letter-spaced.
     * No DOW — the body subtitle carries that.
     */
    data class Range(val label: String) : DateChipText()
}

object DateChipFormatter {

    /** En-dash (U+2013) — used as the range separator. Different from a hyphen. */
    private const val EN_DASH = "–"

    /**
     * Format the supplied epoch-ms timestamp into a single-day chip.
     *
     * Numeral: locale-aware "dd" — zero-padded so single-digit dates emit
     *   "06", lining up visually with two-digit days on neighboring chips.
     * Month label: locale-aware "MMM", uppercased via [Locale.ROOT] (avoids
     *   the Turkish-i / dotless-i pitfall when the device locale differs).
     * Day-of-week label: locale-aware "EEE", uppercased via [Locale.ROOT].
     */
    fun format(timestampMs: Long, zone: ZoneId, locale: Locale): DateChipText.Single {
        val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        return formatSingle(date, locale)
    }

    /**
     * Format a multi-day range into a single horizontal chip label.
     *
     * Examples (en-US):
     *   same-month     → "MAY 05 – 08"
     *   cross-month    → "MAY 31 – JUN 03"
     *   cross-year     → "DEC 30 – JAN 02"
     *
     * If [startMs] and [endMs] fall on the same calendar day in [zone],
     * returns a [DateChipText.Single] instead so the caller doesn't have
     * to branch.
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

        // Same month: "MAY 05 – 08" — month appears once.
        // Cross-month: "MAY 31 – JUN 03" — both months appear.
        val label = if (startMonth == endMonth) {
            "$startMonth $startDay $EN_DASH $endDay"
        } else {
            "$startMonth $startDay $EN_DASH $endMonth $endDay"
        }
        return DateChipText.Range(label)
    }

    /**
     * Day-of-week range as it should appear in the body subtitle for a
     * multi-day event: "Sun – Wed", "Tue – Fri", etc.
     *
     * Title case (not all-caps) to differ from the chip label and read
     * as natural language in the body.
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
