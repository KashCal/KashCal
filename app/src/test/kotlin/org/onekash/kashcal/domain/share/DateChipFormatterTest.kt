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
        // "06" reads consistently next to "10" / "21" on multi-day chips
        // and matches calendar-grid-cell typography.
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
        // French short month for May is "mai" — uppercased becomes "MAI".
        assertEquals("31", out.numeral)
        assertEquals("MAI", out.monthLabel)
    }

    @Test
    fun `Turkish locale uppercase doesn't break — uses Locale-ROOT casing on the formatted value`() {
        // The formatted MMM in tr-TR could be "May." or similar. We uppercase
        // using Locale.ROOT so dotless-i mappings don't fight ASCII.
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.forLanguageTag("tr-TR"))
        // Whatever Turkish renders, uppercased via Locale.ROOT is deterministic
        // and contains only chars that won't break layout (tr 'i' → 'I' in ROOT).
        assertEquals(out.monthLabel, out.monthLabel.uppercase(Locale.ROOT))
        assertEquals(out.dayOfWeekLabel, out.dayOfWeekLabel.uppercase(Locale.ROOT))
    }

    @Test
    fun `Japanese locale produces a month label and dow label`() {
        // Japanese Locale: "5月" for May, "土" for Saturday short. Just verify
        // the formatter doesn't crash and the output is non-empty.
        val out = DateChipFormatter.format(ts(2026, 5, 31), nyc, Locale.JAPAN)
        assertEquals("31", out.numeral)
        // ja's short forms can be a single character or two — non-empty is enough.
        assertTrue(out.monthLabel.isNotEmpty())
        assertTrue(out.dayOfWeekLabel.isNotEmpty())
    }

    @Test
    fun `single-digit day formatted in zone, not UTC`() {
        // Late-evening UTC timestamp that's still the same day in NYC vs the
        // next day in UTC. Confirms we use the supplied zone.
        val sameDayInNyc = LocalDateTime.of(2026, 5, 31, 23, 30)
            .atZone(nyc)
            .toInstant()
            .toEpochMilli()
        val out = DateChipFormatter.format(sameDayInNyc, nyc, Locale.US)
        assertEquals("31", out.numeral)
    }

    // ============== formatRange — multi-day chips ==============

    @Test
    fun `formatRange same month emits zero-padded MMM DD – DD label`() {
        // May 5 09:00 - May 8 17:00. Padded so single-digit days line
        // up with two-digit ones across different events.
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
        // May 31 - Jun 3 in nyc — end day padded to "03".
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
        // Dec 30 - Jan 2 — dec→jan boundary. Year not shown in chip
        // (chats are usually about near-future events; recipient infers
        // year from context). End day padded to "02".
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
        // Defensive: when start and end fall on the same calendar day,
        // formatRange returns a Single rather than a Range with day == day.
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

    // ============== formatDowRange — body subtitle ==============

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
