package org.onekash.kashcal.domain.availability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.insights.SimpleOccurrence
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Tests [FreeBlockFinder.find] with plain [SimpleOccurrence] inputs.
 *
 * Tests cover empty and fully busy days, splits and the minimum block length, clipping today to
 * now, multi-day timed events, all-day handling (the toggle, UTC-anchored day codes, multi-day
 * spans), a block ending at workEnd, a DST day, the passed zone, pre-filtered cancellation, the
 * 1440 end-of-day sentinel, and TRANSP filtering.
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
        // Events 09:00-12:00 and 12:45-17:00 leave a 45-minute gap.
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
        // Now is 12:00 on day 1; work hours 09-17.
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
        // Now is 07:30 on day 1.
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
        // An event from Mon 14:00 to Tue 11:00 leaves Mon 09-14 and Tue 11-17 free.
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
        // CalendarProvider stores all-day events at UTC midnight whatever the viewer's zone,
        // while startDay/endDay are YYYYMMDD codes already matching the user's date
        // (DateTimeUtils.eventTsToDayCode uses UTC for isAllDay = true). All-day matching
        // must use the codes: read in Tokyo (UTC+9), Monday's end (just before Tue 00:00Z)
        // falls on Tuesday morning, which would blank Tuesday.
        val tokyo = ZoneId.of("Asia/Tokyo")
        fun utcMidnight(date: LocalDate): Long =
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // The Room Event.endTs convention: the inclusive last ms of the last day, not the
        // RFC-exclusive next midnight. AndroidCalendarProviderRepository subtracts the same
        // 1 ms from a device all-day end before computing its day code.
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
        // A multi-day all-day event Mon to Wed (startDay = Mon, endDay = Wed) blanks all
        // three days, Tuesday included.
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

        // Control: with the toggle off, the same input gives 3 blocks, so the empty result
        // above comes from the all-day path, not a work window that zeroes out every day.
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
        // An event 09:00-15:00 leaves 15:00-17:00 (120 min), a block ending at workEnd.
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
        // 09:00-17:00 is wholly in EDT after the transition, so the block is 8 hours.
        assertEquals(1, blocks.size)
        assertEquals(LocalTime.of(9, 0), blocks[0].start)
        assertEquals(LocalTime.of(17, 0), blocks[0].end)
        assertEquals(8 * 60L, blocks[0].durationMinutes)
    }

    // ========== Per-call params (no implicit zone) ==========

    @Test
    fun `passed zone is used not systemDefault`() {
        // A finder using ZoneId.systemDefault() would give other boundaries on a host
        // outside Tokyo. The test passes Tokyo and checks Tokyo-local times.
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

    // ========== Cancellation Filtered by the Caller ==========

    @Test
    fun `finder respects pre-filtered input — does not re-filter cancellation`() {
        // InsightOccurrence has no cancelled flag, so the finder can't filter: callers filter
        // upstream. InsightsRepository.getOccurrencesForRange takes its Room rows from
        // OccurrencesDao.getOccurrencesWithEventsForInsights, which drops cancelled rows. This
        // test only shows that a non-cancelled occurrence in the input is busy.
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

    // ========== Fully Busy Day ==========

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
            workEndMin = 1440, // 24:00; must not reach LocalTime.of(24, 0), which throws
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
        // An event 23:00-23:59 in a 09:00-1440 window leaves 09:00-23:00 free.
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

    // ========== TRANSP Filtering (RFC 5545) and Minimum Block ==========

    @Test
    fun `transparent timed event does not split the work window`() {
        // An event 12:00-14:00 marked TRANSPARENT (free) isn't busy, so 09:00-17:00 stays
        // one free block.
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
        // A TRANSPARENT all-day event on Monday is a free marker (a remote-work flag, say),
        // not a busy day. Even with includeAllDayAsBusy = true the day stays free.
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
        // 10-11 TRANSPARENT is ignored; 13-14 OPAQUE splits the window into 09-13 and 14-17.
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
        // 11:30-12:00 is exactly 30 min, which passes with minBlockMinutes = 30.
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
