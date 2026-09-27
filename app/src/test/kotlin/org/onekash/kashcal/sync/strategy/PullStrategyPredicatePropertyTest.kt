package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Property tests for pure timestamp functions the pull path applies to every server event:
 * [PullStrategy.hasValidTimestamps] and the occurrence end-day computation
 * [DateTimeUtils.eventTsToEndDayCode]. They take raw server timestamps, so they must be total:
 * never throw, always self-consistent. hasValidTimestamps gets hostile values (huge, negative,
 * past the 32-bit time_t boundary behind issue #326); the end-day test stays between the epoch
 * and about 2200, both sides of 2038. A seeded generator makes failures reproducible
 * (`fuzz.predicate.seed` and `fuzz.predicate.iterations` override it).
 */
class PullStrategyPredicatePropertyTest {

    private companion object {
        val SEED = System.getProperty("fuzz.predicate.seed")?.toLong() ?: 0xC0FFEEL
        val ITERATIONS = System.getProperty("fuzz.predicate.iterations")?.toInt() ?: 5_000

        // 2038-01-19T03:14:07Z in ms: the signed 32-bit time_t ceiling that a SOGo-class server
        // silently truncates past. The pull path must stay total on both sides of it.
        const val TIME_RANGE_32BIT_MS = 2_147_483_647_000L
        const val YEAR_2100_MS = 4_102_444_800_000L
        val UTC: ZoneId = ZoneId.of("UTC")
    }

    private fun event(startTs: Long, endTs: Long): Event = Event(
        uid = "prop@test",
        calendarId = 1L,
        title = "P",
        startTs = startTs,
        endTs = endTs,
        dtstamp = 0L,
        syncStatus = SyncStatus.SYNCED,
    )

    // ---- hasValidTimestamps ----

    @Test
    fun `hasValidTimestamps is exactly endTs greater-or-equal startTs for random pairs`() {
        val rnd = Random(SEED)
        repeat(ITERATIONS) {
            val a = randomInterestingTs(rnd)
            val b = randomInterestingTs(rnd)
            val valid = PullStrategy.hasValidTimestamps(event(a, b))
            assertEquals(
                "hasValidTimestamps must equal (endTs >= startTs) for start=$a end=$b",
                b >= a,
                valid,
            )
        }
    }

    @Test
    fun `hasValidTimestamps accepts equal timestamps (zero-duration is valid)`() {
        listOf(0L, TIME_RANGE_32BIT_MS, YEAR_2100_MS, Long.MAX_VALUE, -1L).forEach { ts ->
            assertTrue("zero-duration at $ts must be valid", PullStrategy.hasValidTimestamps(event(ts, ts)))
        }
    }

    @Test
    fun `hasValidTimestamps accepts far-future multi-day events surfaced past the 32-bit boundary`() {
        // The shape of event behind issue #326: starts before 2038, ends well after.
        val start = TIME_RANGE_32BIT_MS - 86_400_000L
        val end = YEAR_2100_MS
        assertTrue(PullStrategy.hasValidTimestamps(event(start, end)))
    }

    // ---- eventTsToEndDayCode: total + well-formed for the far-future range ----

    @Test
    fun `eventTsToEndDayCode yields a well-formed YYYYMMDD for random far-future ranges`() {
        val rnd = Random(SEED xor 0x5A5AL)
        repeat(ITERATIONS) {
            // A wide but sane window: the epoch to about 2200, both sides of 2038.
            val start = rnd.nextLong(0L, 7_258_118_400_000L)
            val end = start + rnd.nextLong(0L, 400L * 24 * 60 * 60 * 1000)
            val code = DateTimeUtils.eventTsToEndDayCode(
                endTs = end, startTs = start, isAllDay = false, localZone = UTC,
            )
            assertWellFormedDayCode(code, start, end)
            assertTrue(
                "endDay code $code must be >= startDay for start=$start end=$end",
                code >= DateTimeUtils.eventTsToDayCode(start, isAllDay = false, localZone = UTC),
            )
        }
    }

    private fun assertWellFormedDayCode(code: Int, start: Long, end: Long) {
        val year = code / 10000
        val month = (code / 100) % 100
        val day = code % 100
        assertTrue("year out of range in $code (start=$start end=$end)", year in 1900..2300)
        assertTrue("month out of range in $code (start=$start end=$end)", month in 1..12)
        assertTrue("day out of range in $code (start=$start end=$end)", day in 1..31)
    }

    /** Mix of ordinary, boundary, and pathological timestamps. */
    private fun randomInterestingTs(rnd: Random): Long = when (rnd.nextInt(8)) {
        0 -> 0L
        1 -> -rnd.nextLong(0, Long.MAX_VALUE)
        2 -> TIME_RANGE_32BIT_MS
        3 -> TIME_RANGE_32BIT_MS + rnd.nextLong(0, 100_000_000_000L)
        4 -> YEAR_2100_MS
        5 -> Long.MAX_VALUE
        6 -> Long.MIN_VALUE
        else -> rnd.nextLong()
    }
}
