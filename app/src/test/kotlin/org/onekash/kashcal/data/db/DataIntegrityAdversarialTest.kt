package org.onekash.kashcal.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probes what the schema enforces on its own, with no create callback installed:
 * - foreign keys, and the cascades when an account, a calendar, a master or an event with
 *   occurrences is deleted
 * - the unique (calendar_id, uid, original_instance_time) index, which allows one exception per
 *   master instance
 * - what it allows: a shared UID for a master and its exception, pending operations without
 *   an event, several operations per event, an occurrence whose calendarId differs from its
 *   event's
 * - null optional fields, every [SyncStatus] value and very long text
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DataIntegrityAdversarialTest {

    private lateinit var database: KashCalDatabase

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Foreign Key: Event -> Calendar ====================

    @Test
    fun `event with non-existent calendarId fails FK constraint`() = runTest {
        val event = Event(
            uid = "test@example.com",
            calendarId = 999999L, // doesn't exist
            title = "Orphan Event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis()
        )

        try {
            database.eventsDao().insert(event)
            fail("Should throw FK constraint violation")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message?.contains("FOREIGN KEY") == true)
        }
    }

    @Test
    fun `deleting calendar cascades to events`() = runTest {
        // Account, calendar, event.
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
        val eventId = database.eventsDao().insert(
            Event(
                uid = "event@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        assertNotNull(database.eventsDao().getById(eventId))

        database.calendarsDao().deleteById(calendarId)

        assertNull("Event should be deleted when calendar is deleted",
            database.eventsDao().getById(eventId))
    }

    // ==================== Foreign Key: Calendar -> Account ====================

    @Test
    fun `calendar with non-existent accountId fails FK constraint`() = runTest {
        val calendar = Calendar(
            accountId = 999999L, // doesn't exist
            caldavUrl = "https://test.com/cal/",
            displayName = "Orphan Calendar",
            color = 0xFF2196F3.toInt()
        )

        try {
            database.calendarsDao().insert(calendar)
            fail("Should throw FK constraint violation")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message?.contains("FOREIGN KEY") == true)
        }
    }

    @Test
    fun `deleting account cascades to calendars and events`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
        val eventId = database.eventsDao().insert(
            Event(
                uid = "event@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        database.accountsDao().deleteById(accountId)

        assertNull(database.calendarsDao().getById(calendarId))
        assertNull(database.eventsDao().getById(eventId))
    }

    // ==================== Foreign Key: Exception -> Master Event ====================

    @Test
    fun `exception event with non-existent originalEventId fails FK constraint`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val exception = Event(
            uid = "exception@test.com",
            calendarId = calendarId,
            title = "Orphan Exception",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = 999999L, // no such master
            originalInstanceTime = System.currentTimeMillis()
        )

        try {
            database.eventsDao().insert(exception)
            fail("Should throw FK constraint violation")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message?.contains("FOREIGN KEY") == true)
        }
    }

    @Test
    fun `deleting master event cascades to exceptions`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val masterId = database.eventsDao().insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Master Event",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5"
            )
        )

        val exceptionId = database.eventsDao().insert(
            Event(
                uid = "master@test.com", // Same UID as master
                calendarId = calendarId,
                title = "Modified Instance",
                startTs = System.currentTimeMillis() + 86400000,
                endTs = System.currentTimeMillis() + 90000000,
                dtstamp = System.currentTimeMillis(),
                originalEventId = masterId,
                originalInstanceTime = System.currentTimeMillis() + 86400000
            )
        )

        assertNotNull(database.eventsDao().getById(exceptionId))

        database.eventsDao().deleteById(masterId)

        assertNull("Exception should be deleted when master is deleted",
            database.eventsDao().getById(exceptionId))
    }

    // ==================== Foreign Key: Occurrence -> Event ====================

    @Test
    fun `occurrence with non-existent eventId fails FK constraint`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val occurrence = Occurrence(
            eventId = 999999L, // no such event
            calendarId = calendarId,
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            startDay = 20240615,
            endDay = 20240615
        )

        try {
            database.occurrencesDao().insert(occurrence)
            fail("Should throw FK constraint violation")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message?.contains("FOREIGN KEY") == true)
        }
    }

    @Test
    fun `deleting event cascades to occurrences`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )
        val eventId = database.eventsDao().insert(
            Event(
                uid = "event@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                startDay = 20240615,
                endDay = 20240615
            )
        )

        val occsBefore = database.occurrencesDao().getForEvent(eventId)
        assertEquals(1, occsBefore.size)

        database.eventsDao().deleteById(eventId)

        val occsAfter = database.occurrencesDao().getForEvent(eventId)
        assertTrue("Occurrences should be deleted", occsAfter.isEmpty())
    }

    // ==================== Unique Constraint Tests ====================

    @Test
    fun `duplicate UID in same calendar is allowed`() = runTest {
        // An exception shares its master's UID (RFC 5545 RECURRENCE-ID); the unique
        // (calendar_id, uid, original_instance_time) index tells them apart.
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val uid = "shared-uid@test.com"

        val masterId = database.eventsDao().insert(
            Event(
                uid = uid,
                calendarId = calendarId,
                title = "Master",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5"
            )
        )

        // The exception, same UID, linked through originalEventId.
        val exceptionId = database.eventsDao().insert(
            Event(
                uid = uid,
                calendarId = calendarId,
                title = "Exception",
                startTs = System.currentTimeMillis() + 86400000,
                endTs = System.currentTimeMillis() + 90000000,
                dtstamp = System.currentTimeMillis(),
                originalEventId = masterId,
                originalInstanceTime = System.currentTimeMillis() + 86400000
            )
        )

        assertNotNull(database.eventsDao().getById(masterId))
        assertNotNull(database.eventsDao().getById(exceptionId))
    }

    @Test
    fun `exception unique constraint - same originalEventId and originalInstanceTime`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val masterId = database.eventsDao().insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Master",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5"
            )
        )

        val instanceTime = System.currentTimeMillis() + 86400000

        database.eventsDao().insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Exception 1",
                startTs = instanceTime,
                endTs = instanceTime + 3600000,
                dtstamp = System.currentTimeMillis(),
                originalEventId = masterId,
                originalInstanceTime = instanceTime
            )
        )

        // A second exception for the same instance time breaks the unique
        // (calendar_id, uid, original_instance_time) index.
        try {
            database.eventsDao().insert(
                Event(
                    uid = "master@test.com",
                    calendarId = calendarId,
                    title = "Exception 2",
                    startTs = instanceTime,
                    endTs = instanceTime + 3600000,
                    dtstamp = System.currentTimeMillis(),
                    originalEventId = masterId,
                    originalInstanceTime = instanceTime
                )
            )
            fail("Should fail unique constraint on (originalEventId, originalInstanceTime)")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message?.contains("UNIQUE") == true)
        }
    }

    // ==================== PendingOperation Tests ====================

    @Test
    fun `pendingOperation with non-existent eventId is allowed`() = runTest {
        // eventId isn't a foreign key, so an operation outlives its deleted event.
        val op = PendingOperation(
            eventId = 999999L,
            operation = PendingOperation.OPERATION_DELETE
        )

        val id = database.pendingOperationsDao().insert(op)
        assertTrue("Should allow non-existent eventId", id > 0)
    }

    @Test
    fun `multiple pendingOperations for same event are allowed`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )
        val eventId = database.eventsDao().insert(
            Event(
                uid = "event@test.com",
                calendarId = calendarId,
                title = "Test",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        database.pendingOperationsDao().insert(
            PendingOperation(eventId = eventId, operation = "UPDATE")
        )
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = eventId, operation = "UPDATE")
        )

        val ops = database.pendingOperationsDao().getForEvent(eventId)
        assertEquals("Multiple operations allowed", 2, ops.size)
    }

    // ==================== Null Handling Tests ====================

    @Test
    fun `event with null optional fields is valid`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val event = Event(
            uid = "minimal@test.com",
            calendarId = calendarId,
            title = "Minimal Event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis(),
            // Every optional field null.
            location = null,
            description = null,
            timezone = null,
            rrule = null,
            rdate = null,
            exdate = null,
            duration = null,
            originalEventId = null,
            originalInstanceTime = null,
            organizerEmail = null,
            organizerName = null,
            caldavUrl = null,
            etag = null,
            reminders = null,
            extraProperties = null
        )

        val id = database.eventsDao().insert(event)
        val saved = database.eventsDao().getById(id)

        assertNotNull(saved)
        assertNull(saved!!.location)
        assertNull(saved.rrule)
        assertNull(saved.caldavUrl)
    }

    // ==================== Occurrence Calendar ID Consistency ====================

    @Test
    fun `occurrence calendarId should match event calendarId`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendar1Id = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Calendar 1",
                color = 0xFF2196F3.toInt()
            )
        )
        val calendar2Id = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Calendar 2",
                color = 0xFFFF5722.toInt()
            )
        )

        val eventId = database.eventsDao().insert(
            Event(
                uid = "event@test.com",
                calendarId = calendar1Id,
                title = "Test",
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        // An occurrence with another calendar's calendarId inserts: occurrences.calendar_id
        // has no foreign key, so keeping it equal to the event's is up to the app.
        val occId = database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendar2Id, // not the event's calendar
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                startDay = 20240615,
                endDay = 20240615
            )
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId)
        val occurrence = occurrences.find { it.id == occId }
        assertNotNull(occurrence)
    }

    // ==================== SyncStatus Enum Handling ====================

    @Test
    fun `all SyncStatus values can be stored and retrieved`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        SyncStatus.values().forEach { status ->
            val eventId = database.eventsDao().insert(
                Event(
                    uid = "event-$status@test.com",
                    calendarId = calendarId,
                    title = "Status $status",
                    startTs = System.currentTimeMillis(),
                    endTs = System.currentTimeMillis() + 3600000,
                    dtstamp = System.currentTimeMillis(),
                    syncStatus = status
                )
            )

            val saved = database.eventsDao().getById(eventId)
            assertEquals("SyncStatus $status should round-trip", status, saved!!.syncStatus)
        }
    }

    // ==================== Large Data Tests ====================

    @Test
    fun `event with very long title is stored`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val longTitle = "A".repeat(10000)
        val eventId = database.eventsDao().insert(
            Event(
                uid = "long@test.com",
                calendarId = calendarId,
                title = longTitle,
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        val saved = database.eventsDao().getById(eventId)
        assertEquals(10000, saved!!.title.length)
    }

    @Test
    fun `event with very long description is stored`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test",
                color = 0xFF2196F3.toInt()
            )
        )

        val longDesc = "B".repeat(100000)
        val eventId = database.eventsDao().insert(
            Event(
                uid = "longdesc@test.com",
                calendarId = calendarId,
                title = "Test",
                description = longDesc,
                startTs = System.currentTimeMillis(),
                endTs = System.currentTimeMillis() + 3600000,
                dtstamp = System.currentTimeMillis()
            )
        )

        val saved = database.eventsDao().getById(eventId)
        assertEquals(100000, saved!!.description!!.length)
    }
}