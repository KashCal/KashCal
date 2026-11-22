package org.onekash.kashcal.domain.planner

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.planner.PlannerTestEvents.HOUR
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.PlannerTestEvents.room
import org.onekash.kashcal.domain.reader.EventReader
import java.time.LocalDate
import java.time.ZoneId

/** Tests [lookUpRoomSeriesAllDay], the series all-day flags [movedCue] reads for Room events. */
class SeriesAllDayLookupTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 27)

    private fun series(id: Long, isAllDay: Boolean) = Event(
        id = id, uid = "uid-$id", calendarId = 1L, title = "Series $id",
        startTs = 0L, endTs = HOUR, isAllDay = isAllDay, dtstamp = 0L, syncStatus = SyncStatus.SYNCED,
    )

    @Test
    fun `no changed occurrence reads nothing`() = runTest {
        val reader: EventReader = mockk()
        val events = listOf(room(id = 1, startTs = at(day, 9, zone = zone), endTs = at(day, 10, zone = zone), zone = zone))

        assertTrue(reader.lookUpRoomSeriesAllDay(events).isEmpty())
        coVerify(exactly = 0) { reader.getEventsByIds(any()) }
    }

    @Test
    fun `a changed occurrence gets its series all-day flag in one read`() = runTest {
        val start = at(day, 11, zone = zone)
        val events = listOf(
            room(id = 7, startTs = start, endTs = start + HOUR, zone = zone, seriesId = 5, originalInstanceTime = start - HOUR),
            room(id = 8, startTs = start + 2 * HOUR, endTs = start + 3 * HOUR, zone = zone, seriesId = 5, originalInstanceTime = start),
        )
        val reader: EventReader = mockk {
            coEvery { getEventsByIds(listOf(5L)) } returns mapOf(5L to series(5L, isAllDay = false))
        }

        assertEquals(mapOf(5L to false), reader.lookUpRoomSeriesAllDay(events))
        coVerify(exactly = 1) { reader.getEventsByIds(any()) }
    }

    @Test
    fun `a failed read returns an empty map so the events still show`() = runTest {
        val start = at(day, 11, zone = zone)
        val events = listOf(
            room(id = 7, startTs = start, endTs = start + HOUR, zone = zone, seriesId = 5, originalInstanceTime = start - HOUR),
        )
        val reader: EventReader = mockk {
            coEvery { getEventsByIds(any()) } throws IllegalStateException("db closed")
        }

        assertTrue(reader.lookUpRoomSeriesAllDay(events).isEmpty())
    }
}
