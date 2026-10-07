package org.onekash.kashcal.data.db.dao

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.RecentChange
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.model.ChangeType

/**
 * Tests [RecentChangesDao]: the calendar cascade and event SET NULL, the key's uniqueness, the
 * visible-rows query (cutoff, dismissed, order, calendar join), dismiss and restore.
 */
class RecentChangesDaoTest : BaseDaoTest() {

    private val dao by lazy { database.recentChangesDao() }
    private var calendarId = 0L
    private var otherCalendarId = 0L
    private var eventId = 0L

    @Before
    override fun setup() {
        super.setup()
        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "self@example.test")
            )
            calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://x/a/", displayName = "Work", color = 1)
            )
            otherCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId, caldavUrl = "https://x/b/", displayName = "Home", color = 2,
                    localColorOverride = 3
                )
            )
            eventId = database.eventsDao().insert(
                Event(
                    uid = "u1", calendarId = calendarId, title = "Standup",
                    startTs = 1_000, endTs = 2_000, timezone = "UTC", dtstamp = 0
                )
            )
        }
    }

    private fun change(
        uid: String = "u1",
        instanceTs: Long = 0,
        calendar: Long = calendarId,
        event: Long? = eventId,
        detectedAt: Long = 10_000,
    ) = RecentChange(
        calendarId = calendar, eventUid = uid, instanceTs = instanceTs, eventId = event,
        changeType = ChangeType.NEW, title = "Standup", startTs = 1_000, endTs = 2_000,
        isAllDay = false, isRecurring = false, detectedAt = detectedAt
    )

    @Test
    fun `getByKey finds the row by calendar, uid and instance`() = runTest {
        dao.insert(change())
        dao.insert(change(instanceTs = 5_000))

        assertEquals(5_000L, dao.getByKey(calendarId, "u1", 5_000)!!.instanceTs)
        assertNull(dao.getByKey(otherCalendarId, "u1", 0))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `a second row on the same key is refused`() = runTest {
        dao.insert(change())
        dao.insert(change())
    }

    @Test
    fun `the same uid in another calendar is its own row`() = runTest {
        dao.insert(change())
        dao.insert(change(calendar = otherCalendarId, event = null))

        assertEquals(2, dao.observeVisible(0).first().size)
    }

    @Test
    fun `deleteOccurrencesOf removes only that series' occurrence rows in that calendar`() = runTest {
        dao.insert(change())
        dao.insert(change(instanceTs = 5_000))
        dao.insert(change(instanceTs = 6_000))
        dao.insert(change(uid = "u2", instanceTs = 5_000, event = null))
        dao.insert(change(calendar = otherCalendarId, instanceTs = 5_000, event = null))

        assertEquals(2, dao.deleteOccurrencesOf(calendarId, "u1"))

        val left = dao.observeVisible(0).first().map { Triple(it.change.calendarId, it.change.eventUid, it.change.instanceTs) }
        assertEquals(
            setOf(Triple(calendarId, "u1", 0L), Triple(calendarId, "u2", 5_000L), Triple(otherCalendarId, "u1", 5_000L)),
            left.toSet()
        )
    }

    @Test
    fun `deleting the calendar removes its rows`() = runTest {
        dao.insert(change(calendar = otherCalendarId, event = null))
        database.calendarsDao().deleteById(otherCalendarId)

        assertTrue(dao.observeVisible(0).first().isEmpty())
    }

    @Test
    fun `deleting the event keeps the row with no event id`() = runTest {
        dao.insert(change())
        database.eventsDao().deleteById(eventId)

        val row = dao.getByKey(calendarId, "u1", 0)
        assertNotNull(row)
        assertNull(row!!.eventId)
    }

    @Test
    fun `the pull's upsert of the event keeps the row's event id`() = runTest {
        dao.insert(change())
        val event = database.eventsDao().getById(eventId)!!
        database.eventsDao().upsert(event.copy(title = "Renamed", etag = "e2"))

        assertEquals(eventId, dao.getByKey(calendarId, "u1", 0)!!.eventId)
    }

    @Test
    fun `observeVisible filters by cutoff and dismissal and orders newest first`() = runTest {
        val old = dao.insert(change(uid = "old", event = null, detectedAt = 1_000))
        val older = dao.insert(change(uid = "a", event = null, detectedAt = 5_000))
        val newer = dao.insert(change(uid = "b", event = null, detectedAt = 9_000))
        val dismissed = dao.insert(change(uid = "c", event = null, detectedAt = 9_500))
        dao.dismiss(listOf(dismissed), at = 9_600)

        val visible = dao.observeVisible(cutoff = 2_000).first().map { it.change.id }

        assertEquals(listOf(newer, older), visible)
        assertTrue(old !in visible)
    }

    @Test
    fun `observeVisible joins the calendar name and prefers its local color`() = runTest {
        dao.insert(change())
        dao.insert(change(uid = "h", calendar = otherCalendarId, event = null, detectedAt = 20_000))

        val rows = dao.observeVisible(0).first()

        assertEquals("Home", rows[0].calendarName)
        assertEquals(3, rows[0].calendarColor)
        assertEquals("Work", rows[1].calendarName)
        assertEquals(1, rows[1].calendarColor)
    }

    @Test
    fun `dismiss then restore brings the rows back`() = runTest {
        val a = dao.insert(change(uid = "a", event = null))
        val b = dao.insert(change(uid = "b", event = null))

        assertEquals(2, dao.dismiss(listOf(a, b), at = 50))
        assertTrue(dao.observeVisible(0).first().isEmpty())

        assertEquals(2, dao.restore(listOf(a, b)))
        assertEquals(2, dao.observeVisible(0).first().size)
    }

    @Test
    fun `deleteOlderThan removes only rows before the cutoff`() = runTest {
        dao.insert(change(uid = "old", event = null, detectedAt = 100))
        dao.insert(change(uid = "new", event = null, detectedAt = 900))

        assertEquals(1, dao.deleteOlderThan(500))
        assertNull(dao.getByKey(calendarId, "old", 0))
        assertNotNull(dao.getByKey(calendarId, "new", 0))
    }

    @Test
    fun `existingIds returns only ids still in events`() = runTest {
        assertEquals(listOf(eventId), database.eventsDao().existingIds(listOf(eventId, 999L)))
    }
}
