package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.OVERSIZED_ROW_CHARS

/**
 * Tests the SQL filter of [EventsDao.getMasterEventsWithFutureOccurrenceFlow]: masters and
 * one-off events only (no exceptions), a non-cancelled occurrence ending at or after now, not
 * PENDING_DELETE, sorted by next occurrence ascending; and that a row too large for the cursor
 * window still reads.
 */
class EventsDaoPendingInvitationsTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }

    private var calendarId: Long = 0

    @Before
    override fun setup() {
        super.setup()
        runTest {
            val accountId = accountsDao.insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            calendarId = calendarsDao.insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://cal",
                    displayName = "Test",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    private suspend fun insertEventWithOccurrence(
        uid: String,
        startTs: Long,
        endTs: Long = startTs + 3600_000L,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        originalEventId: Long? = null,
        cancelled: Boolean = false,
        description: String? = null,
        isAllDay: Boolean = false
    ): Long {
        val eventId = eventsDao.insert(
            Event(
                uid = uid,
                calendarId = calendarId,
                title = "T-$uid",
                startTs = startTs,
                endTs = endTs,
                dtstamp = 0L,
                syncStatus = syncStatus,
                originalEventId = originalEventId,
                description = description,
                isAllDay = isAllDay
            )
        )
        occurrencesDao.insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = 20240101,
                endDay = 20240101,
                isCancelled = cancelled
            )
        )
        return eventId
    }

    @Test
    fun `future master event is included`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(uid = "future", startTs = 5_000L)

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertEquals(1, rows.size)
        assertEquals("T-future", rows[0].title)
    }

    @Test
    fun `past event is excluded`() = runTest {
        val now = 10_000L
        insertEventWithOccurrence(uid = "past", startTs = 1_000L, endTs = 2_000L)

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `exception event is excluded`() = runTest {
        val now = 1_000L
        val masterId = insertEventWithOccurrence(uid = "master", startTs = 5_000L)
        insertEventWithOccurrence(
            uid = "exception",
            startTs = 6_000L,
            originalEventId = masterId
        )

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertEquals(1, rows.size)
        assertEquals("T-master", rows[0].title)
    }

    @Test
    fun `pending-delete event is excluded`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(
            uid = "pending-delete",
            startTs = 5_000L,
            syncStatus = SyncStatus.PENDING_DELETE
        )

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `cancelled occurrence is excluded`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(uid = "cancelled", startTs = 5_000L, cancelled = true)

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `multiple events sorted by next occurrence ascending`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(uid = "later", startTs = 50_000L)
        insertEventWithOccurrence(uid = "earlier", startTs = 10_000L)
        insertEventWithOccurrence(uid = "middle", startTs = 30_000L)

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertEquals(listOf("T-earlier", "T-middle", "T-later"), rows.map { it.title })
    }

    @Test
    fun `row with a description over the cursor window still reads`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(uid = "big", startTs = 5_000L, description = "A".repeat(OVERSIZED_ROW_CHARS))

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertEquals(listOf("T-big"), rows.map { it.title })
    }

    @Test
    fun `row carries the event's all-day flag`() = runTest {
        val now = 1_000L
        insertEventWithOccurrence(uid = "all-day", startTs = 5_000L, isAllDay = true)
        insertEventWithOccurrence(uid = "timed", startTs = 6_000L)

        val rows = eventsDao.getMasterEventsWithFutureOccurrenceFlow(now).first()
        assertEquals(listOf(true, false), rows.map { it.isAllDay })
    }
}
