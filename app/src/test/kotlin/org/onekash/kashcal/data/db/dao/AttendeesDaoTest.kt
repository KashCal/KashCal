package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AttendeesDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var attendeesDao: AttendeesDao
    private var eventId1: Long = 0
    private var eventId2: Long = 0
    private var eventId3: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        attendeesDao = database.attendeesDao()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
        eventId1 = database.eventsDao().insert(makeEvent(calendarId, "evt1"))
        eventId2 = database.eventsDao().insert(makeEvent(calendarId, "evt2"))
        eventId3 = database.eventsDao().insert(makeEvent(calendarId, "evt3"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun makeEvent(calendarId: Long, uid: String): Event = Event(
        uid = uid,
        calendarId = calendarId,
        title = "Test",
        startTs = 1_000L,
        endTs = 2_000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    private fun makeAttendee(
        eventId: Long,
        address: String,
        sortOrder: Int = 0,
        partstat: String? = null
    ): Attendee = Attendee(
        eventId = eventId,
        address = address,
        partstat = partstat,
        sortOrder = sortOrder
    )

    @Test
    fun `replaceForEvent inserts attendees on empty event`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:alice@x.com", 0),
                makeAttendee(eventId1, "mailto:bob@x.com", 1)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(2, rows.size)
        assertEquals("mailto:alice@x.com", rows[0].address)
        assertEquals("mailto:bob@x.com", rows[1].address)
    }

    @Test
    fun `replaceForEvent overwrites existing attendees`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0),
                makeAttendee(eventId1, "mailto:b@x.com", 1)
            )
        )
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:c@x.com", 0),
                makeAttendee(eventId1, "mailto:d@x.com", 1),
                makeAttendee(eventId1, "mailto:e@x.com", 2)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(listOf("mailto:c@x.com", "mailto:d@x.com", "mailto:e@x.com"), rows.map { it.address })
    }

    @Test
    fun `replaceForEvent with empty list deletes all attendees for that event`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com"))
        )
        attendeesDao.replaceForEvent(eventId1, emptyList())
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(0, rows.size)
    }

    @Test
    fun `replaceForEvent does not affect other events' attendees`() = runTest {
        attendeesDao.replaceForEvent(eventId1, listOf(makeAttendee(eventId1, "mailto:a1@x.com")))
        attendeesDao.replaceForEvent(eventId2, listOf(makeAttendee(eventId2, "mailto:a2@x.com")))
        attendeesDao.replaceForEvent(eventId1, emptyList())

        assertEquals(0, attendeesDao.getForEvent(eventId1).first().size)
        assertEquals(1, attendeesDao.getForEvent(eventId2).first().size)
    }

    @Test
    fun `getForEvents bulk query returns all matching rows ordered`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0),
                makeAttendee(eventId1, "mailto:b@x.com", 1)
            )
        )
        attendeesDao.replaceForEvent(eventId2, emptyList())
        attendeesDao.replaceForEvent(
            eventId3,
            listOf(
                makeAttendee(eventId3, "mailto:c@x.com", 0),
                makeAttendee(eventId3, "mailto:d@x.com", 1),
                makeAttendee(eventId3, "mailto:e@x.com", 2),
                makeAttendee(eventId3, "mailto:f@x.com", 3)
            )
        )

        val rows = attendeesDao.getForEvents(listOf(eventId1, eventId2, eventId3)).first()
        assertEquals(6, rows.size)
        // Ordered by event_id, then sort_order
        assertEquals(eventId1, rows[0].eventId)
        assertEquals(eventId1, rows[1].eventId)
        assertEquals(eventId3, rows[2].eventId)
        assertEquals(eventId3, rows[5].eventId)
        assertEquals("mailto:c@x.com", rows[2].address)
    }

    @Test
    fun `event delete cascades to attendees (FK CASCADE from P1_9)`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com"),
                makeAttendee(eventId1, "mailto:b@x.com")
            )
        )
        // Delete the parent event — FK CASCADE should remove the attendees.
        database.eventsDao().deleteById(eventId1)

        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(0, rows.size)
    }

    @Test
    fun `replaceForEvent preserves wire order via sort_order`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:zeta@x.com", 0),
                makeAttendee(eventId1, "mailto:alpha@x.com", 1),
                makeAttendee(eventId1, "mailto:mid@x.com", 2)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(
            listOf("mailto:zeta@x.com", "mailto:alpha@x.com", "mailto:mid@x.com"),
            rows.map { it.address }
        )
        assertTrue(rows.zipWithNext().all { (a, b) -> a.sortOrder < b.sortOrder })
    }
}
