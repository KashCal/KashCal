package org.onekash.kashcal.domain.quickadd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale

class WordTokenizerTest {

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

    // ==================== Date Keywords ====================

    @Test
    fun `tokenizes tomorrow as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("tomorrow")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
        assertEquals("tomorrow", tokens[0].text)
    }

    @Test
    fun `tokenizes today as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("today")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
    }

    @Test
    fun `tokenizes yesterday as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("yesterday")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
    }

    @Test
    fun `tokenizes tmr abbreviation as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("tmr")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
    }

    @Test
    fun `tokenizes day_after_tomorrow as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("day_after_tomorrow")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
    }

    // ==================== Months ====================

    @Test
    fun `tokenizes january as MONTH`() {
        val tokens = WordTokenizer.tokenize("january")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.MONTH, tokens[0].type)
        assertEquals(Month.JANUARY, tokens[0].value)
    }

    @Test
    fun `tokenizes jan abbreviation as MONTH`() {
        val tokens = WordTokenizer.tokenize("jan")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.MONTH, tokens[0].type)
        assertEquals(Month.JANUARY, tokens[0].value)
    }

    @Test
    fun `tokenizes sept abbreviation as MONTH`() {
        val tokens = WordTokenizer.tokenize("sept")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.MONTH, tokens[0].type)
        assertEquals(Month.SEPTEMBER, tokens[0].value)
    }

    // ==================== Weekdays ====================

    @Test
    fun `tokenizes monday as WEEKDAY`() {
        val tokens = WordTokenizer.tokenize("monday")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.WEEKDAY, tokens[0].type)
        assertEquals(DayOfWeek.MONDAY, tokens[0].value)
    }

    @Test
    fun `tokenizes mon abbreviation as WEEKDAY`() {
        val tokens = WordTokenizer.tokenize("mon")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.WEEKDAY, tokens[0].type)
        assertEquals(DayOfWeek.MONDAY, tokens[0].value)
    }

    @Test
    fun `tokenizes thur abbreviation as WEEKDAY`() {
        val tokens = WordTokenizer.tokenize("thur")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.WEEKDAY, tokens[0].type)
        assertEquals(DayOfWeek.THURSDAY, tokens[0].value)
    }

    // ==================== Time ====================

    @Test
    fun `tokenizes 3pm as TIME`() {
        val tokens = WordTokenizer.tokenize("3pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(15, 0), tokens[0].value)
    }

    @Test
    fun `tokenizes 12am as TIME with midnight`() {
        val tokens = WordTokenizer.tokenize("12am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(0, 0), tokens[0].value)
    }

    @Test
    fun `tokenizes 12pm as TIME with noon`() {
        val tokens = WordTokenizer.tokenize("12pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(12, 0), tokens[0].value)
    }

    @Test
    fun `tokenizes 15 colon 00 as TIME`() {
        val tokens = WordTokenizer.tokenize("15:00")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(15, 0), tokens[0].value)
    }

    @Test
    fun `tokenizes 3 colon 30pm as TIME`() {
        val tokens = WordTokenizer.tokenize("3:30pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(15, 30), tokens[0].value)
    }

    // ==================== Dot-separated time ====================

    @Test
    fun `tokenizes 3 dot 15pm as TIME`() {
        val tokens = WordTokenizer.tokenize("3.15pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(15, 15), tokens[0].value)
    }

    @Test
    fun `tokenizes 10 dot 30am as TIME`() {
        val tokens = WordTokenizer.tokenize("10.30am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(10, 30), tokens[0].value)
    }

    @Test
    fun `tokenizes 12 dot 30pm as TIME with noon hour`() {
        val tokens = WordTokenizer.tokenize("12.30pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME, tokens[0].type)
        assertEquals(LocalTime.of(12, 30), tokens[0].value)
    }

    @Test
    fun `0 dot 15pm does not tokenize as TIME`() {
        val tokens = WordTokenizer.tokenize("0.15pm")
        assertEquals(1, tokens.size)
        assertNotEquals(TokenType.TIME, tokens[0].type)
    }

    @Test
    fun `3 dot 60pm does not tokenize as TIME`() {
        val tokens = WordTokenizer.tokenize("3.60pm")
        assertEquals(1, tokens.size)
        assertNotEquals(TokenType.TIME, tokens[0].type)
    }

    @Test
    fun `3 dot 15 without meridiem stays STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("3.15")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    @Test
    fun `15 dot 01 without meridiem stays STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("15.01")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    @Test
    fun `15 dot 01 dot 2027 stays STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("15.01.2027")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    // ==================== Dot-separated time ranges ====================

    @Test
    fun `tokenizes 3 dot 15-4 dot 30pm as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("3.15-4.30pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(15, 15), range.start)
        assertEquals(LocalTime.of(16, 30), range.end)
    }

    @Test
    fun `tokenizes 10 dot 30-11 dot 30am as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("10.30-11.30am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(10, 30), range.start)
        assertEquals(LocalTime.of(11, 30), range.end)
    }

    // ==================== Time Keywords ====================

    @Test
    fun `tokenizes noon as TIME_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("noon")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_KEYWORD, tokens[0].type)
        assertEquals(LocalTime.NOON, tokens[0].value)
    }

    @Test
    fun `tokenizes midnight as TIME_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("midnight")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_KEYWORD, tokens[0].type)
        assertEquals(LocalTime.MIDNIGHT, tokens[0].value)
    }

    // ==================== Keywords ====================

    @Test
    fun `tokenizes at as KEYWORD`() {
        val tokens = WordTokenizer.tokenize("at")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.KEYWORD, tokens[0].type)
    }

    @Test
    fun `tokenizes next as KEYWORD`() {
        val tokens = WordTokenizer.tokenize("next")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.KEYWORD, tokens[0].type)
    }

    @Test
    fun `tokenizes last as KEYWORD`() {
        val tokens = WordTokenizer.tokenize("last")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.KEYWORD, tokens[0].type)
    }

    // ==================== Numbers ====================

    @Test
    fun `tokenizes plain number as NUMBER`() {
        val tokens = WordTokenizer.tokenize("15")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.NUMBER, tokens[0].type)
        assertEquals(15, tokens[0].value)
    }

    @Test
    fun `tokenizes ordinal as NUMBER`() {
        val tokens = WordTokenizer.tokenize("15th")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.NUMBER, tokens[0].type)
        assertEquals(15, tokens[0].value)
    }

    @Test
    fun `tokenizes 1st ordinal as NUMBER`() {
        val tokens = WordTokenizer.tokenize("1st")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.NUMBER, tokens[0].type)
        assertEquals(1, tokens[0].value)
    }

    @Test
    fun `tokenizes 2nd ordinal as NUMBER`() {
        val tokens = WordTokenizer.tokenize("2nd")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.NUMBER, tokens[0].type)
        assertEquals(2, tokens[0].value)
    }

    @Test
    fun `tokenizes 3rd ordinal as NUMBER`() {
        val tokens = WordTokenizer.tokenize("3rd")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.NUMBER, tokens[0].type)
        assertEquals(3, tokens[0].value)
    }

    // ==================== Units ====================

    @Test
    fun `tokenizes minutes as UNIT`() {
        val tokens = WordTokenizer.tokenize("minutes")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.UNIT, tokens[0].type)
        assertEquals(ChronoUnit.MINUTES, tokens[0].value)
    }

    @Test
    fun `tokenizes hrs abbreviation as UNIT`() {
        val tokens = WordTokenizer.tokenize("hrs")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.UNIT, tokens[0].type)
        assertEquals(ChronoUnit.HOURS, tokens[0].value)
    }

    // ==================== Years ====================

    @Test
    fun `tokenizes 2027 as YEAR`() {
        val tokens = WordTokenizer.tokenize("2027")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.YEAR, tokens[0].type)
        assertEquals(2027, tokens[0].value)
    }

    // ==================== Structured Dates ====================

    @Test
    fun `tokenizes 1 slash 15 as STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("1/15")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    @Test
    fun `tokenizes ISO date as STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("2027-01-15")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    @Test
    fun `tokenizes European date as STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("15.01.2027")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    // ==================== Time Ranges ====================

    @Test
    fun `tokenizes 2-3pm as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("2-3pm")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(14, 0), range.start)
        assertEquals(LocalTime.of(15, 0), range.end)
    }

    @Test
    fun `tokenizes 10 colon 30-11 colon 30am as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("10:30-11:30am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(10, 30), range.start)
        assertEquals(LocalTime.of(11, 30), range.end)
    }

    @Test
    fun `tokenizes 11pm-1am as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("11pm-1am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(23, 0), range.start)
        assertEquals(LocalTime.of(1, 0), range.end)
    }

    // ==================== Time range with meridiem on start only (issue #194 follow-up Bug E) ====================

    @Test
    fun `tokenizes 5pm-6 as TIME_RANGE with inherited pm`() {
        val tokens = WordTokenizer.tokenize("5pm-6")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(17, 0), range.start)
        assertEquals(LocalTime.of(18, 0), range.end)
    }

    @Test
    fun `tokenizes 9am-10 as TIME_RANGE with inherited am`() {
        val tokens = WordTokenizer.tokenize("9am-10")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(9, 0), range.start)
        assertEquals(LocalTime.of(10, 0), range.end)
    }

    @Test
    fun `tokenizes 10am-2 as TIME_RANGE with end flipped to pm when same-meridiem inversion`() {
        // 10am-2am would be inverted (2am before 10am), so flip end to pm → 10:00-14:00
        // Mirrors the symmetric logic at WordTokenizer parseTimeRange for end-only meridiem
        val tokens = WordTokenizer.tokenize("10am-2")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(10, 0), range.start)
        assertEquals(LocalTime.of(14, 0), range.end)
    }

    @Test
    fun `tokenizes 5pm-6 with minutes as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("5:30pm-6:45")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(17, 30), range.start)
        assertEquals(LocalTime.of(18, 45), range.end)
    }

    @Test
    fun `2-3 without meridiem stays STRUCTURED_DATE`() {
        val tokens = WordTokenizer.tokenize("2-3")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
    }

    @Test
    fun `tokenizes 9-10am as TIME_RANGE`() {
        val tokens = WordTokenizer.tokenize("9-10am")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIME_RANGE, tokens[0].type)
        val range = tokens[0].value as WordTokenizer.TimeRange
        assertEquals(LocalTime.of(9, 0), range.start)
        assertEquals(LocalTime.of(10, 0), range.end)
    }

    // ==================== Recurrence Keywords ====================

    @Test
    fun `tokenizes daily as RECURRENCE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("daily")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.RECURRENCE_KEYWORD, tokens[0].type)
        assertEquals("DAILY", tokens[0].value)
    }

    @Test
    fun `tokenizes weekly as RECURRENCE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("weekly")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.RECURRENCE_KEYWORD, tokens[0].type)
        assertEquals("WEEKLY", tokens[0].value)
    }

    @Test
    fun `tokenizes biweekly as RECURRENCE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("biweekly")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.RECURRENCE_KEYWORD, tokens[0].type)
        assertEquals("BIWEEKLY", tokens[0].value)
    }

    @Test
    fun `tokenizes annually as RECURRENCE_KEYWORD with YEARLY value`() {
        val tokens = WordTokenizer.tokenize("annually")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.RECURRENCE_KEYWORD, tokens[0].type)
        assertEquals("YEARLY", tokens[0].value)
    }

    @Test
    fun `tokenizes every as KEYWORD with EVERY value`() {
        val tokens = WordTokenizer.tokenize("every")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.KEYWORD, tokens[0].type)
        assertEquals("EVERY", tokens[0].value)
    }

    // ==================== Unknown ====================

    @Test
    fun `tokenizes unknown word as UNKNOWN`() {
        val tokens = WordTokenizer.tokenize("coffee")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.UNKNOWN, tokens[0].type)
        assertEquals("coffee", tokens[0].text)
    }

    // ==================== Multi-token input ====================

    @Test
    fun `tokenizes multi-word input`() {
        val tokens = WordTokenizer.tokenize("tomorrow at 3pm")
        assertEquals(3, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
        assertEquals(TokenType.KEYWORD, tokens[1].type)
        assertEquals(TokenType.TIME, tokens[2].type)
    }

    @Test
    fun `tokenizes calendar sentence`() {
        val tokens = WordTokenizer.tokenize("coffee with sarah tomorrow at 3pm")
        assertEquals(6, tokens.size)
        assertEquals(TokenType.UNKNOWN, tokens[0].type) // coffee
        assertEquals(TokenType.UNKNOWN, tokens[1].type) // with
        assertEquals(TokenType.UNKNOWN, tokens[2].type) // sarah
        assertEquals(TokenType.DATE_KEYWORD, tokens[3].type) // tomorrow
        assertEquals(TokenType.KEYWORD, tokens[4].type) // at
        assertEquals(TokenType.TIME, tokens[5].type) // 3pm
    }

    // ==================== All Day Keyword ====================

    @Test
    fun `tokenizes all_day as DATE_KEYWORD`() {
        val tokens = WordTokenizer.tokenize("all_day")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.DATE_KEYWORD, tokens[0].type)
        assertEquals("ALL_DAY", tokens[0].value)
    }

    @Test
    fun `tokenizes empty string as empty list`() {
        val tokens = WordTokenizer.tokenize("")
        assertEquals(0, tokens.size)
    }

    // ==================== Timezone ====================

    @Test
    fun `tokenizes EST as TIMEZONE`() {
        val tokens = WordTokenizer.tokenize("est")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIMEZONE, tokens[0].type)
        assertEquals("America/New_York", tokens[0].value)
    }

    @Test
    fun `tokenizes PST as TIMEZONE`() {
        val tokens = WordTokenizer.tokenize("pst")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIMEZONE, tokens[0].type)
        assertEquals("America/Los_Angeles", tokens[0].value)
    }

    @Test
    fun `tokenizes UTC as TIMEZONE`() {
        val tokens = WordTokenizer.tokenize("utc")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIMEZONE, tokens[0].type)
        assertEquals("UTC", tokens[0].value)
    }

    @Test
    fun `tokenizes GMT as TIMEZONE`() {
        val tokens = WordTokenizer.tokenize("gmt")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.TIMEZONE, tokens[0].type)
        assertEquals("GMT", tokens[0].value)
    }

    // ==================== Locale-aware structured dates (issue #194) ====================

    @Test
    fun `tokenize 5 slash 10 under en_US classifies as M-D`() {
        val tokens = WordTokenizer.tokenize("5/10", locale = Locale.US)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.month)
        assertEquals(10, parts.day)
    }

    @Test
    fun `tokenize 5 slash 10 under en_GB classifies as D-M`() {
        val tokens = WordTokenizer.tokenize("5/10", locale = Locale.UK)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.day)
        assertEquals(10, parts.month)
    }

    @Test
    fun `tokenize 5 slash 10 slash 2026 under en_GB classifies as D-M-Y`() {
        val tokens = WordTokenizer.tokenize("5/10/2026", locale = Locale.UK)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.day)
        assertEquals(10, parts.month)
        assertEquals(2026, parts.year)
    }

    @Test
    fun `tokenize 13 slash 5 under en_US still classifies as D-M because 13 cannot be month`() {
        val tokens = WordTokenizer.tokenize("13/5", locale = Locale.US)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(13, parts.day)
        assertEquals(5, parts.month)
    }

    @Test
    fun `tokenize 5 slash 13 under en_GB still classifies as M-D because 13 cannot be month`() {
        val tokens = WordTokenizer.tokenize("5/13", locale = Locale.UK)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.month)
        assertEquals(13, parts.day)
    }

    @Test
    fun `tokenize ISO date under en_GB classifies as Y-M-D`() {
        val tokens = WordTokenizer.tokenize("2026-10-05", locale = Locale.UK)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(2026, parts.year)
        assertEquals(10, parts.month)
        assertEquals(5, parts.day)
    }

    @Test
    fun `tokenize dot-separated date stays DMY under any locale`() {
        val tokens = WordTokenizer.tokenize("5.10.2026", locale = Locale.US)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.day)
        assertEquals(10, parts.month)
    }

    @Test
    fun `tokenize 5 slash 10 under Locale_GERMANY classifies as D-M`() {
        val tokens = WordTokenizer.tokenize("5/10", locale = Locale.GERMANY)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.day)
        assertEquals(10, parts.month)
    }

    @Test
    fun `tokenize 5 slash 10 under Locale_JAPAN classifies as M-D via y-slash-MM-slash-dd pattern`() {
        // ja_JP's SHORT pattern is "yyyy/MM/dd" — M appears before d → MDY
        val tokens = WordTokenizer.tokenize("5/10", locale = Locale.JAPAN)
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.month)
        assertEquals(10, parts.day)
    }

    @Test
    fun `tokenize with default locale argument still compiles and classifies as MDY on US-pinned default`() {
        // Verifies the overload default works; pinLocaleToUS() sets Locale.US as default.
        val tokens = WordTokenizer.tokenize("5/10")
        assertEquals(TokenType.STRUCTURED_DATE, tokens[0].type)
        val parts = tokens[0].value as WordTokenizer.DateParts
        assertEquals(5, parts.month)
        assertEquals(10, parts.day)
    }
}
