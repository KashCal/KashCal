package org.onekash.kashcal.domain.rrule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.generator.LibRecurEngine
import org.onekash.kashcal.domain.generator.parity.RandomRRuleGenerator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.random.Random

/**
 * Checks [RruleShift.shift] against a second recurrence engine, the test-only lib-recur one:
 * whenever it returns a rule, that rule must generate the old occurrences, each moved by the day
 * shift to the new time of day, and at least one generated rule must be rewritten. The plain
 * cases (one weekday a week, a same day of the month that stays within 1..28) must never be
 * refused, so a helper that refuses everything can't pass.
 *
 * The generator anchors in UTC, so daylight-saving moves are covered by [RruleShiftTest], not
 * here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RruleShiftOracleTest {

    private val savedZone: TimeZone = TimeZone.getDefault()

    // The generator's cases are in UTC; pin the phone to UTC so a default-zone read agrees.
    @Before
    fun pinUtc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(savedZone)

    private val seed = System.getProperty("fuzz.rruleshift.seed")?.toLongOrNull() ?: 20260925L
    private val iterations = System.getProperty("fuzz.rruleshift.iterations")?.toIntOrNull() ?: 400

    @Test
    fun `every rewritten rule generates the moved occurrences in a second engine`() {
        val random = Random(seed)
        val generator = RandomRRuleGenerator(random)
        var rewritten = 0
        repeat(iterations) { i ->
            val case = generator.nextCase(i)
            if (case.rdateStrings != null || case.exdateStrings != null) return@repeat
            val days = listOf(-3L, -2L, -1L, 1L, 2L, 3L)[random.nextInt(6)]
            val newTime = if (case.isAllDay) LocalTime.MIDNIGHT else LocalTime.of(random.nextInt(6, 22), random.nextInt(0, 4) * 15)
            val newStart = movedStart(case.dtstartMs, days, newTime, case.isAllDay)
            val shifted = RruleShift.shift(case.rrule, case.dtstartMs, newStart, case.timezone, case.isAllDay) ?: return@repeat
            rewritten++

            val old = libRecur(case.rrule, case.dtstartMs, case.timezone, case.isAllDay)
            val new = libRecur(shifted, newStart, case.timezone, case.isAllDay)
            val expected = old.map { movedStart(it, days, newTime, case.isAllDay) }
            assertEquals("seed $seed case $i: ${case.rrule} -> $shifted", expected, new)
        }
        assertTrue("some rules were rewritten (seed $seed)", rewritten > 0)
    }

    @Test
    fun `plain weekly and same-day monthly moves are never refused`() {
        val random = Random(seed)
        repeat(iterations) { i ->
            val days = listOf(-3L, -2L, -1L, 1L, 2L, 3L)[random.nextInt(6)]
            val start = ZonedDateTime.of(2024, 1 + random.nextInt(12), 1 + random.nextInt(28), 9, 0, 0, 0, ZoneOffset.UTC)
            val dayOfMonth = start.dayOfMonth
            val weekday = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[start.dayOfWeek.value - 1]
            val newStart = start.plusDays(days).toInstant().toEpochMilli()
            val weekly = "FREQ=WEEKLY;BYDAY=$weekday;COUNT=12"
            assertNotNull("case $i: $weekly", RruleShift.shift(weekly, start.toInstant().toEpochMilli(), newStart, "UTC", false))
            if (dayOfMonth + days in 1..28) {
                val monthly = "FREQ=MONTHLY;BYMONTHDAY=$dayOfMonth;COUNT=12"
                assertNotNull("case $i: $monthly", RruleShift.shift(monthly, start.toInstant().toEpochMilli(), newStart, "UTC", false))
            }
        }
    }

    private fun libRecur(rule: String, start: Long, tz: String?, isAllDay: Boolean): List<Long> =
        LibRecurEngine.expandToTimestamps(
            rrule = if (rule.contains("COUNT=") || rule.contains("UNTIL=")) rule else "$rule;COUNT=200",
            dtstartMs = start,
            rangeStartMs = start - 1_000,
            rangeEndMs = start + 200L * 366 * 86_400_000,
            timezone = tz,
            isAllDay = isAllDay,
            rdateStrings = null,
            exdateStrings = null,
        ).map { (it / 1_000) * 1_000 }

    /** [ts]'s date (in the generator's UTC) + [days] at [time]; all-day at UTC midnight. */
    private fun movedStart(ts: Long, days: Long, time: LocalTime, isAllDay: Boolean): Long {
        val date = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate().plusDays(days)
        return (if (isAllDay) date.atStartOfDay(ZoneOffset.UTC) else ZonedDateTime.of(date, time, ZoneOffset.UTC))
            .toInstant().toEpochMilli()
    }
}
