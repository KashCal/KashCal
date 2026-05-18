package org.onekash.kashcal.domain.reader

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
class EventReaderAttendeeTest {

    private lateinit var database: KashCalDatabase
    private lateinit var reader: EventReader
    private var eventId1: Long = 0
    private var eventId2: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reader = EventReader(database)

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `getAttendeesForEvent emits empty when no attendees`() = runTest {
        val attendees = reader.getAttendeesForEvent(eventId1).first()
        assertTrue(attendees.isEmpty())
    }

    @Test
    fun `getAttendeesForEvent emits inserted attendees`() = runTest {
        database.attendeesDao().replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:alice@x.com", sortOrder = 0),
                makeAttendee(eventId1, "mailto:bob@x.com", sortOrder = 1)
            )
        )
        val attendees = reader.getAttendeesForEvent(eventId1).first()
        assertEquals(2, attendees.size)
        assertEquals("mailto:alice@x.com", attendees[0].address)
        assertEquals("mailto:bob@x.com", attendees[1].address)
    }

    @Test
    fun `getAttendeesForEvent emits empty after replace with empty list`() = runTest {
        database.attendeesDao().replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:alice@x.com"))
        )
        database.attendeesDao().replaceForEvent(eventId1, emptyList())
        val attendees = reader.getAttendeesForEvent(eventId1).first()
        assertTrue(attendees.isEmpty())
    }

    @Test
    fun `getAttendeesForEvent emits empty after parent event delete (FK CASCADE)`() = runTest {
        database.attendeesDao().replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:alice@x.com"))
        )
        database.eventsDao().deleteById(eventId1)
        val attendees = reader.getAttendeesForEvent(eventId1).first()
        assertTrue(attendees.isEmpty())
    }

    @Test
    fun `getAttendeesForEvents bulk returns map keyed by eventId`() = runTest {
        database.attendeesDao().replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:alice@x.com", sortOrder = 0),
                makeAttendee(eventId1, "mailto:bob@x.com", sortOrder = 1)
            )
        )
        database.attendeesDao().replaceForEvent(
            eventId2,
            listOf(makeAttendee(eventId2, "mailto:carol@x.com", sortOrder = 0))
        )

        val map = reader.getAttendeesForEvents(listOf(eventId1, eventId2)).first()
        assertEquals(2, map[eventId1]?.size)
        assertEquals(1, map[eventId2]?.size)
        assertEquals("mailto:alice@x.com", map[eventId1]?.get(0)?.address)
        assertEquals("mailto:carol@x.com", map[eventId2]?.get(0)?.address)
    }

    @Test
    fun `getAttendeesForEvents excludes events with no attendees but still returns map`() = runTest {
        database.attendeesDao().replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:alice@x.com"))
        )
        // eventId2 has no attendees
        val map = reader.getAttendeesForEvents(listOf(eventId1, eventId2)).first()
        assertEquals(1, map[eventId1]?.size)
        // Map either omits the empty key or returns an empty list — both acceptable;
        // composables call map[id].orEmpty() at render time.
        val ev2 = map[eventId2] ?: emptyList()
        assertTrue(ev2.isEmpty())
    }

    @Test
    fun `getAttendeesForEvents with empty list returns empty map`() = runTest {
        val map = reader.getAttendeesForEvents(emptyList()).first()
        assertTrue(map.isEmpty())
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
}
