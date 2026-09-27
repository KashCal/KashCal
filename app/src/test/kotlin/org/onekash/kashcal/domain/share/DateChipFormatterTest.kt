package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class DateChipFormatterTest {

    private val nyc: ZoneId = ZoneId.of("America/New_York")

    private fun ts(year: Int, month: Int, day: Int, time: LocalTime = LocalTime.NOON): Long =
        LocalDateTime.of(LocalDate.of(year, month, day), time).atZone(nyc).toInstant().toEpochMilli()

    @Test
    fun `en-US 31 May 2026 produces 31 MAY SUN single-day chip`() {
        // May 31, 2026 is a Sunday.
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.US)
        assertEquals("31", out.numeral)
        assertEquals("MAY", out.monthLabel)
        assertEquals("SUN", out.dayOfWeekLabel)
    }

    @Test
    fun `single-digit day is zero-padded to two digits on the single-day chip`() {
        // "06" lines up with "10" or "21" on multi-day chips and matches the calendar grid
        // cells.
        val out = DateChipFormatter.format(ts(2026, 5, 6), nyc, Locale.US)
        assertEquals("06", out.numeral)
    }

    @Test
    fun `single-digit day-1 is zero-padded`() {
        val out = DateChipFormatter.format(ts(2026, 5, 1), nyc, Locale.US)
        assertEquals("01", out.numeral)
    }

    @Test
    fun `fr-FR uses French locale month label and zero-pads`() {
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.FRANCE)
        // The French short month for May is "mai", uppercased to "MAI".
        assertEquals("31", out.numeral)
        assertEquals("MAI", out.monthLabel)
    }

    @Test
    fun `Turkish locale uppercase doesn't break — uses Locale-ROOT casing on the formatted value`() {
        // The formatted MMM in tr-TR could be "May." or similar. The formatter uppercases
        // with Locale.ROOT so the Turkish dotted and dotless i mapping doesn't apply.
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.forLanguageTag("tr-TR"))
        // Whatever Turkish renders, each label is already its Locale.ROOT uppercase (tr 'i'
        // becomes 'I' in ROOT).
        assertEquals(out.monthLabel, out.monthLabel.uppercase(Locale.ROOT))
        assertEquals(out.dayOfWeekLabel, out.dayOfWeekLabel.uppercase(Locale.ROOT))
    }

    @Test
    fun `Japanese locale produces a month label and dow label`() {
        // Japanese short forms are like "5月" for May and "土" for Saturday. Checks only that
        // the formatter doesn't crash and the labels aren't empty.
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.JAPAN)
        assertEquals("31", out.numeral)
        // ja's short forms can be one character or two, so non-empty is enough.
        assertTrue(out.monthLabel.isNotEmpty())
        assertTrue(out.dayOfWeekLabel.isNotEmpty())
    }

    @Test
    fun `single-digit day formatted in zone, not UTC`() {
        // 23:30 in NYC is already the next day in UTC, so "31" shows the supplied zone is
        // used.
        val sameDayInNyc = LocalDateTime.of(2026, 5, 31, 23, 30)
            .atZone(nyc)
            .toInstant()
            .toEpochMilli()
        val out = DateChipFormatter.format(sameDayInNyc, nyc, Locale.US)
        assertEquals("31", out.numeral)
    }

    // ============== formatRange: multi-day chips ==============

    @Test
    fun `formatRange same month emits zero-padded MMM DD – DD label`() {
        // May 5 09:00 - May 8 17:00. Padded so single-digit days line up with two-digit ones
        // across events.
        val out = DateChipFormatter.formatRange(
            startMs = ts(2026, 5, 5, LocalTime.of(9, 0)),
            endMs = ts(2026, 5, 8, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        ) as DateChipText.Range
        assertEquals("MAY 05 – 08", out.label)
    }

    @Test
    fun `formatRange cross-month emits zero-padded MMM DD – MMM DD label`() {
        // May 31 - Jun 3 in NYC; the end day is padded to "03".
        val out = DateChipFormatter.formatRange(
            startMs = ts(2026, 5, 31, LocalTime.of(9, 0)),
            endMs = ts(2026, 6, 3, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        ) as DateChipText.Range
        assertEquals("MAY 31 – JUN 03", out.label)
    }

    @Test
    fun `formatRange cross-year handles year boundary`() {
        // Dec 30 - Jan 2 across the year boundary. The chip shows no year (chats are usually
        // about near-future events; the recipient infers it). The end day is padded to "02".
        val out = DateChipFormatter.formatRange(
            startMs = ts(2026, 12, 30, LocalTime.of(9, 0)),
            endMs = ts(2027, 1, 2, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        ) as DateChipText.Range
        assertEquals("DEC 30 – JAN 02", out.label)
    }

    @Test
    fun `formatRange same start and end falls back to single-day chip`() {
        // When start and end fall on the same calendar day, formatRange returns a Single, not
        // a Range from a day to itself.
        val out = DateChipFormatter.formatRange(
            startMs = ts(2026, 5, 31, LocalTime.of(9, 0)),
            endMs = ts(2026, 5, 31, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        )
        assertTrue("expected Single, got $out", out is DateChipText.Single)
        out as DateChipText.Single
        assertEquals("31", out.numeral)
        assertEquals("MAY", out.monthLabel)
        assertEquals("SUN", out.dayOfWeekLabel)
    }

    // ============== formatDowRange: body subtitle ==============

    @Test
    fun `formatDowRange same month emits Tue – Fri`() {
        val out = DateChipFormatter.formatDowRange(
            startMs = ts(2026, 5, 5, LocalTime.of(9, 0)),
            endMs = ts(2026, 5, 8, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        )
        assertEquals("Tue – Fri", out)
    }

    @Test
    fun `formatDowRange cross-month emits Sun – Wed`() {
        val out = DateChipFormatter.formatDowRange(
            startMs = ts(2026, 5, 31, LocalTime.of(9, 0)),
            endMs = ts(2026, 6, 3, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        )
        assertEquals("Sun – Wed", out)
    }

    @Test
    fun `formatDowRange same-day collapses to a single day-of-week`() {
        val out = DateChipFormatter.formatDowRange(
            startMs = ts(2026, 5, 31, LocalTime.of(9, 0)),
            endMs = ts(2026, 5, 31, LocalTime.of(17, 0)),
            zone = nyc,
            locale = Locale.US,
        )
        assertEquals("Sun", out)
    }
}
