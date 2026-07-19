package org.onekash.kashcal.domain.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

class QuickAddParserTest {

    // Reference: Monday April 13, 2026, 10:00 AM
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

    private fun parse(input: String) = QuickAddParser.parse(input, reference)

    // ==================== Full pipeline ====================

    @Test
    fun `Coffee with Sarah tomorrow at 3pm`() {
        val result = parse("Coffee with Sarah tomorrow at 3pm")
        assertEquals("Coffee with Sarah", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertEquals(false, result.isAllDay)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `tomorrow at 3pm Team standup`() {
        val result = parse("tomorrow at 3pm Team standup")
        assertEquals("Team standup", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `Dentist Jan 15 at 2pm`() {
        val result = parse("Dentist Jan 15 at 2pm")
        assertEquals("Dentist", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `input with only date and time has empty title`() {
        val result = parse("tomorrow at 3pm")
        assertEquals("", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `Doctor's appointment next Monday at 9am`() {
        val result = parse("Doctor's appointment next Monday at 9am")
        assertEquals("Doctor's appointment", result.title)
        assertEquals(LocalTime.of(9, 0), result.startTime)
    }

    // ==================== Confidence scoring ====================

    @Test
    fun `date plus time gives HIGH confidence`() {
        val result = parse("tomorrow at 3pm")
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `date only gives MEDIUM confidence`() {
        val result = parse("tomorrow")
        assertEquals(ParseConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `time only gives MEDIUM confidence`() {
        val result = parse("meeting at 3pm")
        assertEquals(ParseConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `neither date nor time gives LOW confidence`() {
        val result = parse("coffee with sarah")
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `rrule only gives MEDIUM confidence`() {
        val result = parse("weekly standup")
        assertEquals(ParseConfidence.MEDIUM, result.confidence)
        assertNotNull(result.rrule)
    }

    @Test
    fun `rrule plus date plus time still gives HIGH confidence`() {
        val result = parse("Standup every Monday at 10am")
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    // ==================== isAllDay ====================

    @Test
    fun `date only results in all day event`() {
        val result = parse("tomorrow")
        assertNull(result.startTime)
        assertTrue(result.isAllDay)
    }

    @Test
    fun `time present results in not all day`() {
        val result = parse("tomorrow at 3pm")
        assertEquals(false, result.isAllDay)
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty input returns LOW with reference date`() {
        val result = parse("")
        assertEquals("", result.title)
        assertEquals(reference.toLocalDate(), result.startDate)
        assertNull(result.startTime)
        assertTrue(result.isAllDay)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `whitespace only input returns LOW`() {
        val result = parse("   ")
        assertEquals("", result.title)
        assertEquals(reference.toLocalDate(), result.startDate)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `tab and newline input returns LOW`() {
        val result = parse("\t\n")
        assertEquals("", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `stop words only gives empty title`() {
        val result = parse("at the in on")
        assertEquals("", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `bare number 15 becomes part of title`() {
        val result = parse("15 things to do")
        assertTrue(result.title.contains("15"))
    }

    @Test
    fun `mixed case TOMORROW at 3PM parses correctly`() {
        val result = parse("TOMORROW at 3PM")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `extra whitespace in input normalizes in title`() {
        val result = parse("Coffee  with   Sarah   tomorrow")
        assertEquals("Coffee with Sarah", result.title)
    }

    @Test
    fun `special characters preserved in title`() {
        val result = parse("Doctor's appointment tomorrow")
        assertTrue(result.title.contains("Doctor's"))
    }

    @Test
    fun `re-schedule preserved in title`() {
        val result = parse("re-schedule tomorrow at 3pm")
        assertTrue(result.title.contains("re-schedule"))
    }

    @Test
    fun `emoji preserved in title`() {
        val result = parse("☕ Coffee tomorrow")
        assertTrue(result.title.contains("☕"))
        assertTrue(result.title.contains("Coffee"))
    }

    @Test
    fun `O'Brien preserved in title`() {
        val result = parse("Lunch with O'Brien tomorrow at noon")
        assertTrue(result.title.contains("O'Brien"))
    }

    @Test
    fun `year boundary January 5 from December resolves to next year`() {
        val decRef = LocalDateTime.of(2026, 12, 30, 10, 0)
        val result = QuickAddParser.parse("January 5", decRef)
        assertEquals(LocalDate.of(2027, 1, 5), result.startDate)
    }

    @Test
    fun `February 30 does not crash and falls back to reference`() {
        val result = parse("February 30")
        // Invalid date falls back to reference
        assertEquals(reference.toLocalDate(), result.startDate)
    }

    @Test
    fun `long input completes quickly`() {
        val longInput = "a]".repeat(500) + " tomorrow at 3pm"
        val start = System.nanoTime()
        val result = parse(longInput)
        val elapsed = (System.nanoTime() - start) / 1_000_000 // ms
        assertTrue("Parse took ${elapsed}ms, expected < 50ms", elapsed < 50)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    // ==================== Relative offsets in full pipeline ====================

    @Test
    fun `in 30 minutes from full pipeline`() {
        val result = parse("meeting in 30 minutes")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(10, 30), result.startTime)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `3 days ago from full pipeline`() {
        val result = parse("3 days ago")
        assertEquals(LocalDate.of(2026, 4, 10), result.startDate)
    }

    // ==================== Structured dates in full pipeline ====================

    @Test
    fun `Dentist 1 slash 15 at 2pm`() {
        val result = parse("Dentist 1/15 at 2pm")
        assertEquals("Dentist", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    // ==================== Locale-aware ambiguous dates (issue #194) ====================

    @Test
    fun `5 slash 10 slash 2026 under en_GB locale resolves to 5 October 2026`() {
        val result = QuickAddParser.parse("meeting 5/10/2026", reference, Locale.UK)
        assertEquals("meeting", result.title)
        assertEquals(LocalDate.of(2026, 10, 5), result.startDate)
    }

    @Test
    fun `5 slash 10 slash 2026 under en_US locale resolves to May 10 2026`() {
        val result = QuickAddParser.parse("meeting 5/10/2026", reference, Locale.US)
        assertEquals("meeting", result.title)
        assertEquals(LocalDate.of(2026, 5, 10), result.startDate)
    }

    @Test
    fun `13 slash 5 slash 2026 resolves to 13 May 2026 in any locale`() {
        assertEquals(LocalDate.of(2026, 5, 13),
            QuickAddParser.parse("meeting 13/5/2026", reference, Locale.US).startDate)
        assertEquals(LocalDate.of(2026, 5, 13),
            QuickAddParser.parse("meeting 13/5/2026", reference, Locale.UK).startDate)
    }

    @Test
    fun `5 slash 13 slash 2026 resolves to May 13 2026 in any locale`() {
        assertEquals(LocalDate.of(2026, 5, 13),
            QuickAddParser.parse("meeting 5/13/2026", reference, Locale.US).startDate)
        assertEquals(LocalDate.of(2026, 5, 13),
            QuickAddParser.parse("meeting 5/13/2026", reference, Locale.UK).startDate)
    }

    @Test
    fun `ISO 2026 dash 10 dash 05 resolves to 5 October 2026 in any locale`() {
        assertEquals(LocalDate.of(2026, 10, 5),
            QuickAddParser.parse("meeting 2026-10-05", reference, Locale.UK).startDate)
        assertEquals(LocalDate.of(2026, 10, 5),
            QuickAddParser.parse("meeting 2026-10-05", reference, Locale.US).startDate)
    }

    @Test
    fun `dot-separated 5 dot 10 dot 2026 always DMY regardless of locale`() {
        assertEquals(LocalDate.of(2026, 10, 5),
            QuickAddParser.parse("meeting 5.10.2026", reference, Locale.US).startDate)
        assertEquals(LocalDate.of(2026, 10, 5),
            QuickAddParser.parse("meeting 5.10.2026", reference, Locale.UK).startDate)
    }

    // ==================== Orphaned tokens preserved in title (issue #194 follow-up Bug D) ====================

    @Test
    fun `unclaimed MONTH appears in title`() {
        val result = parse("Discuss February budget tomorrow")
        assertEquals("Discuss February budget", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `unclaimed UNIT appears in title`() {
        val result = parse("Year end party tomorrow")
        assertEquals("Year end party", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `unclaimed MERIDIEM at start appears in title`() {
        val result = parse("AM coffee tomorrow")
        assertTrue("'AM' should be in title: '${result.title}'", result.title.contains("AM", ignoreCase = true))
        assertTrue("'coffee' should be in title", result.title.contains("coffee", ignoreCase = true))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `unclaimed TIMEZONE at start appears in title`() {
        val result = parse("EST specialist meeting Friday")
        assertTrue("'EST' should be in title: '${result.title}'", result.title.contains("EST", ignoreCase = true))
        assertTrue("'specialist meeting' should be in title", result.title.contains("specialist meeting", ignoreCase = true))
    }

    @Test
    fun `claimed DATE_KEYWORD all day stays out of title`() {
        val result = parse("Team outing all day")
        assertEquals("Team outing", result.title)
        assertTrue(result.isAllDay)
    }

    @Test
    fun `claimed WEEKDAY and DATE_KEYWORD stay out of title`() {
        val result = parse("tomorrow at 3pm Team standup")
        assertEquals("Team standup", result.title)
    }

    @Test
    fun `unclaimed WEEKDAY appears in title`() {
        // "Friday" in a non-date position — if no other date reference is present,
        // WeekdayRule will claim it as a date, so it should NOT leak. But for a
        // construction like "Happy Friday" (typo/name), the WEEKDAY is consumed.
        // This test documents the consumption invariant.
        val result = parse("Happy Friday")
        // WeekdayRule consumes Friday as the bare weekday date
        assertEquals("Happy", result.title)
    }

    // ==================== Dot-separated time ====================

    @Test
    fun `Coffee 3 dot 15pm extracts title Coffee and time 15 colon 15`() {
        val result = parse("Coffee 3.15pm")
        assertEquals("Coffee", result.title)
        assertEquals(LocalTime.of(15, 15), result.startTime)
        assertEquals(false, result.isAllDay)
    }

    @Test
    fun `Meeting tomorrow at 3 dot 30 pm extracts date and time`() {
        val result = parse("Meeting tomorrow at 3.30 pm")
        assertEquals("Meeting", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 30), result.startTime)
        assertEquals(false, result.isAllDay)
    }

    @Test
    fun `Dentist 15 dot 01 treats as date not time`() {
        val result = parse("Dentist 15.01")
        assertEquals("Dentist", result.title)
        assertTrue(result.isAllDay)
    }

    // ==================== P2: Duration ====================

    @Test
    fun `Meeting tomorrow at 2pm for 90 minutes`() {
        val result = parse("Meeting tomorrow at 2pm for 90 minutes")
        assertEquals("Meeting", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(15, 30), result.endTime)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `Meeting for 2 point 5 hours`() {
        val result = parse("Meeting for 2.5 hours")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(12, 30), result.endTime) // 10:00 + 2.5h
    }

    @Test
    fun `Party for Sarah tomorrow at 7pm for 3 hours`() {
        val result = parse("Party for Sarah tomorrow at 7pm for 3 hours")
        assertEquals("Party for Sarah", result.title)
        assertEquals(LocalTime.of(19, 0), result.startTime)
        assertEquals(LocalTime.of(22, 0), result.endTime)
    }

    @Test
    fun `no duration clause - endTime is null`() {
        val result = parse("Meeting tomorrow at 3pm")
        assertNull(result.endTime)
    }

    // ==================== P2: Time ranges ====================

    @Test
    fun `Meeting 2-3pm`() {
        val result = parse("Meeting 2-3pm")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(15, 0), result.endTime)
    }

    @Test
    fun `Meeting 2pm to 4pm`() {
        val result = parse("Meeting 2pm to 4pm")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(16, 0), result.endTime)
    }

    @Test
    fun `Meeting 10 colon 30-11 colon 30am`() {
        val result = parse("Meeting 10:30-11:30am")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(10, 30), result.startTime)
        assertEquals(LocalTime.of(11, 30), result.endTime)
    }

    @Test
    fun `Meeting 5pm-6 parses as TIME_RANGE with inherited pm`() {
        val result = parse("Meeting 5pm-6")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(17, 0), result.startTime)
        assertEquals(LocalTime.of(18, 0), result.endTime)
    }

    // ==================== P2: Location ====================

    @Test
    fun `Coffee at Blue Bottle tomorrow at 3pm`() {
        val result = parse("Coffee at Blue Bottle tomorrow at 3pm")
        assertEquals("Coffee", result.title)
        assertEquals("Blue Bottle", result.location)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `Meeting tomorrow at 3pm at Conference Room B`() {
        val result = parse("Meeting tomorrow at 3pm at Conference Room B")
        assertEquals("Meeting", result.title)
        assertEquals("Conference Room B", result.location)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `no at location gives null location`() {
        val result = parse("Meeting tomorrow at 3pm")
        assertNull(result.location)
    }

    // ==================== P2: Combined features ====================

    @Test
    fun `full pipeline - duration plus location`() {
        val result = parse("Meeting tomorrow at 2pm for 90 minutes at Conference Room")
        assertEquals("Meeting", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(15, 30), result.endTime)
        assertEquals("Conference Room", result.location)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `full pipeline - time range plus location`() {
        val result = parse("Coffee 2-3pm at Blue Bottle")
        assertEquals("Coffee", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(15, 0), result.endTime)
        assertEquals("Blue Bottle", result.location)
    }

    @Test
    fun `Party for Sarah tomorrow at 7pm for 3 hours at The Grand`() {
        val result = parse("Party for Sarah tomorrow at 7pm for 3 hours at The Grand")
        assertEquals("Party for Sarah", result.title)
        assertEquals(LocalTime.of(19, 0), result.startTime)
        assertEquals(LocalTime.of(22, 0), result.endTime)
        assertEquals("The Grand", result.location)
    }

    // ==================== P3: Recurrence ====================

    @Test
    fun `Team standup every Monday at 10am`() {
        val result = parse("Team standup every Monday at 10am")
        assertEquals("Team standup", result.title)
        assertEquals(LocalTime.of(10, 0), result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("BYDAY=MO"))
        // Next Monday from April 13 (Monday) is April 20
        assertEquals(LocalDate.of(2026, 4, 20), result.startDate)
    }

    @Test
    fun `Daily standup at 9am`() {
        val result = parse("Daily standup at 9am")
        assertEquals("standup", result.title)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=DAILY"))
    }

    @Test
    fun `Review every 2 weeks on Friday at 3pm`() {
        val result = parse("Review every 2 weeks on Friday at 3pm")
        assertEquals("Review", result.title)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("INTERVAL=2"))
        assertTrue(result.rrule!!.contains("BYDAY=FR"))
    }

    @Test
    fun `no recurrence keywords gives null rrule`() {
        val result = parse("Meeting tomorrow at 3pm")
        assertNull(result.rrule)
    }

    @Test
    fun `gym every Monday and Wednesday - multi-weekday recurrence with clean title`() {
        val result = parse("gym every Monday and Wednesday")
        assertEquals("gym", result.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", result.rrule)
    }

    @Test
    fun `standup every Monday Wednesday and Friday - three weekdays`() {
        val result = parse("standup every Monday, Wednesday, and Friday")
        assertEquals("standup", result.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", result.rrule)
    }

    @Test
    fun `standup MWF at 9am - shorthand recurrence`() {
        val result = parse("standup MWF at 9am")
        assertEquals("standup", result.title)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", result.rrule)
    }

    @Test
    fun `class TTh - shorthand two weekdays`() {
        val result = parse("class TTh")
        assertEquals("class", result.title)
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", result.rrule)
    }

    @Test
    fun `team sync every other week - interval 2 with clean title`() {
        val result = parse("team sync every other week")
        assertEquals("team sync", result.title)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", result.rrule)
    }

    @Test
    fun `standup every other day - daily interval 2`() {
        val result = parse("standup every other day")
        assertEquals("standup", result.title)
        assertEquals("FREQ=DAILY;INTERVAL=2", result.rrule)
    }

    @Test
    fun `book club first Monday of the month - monthly nth weekday`() {
        val result = parse("book club first Monday of the month")
        assertEquals("book club", result.title)
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", result.rrule)
        // April's 1st Monday (Apr 6) is past the reference Mon Apr 13, so the start
        // date anchors on May's 1st Monday, May 4 — not today.
        assertEquals(LocalDate.of(2026, 5, 4), result.startDate)
    }

    @Test
    fun `rent last Friday of every month - monthly last weekday`() {
        val result = parse("rent last Friday of every month")
        assertEquals("rent", result.title)
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", result.rrule)
        // Last Friday of April 2026 (Apr 24) is on/after the reference.
        assertEquals(LocalDate.of(2026, 4, 24), result.startDate)
    }

    @Test
    fun `pay rent on the 15th of every month - monthly by date`() {
        val result = parse("pay rent on the 15th of every month")
        assertEquals("pay rent", result.title)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", result.rrule)
        // April 15 is on/after the reference Mon Apr 13.
        assertEquals(LocalDate.of(2026, 4, 15), result.startDate)
    }

    @Test
    fun `rent last day of every month - monthly last day`() {
        val result = parse("rent last day of every month")
        assertEquals("rent", result.title)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", result.rrule)
        // Reference month (April 2026) ends on the 30th.
        assertEquals(LocalDate.of(2026, 4, 30), result.startDate)
    }

    @Test
    fun `rent last Friday of this month - one-off in current month with clean title`() {
        // "of this month" (not "the"/"every") means a single occurrence in the
        // current month, NOT a recurrence — and the phrase must be fully consumed
        // so "month" doesn't leak into the title.
        val result = parse("rent last Friday of this month")
        assertEquals("rent", result.title)
        assertNull(result.rrule)
        // Last Friday of April 2026 is Apr 24.
        assertEquals(LocalDate.of(2026, 4, 24), result.startDate)
    }

    @Test
    fun `15th of this month - one-off on the current month's 15th`() {
        val result = parse("15th of this month")
        assertNull(result.rrule)
        assertEquals(LocalDate.of(2026, 4, 15), result.startDate)
    }

    @Test
    fun `pay rent 31st of this month clamps to month end with a clean title`() {
        // February 2026 has 28 days: "31st of this month" must stay in the month
        // (clamp to Feb 28), not roll forward, and must not leak "of this month"
        // into the title.
        val febRef = LocalDateTime.of(2026, 2, 10, 10, 0)
        val result = QuickAddParser.parse("pay rent 31st of this month", febRef)
        assertEquals("pay rent", result.title)
        assertNull(result.rrule)
        assertEquals(LocalDate.of(2026, 2, 28), result.startDate)
    }

    @Test
    fun `fifth Friday of this month falls back to the last Friday in a month without one`() {
        // February 2026 has only four Fridays; stay in the month on the last one.
        val febRef = LocalDateTime.of(2026, 2, 10, 10, 0)
        val result = QuickAddParser.parse("fifth Friday of this month", febRef)
        assertNull(result.rrule)
        assertEquals(LocalDate.of(2026, 2, 27), result.startDate)
    }

    @Test
    fun `15th of March stays a one-off absolute date not monthly recurrence`() {
        val result = parse("15th of March")
        assertNull(result.rrule)
        assertEquals(3, result.startDate.monthValue)
        assertEquals(15, result.startDate.dayOfMonth)
    }

    @Test
    fun `drinks this weekend - date set with clean title`() {
        val result = parse("drinks this weekend")
        assertEquals("drinks", result.title)
        assertEquals(LocalDate.of(2026, 4, 18), result.startDate)
    }

    @Test
    fun `trip next weekend - Saturday after`() {
        val result = parse("trip next weekend")
        assertEquals("trip", result.title)
        assertEquals(LocalDate.of(2026, 4, 25), result.startDate)
    }

    @Test
    fun `every weekend becomes weekly recurrence not a one-off`() {
        val result = parse("brunch every weekend")
        assertEquals("brunch", result.title)
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", result.rrule)
    }

    @Test
    fun `standup at 1500 - compact 24h time`() {
        val result = parse("standup at 1500")
        assertEquals("standup", result.title)
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `call at 0930 - compact time with leading zero`() {
        val result = parse("call at 0930")
        assertEquals("call", result.title)
        assertEquals(LocalTime.of(9, 30), result.startTime)
    }

    @Test
    fun `meeting 15h30 - h-notation time`() {
        val result = parse("meeting 15h30")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(15, 30), result.startTime)
    }

    @Test
    fun `lunch 9h - h-notation on the hour`() {
        val result = parse("lunch 9h")
        assertEquals(LocalTime.of(9, 0), result.startTime)
    }

    @Test
    fun `Jan 5 2027 still parses year not compact time`() {
        val result = parse("trip Jan 5 2027")
        assertEquals(2027, result.startDate.year)
        assertEquals(1, result.startDate.monthValue)
        assertEquals(5, result.startDate.dayOfMonth)
    }

    @Test
    fun `every Monday until 2027 leaves 2027 as year not a time`() {
        val result = parse("standup every Monday until 2027")
        assertNull(result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `meeting for half an hour - 30 minute duration`() {
        val result = parse("meeting at 2pm for half an hour")
        assertEquals(LocalTime.of(14, 0), result.startTime)
        assertEquals(LocalTime.of(14, 30), result.endTime)
    }

    @Test
    fun `lunch in a couple hours - fuzzy forward offset`() {
        // Reference Monday April 13, 10:00 → +2h = 12:00.
        val result = parse("lunch in a couple hours")
        assertEquals(LocalTime.of(12, 0), result.startTime)
    }

    @Test
    fun `review 3 days from now - forward offset`() {
        val result = parse("review 3 days from now")
        assertEquals(LocalDate.of(2026, 4, 16), result.startDate)
    }

    @Test
    fun `gym after work - part of day time cue`() {
        val result = parse("gym after work")
        assertEquals("gym", result.title)
        assertEquals(LocalTime.of(18, 0), result.startTime)
    }

    @Test
    fun `weekly standup - standalone keyword consumed from title`() {
        val result = parse("weekly standup")
        assertEquals("standup", result.title)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
    }

    @Test
    fun `every day gives FREQ=DAILY`() {
        val result = parse("every day")
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=DAILY"))
    }

    @Test
    fun `biweekly gives INTERVAL=2`() {
        val result = parse("biweekly sync")
        assertEquals("sync", result.title)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("INTERVAL=2"))
    }

    // ==================== P3: Emoji ====================

    @Test
    fun `Coffee with Sarah tomorrow at 3pm has coffee emoji`() {
        val result = parse("Coffee with Sarah tomorrow at 3pm")
        assertEquals("\u2615", result.emoji) // ☕
    }

    @Test
    fun `Meeting tomorrow at 2pm has no emoji`() {
        val result = parse("Meeting tomorrow at 2pm")
        assertNull(result.emoji)
    }

    // ==================== P3: Combined P2 + P3 ====================

    @Test
    fun `Coffee every Monday at 3pm for 1 hour at Blue Bottle`() {
        val result = parse("Coffee every Monday at 3pm for 1 hour at Blue Bottle")
        assertEquals("Coffee", result.title)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertEquals(LocalTime.of(16, 0), result.endTime)
        assertEquals("Blue Bottle", result.location)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("BYDAY=MO"))
        assertEquals("\u2615", result.emoji) // ☕
    }

    // ==================== P4: Quarter/half time ====================

    @Test
    fun `meeting quarter past 3 pm`() {
        val result = parse("meeting quarter past 3 pm")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(15, 15), result.startTime)
    }

    @Test
    fun `meeting half past 10`() {
        val result = parse("meeting half past 10")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(10, 30), result.startTime)
    }

    @Test
    fun `meeting quarter to 4`() {
        val result = parse("meeting quarter to 4")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(3, 45), result.startTime)
    }

    // ==================== P4: All day keyword ====================

    @Test
    fun `Team outing all day`() {
        val result = parse("Team outing all day")
        assertEquals("Team outing", result.title)
        assertTrue(result.isAllDay)
        assertEquals(reference.toLocalDate(), result.startDate)
    }

    @Test
    fun `all day meeting tomorrow`() {
        val result = parse("all day meeting tomorrow")
        assertEquals("meeting", result.title)
        assertTrue(result.isAllDay)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `all day alone`() {
        val result = parse("all day")
        assertEquals("", result.title)
        assertTrue(result.isAllDay)
    }

    // ==================== P4: Fuzzy time keywords ====================

    @Test
    fun `meeting morning`() {
        val result = parse("meeting morning")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(8, 0), result.startTime)
    }

    @Test
    fun `meeting this afternoon`() {
        val result = parse("meeting this afternoon")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `dinner this evening`() {
        val result = parse("dinner this evening")
        assertEquals("dinner", result.title)
        assertEquals(LocalTime.of(18, 0), result.startTime)
    }

    @Test
    fun `party tonight`() {
        val result = parse("party tonight")
        assertEquals("party", result.title)
        assertEquals(LocalTime.of(20, 0), result.startTime)
        assertEquals(reference.toLocalDate(), result.startDate)
    }

    @Test
    fun `meeting at night`() {
        val result = parse("meeting at night")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(20, 0), result.startTime)
    }

    // ==================== P4: Every weekday ====================

    @Test
    fun `standup every weekday`() {
        val result = parse("standup every weekday")
        assertEquals("standup", result.title)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("BYDAY=MO,TU,WE,TH,FR"))
    }

    @Test
    fun `every weekday at 9am`() {
        val result = parse("every weekday at 9am")
        assertEquals("", result.title)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("BYDAY=MO,TU,WE,TH,FR"))
    }

    @Test
    fun `weekdays standup`() {
        val result = parse("weekdays standup")
        assertEquals("standup", result.title)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("BYDAY=MO,TU,WE,TH,FR"))
    }

    // ==================== Multi-day events ====================

    @Test
    fun `Conference Friday to Sunday`() {
        val result = parse("Conference Friday to Sunday")
        assertEquals("Conference", result.title)
        // Monday Apr 13 → Friday Apr 17
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
        // endDate: Sunday Apr 19
        assertEquals(LocalDate.of(2026, 4, 19), result.endDate)
    }

    @Test
    fun `Trip Monday to Wednesday`() {
        val result = parse("Trip Monday to Wednesday")
        assertEquals("Trip", result.title)
        // Monday Apr 13 → next Monday Apr 20 (bare weekday same-day advances 7)
        assertEquals(LocalDate.of(2026, 4, 20), result.startDate)
        assertEquals(LocalDate.of(2026, 4, 22), result.endDate)
    }

    @Test
    fun `Party Saturday to Sunday`() {
        val result = parse("Party Saturday to Sunday")
        assertEquals("Party", result.title)
        assertEquals(LocalDate.of(2026, 4, 18), result.startDate)
        assertEquals(LocalDate.of(2026, 4, 19), result.endDate)
    }

    @Test
    fun `bare weekday has no endDate`() {
        val result = parse("meeting Friday")
        assertNull(result.endDate)
    }

    // ==================== Timezone recognition ====================

    @Test
    fun `meeting at 3pm EST`() {
        val result = parse("meeting at 3pm EST")
        assertEquals("meeting", result.title)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertEquals("America/New_York", result.timezone)
    }

    @Test
    fun `call 10am PST`() {
        val result = parse("call 10am PST")
        assertEquals("call", result.title)
        assertEquals(LocalTime.of(10, 0), result.startTime)
        assertEquals("America/Los_Angeles", result.timezone)
    }

    @Test
    fun `standup 9am UTC`() {
        val result = parse("standup 9am UTC")
        assertEquals("standup", result.title)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertEquals("UTC", result.timezone)
    }

    @Test
    fun `meeting at 3pm has null timezone`() {
        val result = parse("meeting at 3pm")
        assertNull(result.timezone)
    }

    @Test
    fun `timezone not in title`() {
        val result = parse("meeting 3pm EST")
        assertEquals("meeting", result.title)
    }

    // ==================== Non-English input fallback ====================
    // Parser is English-only. Non-English input degrades gracefully:
    // unrecognized words become the title, date falls back to reference.
    // English keywords (dates, times, structured dates) still parse when
    // mixed with non-English text.

    @Test
    fun `German input falls back to title`() {
        val result = parse("Kaffee morgen um 15 Uhr")
        assertEquals("Kaffee morgen um 15 Uhr", result.title)
        assertEquals(reference.toLocalDate(), result.startDate)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `French input preserves accented characters`() {
        val result = parse("Réunion demain matin")
        assertEquals("Réunion demain matin", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Japanese CJK input without spaces becomes title`() {
        val result = parse("明日の会議")
        assertEquals("明日の会議", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Arabic RTL input preserved as title`() {
        val result = parse("اجتماع غدا")
        assertEquals("اجتماع غدا", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Korean input preserved as title`() {
        val result = parse("내일 회의")
        assertEquals("내일 회의", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `non-English title with English date and time still parses`() {
        val result = parse("Kaffee tomorrow at 3pm")
        assertEquals("Kaffee", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(15, 0), result.startTime)
        assertEquals(ParseConfidence.HIGH, result.confidence)
    }

    @Test
    fun `Cyrillic with structured date parses date`() {
        val result = parse("Дзвінок 15.04")
        assertTrue(result.title.startsWith("Дзвінок"))
        assertEquals(true, result.isAllDay)
    }

    @Test
    fun `emoji preserved in non-English input`() {
        val result = parse("🎂 Geburtstag morgen")
        assertTrue(result.title.contains("🎂"))
        assertTrue(result.title.contains("Geburtstag"))
    }

    // ==================== Non-English adverse: keyword collisions ====================
    // English keywords that are real words in other languages get consumed,
    // corrupting titles. These tests document current (known-imperfect) behavior.

    @Test
    fun `Hungarian article a is replaced with 1 by NumberWordNormalizer`() {
        // Hungarian: "a megbeszélés" = "the meeting"
        // NumberWordNormalizer maps standalone "a" → "1"
        val result = parse("a megbeszélés")
        assertEquals("1 megbeszélés", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Portuguese a in title corrupted by NumberWordNormalizer`() {
        // Portuguese: "Reunião a tarde" = "Meeting in the afternoon"
        val result = parse("Reunião a tarde")
        assertEquals("Reunião 1 tarde", result.title)
    }

    @Test
    fun `Spanish preposition a corrupted by NumberWordNormalizer`() {
        // Spanish: "Ir a la tienda" = "Go to the store"
        val result = parse("Ir a la tienda")
        assertTrue(result.title.contains("1"))
    }

    @Test
    fun `Irish article an is replaced with 1`() {
        // Irish: "an cruinniú" = "the meeting"
        val result = parse("an cruinniú")
        assertEquals("1 cruinniú", result.title)
    }

    @Test
    fun `Norwegian for consumed as duration keyword`() {
        // Norwegian: "møte for teamet" = "meeting for the team"
        // "for" is a keyword (FOR), consumed by DurationRule or left as keyword
        val result = parse("møte for teamet")
        // "for" gets consumed as KEYWORD, title drops leading/trailing keywords
        assertTrue(result.title.contains("møte"))
    }

    @Test
    fun `English in consumed as keyword from mixed input`() {
        // "in" is keyword IN, consumed by RelativeOffsetRule
        val result = parse("Termin in Berlin")
        // "in" consumed as keyword, "Berlin" may become part of title
        assertNotNull(result.title)
        assertTrue(result.title.isNotEmpty())
    }

    // ==================== Non-English adverse: non-ASCII digit systems ====================
    // \d in regex only matches ASCII 0-9. Non-ASCII digits are preserved by \p{N}
    // in CHAR_CLEANUP but treated as UNKNOWN tokens, not parsed as numbers/times.

    private fun assertNonAsciiDigitNotParsedAsTime(input: String, digit: String) {
        val result = parse(input)
        assertTrue("Expected '$digit' preserved in title: ${result.title}", result.title.contains(digit))
        assertNull(result.startTime)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Devanagari digits not recognized as numbers`() {
        assertNonAsciiDigitNotParsedAsTime("बैठक ३ बजे", "३")
    }

    @Test
    fun `Arabic-Indic digits not recognized as numbers`() {
        assertNonAsciiDigitNotParsedAsTime("اجتماع الساعة ٣", "٣")
    }

    @Test
    fun `Extended Arabic-Indic digits for Farsi not recognized`() {
        assertNonAsciiDigitNotParsedAsTime("جلسه ۳ عصر", "۳")
    }

    @Test
    fun `Thai digits not recognized as numbers`() {
        assertNonAsciiDigitNotParsedAsTime("ประชุม ๓ โมง", "๓")
    }

    @Test
    fun `full-width digits not recognized as numbers`() {
        assertNonAsciiDigitNotParsedAsTime("会議３時", "３")
    }

    @Test
    fun `Bengali digits not recognized as numbers`() {
        assertNonAsciiDigitNotParsedAsTime("সভা ৫টায়", "৫")
    }

    // ==================== Non-English adverse: Unicode normalization ====================

    @Test
    fun `decomposed unicode accent stripped by CHAR_CLEANUP`() {
        // NFD: "café" as "cafe" + U+0301 (combining acute)
        // CHAR_CLEANUP strips combining marks (\p{Mn} not in keep-list)
        val decomposed = "cafe\u0301 tomorrow"
        val result = parse(decomposed)
        // Accent is lost — title becomes "cafe"
        assertEquals("cafe", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `composed unicode accent preserved`() {
        // NFC: "café" as single character é (U+00E9)
        val composed = "caf\u00E9 tomorrow"
        val result = parse(composed)
        assertEquals("café", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `Vietnamese diacritics preserved when composed`() {
        // Vietnamese: "họp" (meeting) — composed form
        val result = parse("họp ngày mai")
        assertTrue(result.title.contains("họp"))
    }

    @Test
    fun `German umlauts preserved`() {
        val result = parse("Büro Termin übermorgen")
        assertTrue(result.title.contains("ü"))
        assertTrue(result.title.contains("ö") || result.title.contains("Büro"))
    }

    @Test
    fun `Turkish dotted and dotless i preserved`() {
        // Turkish: İ (dotted capital), ı (dotless lowercase)
        val result = parse("İstanbul toplantısı")
        assertTrue(result.title.isNotEmpty())
    }

    // ==================== Non-English adverse: special Unicode characters ====================

    @Test
    fun `zero-width joiner stripped between emoji`() {
        // ZWJ (U+200D) is \p{Cf}, stripped by CHAR_CLEANUP
        val input = "👨\u200D💼 meeting tomorrow"
        val result = parse(input)
        // ZWJ stripped, emoji may split but are preserved individually
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `right-to-left mark stripped`() {
        // RLM (U+200F) is \p{Cf}, stripped by CHAR_CLEANUP
        val result = parse("meeting\u200F tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertTrue(result.title.contains("meeting"))
    }

    @Test
    fun `left-to-right mark stripped`() {
        val result = parse("اجتماع\u200E meeting")
        assertTrue(result.title.isNotEmpty())
    }

    @Test
    fun `null byte in input does not crash`() {
        val result = parse("meeting\u0000tomorrow")
        // Null byte (U+0000) is \p{Cc}, stripped by CHAR_CLEANUP → space
        assertNotNull(result)
    }

    @Test
    fun `control characters stripped gracefully`() {
        // Form feed, vertical tab, bell
        val result = parse("meeting\u000C\u000B\u0007 tomorrow")
        assertNotNull(result)
        assertTrue(result.title.contains("meeting"))
    }

    @Test
    fun `soft hyphen stripped`() {
        // Soft hyphen U+00AD is \p{Cf}, stripped
        val result = parse("meet\u00ADing tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    // ==================== Non-English adverse: mixed LTR/RTL ====================

    @Test
    fun `Arabic text with English time still parses time`() {
        // Arabic + English time keyword
        val result = parse("اجتماع at 3pm")
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `Hebrew text with English date parses date`() {
        val result = parse("פגישה tomorrow at 2pm")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(14, 0), result.startTime)
    }

    @Test
    fun `mixed Arabic and English preserves both in title`() {
        val result = parse("اجتماع مع Ahmed")
        assertTrue(result.title.contains("اجتماع"))
        assertTrue(result.title.contains("Ahmed"))
    }

    // ==================== Non-English adverse: punctuation stripping ====================

    @Test
    fun `guillemets stripped from French text`() {
        // « » are not in \p{L}\p{N}\p{So}\s or allowed punctuation
        val result = parse("«Réunion» tomorrow")
        // Guillemets stripped, accents preserved
        assertTrue(result.title.contains("Réunion"))
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `parentheses stripped from German text`() {
        val result = parse("Termin (wichtig) tomorrow")
        assertTrue(result.title.contains("Termin"))
        assertTrue(result.title.contains("wichtig"))
    }

    @Test
    fun `comma stripped but words preserved`() {
        // Japanese: "明日、会議です" — comma is Japanese U+3001
        val result = parse("明日、会議です")
        assertNotNull(result.title)
        assertTrue(result.title.isNotEmpty())
    }

    @Test
    fun `exclamation and question marks stripped`() {
        val result = parse("Important meeting! tomorrow?")
        assertTrue(result.title.contains("Important"))
        assertTrue(result.title.contains("meeting"))
    }

    // ==================== Non-English adverse: structured dates with scripts ====================

    @Test
    fun `structured date with dot separator works in any language context`() {
        // European D.M format
        val result = parse("Встреча 25.12")
        assertTrue(result.title.startsWith("Встреча"))
        assertTrue(result.isAllDay)
    }

    @Test
    fun `structured date with slash works in any language context`() {
        val result = parse("미팅 1/15")
        assertTrue(result.title.contains("미팅"))
    }

    @Test
    fun `ISO date works in any language context`() {
        val result = parse("会議 2026-05-15")
        assertTrue(result.title.contains("会議"))
    }

    // ==================== Non-English adverse: robustness and DoS ====================

    @Test
    fun `very long CJK input completes without crash`() {
        val longCjk = "会議".repeat(5000) + " tomorrow"
        val start = System.nanoTime()
        val result = parse(longCjk)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        assertTrue("CJK parse took ${elapsed}ms, expected < 200ms", elapsed < 200)
    }

    @Test
    fun `very long Arabic input completes without crash`() {
        val longArabic = "اجتماع ".repeat(1000)
        val start = System.nanoTime()
        val result = parse(longArabic)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        assertTrue("Arabic parse took ${elapsed}ms, expected < 500ms", elapsed < 500)
    }

    @Test
    fun `repeated hyphens in compound word do not cause ReDoS`() {
        // Adversarial: tries to trigger backtracking in NumberWordNormalizer compoundRegex
        val input = "twenty-" + "e".repeat(10000)
        val start = System.nanoTime()
        val result = parse(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        assertTrue("Compound ReDoS took ${elapsed}ms, expected < 200ms", elapsed < 200)
    }

    @Test
    fun `single character input does not crash`() {
        for (c in listOf("a", "1", "é", "明", "ع", "🎂", ".", "-", " ")) {
            val result = parse(c)
            assertNotNull(result)
        }
    }

    @Test
    fun `only punctuation returns empty title`() {
        val result = parse("!@#\$%^&*()")
        // All stripped by CHAR_CLEANUP → empty after whitespace trim
        assertEquals("", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `mixed emoji sequence preserved`() {
        val result = parse("🇩🇪 Treffen 🍺 morgen")
        assertTrue(result.title.contains("🍺") || result.title.contains("Treffen"))
    }

    @Test
    fun `surrogate pair emoji does not crash`() {
        // 🦊 (U+1F98A) is outside BMP, encoded as surrogate pair in UTF-16
        val result = parse("🦊 event tomorrow")
        assertNotNull(result)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    // ==================== Non-English adverse: number word collisions ====================

    @Test
    fun `ten is replaced in non-English context`() {
        // NumberWordNormalizer replaces "ten" → "10"
        // Could match in words like "often" — but \b prevents mid-word match
        val result = parse("Often meeting tomorrow")
        assertTrue(result.title.contains("Often"))
        // "ten" inside "Often" should NOT be replaced due to \b word boundary
    }

    @Test
    fun `four not replaced inside fourteen`() {
        // "fourteen" → "14" (entire word match), not "4teen"
        val result = parse("fourteen people tomorrow")
        assertTrue(result.title.contains("14"))
    }

    @Test
    fun `number word replacement does not corrupt am in word`() {
        // "a.m." should not become "1.m." due to negative lookahead (?![.])
        val result = parse("meeting at 3 a.m. tomorrow")
        assertEquals(LocalTime.of(3, 0), result.startTime)
    }

    // ==================== #tag extraction ====================

    @Test
    fun `single hashtag is extracted into categories and dropped from title`() {
        val result = parse("#work Lunch")
        assertEquals(listOf("work"), result.categories)
        assertEquals("Lunch", result.title)
    }

    @Test
    fun `multiple hashtags are all extracted`() {
        val result = parse("Lunch #work #urgent")
        assertEquals(listOf("work", "urgent"), result.categories)
        assertEquals("Lunch", result.title)
    }

    @Test
    fun `hashtag in the middle preserves surrounding title words`() {
        val result = parse("Call #work Bob tomorrow")
        assertEquals(listOf("work"), result.categories)
        assertTrue(result.title.contains("Call"))
        assertTrue(result.title.contains("Bob"))
    }

    @Test
    fun `unicode hashtags are captured`() {
        val result = parse("Dinner #café #日本語")
        assertEquals(listOf("café", "日本語"), result.categories)
    }

    @Test
    fun `bare hash is not treated as a tag`() {
        val result = parse("Meet # now")
        assertEquals(emptyList<String>(), result.categories)
    }

    @Test
    fun `over-length hashtag is not silently truncated into a tag`() {
        val long = "a".repeat(70)
        val result = parse("Lunch #$long")
        // Rejected by the validator, not clipped to a 64-char tag.
        assertEquals(emptyList<String>(), result.categories)
    }

    @Test
    fun `hashtags survive alongside date and time parsing`() {
        val result = parse("Standup #work tomorrow at 9am")
        assertEquals(listOf("work"), result.categories)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertTrue(result.title.contains("Standup"))
    }

    // ==================== Inline note via " //" delimiter ====================

    @Test
    fun `note after slashes is extracted and event parsed from the part before`() {
        val result = parse("Lunch with Sam tomorrow 1pm // bring the signed contract")
        assertEquals("Lunch with Sam", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(13, 0), result.startTime)
        assertEquals("bring the signed contract", result.note)
    }

    @Test
    fun `no delimiter gives null note`() {
        val result = parse("Coffee tomorrow at 3pm")
        assertNull(result.note)
    }

    @Test
    fun `date words inside the note do not move the event schedule`() {
        val result = parse("Standup tomorrow at 9am // remind him about Friday at 5pm")
        // Schedule comes only from the part before " //".
        assertEquals("Standup", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        // The note's "Friday"/"5pm" must NOT have hijacked the date/time.
        assertEquals("remind him about Friday at 5pm", result.note)
    }

    @Test
    fun `note is split on the first delimiter only`() {
        val result = parse("Meeting tomorrow // discuss A // then B")
        assertEquals("Meeting", result.title)
        assertEquals("discuss A // then B", result.note)
    }

    @Test
    fun `note preserves original casing and punctuation verbatim`() {
        val result = parse("Call Bob tomorrow // Bring the PROPOSAL, v2 & notes!")
        assertEquals("Bring the PROPOSAL, v2 & notes!", result.note)
    }

    @Test
    fun `note surrounding whitespace is trimmed`() {
        val result = parse("Call Bob tomorrow //    padded note   ")
        assertEquals("padded note", result.note)
    }

    @Test
    fun `https url is not split because there is no whitespace before the slashes`() {
        val result = parse("Join call https://zoom.us/j/123 tomorrow at 3pm")
        assertNull(result.note)
        assertTrue("URL should stay in the title: '${result.title}'", result.title.contains("https://zoom.us/j/123"))
        assertEquals(LocalTime.of(15, 0), result.startTime)
    }

    @Test
    fun `bare double slash without preceding whitespace stays in the title`() {
        val result = parse("zoom.us//x tomorrow")
        assertNull(result.note)
        assertTrue(result.title.contains("zoom.us//x"))
    }

    @Test
    fun `delimiter with nothing after it produces no note and is dropped from title`() {
        val result = parse("Coffee tomorrow //")
        assertNull(result.note)
        assertEquals("Coffee", result.title)
        assertFalse("delimiter must not leak into title: '${result.title}'", result.title.contains("//"))
    }

    @Test
    fun `delimiter with only whitespace after it produces no note`() {
        val result = parse("Coffee tomorrow //    ")
        assertNull(result.note)
        assertEquals("Coffee", result.title)
    }

    @Test
    fun `note-only input has empty title and captured note`() {
        val result = parse(" // just a note")
        assertEquals("", result.title)
        assertEquals("just a note", result.note)
    }

    @Test
    fun `hashtag inside the note is not extracted as a category and stays verbatim`() {
        val result = parse("Lunch tomorrow // ping #work about it")
        assertEquals(emptyList<String>(), result.categories)
        assertEquals("ping #work about it", result.note)
        assertEquals("Lunch", result.title)
    }

    @Test
    fun `tab before slashes also delimits the note`() {
        val result = parse("Coffee tomorrow\t// tabbed note")
        assertEquals("tabbed note", result.note)
        assertEquals("Coffee", result.title)
    }
}
