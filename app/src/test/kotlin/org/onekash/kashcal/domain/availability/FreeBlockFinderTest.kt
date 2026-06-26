package org.onekash.kashcal.domain.availability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.insights.SimpleOccurrence
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Pure-logic tests for FreeBlockFinder.
 *
 * FreeBlockFinder is the public surface for free-block computation, so direct
 * unit tests are appropriate here.
 */
class FreeBlockFinderTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val finder = FreeBlockFinder()

    // Reference Monday so day-of-week math is deterministic.
    private val mon = LocalDate.of(2026, 5, 25)

    private fun dayCode(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    private fun timed(
        date: LocalDate, startH: Int, startM: Int, endH: Int, endM: Int,
        transparency: String = "OPAQUE"
    ): SimpleOccurrence {
        val s = ZonedDateTime.of(date, LocalTime.of(startH, startM), zone).toInstant().toEpochMilli()
        val e = ZonedDateTime.of(date, LocalTime.of(endH, endM), zone).toInstant().toEpochMilli()
        return SimpleOccurrence(s, e, false, dayCode(date), dayCode(date), 1L, transparency)
    }

    private fun allDay(date: LocalDate, transparency: String = "OPAQUE"): SimpleOccurrence {
        val s = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val e = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return SimpleOccurrence(s, e, true, dayCode(date), dayCode(date), 1L, transparency)
    }

    private fun nowAt(date: LocalDate, h: Int, m: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    // ========== Empty range ==========

    @Test
    fun `fully empty range returns one block per day spanning full work window`() {
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0), // before range starts
            zone = zone
        )
        assertEquals(3, blocks.size)
        blocks.forEach { block ->
            assertEquals(LocalTime.of(9, 0), block.start)
            assertEquals(LocalTime.of(17, 0), block.end)
            assertEquals(8 * 60L, block.durationMinutes)
        }
        assertEquals(mon, blocks[0].day)
        assertEquals(mon.plusDays(1), blocks[1].day)
        assertEquals(mon.plusDays(2), blocks[2].day)
    }

    // ========== Splits + filtering ==========

    @Test
    fun `single timed event splits day into two blocks`() {
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 12, 0, 14, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(2, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(12, 0), blocks[0].end)
        assertEquals(LocalTime.of(14, 0), blocks[1].start)
        assertEquals(LocalTime.of(17, 0), blocks[1].end)
    }

    @Test
    fun `sub-threshold gap is filtered out`() {
        // Event 09:00-12:00, then 12:45-17:00 — leaves a 45-minute gap.
        val blocks = finder.find(
            occurrences = listOf(
                timed(mon, 9, 0, 12, 0),
                timed(mon, 12, 45, 17, 0)
            ),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertTrue("45-minute gap should not appear with min=60", blocks.isEmpty())
    }

    // ========== Today clipping ==========

    @Test
    fun `today clipped to now when now is mid-window`() {
        // now = 12:00 on day 1, work 09-17.
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon, 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(12, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
    }

    @Test
    fun `today omitted entirely when now is past workEnd`() {
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = mon,
            days = 2,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon, 21, 30),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(mon.plusDays(1), blocks[0].day)
    }

    @Test
    fun `today preserved fully when now is before workStart`() {
        // now = 07:30 on day 1.
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = mon,
            days = 1,
            workStartMin = 480,
            workEndMin = 1200,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon, 7, 30),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(8, 0), blocks[0].start)
        assertEquals(LocalTime.of(20, 0), blocks[0].end)
    }

    // ========== Multi-day spanning event ==========

    @Test
    fun `multi-day event reduces each covered day's window`() {
        // Event from Mon 14:00 to Tue 11:00. Mon afternoon clipped to 09-14 and
        // Tue morning clipped to 11-17.
        val s = ZonedDateTime.of(mon, LocalTime.of(14, 0), zone).toInstant().toEpochMilli()
        val e = ZonedDateTime.of(mon.plusDays(1), LocalTime.of(11, 0), zone).toInstant().toEpochMilli()
        val multiDay = SimpleOccurrence(s, e, false, dayCode(mon), dayCode(mon.plusDays(1)), 1L)

        val blocks = finder.find(
            occurrences = listOf(multiDay),
            startDay = mon,
            days = 2,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(2, blocks.size)
        // Mon morning block.
        assertEquals(mon, blocks[0].day)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(14, 0), blocks[0].end)
        // Tue afternoon block.
        assertEquals(mon.plusDays(1), blocks[1].day)
        assertEquals(LocalTime.of(11, 0), blocks[1].start)
        assertEquals(LocalTime.of(17, 0), blocks[1].end)
    }

    // ========== All-day handling ==========

    @Test
    fun `all-day events ignored when includeAllDayAsBusy is false`() {
        val blocks = finder.find(
            occurrences = listOf(allDay(mon)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
    }

    @Test
    fun `all-day events block whole day when includeAllDayAsBusy is true`() {
        val blocks = finder.find(
            occurrences = listOf(allDay(mon)),
            startDay = mon,
            days = 2,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = true,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(mon.plusDays(1), blocks[0].day)
    }

    @Test
    fun `all-day Mon and Wed in non-UTC zone leave Tue free (UTC-anchored storage)`() {
        // Reproduces the device-storage convention: CalendarProvider stores all-day
        // events at UTC midnight regardless of the viewer's zone, but startDay/endDay
        // are pre-computed YYYYMMDD codes that already match the user's perceived date
        // (DateTimeUtils.eventTsToDayCode uses UTC for isAllDay=true).
        //
        // Bug: covers() used to re-derive startDate/endDate by reinterpreting the
        // UTC-midnight startTs/endTs in the local zone — for Tokyo (UTC+9), Monday's
        // all-day endTs of Tue 00:00Z resolves to Tue 09:00 Tokyo, so covers() returned
        // true for Tuesday and the day was incorrectly suppressed.
        val tokyo = ZoneId.of("Asia/Tokyo")
        fun utcMidnight(date: LocalDate): Long =
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Match Room/Event.endTs convention: stored inclusive (last ms of last day),
        // not RFC-exclusive next-day midnight. AndroidCalendarProviderRepository.kt:182
        // performs the same `endMs - 1` decrement before the day-code computation.
        fun utcInclusiveEnd(date: LocalDate): Long = utcMidnight(date.plusDays(1)) - 1

        val mondayAllDay = SimpleOccurrence(
            startTs = utcMidnight(mon),
            endTs = utcInclusiveEnd(mon),
            isAllDay = true,
            startDay = dayCode(mon),
            endDay = dayCode(mon),
            calendarId = 1L
        )
        val wednesdayAllDay = SimpleOccurrence(
            startTs = utcMidnight(mon.plusDays(2)),
            endTs = utcInclusiveEnd(mon.plusDays(2)),
            isAllDay = true,
            startDay = dayCode(mon.plusDays(2)),
            endDay = dayCode(mon.plusDays(2)),
            calendarId = 1L
        )

        val blocks = finder.find(
            occurrences = listOf(mondayAllDay, wednesdayAllDay),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = true,
            now = ZonedDateTime.of(mon.minusDays(1), LocalTime.of(12, 0), tokyo)
                .toInstant().toEpochMilli(),
            zone = tokyo
        )
        assertEquals("Tuesday should be free between two all-day events", 1, blocks.size)
        assertEquals(mon.plusDays(1), blocks[0].day)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
    }

    @Test
    fun `genuine multi-day all-day event suppresses all covered days`() {
        // Regression guard for the covers() fix: a real multi-day all-day Mon->Wed
        // event (startDay = Mon, endDay = Wed) must still suppress Tuesday.
        val multiDayAllDay = SimpleOccurrence(
            startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli(),
            endTs = mon.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli(),
            isAllDay = true,
            startDay = dayCode(mon),
            endDay = dayCode(mon.plusDays(2)),
            calendarId = 1L
        )

        val suppressed = finder.find(
            occurrences = listOf(multiDayAllDay),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = true,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertTrue("Mon-Wed all-day spanning event must suppress all three days", suppressed.isEmpty())

        // Control: with the toggle OFF, the same input must produce 3 blocks.
        // Proves emptiness above came from the all-day path, not from a broken
        // work-window calculation that would silently zero out every day.
        val notSuppressed = finder.find(
            occurrences = listOf(multiDayAllDay),
            startDay = mon,
            days = 3,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(
            "With toggle off, the same input must produce one free block per day",
            3,
            notSuppressed.size
        )
    }

    // ========== Closed-interval boundary ==========

    @Test
    fun `block ending exactly at workEnd is included`() {
        // Event 09:00-15:00 leaves 15:00-17:00 (= 120 min) as a free block at workEnd.
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 9, 0, 15, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(15, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
    }

    // ========== DST transition ==========

    @Test
    fun `DST spring-forward day handled without off-by-one`() {
        // 2026-03-08 is the spring-forward date in America/New_York (23-hour day).
        val dstDay = LocalDate.of(2026, 3, 8)
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = dstDay,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(dstDay.minusDays(1), 12, 0),
            zone = zone
        )
        // Free window 09:00-17:00 is wholly in EDT after the transition; no off-by-one.
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
        assertEquals(8 * 60L, blocks[0].durationMinutes)
    }

    // ========== Per-call params (no implicit zone) ==========

    @Test
    fun `passed zone is used not systemDefault`() {
        // If finder captured ZoneId.systemDefault(), running this test on a host
        // with TZ != Tokyo would yield different boundaries. We pass Tokyo
        // explicitly and verify Tokyo-local times in the output.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val tokyoNoonInstant = ZonedDateTime.of(mon, LocalTime.of(12, 0), tokyo)
            .toInstant().toEpochMilli()
        val tokyoOnePmInstant = ZonedDateTime.of(mon, LocalTime.of(13, 0), tokyo)
            .toInstant().toEpochMilli()
        val tokyoEvent = SimpleOccurrence(
            tokyoNoonInstant, tokyoOnePmInstant, false, dayCode(mon), dayCode(mon), 1L
        )

        val blocks = finder.find(
            occurrences = listOf(tokyoEvent),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = ZonedDateTime.of(mon.minusDays(1), LocalTime.of(12, 0), tokyo)
                .toInstant().toEpochMilli(),
            zone = tokyo
        )
        // 09-12 and 13-17 in Tokyo local time.
        assertEquals(2, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(12, 0), blocks[0].end)
        assertEquals(LocalTime.of(13, 0), blocks[1].start)
        assertEquals(LocalTime.of(17, 0), blocks[1].end)
    }

    // ========== Caller responsibility for cancelled ==========

    @Test
    fun `finder respects pre-filtered input — does not re-filter cancellation`() {
        // The finder doesn't know about is_cancelled. It trusts callers to filter
        // upstream (matches getOccurrencesWithEventsForInsights query semantics).
        // This test asserts: a "regular" non-cancelled occurrence in input blocks
        // free time as expected — equivalent to what a caller-filtered list yields.
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 10, 0, 11, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        // Splits into 09-10 (60 min) and 11-17 (360 min); both pass the 60-min filter.
        assertEquals(2, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(10, 0), blocks[0].end)
        assertEquals(LocalTime.of(11, 0), blocks[1].start)
        assertEquals(LocalTime.of(17, 0), blocks[1].end)
    }

    // ========== Returns empty list for fully-busy day ==========

    @Test
    fun `fully busy day produces no blocks`() {
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 8, 0, 18, 0)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertTrue(blocks.isEmpty())
    }

    // ========== End-of-day sentinel (workEnd = 1440) ==========

    @Test
    fun `workEnd of 1440 (end of day) does not crash and clips block to end of day`() {
        val blocks = finder.find(
            occurrences = emptyList(),
            startDay = mon,
            days = 1,
            workStartMin = 0,
            workEndMin = 1440, // 24:00 — must not throw LocalTime.of(24,0)
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(0, 0), blocks[0].start)
        assertEquals(LocalTime.MAX, blocks[0].end)
        assertEquals(24 * 60L, blocks[0].durationMinutes)
    }

    @Test
    fun `event near end-of-day in a 1440 window leaves a leading free block`() {
        // Event 23:00-23:59 inside 09:00-1440 leaves 09:00-23:00 as a free block.
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 23, 0, 23, 59)),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1440,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertTrue(blocks.isNotEmpty())
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(23, 0), blocks[0].end)
    }

    // ========== Custom min block ==========

    // ========== Free-busy (RFC 5545 TRANSP) filtering ==========

    @Test
    fun `transparent timed event does not split the work window`() {
        // Event 12:00-14:00 marked TRANSPARENT (free) — must not contribute to
        // the busy mask. The full 09:00-17:00 window should remain a single
        // free block.
        val blocks = finder.find(
            occurrences = listOf(timed(mon, 12, 0, 14, 0, transparency = "TRANSPARENT")),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
        assertEquals(8 * 60L, blocks[0].durationMinutes)
    }

    @Test
    fun `transparent all-day event does not blank the day even when toggle is on`() {
        // All-day TRANSPARENT covers Monday — it's a "free" marker (e.g.
        // remote-work flag), not a busy day. Even with includeAllDayAsBusy=true
        // the day must not be blanked, and the timed window must remain free.
        val blocks = finder.find(
            occurrences = listOf(allDay(mon, transparency = "TRANSPARENT")),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = true,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
    }

    @Test
    fun `mixed busy and free events filter only the busy ones`() {
        // 10-11 TRANSPARENT (free, ignored), 13-14 OPAQUE (busy, splits window).
        // Expected output: 09-13 and 14-17.
        val blocks = finder.find(
            occurrences = listOf(
                timed(mon, 10, 0, 11, 0, transparency = "TRANSPARENT"),
                timed(mon, 13, 0, 14, 0, transparency = "OPAQUE")
            ),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 60,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(2, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(13, 0), blocks[0].end)
        assertEquals(LocalTime.of(14, 0), blocks[1].start)
        assertEquals(LocalTime.of(17, 0), blocks[1].end)
    }

    @Test
    fun `30-minute min threshold accepts 30-minute gap`() {
        // 11:30-12:00 is exactly 30 min — passes when minBlockMinutes=30.
        val blocks = finder.find(
            occurrences = listOf(
                timed(mon, 9, 0, 11, 30),
                timed(mon, 12, 0, 17, 0)
            ),
            startDay = mon,
            days = 1,
            workStartMin = 540,
            workEndMin = 1020,
            minBlockMinutes = 30,
            includeAllDayAsBusy = false,
            now = nowAt(mon.minusDays(1), 12, 0),
            zone = zone
        )
        assertEquals(1, blocks.size)
        assertEquals(30L, blocks[0].durationMinutes)
    }
}
