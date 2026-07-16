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
 * Differential/oracle fuzz test for the hand-rolled RFC 5545 duration parsers.
 *
 * KashCal has three independent duration parsers, none delegating to each other:
 *  - `DateTimeUtils.parseDurationToMillis` (app) — device-calendar DURATION column
 *  - `ReminderScheduler.parseIsoDuration` (app) — VALARM TRIGGER strings
 *  - `icaldav-core` `DurationUtils.parse` — the library parser, well-tested
 *
 * This test generates random, well-formed *positive* RFC 5545 durations (the
 * shared space all three claim to handle) and — because it generates the
 * component values itself — knows the ground-truth milliseconds. It checks each
 * parser against that ground truth, so a mismatch identifies *which* parser is
 * wrong, not merely that two disagree.
 *
 * Seed + iterations are fixed constants, overridable via -Dfuzz.duration.seed=
 * and -Dfuzz.duration.iterations= for longer nightly runs. A failure prints the
 * exact input, the ground truth, and each parser's answer.
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
        // The generator above caps magnitudes so it can assert ground truth, which
        // means it can NEVER exercise Long overflow — yet a silent overflow to a
        // negative duration was the actual production bug. This case guards that
        // class explicitly, ground-truth-free: on inputs whose true millisecond
        // total exceeds Long, no parser may return a negative (end-before-start),
        // and the two null-returning parsers must fail safe to null.
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

            // icaldav may either fail safe (null) or throw on .toMillis(); what it
            // must never do is return a negative. Mirror the caller's guarded call.
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
        // Keep magnitudes modest so ground-truth millis never overflows Long and the
        // input stays in the space all three parsers claim to handle.
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
            // Guarantee at least one component so we never emit a bare "P".
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
