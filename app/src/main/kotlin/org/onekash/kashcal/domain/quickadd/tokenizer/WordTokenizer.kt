package org.onekash.kashcal.domain.quickadd.tokenizer

import org.onekash.kashcal.util.DateTimeUtils
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale

object WordTokenizer {

    // ==================== Keyword Maps ====================

    private val dateKeywords = mapOf(
        "today" to "today", "tdy" to "today", "tday" to "today", "2day" to "today",
        "tomorrow" to "tomorrow", "tmrw" to "tomorrow", "tmr" to "tomorrow",
        "tomorow" to "tomorrow", "tomoro" to "tomorrow",
        "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
        "2moro" to "tomorrow", "2morrow" to "tomorrow",
        "yesterday" to "yesterday", "yday" to "yesterday", "ystrday" to "yesterday",
        "day_before_yesterday" to "day_before_yesterday",
        "day_after_tomorrow" to "day_after_tomorrow",
        "weekend" to "weekend",
        "all_day" to "ALL_DAY"
    )

    private val months = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER
    )

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY
    )

    private val keywords = mapOf(
        "at" to "AT", "of" to "OF", "in" to "IN",
        "ago" to "AGO", "from" to "FROM",
        "next" to "NEXT", "this" to "THIS", "last" to "LAST",
        "now" to "NOW", "the" to "THE", "on" to "ON",
        "for" to "FOR", "to" to "TO", "every" to "EVERY",
        "quarter_past" to "QUARTER_PAST", "half_past" to "HALF_PAST", "quarter_to" to "QUARTER_TO",
        "times" to "TIMES", "until" to "UNTIL"
    )

    private val timeKeywords = mapOf(
        "noon" to LocalTime.NOON,
        "lunchtime" to LocalTime.NOON,
        "midnight" to LocalTime.MIDNIGHT,
        "morning" to LocalTime.of(8, 0),
        "afternoon" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0),
        "after_work" to LocalTime.of(18, 0),
        "night" to LocalTime.of(20, 0),
        "tonight" to LocalTime.of(20, 0)
    )

    private val recurrenceKeywords = mapOf(
        "daily" to "DAILY",
        "weekly" to "WEEKLY",
        "biweekly" to "BIWEEKLY",
        "monthly" to "MONTHLY",
        "yearly" to "YEARLY",
        "annually" to "YEARLY"
    )

    private val meridiems = setOf(
        "am", "pm", "a.m", "p.m", "a.m.", "p.m."
    )

    private val timezoneAbbreviations = mapOf(
        "est" to "America/New_York",
        "edt" to "America/New_York",
        "cst" to "America/Chicago",
        "cdt" to "America/Chicago",
        "mst" to "America/Denver",
        "mdt" to "America/Denver",
        "pst" to "America/Los_Angeles",
        "pdt" to "America/Los_Angeles",
        "utc" to "UTC",
        "gmt" to "GMT"
    )

    private val units = mapOf(
        "second" to ChronoUnit.SECONDS, "seconds" to ChronoUnit.SECONDS,
        "sec" to ChronoUnit.SECONDS, "secs" to ChronoUnit.SECONDS,
        "minute" to ChronoUnit.MINUTES, "minutes" to ChronoUnit.MINUTES,
        "min" to ChronoUnit.MINUTES, "mins" to ChronoUnit.MINUTES,
        "hour" to ChronoUnit.HOURS, "hours" to ChronoUnit.HOURS,
        "hr" to ChronoUnit.HOURS, "hrs" to ChronoUnit.HOURS,
        "day" to ChronoUnit.DAYS, "days" to ChronoUnit.DAYS,
        "week" to ChronoUnit.WEEKS, "weeks" to ChronoUnit.WEEKS,
        "month" to ChronoUnit.MONTHS, "months" to ChronoUnit.MONTHS,
        "year" to ChronoUnit.YEARS, "years" to ChronoUnit.YEARS,
        "yr" to ChronoUnit.YEARS, "yrs" to ChronoUnit.YEARS
    )

    // ==================== Regex Patterns ====================

    // Time range: "2-3pm", "5pm-6", "10:30-11:30am", "3.15-4.30pm", "11pm-1am". A meridiem may
    // sit on either side; parseTimeRange rejects a range with none.
    private val timeRangeRegex = Regex(
        """(\d{1,2})(?:[:.](\d{2}))?(am|pm|a\.m\.?|p\.m\.?)?-(\d{1,2})(?:[:.](\d{2}))?(am|pm|a\.m\.?|p\.m\.?)?""",
        RegexOption.IGNORE_CASE
    )

    // Structured dates such as M/D, D/M/Y, Y-M-D and D.M.Y; parseStructuredDate decides the order.
    private val structuredDateRegex = Regex(
        """(\d{1,4})[/\-.](\d{1,2})(?:[/\-.](\d{1,4}))?"""
    )

    // Time: "3pm", "3:30pm", "3.30pm", "15:00", "3:30". Needs a colon, or a meridiem after an
    // hour or a dotted time, so a bare "15" isn't a time. Alternatives: colon form, dot form,
    // meridiem-only form.
    private val timeRegex = Regex(
        """(\d{1,2})(?::(\d{2}))\s*(am|pm|a\.m\.?|p\.m\.?)?|(\d{1,2})\.(\d{2})\s*(am|pm|a\.m\.?|p\.m\.?)|(\d{1,2})\s*(am|pm|a\.m\.?|p\.m\.?)""",
        RegexOption.IGNORE_CASE
    )

    // 24-hour "h" notation: "9h", "15h30".
    private val hNotationRegex = Regex("""(\d{1,2})h(\d{2})?""", RegexOption.IGNORE_CASE)

    // Year: 1000 to 2999.
    private val yearRegex = Regex("""[12]\d{3}""")

    // Ordinal ("15th", "1st") and plain number ("15").
    private val ordinalRegex = Regex("""(\d+)(st|nd|rd|th)""", RegexOption.IGNORE_CASE)
    private val numberRegex = Regex("""\d+""")
    private val whitespaceRegex = Regex("""\s+""")

    // ==================== Tokenize ====================

    fun tokenize(
        input: String,
        originalWords: List<String>? = null,
        locale: Locale = Locale.getDefault()
    ): List<Token> {
        if (input.isBlank()) return emptyList()

        val words = input.split(whitespaceRegex)
        return words.mapIndexed { index, word ->
            val original = originalWords?.getOrNull(index) ?: word
            classifyWord(word, original, locale)
        }
    }

    private fun classifyWord(
        word: String,
        originalText: String = word,
        locale: Locale = Locale.getDefault()
    ): Token {
        // The checks run in this order; the first match wins.
        // Time range before structured date, so "2-3pm" is a range.
        timeRangeRegex.matchEntire(word)?.let { match ->
            parseTimeRange(word, match, originalText)?.let { return it }
        }

        // Structured date before numbers, so "1/15" is a date.
        structuredDateRegex.matchEntire(word)?.let { match ->
            return parseStructuredDate(word, match, originalText, locale)
        }

        months[word]?.let {
            return Token(TokenType.MONTH, word, it, originalText)
        }

        weekdays[word]?.let {
            return Token(TokenType.WEEKDAY, word, it, originalText)
        }

        dateKeywords[word]?.let {
            return Token(TokenType.DATE_KEYWORD, word, it, originalText)
        }

        timeKeywords[word]?.let {
            return Token(TokenType.TIME_KEYWORD, word, it, originalText)
        }

        if (word in meridiems) {
            return Token(TokenType.MERIDIEM, word, word, originalText)
        }

        timezoneAbbreviations[word]?.let {
            return Token(TokenType.TIMEZONE, word, it, originalText)
        }

        recurrenceKeywords[word]?.let {
            return Token(TokenType.RECURRENCE_KEYWORD, word, it, originalText)
        }

        units[word]?.let {
            return Token(TokenType.UNIT, word, it, originalText)
        }

        keywords[word]?.let {
            return Token(TokenType.KEYWORD, word, it, originalText)
        }

        timeRegex.matchEntire(word)?.let { match ->
            parseTime(word, match, originalText)?.let { return it }
        }

        // "h" notation before year and number.
        hNotationRegex.matchEntire(word)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()
            val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            if (hour != null && hour in 0..23 && minute in 0..59) {
                return Token(TokenType.TIME, word, LocalTime.of(hour, minute), originalText)
            }
        }

        // Year before plain numbers.
        if (yearRegex.matchEntire(word) != null) {
            return Token(TokenType.YEAR, word, word.toInt(), originalText)
        }

        ordinalRegex.matchEntire(word)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return Token(TokenType.UNKNOWN, word, word, originalText)
            return Token(TokenType.NUMBER, word, num, originalText)
        }

        // A number too large for Int, such as "99999999999", is UNKNOWN.
        if (numberRegex.matchEntire(word) != null) {
            val num = word.toIntOrNull() ?: return Token(TokenType.UNKNOWN, word, word, originalText)
            return Token(TokenType.NUMBER, word, num, originalText)
        }

        return Token(TokenType.UNKNOWN, word, word, originalText)
    }

    private fun parseTimeRange(word: String, match: MatchResult, originalText: String = word): Token? {
        val startHourRaw = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val startMeridiem = match.groupValues[3].lowercase().replace(".", "").takeIf { it.isNotEmpty() }
        val endHourRaw = match.groupValues[4].toIntOrNull() ?: return null
        val endMinute = match.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val endMeridiem = match.groupValues[6].lowercase().replace(".", "").takeIf { it.isNotEmpty() }

        if (startMinute > 59 || endMinute > 59) return null
        // Without a meridiem, "2-3" is a structured date, not a range.
        if (startMeridiem == null && endMeridiem == null) return null

        val startHour: Int
        val endHour: Int

        if (startMeridiem != null && endMeridiem != null) {
            startHour = resolveMeridiemHour(startHourRaw, startMeridiem) ?: return null
            endHour = resolveMeridiemHour(endHourRaw, endMeridiem) ?: return null
        } else if (startMeridiem != null) {
            // The end takes the start's meridiem, or the opposite one when that would put it
            // before the start: "10am-2" is 10:00 to 14:00.
            startHour = resolveMeridiemHour(startHourRaw, startMeridiem) ?: return null
            val sameAsStart = resolveMeridiemHour(endHourRaw, startMeridiem) ?: return null
            val startTime24 = startHour * 60 + startMinute
            val endSame = sameAsStart * 60 + endMinute
            endHour = if (endSame < startTime24) {
                val oppositeMeridiem = if (startMeridiem.startsWith("p")) "am" else "pm"
                resolveMeridiemHour(endHourRaw, oppositeMeridiem) ?: sameAsStart
            } else {
                sameAsStart
            }
        } else {
            // The start takes the end's meridiem, or the opposite one when that would put it
            // after the end: "9-5pm" is 9:00 to 17:00. endMeridiem is non-null by the guard.
            endHour = resolveMeridiemHour(endHourRaw, endMeridiem!!) ?: return null
            val sameAsEnd = resolveMeridiemHour(startHourRaw, endMeridiem) ?: return null
            val endTime24 = endHour * 60 + endMinute
            val startSame = sameAsEnd * 60 + startMinute
            startHour = if (startSame > endTime24) {
                val oppositeMeridiem = if (endMeridiem.startsWith("p")) "am" else "pm"
                resolveMeridiemHour(startHourRaw, oppositeMeridiem) ?: sameAsEnd
            } else {
                sameAsEnd
            }
        }

        val startTime = LocalTime.of(startHour, startMinute)
        val endTime = LocalTime.of(endHour, endMinute)

        return Token(TokenType.TIME_RANGE, word, TimeRange(startTime, endTime), originalText)
    }

    internal fun resolveMeridiemHour(hour: Int, meridiem: String): Int? {
        if (hour < 1 || hour > 12) return null
        return when {
            meridiem.startsWith("p") -> if (hour == 12) 12 else hour + 12
            meridiem.startsWith("a") -> if (hour == 12) 0 else hour
            else -> null
        }
    }

    private fun parseTime(word: String, match: MatchResult, originalText: String = word): Token? {
        // Groups 1-3 are the colon form, 4-6 the dot form, 7-8 the meridiem-only form.
        val hour: Int
        val minute: Int
        val meridiem: String

        if (match.groupValues[1].isNotEmpty()) {
            hour = match.groupValues[1].toIntOrNull() ?: return null
            minute = match.groupValues[2].toIntOrNull() ?: 0
            meridiem = match.groupValues[3].lowercase().replace(".", "")
        } else if (match.groupValues[4].isNotEmpty()) {
            hour = match.groupValues[4].toIntOrNull() ?: return null
            minute = match.groupValues[5].toIntOrNull() ?: 0
            meridiem = match.groupValues[6].lowercase().replace(".", "")
        } else {
            hour = match.groupValues[7].toIntOrNull() ?: return null
            minute = 0
            meridiem = match.groupValues[8].lowercase().replace(".", "")
        }

        if (minute > 59) return null

        val resolvedHour = when {
            meridiem.startsWith("p") -> {
                if (hour > 12 || hour < 1) return null
                if (hour == 12) 12 else hour + 12
            }
            meridiem.startsWith("a") -> {
                if (hour > 12 || hour < 1) return null
                if (hour == 12) 0 else hour
            }
            else -> {
                // No meridiem: 24-hour.
                if (hour > 23) return null
                hour
            }
        }

        return Token(TokenType.TIME, word, LocalTime.of(resolvedHour, minute), originalText)
    }

    data class TimeRange(val start: LocalTime, val end: LocalTime)

    data class DateParts(val day: Int, val month: Int, val year: Int?)

    private fun parseStructuredDate(
        word: String,
        match: MatchResult,
        originalText: String = word,
        locale: Locale = Locale.getDefault()
    ): Token {
        val part1 = match.groupValues[1].toInt()
        val part2 = match.groupValues[2].toInt()
        val part3 = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()

        val separator = word.first { it == '/' || it == '-' || it == '.' }

        val dateParts = when {
            // Y-M-D: a dash and a first part over 31. Without a third part, the second part
            // is both the month and the day.
            separator == '-' && part1 > 31 -> DateParts(
                day = part3 ?: part2,
                month = part2,
                year = part1
            )
            // Dots always mean D.M.Y.
            separator == '.' -> DateParts(
                day = part1,
                month = part2,
                year = resolveYear(part3)
            )
            // A first part over 12 can't be a month, so it is the day.
            part1 > 12 -> DateParts(
                day = part1,
                month = part2,
                year = resolveYear(part3)
            )
            // A second part over 12 can't be a month, so the first is.
            part2 > 12 -> DateParts(
                day = part2,
                month = part1,
                year = resolveYear(part3)
            )
            DateTimeUtils.isDayFirstLocale(locale) -> DateParts(
                day = part1,
                month = part2,
                year = resolveYear(part3)
            )
            else -> DateParts(
                day = part2,
                month = part1,
                year = resolveYear(part3)
            )
        }

        return Token(TokenType.STRUCTURED_DATE, word, dateParts, originalText)
    }

    private fun resolveYear(year: Int?): Int? {
        if (year == null) return null
        return when {
            year in 0..50 -> 2000 + year
            year in 51..99 -> 1900 + year
            else -> year
        }
    }
}
