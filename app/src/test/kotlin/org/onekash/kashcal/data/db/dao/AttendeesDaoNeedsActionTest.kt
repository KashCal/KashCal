package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Robolectric tests for [AttendeesDao.getNeedsActionAttendeesForEvents].
 * Asserts SQL filter: only rows with partstat = 'NEEDS-ACTION', scoped
 * to the requested event IDs.
 */
class AttendeesDaoNeedsActionTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val attendeesDao by lazy { database.attendeesDao() }

    private var eventId1: Long = 0
    private var eventId2: Long = 0
    private var eventId3: Long = 0

    @Before
    override fun setup() {
        super.setup()
        runTest {
            val accountId = accountsDao.insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            val calendarId = calendarsDao.insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://cal",
                    displayName = "Test",
                    color = 0xFF0000FF.toInt()
                )
            )
            eventId1 = eventsDao.insert(makeEvent(calendarId, "e1"))
            eventId2 = eventsDao.insert(makeEvent(calendarId, "e2"))
            eventId3 = eventsDao.insert(makeEvent(calendarId, "e3"))
        }
    }

    private fun makeEvent(calendarId: Long, uid: String): Event = Event(
        uid = uid,
        calendarId = calendarId,
        title = "T",
        startTs = 1000L,
        endTs = 2000L,
        dtstamp = 0L,
        syncStatus = SyncStatus.SYNCED
    )

    @Test
    fun `returns only NEEDS-ACTION rows for requested events`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                Attendee(eventId = eventId1, address = "mailto:a@x", partstat = "NEEDS-ACTION"),
                Attendee(eventId = eventId1, address = "mailto:b@x", partstat = "ACCEPTED")
            )
        )
        attendeesDao.replaceForEvent(
            eventId2,
            listOf(
                Attendee(eventId = eventId2, address = "mailto:c@x", partstat = "DECLINED"),
                Attendee(eventId = eventId2, address = "mailto:d@x", partstat = "NEEDS-ACTION")
            )
        )

        val rows = attendeesDao.getNeedsActionAttendeesForEvents(listOf(eventId1, eventId2))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.partstat == "NEEDS-ACTION" })
        assertEquals(setOf("mailto:a@x", "mailto:d@x"), rows.map { it.address }.toSet())
    }

    @Test
    fun `events outside requested set are excluded`() = runTest {
        attendeesDao.replaceForEvent(
            eventId3,
            listOf(
                Attendee(eventId = eventId3, address = "mailto:z@x", partstat = "NEEDS-ACTION")
            )
        )

        val rows = attendeesDao.getNeedsActionAttendeesForEvents(listOf(eventId1, eventId2))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `empty event id list returns empty`() = runTest {
        val rows = attendeesDao.getNeedsActionAttendeesForEvents(emptyList())
        assertTrue(rows.isEmpty())
    }
}
