package org.onekash.kashcal.util.duration

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.reminder.scheduler.parseIsoDuration
import org.onekash.kashcal.util.DateTimeUtils
import kotlin.random.Random

/**
 * Checks the three independent RFC 5545 duration parsers against generated ground truth.
 *
 * None of the parsers delegates to another:
 *  - `DateTimeUtils.parseDurationToMillis` (app): the device-calendar DURATION column
 *  - `parseIsoDuration` in ReminderScheduler.kt (app): reminder and VALARM trigger durations
 *  - `DurationUtils.parse` (icaldav-core): the library parser
 *
 * The generator builds random well-formed positive durations (the space all three claim to
 * handle) from component values it chose, so it knows the true milliseconds. Each parser is
 * checked against that value, so a mismatch names the wrong parser, not only a disagreement.
 *
 * Seed and iterations are fixed constants, overridable with -Dfuzz.duration.seed= and
 * -Dfuzz.duration.iterations= for longer runs. A failure prints the input, the ground truth
 * and each parser's answer.
 */
class DurationParserDifferentialTest {

    private val seed: Long =
        System.getProperty("fuzz.duration.seed")?.toLongOrNull() ?: DEFAULT_SEED
    private val iterations: Int =
        System.getProperty("fuzz.duration.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS

    @Test
    fun `well-formed positive durations parse identically across all three parsers`() {
        val random = Random(seed)
        val findings = mutableListOf<String>()

        repeat(iterations) {
            val gen = nextDuration(random)
            val truth = gen.expectedMillis

            val dtUtils = DateTimeUtils.parseDurationToMillis(gen.text)
            val reminder = parseIsoDuration(gen.text)
            val icaldav = DurationUtils.parse(gen.text)?.toMillis()

            val mismatches = buildList {
                if (dtUtils != truth) add("DateTimeUtils.parseDurationToMillis=$dtUtils")
                if (reminder != truth) add("ReminderScheduler.parseIsoDuration=$reminder")
                if (icaldav != truth) add("icaldav DurationUtils.parse=$icaldav")
            }
            if (mismatches.isNotEmpty()) {
                findings += "input='${gen.text}' expected=$truth but " +
                    mismatches.joinToString(", ")
            }
        }

        println("Duration differential fuzz: ran $iterations cases (seed=$seed), " +
            "${findings.size} finding(s).")

        if (findings.isNotEmpty()) {
            // Show a bounded sample so the failure message stays readable.
            val shown = findings.take(40)
            val more = if (findings.size > shown.size) "\n… and ${findings.size - shown.size} more" else ""
            fail(
                "Found ${findings.size} duration parser divergence(s) from ground truth " +
                    "(seed=$seed). Reproduce with -Dfuzz.duration.seed=$seed.\n\n" +
                    shown.joinToString("\n") + more,
            )
        }
    }

    @Test
    fun `overflowing durations fail safe across all parsers - never a negative`() {
        // The generator caps magnitudes to know ground truth, so it never reaches Long
        // overflow, where a silent wrap gives a negative (end-before-start) duration. On
        // inputs whose true total exceeds Long, no parser may return a negative, and the
        // two app parsers must return null.
        val overflowing = listOf(
            "P999999999999W",
            "PT99999999999999999H",
            "P100000000000000D",
            "P1000000000000000000W",
        )
        for (input in overflowing) {
            val dtUtils = DateTimeUtils.parseDurationToMillis(input)
            val reminder = parseIsoDuration(input)

            assertNull("parseDurationToMillis must fail safe (null) on overflow: $input", dtUtils)
            assertNull("parseIsoDuration must fail safe (null) on overflow: $input", reminder)

            // icaldav may return null or throw from .toMillis(), but never a negative.
            val icaldav = try {
                DurationUtils.parse(input)?.toMillis()
            } catch (_: ArithmeticException) {
                null
            }
            if (icaldav != null) {
                assertTrue("icaldav must not return a negative duration on overflow: $input", icaldav >= 0)
            }
        }
    }

    /** A generated duration string plus the ground-truth milliseconds it encodes. */
    private data class GeneratedDuration(val text: String, val expectedMillis: Long)

    private fun nextDuration(random: Random): GeneratedDuration {
        // Two RFC 5545 shapes: week form (P{n}W) or date-time form (P{d}DT{h}H{m}M{s}S).
        // Magnitudes stay small so the ground truth never overflows a Long.
        return if (random.nextInt(4) == 0) {
            val weeks = random.nextInt(1, 100).toLong()
            GeneratedDuration("P${weeks}W", weeks * 7 * DAY_MS)
        } else {
            val days = if (random.nextBoolean()) random.nextInt(0, 60).toLong() else 0L
            val hours = if (random.nextBoolean()) random.nextInt(0, 48).toLong() else 0L
            val minutes = if (random.nextBoolean()) random.nextInt(0, 120).toLong() else 0L
            val seconds = if (random.nextBoolean()) random.nextInt(0, 120).toLong() else 0L

            val sb = StringBuilder("P")
            if (days > 0) sb.append("${days}D")
            val hasTime = hours > 0 || minutes > 0 || seconds > 0
            if (hasTime) {
                sb.append("T")
                if (hours > 0) sb.append("${hours}H")
                if (minutes > 0) sb.append("${minutes}M")
                if (seconds > 0) sb.append("${seconds}S")
            }
            // At least one component, so a bare "P" is never emitted.
            if (sb.length == 1) {
                sb.append("T1M")
                return GeneratedDuration(sb.toString(), 60_000L)
            }
            val millis = days * DAY_MS + hours * HOUR_MS + minutes * MIN_MS + seconds * SEC_MS
            GeneratedDuration(sb.toString(), millis)
        }
    }

    private companion object {
        const val SEC_MS = 1_000L
        const val MIN_MS = 60 * SEC_MS
        const val HOUR_MS = 60 * MIN_MS
        const val DAY_MS = 24 * HOUR_MS
        const val DEFAULT_SEED = 20260715L
        const val DEFAULT_ITERATIONS = 3000
    }
}
