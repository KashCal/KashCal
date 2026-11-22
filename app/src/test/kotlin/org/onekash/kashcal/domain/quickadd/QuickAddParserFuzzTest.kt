package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Feeds Quick Add random phrases built from date, time, range, length and repeat fragments mixed
 * with plain words, and checks what must hold for any input:
 *  - parsing never throws;
 *  - an end date is never before the start date;
 *  - the title and the location only hold characters the user typed, in order (both are cut
 *    from the typed text).
 *
 * The seed and count are fixed so the run is the same every time; a longer or different run takes
 * `-Pquickadd.fuzz.seed=` and `-Pquickadd.fuzz.iterations=`. A failure prints the seed and up to
 * [MAX_REPORTED] failing inputs.
 */
class QuickAddParserFuzzTest {

    private val seed: Long = System.getProperty("quickadd.fuzz.seed")?.toLongOrNull() ?: DEFAULT_SEED
    private val iterations: Int =
        System.getProperty("quickadd.fuzz.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS

    // Reference: Monday April 13, 2026, 10:00 AM
    private val reference = LocalDateTime.of(2026, 4, 13, 10, 0)

    @Test
    fun `random phrases parse without errors, backwards events or untyped text`() {
        val random = Random(seed)
        val findings = mutableListOf<String>()
        for (i in 0 until iterations) {
            val input = phrase(random)
            val weekStart = if (random.nextBoolean()) Calendar.SUNDAY else Calendar.MONDAY
            val problem = try {
                violation(QuickAddParser.parse(input, reference, Locale.US, weekStart), input)
            } catch (e: Throwable) {
                "threw $e"
            }
            if (problem != null) findings += "[$input] (week start $weekStart): $problem"
        }
        if (findings.isNotEmpty()) {
            fail(
                "${findings.size} of $iterations random phrases broke a rule (seed=$seed, " +
                    "-Pquickadd.fuzz.seed=$seed):\n" + findings.take(MAX_REPORTED).joinToString("\n")
            )
        }
    }

    /** Returns what [result] breaks for [input], or null. */
    private fun violation(result: QuickAddResult, input: String): String? {
        val end = result.endDate
        if (end != null && end.isBefore(result.startDate)) return "ends $end before it starts ${result.startDate}"
        if (!isTypedSubsequence(result.title, input)) return "title '${result.title}' is not typed text"
        val location = result.location
        if (location != null && !isTypedSubsequence(location, input)) return "location '$location' is not typed text"
        return null
    }

    /** True when every non-space character of [text] appears in [typed] in the same order. */
    private fun isTypedSubsequence(text: String, typed: String): Boolean {
        var at = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            at = typed.indexOf(c, at)
            if (at < 0) return false
            at++
        }
        return true
    }

    private fun phrase(random: Random): String {
        val parts = mutableListOf<String>()
        repeat(1 + random.nextInt(6)) {
            parts += if (random.nextInt(3) == 0) WORDS.random(random) else fragment(random)
        }
        val joiner = listOf(" ", " ", " ", "  ", ", ", " \u2014 ", "\t").random(random)
        return parts.joinToString(joiner)
    }

    private fun fragment(random: Random): String {
        val month = MONTHS.random(random)
        val weekday = WEEKDAYS.random(random)
        val day = random.nextInt(0, 33)
        val day2 = random.nextInt(0, 33)
        val hour = random.nextInt(0, 25)
        val hour2 = random.nextInt(0, 25)
        val dash = DASHES.random(random)
        return when (random.nextInt(16)) {
            0 -> "every $weekday"
            1 -> "until $month $day"
            2 -> "until $month $day ${random.nextInt(2024, 2030)}"
            3 -> "$month $day$dash$day2"
            4 -> "$month $day to $day2"
            5 -> "$day$dash$day2 $month"
            6 -> "${hour}pm$dash${hour2}pm"
            7 -> "from $hour to $hour2"
            8 -> "at $hour"
            9 -> "for ${random.nextInt(0, 200)} ${listOf("minutes", "hours", "min", "h").random(random)}"
            10 -> "${random.nextInt(0, 25)}h${random.nextInt(0, 60).toString().padStart(2, '0')}$dash${hour2}h"
            11 -> "$weekday ${listOf("this week", "next week", "of next week").random(random)}"
            12 -> "${listOf("tonight", "this evening", "tomorrow afternoon", "every morning").random(random)} at $hour"
            13 -> "$weekday to ${WEEKDAYS.random(random)}"
            14 -> "every ${random.nextInt(0, 33)}${listOf("st", "nd", "rd", "th").random(random)}"
            else -> "$month $day $hour:${random.nextInt(0, 60).toString().padStart(2, '0')}"
        }
    }

    private companion object {
        const val DEFAULT_SEED = 20261029L
        const val DEFAULT_ITERATIONS = 3000
        const val MAX_REPORTED = 20

        val MONTHS = listOf("jan", "feb", "mar", "june", "aug", "sep", "dec", "December")
        val WEEKDAYS = listOf("monday", "tue", "Friday", "sunday", "sat")
        val DASHES = listOf("-", "\u2013", "\u2014", " - ", " \u2014 ")
        val WORDS = listOf(
            "Lunch", "with", "Sam", "at", "Blue Bottle", "tomorrow", "today", "noon", "midnight",
            "#work", "// bring notes", "a", "an", "a couple of", "half an hour", "and", "of", "the",
            "🎉", "👨‍👩‍👧", "🇺🇸",
            "\"Dune\"", "(downtown)", "@", "!", "?", ":", "/", "Q3", "2026", "99", "0", "24", "12:30:",
            "pst", "o'clock", "every", "once a week", "everyday", "each", "on mondays", "until",
            "end of the month", "a.m.", "p.m.", "3pm", "9am", "15:00", "9.30", "100m", "2h45m",
        )
    }
}
