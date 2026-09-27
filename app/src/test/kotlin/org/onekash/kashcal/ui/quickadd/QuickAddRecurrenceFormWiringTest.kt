package org.onekash.kashcal.ui.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.QuickAddParser
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale

/**
 * Checks that an nth-weekday Quick Add phrase ("last Friday of every month") reaches the monthly
 * picker as the same pattern.
 *
 * QuickAddParser emits the RRULE and anchors the start date to the first real occurrence; the
 * RRULE flows verbatim into the event form, and RecurrencePicker seeds its selector from
 * [RruleBuilder.parseRrule]. These tests assert both ends without an emulator: the parser output
 * (RRULE and anchored start weekday) and what parseRrule returns for it.
 *
 * `QuickAddViewModel.toCalendarIntentData` passes `result.rrule` through verbatim and derives
 * `startTimeMillis` from the anchored `result.startDate`, so the parser assertions cover that hop
 * too; the ViewModel adds no recurrence logic.
 */
class QuickAddRecurrenceFormWiringTest {

    // Reference: Monday April 13, 2026, 10:00 AM (mirrors QuickAddParserTest).
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private var originalLocale: Locale? = null

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun `last Friday of every month emits BYDAY -1FR anchored on a Friday and parses to last Friday`() {
        val result = QuickAddParser.parse("rent last Friday of every month", reference)

        // Parser output: the rrule the ViewModel forwards verbatim, and a start date anchored to
        // the first real last Friday (Fri Apr 24, 2026).
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", result.rrule)
        assertEquals(DayOfWeek.FRIDAY, result.startDate.dayOfWeek)

        // What the picker seeds from: parseRrule of the forwarded rrule, with the anchored start
        // date's fields as fallback defaults. The parsed rule must win: NthWeekday(-1, FRIDAY),
        // not a guess from the start date.
        val startOrdinal = (result.startDate.dayOfMonth - 1) / 7 + 1
        val parsed = RruleBuilder.parseRrule(
            result.rrule,
            defaultWeekday = result.startDate.dayOfWeek,
            defaultDayOfMonth = result.startDate.dayOfMonth,
            defaultOrdinal = startOrdinal,
        )
        assertEquals(
            MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY),
            parsed.monthlyPattern,
        )
    }

    @Test
    fun `first Monday of the month emits BYDAY 1MO anchored on a Monday and parses to first Monday`() {
        val result = QuickAddParser.parse("book club first Monday of the month", reference)

        assertEquals("FREQ=MONTHLY;BYDAY=1MO", result.rrule)
        assertEquals(DayOfWeek.MONDAY, result.startDate.dayOfWeek)

        val parsed = RruleBuilder.parseRrule(
            result.rrule,
            defaultWeekday = result.startDate.dayOfWeek,
            defaultDayOfMonth = result.startDate.dayOfMonth,
            defaultOrdinal = (result.startDate.dayOfMonth - 1) / 7 + 1,
        )
        assertEquals(
            MonthlyPattern.NthWeekday(1, DayOfWeek.MONDAY),
            parsed.monthlyPattern,
        )
    }

    @Test
    fun `second Monday variant emits BYDAY 2MO and parses to second Monday`() {
        val result = QuickAddParser.parse("standup second Monday of every month", reference)

        assertEquals("FREQ=MONTHLY;BYDAY=2MO", result.rrule)
        assertEquals(DayOfWeek.MONDAY, result.startDate.dayOfWeek)

        val parsed = RruleBuilder.parseRrule(
            result.rrule,
            defaultWeekday = result.startDate.dayOfWeek,
            defaultDayOfMonth = result.startDate.dayOfMonth,
            defaultOrdinal = (result.startDate.dayOfMonth - 1) / 7 + 1,
        )
        assertEquals(
            MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY),
            parsed.monthlyPattern,
        )
    }
}
