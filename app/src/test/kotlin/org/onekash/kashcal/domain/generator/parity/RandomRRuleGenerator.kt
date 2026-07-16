package org.onekash.kashcal.domain.generator.parity

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Generates random but WELL-FORMED and UNAMBIGUOUS [RRuleCase]s to drive the
 * two-engine differential oracle (see [RRuleDifferentialFuzzTest]).
 *
 * Design intent: stay inside the valid, unambiguous, bounded RRULE space so any
 * cross-engine divergence is a genuine correctness finding rather than benign
 * noise. Specifically, generated rules:
 *  - use only DAILY/WEEKLY/MONTHLY/YEARLY (sub-daily frequencies add DST-fold
 *    ambiguity and iteration-cap divergence that both engines legitimately
 *    disagree on — those live in the curated AdversarialCorpus instead);
 *  - anchor in UTC on a whole second, so no DST gap/overlap and no sub-second
 *    truncation difference between engines;
 *  - **derive every BY-part from the chosen DTSTART** so DTSTART itself always
 *    satisfies the rule. RFC 5545 §3.8.5.3 states the recurrence set generated
 *    with a DTSTART "not synchronized with the recurrence rule is undefined" —
 *    so an unsynchronized DTSTART lets engines legitimately differ. Deriving the
 *    parts keeps every generated case in the spec-defined space.
 *  - terminate via COUNT, a UTC UNTIL, or the range clamp — never COUNT+UNTIL
 *    together (RFC 5545 §3.3.10 leaves that undefined too).
 *
 * Malformed-input robustness is out of scope here by design; the Jazzer
 * never-throw harnesses and AdversarialCorpus cover that. This generator's job
 * is to find inputs where the two engines both succeed but *disagree*.
 */
class RandomRRuleGenerator(private val random: Random) {

    private companion object {
        // Fixed UTC anchors so generation is deterministic and DST plays no part.
        val RANGE_START: Long = utc(2024, 1, 1)
        val RANGE_END: Long = utc(2028, 1, 1) // 4-year window gives YEARLY rules room
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000

        // ical4j / lib-recur weekday tokens, indexed by DayOfWeek.value (1=MON..7=SUN).
        val WEEKDAY_TOKENS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        val UNTIL_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        fun utc(y: Int, m: Int, d: Int): Long =
            ZonedDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun nextCase(index: Int): RRuleCase {
        // DTSTART: whole-second UTC instant in the first year of the window, but
        // constrained to day-of-month 1..28 so a monthly BYMONTHDAY derived from
        // it exists in every month.
        val startDay = random.nextLong(0, 300)
        val secondsIntoDay = random.nextLong(0, ONE_DAY_MS / 1000) * 1000
        val rawDtstart = RANGE_START + startDay * ONE_DAY_MS + secondsIntoDay
        val dt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawDtstart), ZoneOffset.UTC)
        // Clamp day-of-month into 1..28.
        val dtStart = dt.withDayOfMonth(((dt.dayOfMonth - 1) % 28) + 1)
        val dtstartMs = dtStart.toInstant().toEpochMilli()

        val freq = listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY").random(random)
        val parts = mutableListOf("FREQ=$freq")

        if (random.nextBoolean()) parts += "INTERVAL=${random.nextInt(1, 5)}"

        // Every BY-part below is derived from dtStart so DTSTART matches the rule.
        val dtWeekday = WEEKDAY_TOKENS[dtStart.dayOfWeek.value - 1]
        when (freq) {
            "WEEKLY" -> if (random.nextBoolean()) {
                // Always include dtStart's own weekday; optionally add others.
                val extra = WEEKDAY_TOKENS.shuffled(random).take(random.nextInt(0, 3))
                val set = (listOf(dtWeekday) + extra).distinct()
                parts += "BYDAY=${set.joinToString(",")}"
            }
            "MONTHLY" -> when (random.nextInt(3)) {
                0 -> parts += "BYMONTHDAY=${dtStart.dayOfMonth}"
                1 -> {
                    // Ordinal of dtStart's weekday within its month (1..4; dom<=28).
                    val ordinal = (dtStart.dayOfMonth - 1) / 7 + 1
                    parts += "BYDAY=$ordinal$dtWeekday"
                }
                else -> {} // plain monthly on the DTSTART day
            }
            "YEARLY" -> if (random.nextBoolean()) parts += "BYMONTH=${dtStart.monthValue}"
            else -> {} // DAILY: no BY* part
        }

        // Termination: COUNT | UNTIL | neither (range clamp). Never both.
        when (random.nextInt(3)) {
            0 -> parts += "COUNT=${random.nextInt(1, 25)}"
            1 -> parts += "UNTIL=${randomUntil(dtstartMs)}"
            else -> {}
        }

        return RRuleCase(
            name = "fuzz #$index: ${parts.joinToString(";")}",
            category = "fuzz",
            rrule = parts.joinToString(";"),
            dtstartMs = dtstartMs,
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = RANGE_START,
            rangeEndMs = RANGE_END,
        )
    }

    private fun randomUntil(dtstartMs: Long): String {
        // A UTC UNTIL strictly after dtstart, within the window.
        val span = (RANGE_END - dtstartMs).coerceAtLeast(ONE_DAY_MS + 1)
        val untilMs = dtstartMs + random.nextLong(ONE_DAY_MS, span)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(untilMs), ZoneOffset.UTC)
            .format(UNTIL_FMT)
    }
}
