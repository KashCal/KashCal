package org.onekash.kashcal.domain.generator.parity

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Generates random well-formed, unambiguous [RRuleCase]s for [RRuleDifferentialFuzzTest].
 *
 * Generation stays inside the valid, unambiguous, bounded RRULE space, so a cross-engine
 * divergence is a correctness finding, not benign noise. Generated rules:
 *  - use only DAILY, WEEKLY, MONTHLY or YEARLY. Sub-daily frequencies add DST-fold ambiguity
 *    and iteration-cap divergence the engines legitimately disagree on; those cases live in the
 *    curated AdversarialCorpus.
 *  - anchor in UTC on a whole second, so there is no DST gap or overlap and no sub-second
 *    truncation difference.
 *  - derive every BY-part from the chosen DTSTART, so DTSTART always satisfies the rule. RFC 5545
 *    §3.8.5.3: the recurrence set generated with a DTSTART "not synchronized with the recurrence
 *    rule is undefined", so engines may legitimately differ on one.
 *  - end by COUNT, a UTC UNTIL, or the range end, never COUNT and UNTIL together (RFC 5545
 *    §3.3.10: they "MUST NOT occur in the same 'recur'").
 *
 * Malformed-input robustness is out of scope; the Jazzer never-throw harnesses and
 * AdversarialCorpus cover it. This generator looks for inputs where both engines succeed but
 * disagree.
 */
class RandomRRuleGenerator(private val random: Random) {

    private companion object {
        // Fixed UTC anchors so generation is deterministic and DST plays no part.
        val RANGE_START: Long = utc(2024, 1, 1)
        val RANGE_END: Long = utc(2028, 1, 1) // 4-year window gives YEARLY rules room
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000

        // RFC 5545 weekday tokens, indexed by DayOfWeek.value - 1 (0=MO..6=SU).
        val WEEKDAY_TOKENS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        val UNTIL_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        fun utc(y: Int, m: Int, d: Int): Long =
            ZonedDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun nextCase(index: Int): RRuleCase {
        // DTSTART: a whole-second UTC instant in the first year of the window, with
        // day-of-month clamped to 1..28 so a monthly BYMONTHDAY derived from it exists in
        // every month.
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
                    // Nth weekday of dtStart's own weekday. DTSTART must satisfy the rule
                    // or the recurrence set is undefined (RFC 5545 §3.8.5.3) and the test
                    // flaps. The positional ordinal always matches; "last" (-1) matches
                    // only when dtStart is the last <weekday> of its month, so -1 is
                    // emitted only then. That keeps -1FR-style expansion in the oracle
                    // without noise.
                    val positional = (dtStart.dayOfMonth - 1) / 7 + 1
                    val isLastOfWeekdayInMonth =
                        dtStart.dayOfMonth + 7 > dtStart.toLocalDate().lengthOfMonth()
                    val ordinal = if (isLastOfWeekdayInMonth && random.nextBoolean()) -1 else positional
                    parts += "BYDAY=$ordinal$dtWeekday"
                }
                else -> {} // plain monthly on the DTSTART day
            }
            "YEARLY" -> if (random.nextBoolean()) parts += "BYMONTH=${dtStart.monthValue}"
            else -> {} // DAILY: no BY* part
        }

        // Termination: COUNT, UNTIL, or neither (the range end). Never both.
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
        // A UTC UNTIL at least a day after dtstart and before the window end.
        val span = (RANGE_END - dtstartMs).coerceAtLeast(ONE_DAY_MS + 1)
        val untilMs = dtstartMs + random.nextLong(ONE_DAY_MS, span)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(untilMs), ZoneOffset.UTC)
            .format(UNTIL_FMT)
    }
}
