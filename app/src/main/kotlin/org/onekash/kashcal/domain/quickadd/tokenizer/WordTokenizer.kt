package org.onekash.kashcal.domain.quickadd.tokenizer

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.temporal.ChronoUnit

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
        "day_after_tomorrow" to "day_after_tomorrow"
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
        "for" to "FOR", "to" to "TO", "every" to "EVERY"
    )

    private val timeKeywords = mapOf(
        "noon" to LocalTime.NOON,
        "midnight" to LocalTime.MIDNIGHT
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

    // Time range: "2-3pm", "10:30-11:30am", "11pm-1am"
    // Must require meridiem on at least one side to disambiguate from structured dates
    private val timeRangeRegex = Regex(
        """(\d{1,2})(?::(\d{2}))?(am|pm|a\.m\.?|p\.m\.?)?-(\d{1,2})(?::(\d{2}))?(am|pm|a\.m\.?|p\.m\.?)""",
        RegexOption.IGNORE_CASE
    )

    // Structured dates: M/D, M/D/Y, Y-M-D, D.M.Y
    private val structuredDateRegex = Regex(
        """(\d{1,4})[/\-.](\d{1,2})(?:[/\-.](\d{1,4}))?"""
    )

    // Time: "3pm", "3:30pm", "15:00", "3:30" — requires colon OR meridiem (bare "15" is not time)
    private val timeRegex = Regex(
        """(\d{1,2})(?::(\d{2}))\s*(am|pm|a\.m\.?|p\.m\.?)?|(\d{1,2})\s*(am|pm|a\.m\.?|p\.m\.?)""",
        RegexOption.IGNORE_CASE
    )

    // Year: 1000-2999
    private val yearRegex = Regex("""[12]\d{3}""")

    // Number or ordinal: "15", "15th", "1st", "2nd", "3rd"
    private val ordinalRegex = Regex("""(\d+)(st|nd|rd|th)""", RegexOption.IGNORE_CASE)
    private val numberRegex = Regex("""\d+""")
    private val whitespaceRegex = Regex("""\s+""")

    // ==================== Tokenize ====================

    fun tokenize(input: String, originalWords: List<String>? = null): List<Token> {
        if (input.isBlank()) return emptyList()

        val words = input.split(whitespaceRegex)
        return words.mapIndexed { index, word ->
            val original = originalWords?.getOrNull(index) ?: word
            classifyWord(word, original)
        }
    }

    private fun classifyWord(word: String, originalText: String = word): Token {
        // 0. Time range (must check before structured date to catch "2-3pm")
        timeRangeRegex.matchEntire(word)?.let { match ->
            parseTimeRange(word, match, originalText)?.let { return it }
        }

        // 1. Structured date (must check before numbers to catch "1/15")
        structuredDateRegex.matchEntire(word)?.let { match ->
            return parseStructuredDate(word, match, originalText)
        }

        // 2. Month names
        months[word]?.let {
            return Token(TokenType.MONTH, word, it, originalText)
        }

        // 3. Weekday names
        weekdays[word]?.let {
            return Token(TokenType.WEEKDAY, word, it, originalText)
        }

        // 4. Date keywords (today, tomorrow, etc.)
        dateKeywords[word]?.let {
            return Token(TokenType.DATE_KEYWORD, word, it, originalText)
        }

        // 5. Time keywords (noon, midnight)
        timeKeywords[word]?.let {
            return Token(TokenType.TIME_KEYWORD, word, it, originalText)
        }

        // 6. Meridiem (am, pm) — must check before units to avoid conflicts with "min"
        if (word in meridiems) {
            return Token(TokenType.MERIDIEM, word, word, originalText)
        }

        // 6b. Recurrence keywords (daily, weekly, etc.) — before units and general keywords
        recurrenceKeywords[word]?.let {
            return Token(TokenType.RECURRENCE_KEYWORD, word, it, originalText)
        }

        // 7. Unit words (minutes, hours, days, etc.)
        units[word]?.let {
            return Token(TokenType.UNIT, word, it, originalText)
        }

        // 8. General keywords (at, in, next, last, etc.)
        keywords[word]?.let {
            return Token(TokenType.KEYWORD, word, it, originalText)
        }

        // 9. Time with meridiem ("3pm", "3:30pm", "15:00")
        timeRegex.matchEntire(word)?.let { match ->
            parseTime(word, match, originalText)?.let { return it }
        }

        // 10. Year (1000-2999) — check before general numbers
        if (yearRegex.matchEntire(word) != null) {
            return Token(TokenType.YEAR, word, word.toInt(), originalText)
        }

        // 11. Ordinal ("15th", "1st", "2nd", "3rd")
        ordinalRegex.matchEntire(word)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return Token(TokenType.UNKNOWN, word, word, originalText)
            return Token(TokenType.NUMBER, word, num, originalText)
        }

        // 12. Plain number (guard against overflow for huge numbers like "99999999999")
        if (numberRegex.matchEntire(word) != null) {
            val num = word.toIntOrNull() ?: return Token(TokenType.UNKNOWN, word, word, originalText)
            return Token(TokenType.NUMBER, word, num, originalText)
        }

        // 13. Unknown
        return Token(TokenType.UNKNOWN, word, word, originalText)
    }

    private fun parseTimeRange(word: String, match: MatchResult, originalText: String = word): Token? {
        val startHourRaw = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val startMeridiem = match.groupValues[3].lowercase().replace(".", "").takeIf { it.isNotEmpty() }
        val endHourRaw = match.groupValues[4].toIntOrNull() ?: return null
        val endMinute = match.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val endMeridiem = match.groupValues[6].lowercase().replace(".", "")

        if (startMinute > 59 || endMinute > 59) return null

        // Resolve end time first (it always has a meridiem)
        val endHour = resolveMeridiemHour(endHourRaw, endMeridiem) ?: return null

        // Resolve start time: use its own meridiem if present, otherwise infer from end meridiem
        val startHour = if (startMeridiem != null) {
            resolveMeridiemHour(startHourRaw, startMeridiem) ?: return null
        } else {
            resolveMeridiemHour(startHourRaw, endMeridiem) ?: return null
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
        // Two alternations: group 1-3 = colon form, group 4-5 = meridiem-only form
        val hour: Int
        val minute: Int
        val meridiem: String

        if (match.groupValues[1].isNotEmpty()) {
            // Colon form: "15:00", "3:30pm"
            hour = match.groupValues[1].toIntOrNull() ?: return null
            minute = match.groupValues[2].toIntOrNull() ?: 0
            meridiem = match.groupValues[3].lowercase().replace(".", "")
        } else {
            // Meridiem-only form: "3pm"
            hour = match.groupValues[4].toIntOrNull() ?: return null
            minute = 0
            meridiem = match.groupValues[5].lowercase().replace(".", "")
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
                // 24-hour format or ambiguous
                if (hour > 23) return null
                hour
            }
        }

        return Token(TokenType.TIME, word, LocalTime.of(resolvedHour, minute), originalText)
    }

    data class TimeRange(val start: LocalTime, val end: LocalTime)

    data class DateParts(val day: Int, val month: Int, val year: Int?)

    private fun parseStructuredDate(word: String, match: MatchResult, originalText: String = word): Token {
        val part1 = match.groupValues[1].toInt()
        val part2 = match.groupValues[2].toInt()
        val part3 = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()

        val separator = word.first { it == '/' || it == '-' || it == '.' }

        val dateParts = when {
            // ISO: Y-M-D (year is 4 digits in first position)
            separator == '-' && part1 > 31 -> DateParts(
                day = part3 ?: part2,
                month = part2,
                year = part1
            )
            // European: D.M.Y (dot separator)
            separator == '.' -> DateParts(
                day = part1,
                month = part2,
                year = resolveYear(part3)
            )
            // D/M/Y or D-M-Y: first number > 12 can't be a month
            part1 > 12 -> DateParts(
                day = part1,
                month = part2,
                year = resolveYear(part3)
            )
            // M/D or M/D/Y (US style, month-first)
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
