package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingCancel
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PendingCancelsDao] — the queue of removed attendees awaiting an
 * iTIP CANCEL. A row records a guest dropped from an event's attendee set; the
 * push drain reads it, sends the CANCEL (or skips for the implicit fleet), and
 * deletes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingCancelsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var dao: PendingCancelsDao
    private var eventId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pendingCancelsDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "self@example.test")
            )
            val calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://x/", displayName = "C", color = 0)
            )
            eventId = database.eventsDao().insert(
                Event(
                    uid = "u", calendarId = calendarId, title = "E",
                    startTs = 0, endTs = 0, timezone = "UTC", dtstamp = 0
                )
            )
        }
    }

    @After
    fun teardown() = database.close()

    private fun cancel(
        address: String = "mailto:gone@example.test",
        recurrenceId: Long? = null,
        scheduleAgent: String? = "CLIENT",
        scheduleStatus: String? = null,
        sequence: Int = 0,
    ) = PendingCancel(
        eventId = eventId,
        recurrenceId = recurrenceId,
        address = address,
        scheduleAgent = scheduleAgent,
        scheduleStatus = scheduleStatus,
        sequence = sequence,
    )

    @Test
    fun `insert and getForEvent round-trip`() = runTest {
        dao.upsert(cancel(sequence = 4))

        val rows = dao.getForEvent(eventId)
        assertEquals(1, rows.size)
        assertEquals("mailto:gone@example.test", rows[0].address)
        assertEquals(4, rows[0].sequence)
        assertEquals("CLIENT", rows[0].scheduleAgent)
        assertEquals(0, rows[0].attemptCount)
    }

    @Test
    fun `upsert is idempotent on event_id plus recurrence_id plus address`() = runTest {
        dao.upsert(cancel(sequence = 0))
        dao.upsert(cancel(sequence = 1)) // same event/recurrence/address, later sequence

        val rows = dao.getForEvent(eventId)
        assertEquals("re-removing the same guest must not duplicate the cancel", 1, rows.size)
        assertEquals("latest enqueue wins", 1, rows[0].sequence)
    }

    @Test
    fun `upsert dedups on canonical address despite mailto-prefix and case drift`() = runTest {
        // A server reforming the address between enqueues (mailto: prefix /
        // case) must not spawn a duplicate cancel for the same guest.
        dao.upsert(cancel(address = "mailto:Bob@Example.test", sequence = 0))
        dao.upsert(cancel(address = "bob@example.test", sequence = 1))

        val rows = dao.getForEvent(eventId)
        assertEquals("a re-removal in a different address form must not duplicate", 1, rows.size)
        assertEquals("latest enqueue wins", 1, rows[0].sequence)
    }

    @Test
    fun `master and per-occurrence cancels for the same address coexist`() = runTest {
        dao.upsert(cancel(recurrenceId = null))           // all-events
        dao.upsert(cancel(recurrenceId = 1_700_000_000_000L)) // just-this-occurrence

        val rows = dao.getForEvent(eventId)
        assertEquals("a series cancel and a per-instance cancel are distinct rows", 2, rows.size)
    }

    @Test
    fun `deleteById removes a drained cancel`() = runTest {
        dao.upsert(cancel())
        val row = dao.getForEvent(eventId).single()

        dao.deleteById(row.id)

        assertEquals(0, dao.getForEvent(eventId).size)
    }

    @Test
    fun `incrementAttempt advances the bounded-retry counter`() = runTest {
        dao.upsert(cancel())
        val id = dao.getForEvent(eventId).single().id

        dao.incrementAttempt(id)
        dao.incrementAttempt(id)

        assertEquals(2, dao.getForEvent(eventId).single().attemptCount)
    }

    @Test
    fun `event delete cascades pending cancels`() = runTest {
        dao.upsert(cancel())
        assertEquals(1, dao.getForEvent(eventId).size)

        database.eventsDao().deleteById(eventId)

        assertEquals(0, dao.getForEvent(eventId).size)
    }
}
