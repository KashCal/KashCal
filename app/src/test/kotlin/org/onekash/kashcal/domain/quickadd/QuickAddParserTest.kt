package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class QuickAddParserTest {

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    private fun parse(input: String) = QuickAddParser.parse(input, reference)

    // ==================== Full pipeline (US7) ====================

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

    // ==================== Confidence scoring (US8) ====================

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

    @Test
    fun `Meeting for 2 dot 5 hours still works with duration`() {
        val result = parse("Meeting for 2.5 hours")
        assertEquals("Meeting", result.title)
        assertEquals(LocalTime.of(12, 30), result.endTime) // 10:00 + 2.5h
    }

    // ==================== P2: Duration (US10) ====================

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

    // ==================== P2: Time ranges (US11) ====================

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

    // ==================== P2: Location (US9) ====================

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

    // ==================== P3: Recurrence (US12) ====================

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

    // ==================== P3: Emoji (US13) ====================

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
}
