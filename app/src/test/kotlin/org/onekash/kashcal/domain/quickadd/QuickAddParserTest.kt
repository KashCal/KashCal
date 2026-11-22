package org.onekash.kashcal.domain.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
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
    fun `an article in the title stays a word`() {
        // The parser reads "a" and "an" as 1 so it can parse "in an hour"; the title must still
        // show the words that were typed.
        val result = parse("Become a morning person Monday 5am")
        assertEquals("Become a morning person", result.title)
        assertEquals(LocalDate.of(2026, 4, 20), result.startDate)
        assertEquals(LocalTime.of(5, 0), result.startTime)
    }

    @Test
    fun `number words in the title stay words`() {
        assertEquals("Lunch with two friends", parse("Lunch with two friends tomorrow at noon").title)
        assertEquals("Call an electrician", parse("Call an electrician Friday").title)
    }

    @Test
    fun `number words in a location stay words`() {
        assertEquals("Twenty-One Club", parse("Dinner at Twenty-One Club at 8pm").location)
    }

    @Test
    fun `an article in a duration is still read as one`() {
        val result = parse("Standup tomorrow at 9am for an hour")
        assertEquals("Standup", result.title)
        assertEquals(LocalTime.of(10, 0), result.endTime)
    }

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
        // A date that doesn't exist sets nothing, so the date is the reference date.
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

    // ==================== Unclaimed tokens stay in the title (issue #194) ====================

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
    fun `a zone abbreviation at the start appears in the title`() {
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
        // WeekdayRule claims a bare weekday as the date, so "Friday" leaves the title even in a
        // phrase like "Happy Friday".
        val result = parse("Happy Friday")
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

    // ==================== Duration ====================

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

    // ==================== Time ranges ====================

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

    // ==================== Location ====================

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

    // ==================== Combined features ====================

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

    // ==================== Recurrence ====================

    @Test
    fun `Team standup every Monday at 10am`() {
        val result = parse("Team standup every Monday at 10am")
        assertEquals("Team standup", result.title)
        assertEquals(LocalTime.of(10, 0), result.startTime)
        assertNotNull(result.rrule)
        assertTrue(result.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(result.rrule!!.contains("BYDAY=MO"))
        // The reference is Monday April 13, so the first Monday after it is April 20.
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
        // April's 1st Monday (Apr 6) is before the reference Mon Apr 13, so the start date is
        // May's 1st Monday, May 4, not today.
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
        // "of this month", unlike "of the" or "of every" month, is one occurrence in the current
        // month, not a recurrence. The whole phrase is consumed, so "month" stays out of the
        // title.
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
        // February 2026 has 28 days: "31st of this month" clamps to Feb 28 instead of rolling
        // into March, and "of this month" stays out of the title.
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
        // Reference Monday April 13, 10:00, plus 2h is 12:00.
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

    // ==================== Emoji ====================

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

    // ==================== Emoji with duration, location and recurrence ====================

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

    // ==================== Quarter past, half past, quarter to ====================

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

    // ==================== All day keyword ====================

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

    // ==================== Fuzzy time keywords ====================

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

    // ==================== Every weekday ====================

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
        // Monday Apr 13 → next Monday Apr 20 (a bare weekday on the same day is a week later)
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

    // ==================== Zone abbreviations ====================

    @Test
    fun `zone abbreviations are not read and stay in the title`() {
        // A typed zone is a word; times use the device zone.
        val est = parse("meeting at 3pm EST")
        assertEquals("meeting EST", est.title)
        assertEquals(LocalTime.of(15, 0), est.startTime)
        assertEquals("Call pst", parse("Call at 3pm pst").title)
        assertEquals("standup UTC", parse("standup 9am UTC").title)
        assertEquals("meeting est", parse("meeting 3pm est").title)
        assertEquals("sync gmt", parse("sync 9am gmt").title)
    }

    // ==================== Non-English input fallback ====================
    // The parser is English-only. Unrecognized words become the title and the date is the
    // reference date. English dates, times and structured dates still parse when mixed with
    // non-English text.

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
    // English words that are also words in other languages: NumberWordNormalizer reads "a"
    // and "an" as 1 for the rules, and the title still shows them as typed.

    @Test
    fun `Hungarian article a stays in the title`() {
        // Hungarian: "a megbeszélés" = "the meeting"
        val result = parse("a megbeszélés")
        assertEquals("a megbeszélés", result.title)
        assertEquals(ParseConfidence.LOW, result.confidence)
    }

    @Test
    fun `Portuguese a stays in the title`() {
        // Portuguese: "Reunião a tarde" = "Meeting in the afternoon"
        val result = parse("Reunião a tarde")
        assertEquals("Reunião a tarde", result.title)
    }

    @Test
    fun `Spanish preposition a stays in the title`() {
        // Spanish: "Ir a la tienda" = "Go to the store"
        val result = parse("Ir a la tienda")
        assertEquals("Ir a la tienda", result.title)
    }

    @Test
    fun `Irish article an stays in the title`() {
        // Irish: "an cruinniú" = "the meeting"
        val result = parse("an cruinniú")
        assertEquals("an cruinniú", result.title)
    }

    @Test
    fun `Norwegian for consumed as duration keyword`() {
        // Norwegian: "møte for teamet" = "meeting for the team"
        // "for" is a KEYWORD. DurationRule takes it only before a number and a unit, so it
        // stays in the title (not asserted here).
        val result = parse("møte for teamet")
        assertTrue(result.title.contains("møte"))
    }

    @Test
    fun `English in consumed as keyword from mixed input`() {
        // "in" is a KEYWORD. RelativeOffsetRule takes it only before a number and a unit, so
        // the title keeps "in" and "Berlin" (not asserted here).
        val result = parse("Termin in Berlin")
        assertNotNull(result.title)
        assertTrue(result.title.isNotEmpty())
    }

    // ==================== Non-English adverse: non-ASCII digit systems ====================
    // \d matches only ASCII 0-9. CHAR_CLEANUP keeps other digits (\p{N}), but they tokenize as
    // UNKNOWN, never as numbers or times.

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
    fun `decomposed unicode accent stays in the title as typed`() {
        // NFD: "café" as "cafe" + U+0301 (combining acute). CHAR_CLEANUP turns the combining
        // mark into a space for the rules, but the title is cut from the typed text.
        val decomposed = "cafe\u0301 tomorrow"
        val result = parse(decomposed)
        assertEquals("cafe\u0301", result.title)
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
        // Vietnamese: "họp" (meeting), composed form
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
    fun `zero-width joiner stays inside an emoji in the title`() {
        // ZWJ (U+200D) is \p{Cf}: CHAR_CLEANUP splits the emoji for the rules, but the title is
        // cut from the typed text and keeps the joined emoji.
        val input = "👨\u200D💼 meeting tomorrow"
        val result = parse(input)
        assertEquals("👨\u200D💼 meeting", result.title)
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
    }

    @Test
    fun `right-to-left mark stripped`() {
        // RLM (U+200F) is \p{Cf}, which CHAR_CLEANUP replaces with a space.
        val result = parse("meeting\u200F tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        assertEquals("meeting", result.title)
    }

    @Test
    fun `left-to-right mark stripped`() {
        val result = parse("اجتماع\u200E meeting")
        assertTrue(result.title.isNotEmpty())
    }

    @Test
    fun `null byte in input does not crash`() {
        val result = parse("meeting\u0000tomorrow")
        // Null byte (U+0000) is \p{Cc}, which CHAR_CLEANUP replaces with a space.
        assertEquals("meeting", result.title)
    }

    @Test
    fun `control characters stripped gracefully`() {
        // Form feed, vertical tab, bell
        val result = parse("meeting\u000C\u000B\u0007 tomorrow")
        assertNotNull(result)
        assertEquals("meeting", result.title)
    }

    @Test
    fun `soft hyphen stripped`() {
        // Soft hyphen U+00AD is \p{Cf}, which CHAR_CLEANUP replaces with a space.
        val result = parse("meet\u00ADing tomorrow")
        assertEquals(LocalDate.of(2026, 4, 14), result.startDate)
        // The invisible soft hyphen is dropped from the title, not turned into a space.
        assertEquals("meeting", result.title)
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
        // The guillemets go and the accents stay.
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
        // Japanese: "明日、会議です"; the comma is the ideographic comma U+3001.
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
        // Tries to trigger backtracking in NumberWordNormalizer's compoundRegex.
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
        // CHAR_CLEANUP replaces them all with spaces, which the whitespace trim removes.
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
        // NumberWordNormalizer replaces "ten" with "10", but \b stops a match inside a word
        // like "often". The title copy skips number words, so check the normalizer itself.
        assertEquals("often meeting tomorrow", NormalizerChain().normalize("Often meeting tomorrow"))
        val result = parse("Often meeting tomorrow")
        assertTrue(result.title.contains("Often"))
    }

    @Test
    fun `four not replaced inside fourteen`() {
        // "fourteen" is read as one number, never as "4teen"; the title shows the typed word.
        assertEquals("14 people tomorrow", NormalizerChain().normalize("fourteen people tomorrow"))
        val result = parse("fourteen people tomorrow")
        assertEquals("fourteen people", result.title)
    }

    @Test
    fun `number word replacement does not corrupt am in word`() {
        // The (?![.]) lookahead keeps "a.m." from becoming "1.m.".
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
        // The note's "Friday" and "5pm" don't change the date or time.
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

    // ==================== Typed text in the title and location ====================

    @Test
    fun `a phrase inside a word is not joined`() {
        // "c-all day" must not be read as the "all day" phrase, or "day after tomorrow" is lost.
        val result = parse("call day after tomorrow")
        assertEquals(LocalDate.of(2026, 4, 15), result.startDate)
        assertEquals("call", result.title)

        val timed = parse("Call day after tomorrow 3pm")
        assertEquals(LocalDate.of(2026, 4, 15), timed.startDate)
        assertEquals(LocalTime.of(15, 0), timed.startTime)
        assertEquals("Call", timed.title)
    }

    @Test
    fun `titles keep words that contain a joined phrase`() {
        assertEquals("Install day", parse("Install day tomorrow 9am").title)
        assertEquals("Football day", parse("Football day tomorrow").title)
        assertEquals("Small day trip", parse("Small day trip Saturday").title)
        assertEquals("Recall day", parse("Recall day Friday").title)
        assertEquals("Meeting after workshop", parse("Meeting after workshop tomorrow").title)
        assertEquals("Hall Day Spa", parse("Lunch at Hall Day Spa").location)
    }

    @Test
    fun `joined phrases still work as whole words`() {
        assertTrue(parse("Offsite all day Friday").isAllDay)
        assertEquals("Offsite", parse("Offsite all day Friday").title)
        assertEquals(LocalTime.of(18, 0), parse("Drinks after work").startTime)
        assertEquals(LocalTime.of(14, 30), parse("Call half past 2 pm").startTime)
        assertEquals(LocalTime.of(3, 15), parse("Call quarter past 3").startTime)
        assertEquals(LocalTime.of(4, 45), parse("Call quarter to 5").startTime)
        assertEquals(LocalDate.of(2026, 4, 15), parse("Gym day after tomorrow").startDate)
        assertEquals(LocalDate.of(2026, 4, 11), parse("Gym day before yesterday").startDate)
    }

    @Test
    fun `unconsumed rewritten words show as typed`() {
        assertEquals(
            "Dinner with a couple of friends",
            parse("Dinner with a couple of friends Friday").title
        )
        assertEquals("Read half an hour of Dickens", parse("Read half an hour of Dickens").title)
        assertEquals("Become a morning person", parse("Become a morning person Monday 5am").title)
        assertEquals("Twenty-One Club", parse("Dinner at Twenty-One Club at 8pm").location)
    }

    @Test
    fun `characters between kept words stay as typed`() {
        assertEquals("Bob & Sue's BBQ (backyard)", parse("Bob & Sue's BBQ (backyard) Friday").title)
    }

    @Test
    fun `separators left by a cut are tidied`() {
        assertEquals("Dinner", parse("Dinner, tomorrow at 7pm").title)
        assertEquals("Lunch with Sam", parse("Lunch with Sam, Friday, at noon").title)
        assertEquals("Call mom", parse("Call mom @ 5pm").title)
        assertEquals("Important meeting!", parse("Important meeting! tomorrow?").title)
        assertEquals("Lunch", parse("Lunch (Friday)").title)
        assertEquals("Party", parse("Party \"tomorrow\"").title)
        assertEquals("Lunch with Sam Jr.", parse("Lunch with Sam Jr. tomorrow").title)
        assertEquals("Movie !10", parse("Movie tomorrow at 11 for 1.5h !10").title)
    }

    @Test
    fun `whitespace and control characters in the title are tidied`() {
        assertEquals("Lunch with Sam", parse("Lunch  \twith\r Sam tomorrow").title)
        assertEquals("Lunch with Sam", parse("Lunch\u0000with Sam tomorrow").title)
        assertEquals("", parse(", ; tomorrow at 3pm").title)
    }

    @Test
    fun `emoji in the title survive whole`() {
        assertEquals("\uD83D\uDC4D\uD83C\uDFFD Party", parse("\uD83D\uDC4D\uD83C\uDFFD Party Friday").title)
        // Scotland's flag: a black flag, tag characters (format characters), then a cancel tag.
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals("$scotland Rugby", parse("$scotland Rugby Saturday").title)
    }

    @Test
    fun `the location is the typed text of its words`() {
        assertEquals("O'Brien's Pub", parse("Coffee at O'Brien's Pub tomorrow at 3pm").location)
        assertEquals("Bob & Sue's", parse("Dinner at Bob & Sue's tomorrow").location)
        assertEquals("Week Street Cafe", parse("Coffee at Week Street Cafe").location)
    }

    @Test
    fun `date and time words inside a location are cut out of it`() {
        assertEquals("The Pub with Bob", parse("Drinks at The Pub tonight with Bob").location)
        assertEquals("Cafe Luna with Sam", parse("Lunch at Cafe Luna 12:30 with Sam").location)
        assertEquals("Blue Bottle downtown", parse("Coffee at Blue Bottle tomorrow downtown").location)
    }

    @Test
    fun `tags and notes keep working with typed titles`() {
        val result = parse("#work lunch tomorrow // bring #notes")
        assertEquals(listOf("work"), result.categories)
        assertEquals("bring #notes", result.note)
        assertEquals("lunch", result.title)

        val longTag = "#" + "x".repeat(65)
        assertEquals("$longTag lunch", parse("$longTag lunch tomorrow").title)
    }

    @Test
    fun `edge keywords are still dropped from the title`() {
        assertEquals("meeting", parse("meeting at 3pm").title)
        assertEquals("", parse("at the in on").title)
    }

    // ==================== Series end ====================

    @Test
    fun `until the end of the month leaves the title clean`() {
        val result = parse("standup every weekday until the end of the month")
        assertEquals("standup", result.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260430", result.rrule)
    }

    // ==================== Bare hours and day-half words ====================

    private fun assertTimed(input: String, date: LocalDate, time: LocalTime, title: String) {
        val result = parse(input)
        assertEquals("$input: date", date, result.startDate)
        assertEquals("$input: time", time, result.startTime)
        assertEquals("$input: title", title, result.title)
    }

    private val today = LocalDate.of(2026, 4, 13)
    private val tomorrow = LocalDate.of(2026, 4, 14)

    @Test
    fun `a bare hour after at is taken as typed`() {
        assertTimed("Dinner at 8", today, LocalTime.of(8, 0), "Dinner")
        assertNull(parse("Dinner at 8").location)
        assertTimed("Standup at 9", today, LocalTime.of(9, 0), "Standup")
        assertTimed("call at 3", today, LocalTime.of(3, 0), "call")
        assertTimed("Review at 13", today, LocalTime.of(13, 0), "Review")
        assertTimed("Backup at 0", today, LocalTime.of(0, 0), "Backup")
        assertTimed("standup tomorrow at 9", tomorrow, LocalTime.of(9, 0), "standup")
    }

    @Test
    fun `a number after at with a word after it stays in the location`() {
        for ((input, place, title) in listOf(
            Triple("Party at 10 Downing Street", "10 Downing Street", "Party"),
            Triple("Dinner at 21 Club", "21 Club", "Dinner"),
            Triple("Meeting at 3 Main St tomorrow", "3 Main St", "Meeting"),
        )) {
            val result = parse(input)
            assertEquals("$input: location", place, result.location)
            assertEquals("$input: title", title, result.title)
            assertNull("$input: time", result.startTime)
        }
        assertEquals(tomorrow, parse("Meeting at 3 Main St tomorrow").startDate)
        assertTimed("Meet at 5 o'clock", today, LocalTime.of(5, 0), "Meet")
        assertTimed("Dinner at 8 tomorrow", tomorrow, LocalTime.of(8, 0), "Dinner")
        // A word right after the number makes it a place.
        assertNull(parse("Dinner at 8 with Sam").startTime)
    }

    @Test
    fun `a bare hour keeps its time when a date, emoji or hour word follows it`() {
        assertTimed("Dinner at 8 tomorrow with Sam", tomorrow, LocalTime.of(8, 0), "Dinner with Sam")
        assertTimed("Call at 8 sharp", today, LocalTime.of(8, 0), "Call")
        assertTimed("Meet at 5 o\u2019clock", today, LocalTime.of(5, 0), "Meet")
        assertEquals(LocalTime.of(8, 0), parse("Party at 8 \uD83C\uDF89").startTime)
    }

    @Test
    fun `a spelled-out bare hour is not an hour`() {
        val result = parse("Dinner at eight")
        assertNull(result.startTime)
        assertEquals("eight", result.location)
    }

    @Test
    fun `a lone hour never becomes the location`() {
        val coffee = parse("coffee at 9 at Blue Bottle")
        assertEquals(LocalTime.of(9, 0), coffee.startTime)
        assertEquals("Blue Bottle", coffee.location)
        assertEquals("coffee", coffee.title)

        val kids = parse("pick up kids at 3 tomorrow")
        assertEquals(tomorrow, kids.startDate)
        assertEquals(LocalTime.of(3, 0), kids.startTime)
        assertNull(kids.location)
    }

    @Test
    fun `articles and vague counts after at are not hours`() {
        val cafe = parse("Lunch at a cafe tomorrow")
        assertNull(cafe.startTime)
        assertEquals("a cafe", cafe.location)
        assertEquals("an Irish pub", parse("Coffee at an Irish pub").location)
        val bars = parse("Drinks at a few bars Friday")
        assertNull(bars.startTime)
        assertEquals("a few bars", bars.location)
    }

    @Test
    fun `an explicit time wins over a bare at-hour`() {
        val result = parse("Dinner at 7pm at 8 Main St")
        assertEquals(LocalTime.of(19, 0), result.startTime)
        assertEquals("8 Main St", result.location)
    }

    @Test
    fun `a day-half word makes a bare hour pm`() {
        assertTimed("party tomorrow evening at 9", tomorrow, LocalTime.of(21, 0), "party")
        assertTimed("lunch tomorrow afternoon at 1", tomorrow, LocalTime.of(13, 0), "lunch")
        assertTimed("dinner this evening at 7", today, LocalTime.of(19, 0), "dinner")
        assertTimed("Dinner tonight at 8", today, LocalTime.of(20, 0), "Dinner")
        assertTimed("dinner at 8 tonight", today, LocalTime.of(20, 0), "dinner")
        assertNull(parse("dinner at 8 tonight").location)
        assertTimed("tonight at 8:30 movie", today, LocalTime.of(20, 30), "movie")
        assertTimed("Drinks quarter past six tonight", today, LocalTime.of(18, 15), "Drinks")
        assertTimed("Show tonight at 8.15", today, LocalTime.of(20, 15), "Show")
    }

    @Test
    fun `a day-half word leaves morning, am-pm and 24-hour times alone`() {
        assertTimed("breakfast tomorrow morning at 8", tomorrow, LocalTime.of(8, 0), "breakfast")
        assertTimed("party this evening at 9pm", today, LocalTime.of(21, 0), "party")
        assertTimed("flight tonight at 7am", today, LocalTime.of(7, 0), "flight")
        assertTimed("show tonight at 20:00", today, LocalTime.of(20, 0), "show")
        assertTimed("call this evening at 12", today, LocalTime.of(12, 0), "call")
        assertTimed("train tonight at 0830", today, LocalTime.of(8, 30), "train")
        assertTimed("train tonight at 08:30", today, LocalTime.of(8, 30), "train")
        // A pm day-half word moves any hour from 1 to 11.
        assertTimed("call tonight at 1", today, LocalTime.of(13, 0), "call")
    }

    @Test
    fun `an anchored day-half word never leaks into the title`() {
        assertTimed("Dinner tonight at 8pm", today, LocalTime.of(20, 0), "Dinner")
        assertTimed("call this afternoon at 3", today, LocalTime.of(15, 0), "call")
        assertTimed("dinner at 8 in the evening", today, LocalTime.of(20, 0), "dinner")
        assertTimed("meds at 10 at night", today, LocalTime.of(22, 0), "meds")
        val water = parse("meds at 10 at night with water")
        assertEquals(LocalTime.of(22, 0), water.startTime)
        assertEquals("meds with water", water.title)
        assertNull(water.location)
        assertTimed("dinner at 8 in the evening with Sam", today, LocalTime.of(20, 0), "dinner with Sam")
        assertEquals(LocalTime.of(20, 0), parse("dinner tonight").startTime)
        assertEquals("dinner", parse("dinner tonight").title)
    }

    @Test
    fun `a day-half word inside the title stays in the title`() {
        assertEquals("Become a morning person", parse("Become a morning person Monday 5am").title)
        assertEquals("Morning standup", parse("Morning standup at 9am").title)
        assertEquals("Evening class", parse("Evening class tomorrow 7pm").title)
        assertEquals("Date night", parse("Date night friday at 7pm").title)
        assertEquals("Game night", parse("Game night at 7pm").title)
        assertTimed("Movie night at 8", today, LocalTime.of(20, 0), "Movie night")
        assertTimed("Evening class at 7", today, LocalTime.of(19, 0), "Evening class")
        assertTimed("6am Morning run", today, LocalTime.of(6, 0), "Morning run")
        assertTimed("7pm night shift", today, LocalTime.of(19, 0), "night shift")
        assertTimed("Evening class tomorrow morning at 8", tomorrow, LocalTime.of(8, 0), "Evening class")
    }

    @Test
    fun `a day-half word after a date is anchored`() {
        assertTimed("Dinner Aug 5 evening at 7", LocalDate.of(2026, 8, 5), LocalTime.of(19, 0), "Dinner")
    }

    @Test
    fun `without a time a day-half word keeps its default`() {
        assertEquals(LocalTime.of(8, 0), parse("meeting morning").startTime)
        val game = parse("Game night Friday")
        assertEquals(LocalTime.of(20, 0), game.startTime)
        assertEquals("Game", game.title)
    }

    @Test
    fun `every morning and every evening repeat daily`() {
        val yoga = parse("Yoga every morning at 7")
        assertEquals("FREQ=DAILY", yoga.rrule)
        assertEquals(LocalTime.of(7, 0), yoga.startTime)
        assertEquals("Yoga", yoga.title)
        val walk = parse("walk every evening at 6")
        assertEquals("FREQ=DAILY", walk.rrule)
        assertEquals(LocalTime.of(18, 0), walk.startTime)
        assertEquals("walk", walk.title)
        val evening = parse("journal every evening")
        assertEquals("FREQ=DAILY", evening.rrule)
        assertEquals(LocalTime.of(18, 0), evening.startTime)
        val meds = parse("meds every night at 10")
        assertEquals("FREQ=DAILY", meds.rrule)
        assertEquals(LocalTime.of(22, 0), meds.startTime)
    }

    @Test
    fun `a dotted time after at is a time`() {
        assertTimed("meeting at 9.30", today, LocalTime.of(9, 30), "meeting")
        assertTimed("meeting at 1.10", today, LocalTime.of(1, 10), "meeting")
        // One-digit minutes and a dotted pair without "at" keep their date reading.
        assertEquals(LocalDate.of(2026, 5, 1), parse("meeting at 1.5").startDate)
        assertEquals(LocalDate.of(2027, 3, 15), parse("trip 15.03").startDate)
        assertEquals(LocalDate.of(2026, 12, 25), parse("Party at 25.12").startDate)
    }

    @Test
    fun `existing as-typed forms stay`() {
        assertEquals(LocalTime.of(6, 15), parse("call at quarter past six").startTime)
        assertEquals(LocalTime.of(9, 30), parse("call at 9 30").startTime)
        assertEquals(LocalTime.of(15, 0), parse("call at 1500").startTime)
        assertEquals("Room 101", parse("Meeting at Room 101 at 3pm").location)
    }

    @Test
    fun `the bare-hour guard skips consumed words after the hour`() {
        val result = parse("Standup at 9 June 5")
        assertEquals(LocalDate.of(2026, 6, 5), result.startDate)
        assertEquals(LocalTime.of(9, 0), result.startTime)
        assertNull(result.location)
    }

    // ==================== Time ranges ====================

    private fun assertRange(input: String, start: LocalTime, end: LocalTime, title: String) {
        val result = parse(input)
        assertEquals("$input: start", start, result.startTime)
        assertEquals("$input: end", end, result.endTime)
        assertEquals("$input: title", title, result.title)
    }

    @Test
    fun `a spaced dash between two times is a range`() {
        assertRange("meeting 3pm - 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "meeting")
        assertRange("meeting 15:00 - 16:30", LocalTime.of(15, 0), LocalTime.of(16, 30), "meeting")
    }

    @Test
    fun `a glued 24-hour range with minutes is a range`() {
        assertRange("meeting 15:00-16:30", LocalTime.of(15, 0), LocalTime.of(16, 30), "meeting")
        assertRange("9:00-10:30 standup", LocalTime.of(9, 0), LocalTime.of(10, 30), "standup")
    }

    @Test
    fun `a bare start borrows the end's am or pm`() {
        assertRange("Call from 3 to 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "Call")
        assertRange("Call 3 to 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "Call")
        assertRange("Workshop from 10 to 2pm", LocalTime.of(10, 0), LocalTime.of(14, 0), "Workshop")
        assertRange("Call 3 - 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "Call")
    }

    @Test
    fun `a range without am or pm ends after its start`() {
        assertRange("Shift from 9 to 5", LocalTime.of(9, 0), LocalTime.of(17, 0), "Shift")
        assertRange("Shift from 13 to 15", LocalTime.of(13, 0), LocalTime.of(15, 0), "Shift")
        assertRange("Meeting at 9 to 10", LocalTime.of(9, 0), LocalTime.of(10, 0), "Meeting")
    }

    @Test
    fun `a day-half word applies to a range`() {
        assertRange("dinner tonight from 7 to 9", LocalTime.of(19, 0), LocalTime.of(21, 0), "dinner")
        assertRange("Game night from 7 to 10", LocalTime.of(19, 0), LocalTime.of(22, 0), "Game night")
        // The end that comes soonest after the start wins, past midnight if need be.
        assertRange("party tonight from 9 to 1", LocalTime.of(21, 0), LocalTime.of(1, 0), "party")
    }

    @Test
    fun `words typed as numbers are not range hours`() {
        val friend = parse("Coffee from a friend at 3pm")
        assertEquals(LocalTime.of(15, 0), friend.startTime)
        assertNull(friend.endTime)
        assertEquals("Coffee from a friend", friend.title)
        assertNull(parse("notes from a to b").endTime)
    }

    @Test
    fun `existing range forms are unchanged`() {
        assertRange("meeting 3-5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "meeting")
        assertRange("meeting 3pm-5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "meeting")
        assertRange("meeting 3pm to 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "meeting")
        assertRange("meeting from 3pm to 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "meeting")
        assertRange("meeting 5pm-6", LocalTime.of(17, 0), LocalTime.of(18, 0), "meeting")
        assertRange("party 11pm-1am", LocalTime.of(23, 0), LocalTime.of(1, 0), "party")
        assertRange("meeting 10:30-11:30am", LocalTime.of(10, 30), LocalTime.of(11, 30), "meeting")
        assertRange("Review from 3pm to 5pm with Bob", LocalTime.of(15, 0), LocalTime.of(17, 0), "Review with Bob")
    }

    // ==================== Calendar-date ranges ====================

    private fun assertDayRange(input: String, start: LocalDate, end: LocalDate, title: String) {
        val result = parse(input)
        assertEquals("$input: start", start, result.startDate)
        assertEquals("$input: end", end, result.endDate)
        assertTrue("$input: all-day", result.isAllDay)
        assertEquals("$input: title", title, result.title)
    }

    @Test
    fun `calendar-date ranges make multi-day events`() {
        val aug23 = LocalDate.of(2026, 8, 23)
        val aug28 = LocalDate.of(2026, 8, 28)
        assertDayRange("Conference Aug 23 to 28", aug23, aug28, "Conference")
        assertDayRange("Conference Aug 23-28", aug23, aug28, "Conference")
        assertDayRange("Conference Aug 23 - 28", aug23, aug28, "Conference")
        assertDayRange("Trip 23-28 August", aug23, aug28, "Trip")
        assertDayRange("Trip 23 to 28 August", aug23, aug28, "Trip")
        assertDayRange("Trip 23 - 28 August", aug23, aug28, "Trip")
        assertDayRange("Offsite Aug 30 to Sep 2", LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2), "Offsite")
        assertDayRange("Trip from Aug 23 to 28 with kids", aug23, aug28, "Trip with kids")
    }

    @Test
    fun `a time after a day range end keeps its time reading`() {
        val result = parse("Meeting Aug 3 to 5 pm")
        assertEquals(LocalDate.of(2026, 8, 3), result.startDate)
        assertEquals(LocalTime.of(17, 0), result.startTime)
        assertEquals("Meeting", result.title)
    }

    // ==================== Glued and compound durations ====================

    @Test
    fun `glued durations set the length with or without for`() {
        val two = LocalTime.of(14, 0)
        assertRange("workshop tomorrow 2pm 2h", two, LocalTime.of(16, 0), "workshop")
        assertRange("workshop tomorrow 2pm for 45m", two, LocalTime.of(14, 45), "workshop")
        assertRange("workshop tomorrow 2pm 2h45m", two, LocalTime.of(16, 45), "workshop")
        assertRange("workshop tomorrow 2pm for 90min", two, LocalTime.of(15, 30), "workshop")
        assertRange("workshop tomorrow 2pm 2hrs", two, LocalTime.of(16, 0), "workshop")
        assertRange("Movie tomorrow at 11 for 1.5h", LocalTime.of(11, 0), LocalTime.of(12, 30), "Movie")
        assertNull(parse("Movie tomorrow at 11 for 1.5h").location)
    }

    @Test
    fun `an h-notation length after for or another time is a duration`() {
        assertRange("Movie tomorrow at 11 for 2h", LocalTime.of(11, 0), LocalTime.of(13, 0), "Movie")
        assertNull(parse("Movie tomorrow at 11 for 2h").location)
        assertRange("meeting at 9 2h", LocalTime.of(9, 0), LocalTime.of(11, 0), "meeting")
        // With no other time, h-notation is still a time of day.
        assertEquals(LocalTime.of(9, 0), parse("9h meeting").startTime)
        assertEquals(LocalTime.of(15, 30), parse("meeting 15h30").startTime)
    }

    @Test
    fun `spelled-out lengths add up`() {
        val two = LocalTime.of(14, 0)
        assertRange("workshop tomorrow 2pm for an hour and a half", two, LocalTime.of(15, 30), "workshop")
        assertRange("workshop tomorrow 2pm for 2 hours 30 minutes", two, LocalTime.of(16, 30), "workshop")
        assertRange("workshop tomorrow 2pm for 2 hours and 30 minutes", two, LocalTime.of(16, 30), "workshop")
        assertRange("workshop tomorrow 2pm for 2 and a half hours", two, LocalTime.of(16, 30), "workshop")
        assertRange("workshop tomorrow 2pm for 1h 30m", two, LocalTime.of(15, 30), "workshop")
    }

    @Test
    fun `a glued length without a start time needs for`() {
        val glued = parse("Gym for 45m")
        val spelled = parse("Gym for 45 minutes")
        assertEquals(spelled.startTime, glued.startTime)
        assertEquals(spelled.endTime, glued.endTime)
        assertEquals(spelled.isAllDay, glued.isAllDay)
        assertEquals("Gym", glued.title)
        assertEquals("Olympic 100m final", parse("Olympic 100m final tomorrow").title)
        val swim = parse("Swim 400m tomorrow 6am")
        assertEquals(LocalTime.of(6, 0), swim.startTime)
        assertNull(swim.endTime)
        assertEquals("Swim 400m", swim.title)
        assertEquals("Olympic 100m final", parse("Olympic 100m final tomorrow 7pm").title)
    }

    @Test
    fun `spelled-out durations are unchanged`() {
        val nine = LocalTime.of(9, 0)
        assertRange("Standup tomorrow at 9am for an hour", nine, LocalTime.of(10, 0), "Standup")
        assertRange("Standup tomorrow at 9am for 90 minutes", nine, LocalTime.of(10, 30), "Standup")
        assertRange("Standup tomorrow at 9am for 2.5 hours", nine, LocalTime.of(11, 30), "Standup")
        assertRange("Standup tomorrow at 9am for half an hour", nine, LocalTime.of(9, 30), "Standup")
    }

    // ==================== Recurrence and weekday wording ====================

    private fun assertSeries(input: String, rrule: String?, date: LocalDate, time: LocalTime?, title: String) {
        val result = parse(input)
        assertEquals("$input: rrule", rrule, result.rrule)
        assertEquals("$input: date", date, result.startDate)
        assertEquals("$input: time", time, result.startTime)
        assertEquals("$input: title", title, result.title)
    }

    @Test
    fun `everyday is daily`() =
        assertSeries("gym everyday at 7am", "FREQ=DAILY", today, LocalTime.of(7, 0), "gym")

    @Test
    fun `each reads as every`() {
        assertSeries("standup each tuesday 9am", "FREQ=WEEKLY;BYDAY=TU", tomorrow, LocalTime.of(9, 0), "standup")
        assertEquals("FREQ=DAILY", parse("water each day").rrule)
        assertEquals("FREQ=WEEKLY", parse("review each week").rrule)
        val gifts = parse("Gifts for each kid")
        assertNull(gifts.rrule)
        assertEquals("Gifts for each kid", gifts.title)
        assertEquals("Tickets \$10 each", parse("Tickets \$10 each").title)
    }

    @Test
    fun `on with plural weekdays repeats weekly`() {
        assertSeries(
            "lunch on mondays and wednesdays at noon", "FREQ=WEEKLY;BYDAY=MO,WE",
            LocalDate.of(2026, 4, 15), LocalTime.NOON, "lunch"
        )
        assertSeries(
            "yoga on weekends at 8am", "FREQ=WEEKLY;BYDAY=SA,SU",
            LocalDate.of(2026, 4, 18), LocalTime.of(8, 0), "yoga"
        )
        assertSeries("swim on fridays", "FREQ=WEEKLY;BYDAY=FR", LocalDate.of(2026, 4, 17), null, "swim")
        assertNull(parse("Mondays are hard").rrule)
        assertNull(parse("dinner on suns").rrule)
        assertNull(parse("chess on sats").rrule)
    }

    @Test
    fun `once a period repeats at that period`() {
        assertSeries("water plants once a week", "FREQ=WEEKLY", today, null, "water plants")
        assertEquals("FREQ=DAILY", parse("vitamins once a day").rrule)
        assertEquals("FREQ=MONTHLY", parse("budget once a month").rrule)
        assertEquals("FREQ=YEARLY", parse("checkup once a year").rrule)
        val once = parse("once upon a time")
        assertNull(once.rrule)
        assertEquals("once upon a time", once.title)
    }

    @Test
    fun `every with an ordinal day repeats monthly on that day`() {
        assertSeries(
            "rent every 1st at 9am", "FREQ=MONTHLY;BYMONTHDAY=1",
            LocalDate.of(2026, 5, 1), LocalTime.of(9, 0), "rent"
        )
        assertEquals(LocalDate.of(2026, 4, 15), parse("pay every 15th").startDate)
        assertEquals(LocalDate.of(2026, 5, 31), parse("pay every 31st").startDate)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", parse("review every 2 weeks").rrule)
        assertFalse(parse("club every 2nd tuesday").rrule.orEmpty().contains("BYMONTHDAY"))
    }

    @Test
    fun `a weekday of next week or this week`() {
        assertTimed("pitch tuesday next week 10am", LocalDate.of(2026, 4, 21), LocalTime.of(10, 0), "pitch")
        assertTimed("pitch monday of next week at 10am", LocalDate.of(2026, 4, 20), LocalTime.of(10, 0), "pitch")
        assertEquals(LocalDate.of(2026, 4, 17), parse("demo friday this week").startDate)
        assertEquals("demo", parse("demo friday this week").title)
        assertEquals(LocalDate.of(2026, 4, 21), parse("pitch next tuesday").startDate)
    }

    @Test
    fun `next week follows the first day of the week`() {
        val mondayFirst = QuickAddParser.parse("brunch sunday next week", reference, Locale.US, java.util.Calendar.MONDAY)
        assertEquals(LocalDate.of(2026, 4, 26), mondayFirst.startDate)
        val sundayFirst = QuickAddParser.parse("brunch sunday next week", reference, Locale.US, java.util.Calendar.SUNDAY)
        assertEquals(LocalDate.of(2026, 4, 19), sundayFirst.startDate)
    }

    // ==================== Review regressions ====================

    @Test
    fun `an until day of zero does not crash`() {
        // Typing "until june 05" one key at a time passes through "until june 0".
        val result = parse("Gym every day until june 0")
        assertEquals("FREQ=DAILY", result.rrule?.substringBefore(";UNTIL"))
        parse("Gym every day until june 00")
    }

    @Test
    fun `every with an ordinal and a month repeats yearly on that date`() {
        assertSeries("Birthday every 1st of March", "FREQ=YEARLY", LocalDate.of(2027, 3, 1), null, "Birthday")
        assertSeries("Christmas every 25th december", "FREQ=YEARLY", LocalDate.of(2026, 12, 25), null, "Christmas")
        assertSeries("Christmas every 25th of December", "FREQ=YEARLY", LocalDate.of(2026, 12, 25), null, "Christmas")
    }

    @Test
    fun `a date range with a rule ends the series instead of stretching each occurrence`() {
        val camp = parse("Camp every day Aug 3 to 7 at 9am")
        assertEquals(LocalDate.of(2026, 8, 3), camp.startDate)
        assertEquals(LocalTime.of(9, 0), camp.startTime)
        assertNull(camp.endDate)
        assertTrue(camp.rrule!!.startsWith("FREQ=DAILY;UNTIL=20260807T"))
        assertEquals("Camp", camp.title)

        val school = parse("Summer school weekdays June 1 to 26 at 9am")
        assertNull(school.endDate)
        assertTrue(school.rrule!!.startsWith("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260626T"))

        val allDay = parse("Camp daily Aug 3 to 7")
        assertNull(allDay.endDate)
        assertEquals("FREQ=DAILY;UNTIL=20260807", allDay.rrule)
    }

    @Test
    fun `a year after a range across New Year belongs to the end`() {
        assertDayRange(
            "Vacation Dec 24 to Jan 2 2027", LocalDate.of(2026, 12, 24), LocalDate.of(2027, 1, 2), "Vacation"
        )
    }

    @Test
    fun `each other is not every other`() {
        val result = parse("Team lunch to get to know each other friday")
        assertNull(result.rrule)
        assertEquals(LocalDate.of(2026, 4, 17), result.startDate)
        assertEquals("Team lunch to get to know each other", result.title)
    }

    @Test
    fun `every 1st and 15th is not cut down to the 1st`() {
        val result = parse("Pay bills every 1st and 15th")
        assertFalse(result.rrule.orEmpty().contains("BYMONTHDAY=1"))
        assertTrue(result.title.contains("15th"))
    }

    @Test
    fun `quotes around kept words stay`() {
        assertEquals("Watch \"Dune\"", parse("Watch \"Dune\" tomorrow 8pm").title)
        assertEquals("\"Board meeting\"", parse("\"Board meeting\" tomorrow 3pm").title)
        assertEquals("'Board meeting'", parse("'Board meeting' tomorrow").title)
        assertEquals("\"Lunch with Bob\"", parse("tomorrow \"Lunch with Bob\"").title)
        assertEquals("\"Dune\" screening", parse("Friday \"Dune\" screening").title)
        assertEquals("Fix foo() bug", parse("Fix foo() bug tomorrow").title)
    }

    @Test
    fun `a location keeps its closing bracket`() {
        assertEquals("Joe's (downtown)", parse("Dinner at Joe's (downtown) tomorrow 7pm").location)
        assertEquals("Joe's Diner", parse("Dinner at \"Joe's Diner\" tomorrow 7pm").location)
        assertEquals("Dinner with Bob", parse("Dinner\u200Bwith Bob tomorrow").title)
    }

    @Test
    fun `a location may start with a glued number and m`() {
        val result = parse("Meeting at 3M HQ tomorrow 2pm")
        assertEquals("3M HQ", result.location)
        assertEquals("Meeting", result.title)
    }

    @Test
    fun `o'clock after a bare hour belongs to the time`() {
        assertTimed("Meet at 5 o'clock", today, LocalTime.of(5, 0), "Meet")
    }

    @Test
    fun `old unicode inputs keep their titles`() {
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDCBC meeting", parse("\uD83D\uDC68\u200D\uD83D\uDCBC meeting tomorrow").title)
        assertEquals("Lunch, with Sam", parse("Lunch,, with Sam tomorrow").title)
    }

    @Test
    fun `day-half anchors after a weekday and a past month`() {
        assertTimed("dinner friday evening at 7", LocalDate.of(2026, 4, 17), LocalTime.of(19, 0), "dinner")
        assertDayRange("Trip Mar 1 to 5", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 5), "Trip")
    }

    @Test
    fun `a second date never moves a range's start past its end`() {
        for (input in listOf(
            "Vacation dec 29 to 30 dentist feb 15", "Trip 3-5 aug 3-5", "Camp Friday to Sunday jan 15",
            "Sam, dec 1-15, tue to sat, every tue", "Camp tue to sat aug 17 to 20",
        )) {
            val result = parse(input)
            val end = result.endDate
            assertTrue("$input: end after start", end == null || !end.isBefore(result.startDate))
        }
    }

    // ==================== En and em dashes, colons ====================

    @Test
    fun `en and em dashes work like a hyphen in date ranges`() {
        val aug23 = LocalDate.of(2026, 8, 23)
        val aug28 = LocalDate.of(2026, 8, 28)
        assertDayRange("Conference Aug 23\u201328", aug23, aug28, "Conference")
        assertDayRange("Conference Aug 23\u201428", aug23, aug28, "Conference")
        assertDayRange("Conference Aug 23 \u2014 28", aug23, aug28, "Conference")
        assertDayRange("Trip 23\u201328 August", aug23, aug28, "Trip")
    }

    @Test
    fun `en and em dashes work like a hyphen in time ranges`() {
        assertRange("Meeting 3pm\u20145pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "Meeting")
        assertRange("Meeting 3pm \u2013 5pm", LocalTime.of(15, 0), LocalTime.of(17, 0), "Meeting")
        assertRange("Meeting 15:00\u201316:30", LocalTime.of(15, 0), LocalTime.of(16, 30), "Meeting")
        assertRange("Shift 9a.m.\u20135p.m.", LocalTime.of(9, 0), LocalTime.of(17, 0), "Shift")
    }

    @Test
    fun `a dash joining words still separates them`() {
        assertEquals(LocalDate.of(2026, 4, 14), parse("Lunch with Sam\u2014tomorrow").startDate)
        assertEquals("Lunch with Sam", parse("Lunch with Sam\u2014tomorrow").title)
        assertTimed("Flight tomorrow\u20143pm", tomorrow, LocalTime.of(15, 0), "Flight")
        assertTimed("Standup\u20149am", today, LocalTime.of(9, 0), "Standup")
        assertTimed("Meeting 3pm\u2014Sam's office", today, LocalTime.of(15, 0), "Meeting \u2014Sam's office")
    }

    @Test
    fun `a dashed number pair outside a range is not a date`() {
        val office = parse("Office hours 2\u20133 tomorrow")
        assertEquals(tomorrow, office.startDate)
        assertEquals("Office hours 2\u20133", office.title)
        val meeting = parse("Meeting 3\u20134")
        assertEquals(today, meeting.startDate)
        assertEquals("Meeting 3\u20134", meeting.title)
        assertEquals(LocalDate.of(2026, 4, 17), parse("Grades 3\u20135 assembly friday").startDate)
        assertEquals(LocalDate.of(2026, 4, 20), parse("Grades 3\u20135 assembly 4/20").startDate)
        assertEquals("2026\u201304\u201320", parse("2026\u201304\u201320").title)
    }

    @Test
    fun `glued slash and dot dates with a dash keep their start`() {
        val april20 = LocalDate.of(2026, 4, 20)
        assertEquals(april20, parse("Trip 4/20\u20134/22").startDate)
        assertEquals(april20, parse("Trip 4/20\u201322").startDate)
        assertEquals(april20, parse("Trip 20.4\u201322.4").startDate)
    }

    @Test
    fun `glued h-notation ranges are read`() {
        assertRange("Train 15h30-18h", LocalTime.of(15, 30), LocalTime.of(18, 0), "Train")
        assertRange("Train 15h30\u201318h", LocalTime.of(15, 30), LocalTime.of(18, 0), "Train")
        assertRange("Train 9h-11h", LocalTime.of(9, 0), LocalTime.of(11, 0), "Train")
        assertRange("Train tonight 9h-11h", LocalTime.of(9, 0), LocalTime.of(11, 0), "Train")
        assertRange("meeting at 9 2h", LocalTime.of(9, 0), LocalTime.of(11, 0), "meeting")
        val run = parse("Run for 1h-2h")
        assertNull(run.startTime)
        assertEquals("Run for 1h-2h", run.title)
    }

    @Test
    fun `a separator between kept words stays when a word next to it is cut`() {
        assertEquals("Q3 review \u2014 bring notes", parse("Q3 review tomorrow \u2014 bring notes").title)
        assertEquals("Q3 review - bring notes", parse("Q3 review tomorrow - bring notes").title)
        assertEquals("Read / write", parse("Read tomorrow / write Friday").title)
        assertEquals("Meet Bob - Room 4", parse("Meet Bob - tomorrow at 3pm - Room 4").title)
        assertEquals("Q3 review", parse("Q3 review \u2014 tomorrow").title)
        assertEquals("Call mom", parse("Call mom @ 5pm").title)
        assertEquals("Call mom with Sam", parse("Call mom @ 5pm with Sam").title)
        assertEquals("Planning \u2014 Q3 review", parse("Planning \u2014 Q3 review tomorrow").title)
        assertEquals("Joe\u2019s \u2014 downtown", parse("Dinner at Joe\u2019s \u2014 downtown tomorrow").location)
    }

    @Test
    fun `a word-final colon separates for the rules`() {
        val snacks = parse("Lunch tomorrow: bring snacks")
        assertEquals(tomorrow, snacks.startDate)
        assertEquals("Lunch: bring snacks", snacks.title)
        val agenda = parse("Meeting at 3pm: agenda")
        assertEquals(LocalTime.of(15, 0), agenda.startTime)
        assertEquals("Meeting: agenda", agenda.title)
        assertNull(agenda.location)
        val noon = parse("Lunch 12:30: bring snacks")
        assertEquals(LocalTime.of(12, 30), noon.startTime)
        assertEquals("Lunch: bring snacks", noon.title)
        assertEquals(LocalTime.of(15, 0), parse("meeting 15:00").startTime)
    }

    // ==================== Until year, this week ====================

    @Test
    fun `a year after the until date sets that year`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270615", parse("Gym every monday until june 15 2027").rrule)
        assertEquals("Gym", parse("Gym every monday until june 15 2027").title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270630", parse("Gym every monday until june 2027").rrule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270630", parse("Gym every monday until the end of june 2027").rrule)
        val past = parse("Gym every monday until june 15 2025")
        assertNull(past.rrule)
        assertEquals("Gym", past.title)
    }

    @Test
    fun `the until day is clamped with the stated year`() {
        assertEquals("FREQ=DAILY;UNTIL=20280229", parse("Gym every day until feb 29 2028").rrule)
        assertEquals("FREQ=DAILY;UNTIL=20270228", parse("Gym every day until feb 29 2027").rrule)
    }

    @Test
    fun `a weekday this week is the coming such day`() {
        for (week in listOf(java.util.Calendar.SUNDAY, java.util.Calendar.MONDAY)) {
            val lunch = QuickAddParser.parse("lunch sunday this week", reference, Locale.US, week)
            assertEquals("week start $week", LocalDate.of(2026, 4, 19), lunch.startDate)
            assertEquals("lunch", lunch.title)
            assertEquals(LocalDate.of(2026, 4, 17), QuickAddParser.parse("demo friday this week", reference, Locale.US, week).startDate)
            assertEquals(today, QuickAddParser.parse("standup monday this week", reference, Locale.US, week).startDate)
        }
        assertEquals(
            LocalDate.of(2026, 4, 19),
            QuickAddParser.parse("brunch sunday next week", reference, Locale.US, java.util.Calendar.SUNDAY).startDate
        )
    }

    // ==================== Ranges, digits, separators (review) ====================

    @Test
    fun `a calendar range replaces an earlier weekday range`() {
        val oct5 = LocalDate.of(2026, 10, 5)
        val oct7 = LocalDate.of(2026, 10, 7)
        assertDayRange("Offsite Monday to Wednesday, Oct 5 to 7", oct5, oct7, "Offsite")
        assertDayRange("Workshop Mon to Wed Oct 5-7", oct5, oct7, "Workshop")
        assertDayRange("Camp tue to sat aug 17 to 20", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 20), "Camp")
        // With a repeat rule the calendar range ends the series and the weekday range's end goes.
        val series = parse("Sam, dec 1-15, tue to sat, every tue")
        assertEquals(LocalDate.of(2026, 12, 1), series.startDate)
        assertNull(series.endDate)
        assertEquals("FREQ=WEEKLY;BYDAY=TU;UNTIL=20261215", series.rrule)
    }

    @Test
    fun `a separator between two cut words goes`() {
        assertEquals("Lunch with Sam", parse("Lunch tomorrow \u2014 3pm with Sam").title)
        assertEquals("Flight with Sam", parse("Flight tomorrow\u20143pm with Sam").title)
        assertEquals("Meet Bob - Room 4", parse("Meet Bob - tomorrow at 3pm - Room 4").title)
    }

    @Test
    fun `an am or pm after a range end belongs to it`() {
        assertRange("Webinar 2:00 PM \u2013 3:00 PM", LocalTime.of(14, 0), LocalTime.of(15, 0), "Webinar")
        assertRange("Webinar 2:00 PM - 3:00 PM", LocalTime.of(14, 0), LocalTime.of(15, 0), "Webinar")
        assertRange("Webinar 2:00 PM to 3:00 PM", LocalTime.of(14, 0), LocalTime.of(15, 0), "Webinar")
        assertRange("Meeting 11:30 PM \u2013 12:30 AM", LocalTime.of(23, 30), LocalTime.of(0, 30), "Meeting")
    }

    @Test
    fun `a number word matched through case folding is still that number`() {
        // A dotless i (U+0131) matches "i" case-insensitively, so the matched text is not a
        // lowercase map key; on the phone ligatures and the long s fold the same way.
        val gym = parse("Gym tomorrow 7am for f\u0131fteen minutes")
        assertEquals(LocalTime.of(7, 15), gym.endTime)
        assertEquals("Gym", gym.title)
    }

    @Test
    fun `an emoji glued to a dashed pair keeps today's reading`() {
        val trip = parse("Trip \uD83D\uDCC523\u201328 Aug")
        assertEquals(LocalDate.of(2026, 8, 28), trip.startDate)
    }

    @Test
    fun `spaced h-notation ranges and colon forms stay as decided`() {
        assertRange("Train 15h30 - 18h", LocalTime.of(15, 30), LocalTime.of(18, 0), "Train")
        assertRange("Train 15h30 to 18h", LocalTime.of(15, 30), LocalTime.of(18, 0), "Train")
        assertRange("Hike 2h-3h", LocalTime.of(2, 0), LocalTime.of(3, 0), "Hike")
        assertEquals("Call: bring notes", parse("Call 3 p.m.: bring notes").title)
        assertEquals("Note: call mom", parse("Note: call mom tomorrow").title)
    }

    @Test
    fun `a range start without am or pm borrows the end's`() {
        assertRange("Webinar 2:00 - 3:00 PM", LocalTime.of(14, 0), LocalTime.of(15, 0), "Webinar")
        assertRange("Webinar 10:00 - 11:00 PM", LocalTime.of(22, 0), LocalTime.of(23, 0), "Webinar")
        assertRange("Meeting from 2:00 to 3:00 pm", LocalTime.of(14, 0), LocalTime.of(15, 0), "Meeting")
        assertRange("Meeting 2:00 - 3:00 p.m.", LocalTime.of(14, 0), LocalTime.of(15, 0), "Meeting")
        assertRange("Lunch 11:00 - 12:30 PM", LocalTime.of(11, 0), LocalTime.of(12, 30), "Lunch")
        assertRange("Shift 14:00 - 3:00 PM", LocalTime.of(14, 0), LocalTime.of(15, 0), "Shift")
    }

    @Test
    fun `a compound number word matched through case folding is still that number`() {
        val run = parse("Run tomorrow 7am for f\u0131fty-five minutes")
        assertEquals(LocalDate.of(2026, 4, 14), run.startDate)
        assertEquals(LocalTime.of(7, 55), run.endTime)
        assertEquals("Run", run.title)
        assertEquals(LocalDate.of(2026, 4, 14), parse("Party f\u0131fty-one people tomorrow").startDate)
    }
}
