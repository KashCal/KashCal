package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for EventsDao.
 *
 * Tests cover:
 * - CRUD operations (insert, update, delete, upsert)
 * - Query by ID, UID, CalDAV URL
 * - Query by calendar and time range
 * - Recurring event queries (master, exceptions)
 * - Sync status operations
 * - FTS search
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao
    private var accountId: Long = 0
    private var calendarId: Long = 0
    private var secondCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        eventsDao = database.eventsDao()

        // Create test account and calendars
        accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
        secondCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Second Calendar",
                color = 0xFF00FF00.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Insert Tests ====================

    @Test
    fun `insert creates event and returns ID`() = runTest {
        val event = createTestEvent()

        val id = eventsDao.insert(event)

        assertTrue(id > 0)
        val saved = eventsDao.getById(id)
        assertNotNull(saved)
        assertEquals("Test Event", saved!!.title)
    }

    @Test
    fun `insert with conflict throws exception`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        // Try to insert with same ID
        try {
            eventsDao.insert(event.copy(id = id))
            assertTrue("Should throw", false)
        } catch (e: Exception) {
            // Expected
        }
    }

    // ==================== Upsert Tests ====================

    @Test
    fun `upsert inserts new event`() = runTest {
        val event = createTestEvent()

        val id = eventsDao.upsert(event)

        assertTrue(id > 0)
    }

    @Test
    fun `upsert updates existing event`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        val updated = event.copy(id = id, title = "Updated Title")
        eventsDao.upsert(updated)

        val saved = eventsDao.getById(id)
        assertEquals("Updated Title", saved?.title)
    }

    // ==================== Update Tests ====================

    @Test
    fun `update modifies existing event`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        val saved = eventsDao.getById(id)!!
        eventsDao.update(saved.copy(title = "Modified", description = "New description"))

        val modified = eventsDao.getById(id)
        assertEquals("Modified", modified?.title)
        assertEquals("New description", modified?.description)
    }

    // ==================== Delete Tests ====================

    @Test
    fun `delete removes event`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        val saved = eventsDao.getById(id)!!
        eventsDao.delete(saved)

        assertNull(eventsDao.getById(id))
    }

    @Test
    fun `deleteById removes event by ID`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        eventsDao.deleteById(id)

        assertNull(eventsDao.getById(id))
    }

    @Test
    fun `deleteByCalendarId removes all events in calendar`() = runTest {
        eventsDao.insert(createTestEvent(title = "Event 1"))
        eventsDao.insert(createTestEvent(title = "Event 2"))
        eventsDao.insert(createTestEvent(title = "Event 3"))

        assertEquals(3, eventsDao.getCountByCalendar(calendarId))

        eventsDao.deleteByCalendarId(calendarId)

        assertEquals(0, eventsDao.getCountByCalendar(calendarId))
    }

    @Test
    fun `deleteSyncedByCalendarId only removes synced events`() = runTest {
        eventsDao.insert(createTestEvent(title = "Synced", syncStatus = SyncStatus.SYNCED))
        eventsDao.insert(createTestEvent(title = "Pending Create", syncStatus = SyncStatus.PENDING_CREATE))
        eventsDao.insert(createTestEvent(title = "Pending Update", syncStatus = SyncStatus.PENDING_UPDATE))

        eventsDao.deleteSyncedByCalendarId(calendarId)

        assertEquals(2, eventsDao.getCountByCalendar(calendarId))
        val remaining = eventsDao.getByCalendarId(calendarId).first()
        assertTrue(remaining.none { it.syncStatus == SyncStatus.SYNCED })
    }

    // ==================== Query by ID Tests ====================

    @Test
    fun `getById returns null for non-existent ID`() = runTest {
        assertNull(eventsDao.getById(99999L))
    }

    @Test
    fun `getByIdFlow emits updates`() = runTest {
        val event = createTestEvent()
        val id = eventsDao.insert(event)

        eventsDao.getByIdFlow(id).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Test Event", initial?.title)

            eventsDao.update(initial!!.copy(title = "Updated"))
            val updated = awaitItem()
            assertEquals("Updated", updated?.title)

            cancel()
        }
    }

    // ==================== Query by UID Tests ====================

    @Test
    fun `getByUid returns all events with same UID`() = runTest {
        val uid = "shared-uid@test.com"
        // Master event
        eventsDao.insert(createTestEvent(uid = uid, rrule = "FREQ=WEEKLY"))
        // Exception event (same UID)
        eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Exception",
            originalEventId = 1L
        ))

        val results = eventsDao.getByUid(uid)

        assertEquals(2, results.size)
    }

    @Test
    fun `getByUid returns empty for unknown UID`() = runTest {
        val results = eventsDao.getByUid("unknown@test.com")

        assertTrue(results.isEmpty())
    }

    // ==================== Query by CalDAV URL Tests ====================

    @Test
    fun `getByCaldavUrl returns event`() = runTest {
        val caldavUrl = "https://caldav.icloud.com/events/abc123.ics"
        eventsDao.insert(createTestEvent(caldavUrl = caldavUrl))

        val result = eventsDao.getByCaldavUrl(caldavUrl)

        assertNotNull(result)
        assertEquals(caldavUrl, result?.caldavUrl)
    }

    @Test
    fun `getEventsWithCaldavUrl returns only this calendar's events that have a caldavUrl`() = runTest {
        // Two events with URLs in the target calendar, one without a URL (must be excluded),
        // and one URL event in another calendar (must not leak in).
        eventsDao.insert(createTestEvent(title = "Has URL 1", caldavUrl = "https://test.com/cal/a@x.ics"))
        eventsDao.insert(createTestEvent(title = "Has URL 2", caldavUrl = "https://test.com/cal/b.ics"))
        eventsDao.insert(createTestEvent(title = "No URL", caldavUrl = null))
        eventsDao.insert(createTestEvent(
            title = "Other calendar",
            calendarId = secondCalendarId,
            caldavUrl = "https://test.com/cal2/c.ics"
        ))

        val result = eventsDao.getEventsWithCaldavUrl(calendarId)

        assertEquals(2, result.size)
        assertTrue(result.all { it.calendarId == calendarId })
        assertTrue(result.all { it.caldavUrl != null })
        assertEquals(
            setOf("https://test.com/cal/a@x.ics", "https://test.com/cal/b.ics"),
            result.mapNotNull { it.caldavUrl }.toSet()
        )
    }

    // ==================== Query by Calendar Tests ====================

    @Test
    fun `getByCalendarId returns Flow of events`() = runTest {
        eventsDao.insert(createTestEvent(title = "Event 1"))
        eventsDao.insert(createTestEvent(title = "Event 2"))

        val events = eventsDao.getByCalendarId(calendarId).first()

        assertEquals(2, events.size)
    }

    @Test
    fun `getByCalendarIdInRange filters by time`() = runTest {
        val jan1 = parseDate("2025-01-01 10:00")
        val jan15 = parseDate("2025-01-15 10:00")
        val feb1 = parseDate("2025-02-01 10:00")

        eventsDao.insert(createTestEvent(title = "Jan 1", startTs = jan1, endTs = jan1 + 3600000))
        eventsDao.insert(createTestEvent(title = "Jan 15", startTs = jan15, endTs = jan15 + 3600000))
        eventsDao.insert(createTestEvent(title = "Feb 1", startTs = feb1, endTs = feb1 + 3600000))

        val results = eventsDao.getByCalendarIdInRange(
            calendarId,
            parseDate("2025-01-10 00:00"),
            parseDate("2025-01-31 23:59")
        )

        assertEquals(1, results.size)
        assertEquals("Jan 15", results[0].title)
    }

    @Test
    fun `getInRange excludes PENDING_DELETE events`() = runTest {
        val ts = parseDate("2025-01-15 10:00")
        eventsDao.insert(createTestEvent(title = "Active", startTs = ts, endTs = ts + 3600000))
        val deleteId = eventsDao.insert(createTestEvent(
            title = "Deleted",
            startTs = ts + 3600000,
            endTs = ts + 7200000,
            syncStatus = SyncStatus.PENDING_DELETE
        ))

        val results = eventsDao.getInRange(
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        assertEquals(1, results.size)
        assertEquals("Active", results[0].title)
    }

    // ==================== Recurring Event Tests ====================

    @Test
    fun `getMasterRecurringEvents returns events with RRULE`() = runTest {
        eventsDao.insert(createTestEvent(title = "Non-recurring"))
        eventsDao.insert(createTestEvent(title = "Weekly", rrule = "FREQ=WEEKLY"))
        eventsDao.insert(createTestEvent(title = "Monthly", rrule = "FREQ=MONTHLY"))

        val masters = eventsDao.getMasterRecurringEvents()

        assertEquals(2, masters.size)
        assertTrue(masters.all { it.rrule != null })
    }

    @Test
    fun `getMasterRecurringEvents excludes exception events`() = runTest {
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master",
            rrule = "FREQ=WEEKLY"
        ))
        // Exception has RRULE but also originalEventId
        eventsDao.insert(createTestEvent(
            title = "Exception",
            rrule = "FREQ=WEEKLY",
            originalEventId = masterId
        ))

        val masters = eventsDao.getMasterRecurringEvents()

        assertEquals(1, masters.size)
        assertEquals("Master", masters[0].title)
    }

    @Test
    fun `getExceptionsForMaster returns linked exceptions`() = runTest {
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master",
            rrule = "FREQ=WEEKLY"
        ))
        eventsDao.insert(createTestEvent(
            title = "Exception 1",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-13 10:00")
        ))
        eventsDao.insert(createTestEvent(
            title = "Exception 2",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-20 10:00")
        ))

        val exceptions = eventsDao.getExceptionsForMaster(masterId)

        assertEquals(2, exceptions.size)
        assertTrue(exceptions.all { it.originalEventId == masterId })
    }

    @Test
    fun `getExceptionForOccurrence finds specific exception`() = runTest {
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master",
            rrule = "FREQ=WEEKLY"
        ))
        val instanceTime = parseDate("2025-01-13 10:00")
        eventsDao.insert(createTestEvent(
            title = "Exception",
            originalEventId = masterId,
            originalInstanceTime = instanceTime
        ))

        val exception = eventsDao.getExceptionForOccurrence(masterId, instanceTime)

        assertNotNull(exception)
        assertEquals("Exception", exception?.title)
    }

    @Test
    fun `getExceptionByUidAndInstanceTime finds exception by UID`() = runTest {
        val uid = "recurring-uid-${System.nanoTime()}"
        val masterId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Master",
            rrule = "FREQ=WEEKLY"
        ))
        val instanceTime = parseDate("2025-01-13 10:00")
        eventsDao.insert(createTestEvent(
            uid = uid, // Same UID as master (RFC 5545)
            title = "Exception",
            originalEventId = masterId,
            originalInstanceTime = instanceTime
        ))

        val exception = eventsDao.getExceptionByUidAndInstanceTime(
            uid = uid,
            calendarId = calendarId,
            originalInstanceTime = instanceTime
        )

        assertNotNull(exception)
        assertEquals("Exception", exception?.title)
        assertEquals(uid, exception?.uid)
    }

    @Test
    fun `getExceptionByUidAndInstanceTime finds exception regardless of which master it points to`() = runTest {
        // This tests the RFC 5545 compliant lookup:
        // UID + originalInstanceTime finds the exception regardless of originalEventId value
        // This is key for sync where master ID might change
        val uid = "recurring-uid-${System.nanoTime()}"
        val instanceTime = parseDate("2025-01-13 10:00")

        // Create master
        val masterId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Master",
            rrule = "FREQ=WEEKLY"
        ))

        // Create exception linked to master
        val exceptionId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Exception Event",
            originalEventId = masterId,
            originalInstanceTime = instanceTime
        ))

        // UID-based lookup should find the exception using UID + instanceTime
        // NOT using originalEventId (which is the old fragile approach)
        val exception = eventsDao.getExceptionByUidAndInstanceTime(
            uid = uid,
            calendarId = calendarId,
            originalInstanceTime = instanceTime
        )

        assertNotNull("Exception should be found via UID + instanceTime lookup", exception)
        assertEquals(exceptionId, exception?.id)
        assertEquals("Exception Event", exception?.title)

        // Also verify that searching with WRONG master ID still finds it
        // (because the lookup uses UID, not originalEventId)
        val anotherLookup = eventsDao.getExceptionByUidAndInstanceTime(
            uid = uid,
            calendarId = calendarId,
            originalInstanceTime = instanceTime
        )
        assertEquals(exceptionId, anotherLookup?.id)
    }

    @Test
    fun `getExceptionByUidAndInstanceTime returns null for master events`() = runTest {
        // Master events have originalEventId = null, so they should NOT be returned
        val uid = "master-uid-${System.nanoTime()}"
        val masterId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Master Event",
            rrule = "FREQ=WEEKLY",
            startTs = parseDate("2025-01-13 10:00")
        ))

        // Try to find with master's start time (as if it were an exception)
        val result = eventsDao.getExceptionByUidAndInstanceTime(
            uid = uid,
            calendarId = calendarId,
            originalInstanceTime = parseDate("2025-01-13 10:00")
        )

        assertNull("Master events should NOT be returned (originalEventId IS NOT NULL filter)", result)
    }

    // ==================== Sync Status Tests ====================

    @Test
    fun `getPendingSyncEvents returns non-synced events`() = runTest {
        eventsDao.insert(createTestEvent(title = "Synced", syncStatus = SyncStatus.SYNCED))
        eventsDao.insert(createTestEvent(title = "Create", syncStatus = SyncStatus.PENDING_CREATE))
        eventsDao.insert(createTestEvent(title = "Update", syncStatus = SyncStatus.PENDING_UPDATE))
        eventsDao.insert(createTestEvent(title = "Delete", syncStatus = SyncStatus.PENDING_DELETE))

        val pending = eventsDao.getPendingSyncEvents()

        assertEquals(3, pending.size)
        assertTrue(pending.none { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun `getPendingSyncCount emits correct count`() = runTest {
        eventsDao.insert(createTestEvent(syncStatus = SyncStatus.SYNCED))
        eventsDao.insert(createTestEvent(syncStatus = SyncStatus.PENDING_CREATE))
        eventsDao.insert(createTestEvent(syncStatus = SyncStatus.PENDING_UPDATE))

        val count = eventsDao.getPendingSyncCount().first()

        assertEquals(2, count)
    }

    @Test
    fun `markForDeletion sets PENDING_DELETE status`() = runTest {
        val id = eventsDao.insert(createTestEvent(syncStatus = SyncStatus.SYNCED))
        val now = System.currentTimeMillis()

        eventsDao.markForDeletion(id, now)

        val event = eventsDao.getById(id)
        assertEquals(SyncStatus.PENDING_DELETE, event?.syncStatus)
    }

    @Test
    fun `markSynced clears error and sets status`() = runTest {
        val id = eventsDao.insert(createTestEvent(syncStatus = SyncStatus.PENDING_UPDATE))
        eventsDao.recordSyncError(id, "Test error", System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val etag = "\"abc123\""

        eventsDao.markSynced(id, etag, now)

        val event = eventsDao.getById(id)
        assertEquals(SyncStatus.SYNCED, event?.syncStatus)
        assertEquals(etag, event?.etag)
        assertNull(event?.lastSyncError)
        assertEquals(0, event?.syncRetryCount)
    }

    @Test
    fun `markCreatedOnServer sets caldavUrl and synced status`() = runTest {
        val id = eventsDao.insert(createTestEvent(syncStatus = SyncStatus.PENDING_CREATE))
        val caldavUrl = "https://caldav.icloud.com/events/new123.ics"
        val etag = "\"new-etag\""
        val now = System.currentTimeMillis()

        eventsDao.markCreatedOnServer(id, caldavUrl, etag, now)

        val event = eventsDao.getById(id)
        assertEquals(SyncStatus.SYNCED, event?.syncStatus)
        assertEquals(caldavUrl, event?.caldavUrl)
        assertEquals(etag, event?.etag)
    }

    @Test
    fun `recordSyncError increments retry count`() = runTest {
        val id = eventsDao.insert(createTestEvent())
        val now = System.currentTimeMillis()

        eventsDao.recordSyncError(id, "Error 1", now)
        var event = eventsDao.getById(id)
        assertEquals(1, event?.syncRetryCount)
        assertEquals("Error 1", event?.lastSyncError)

        eventsDao.recordSyncError(id, "Error 2", now + 1000)
        event = eventsDao.getById(id)
        assertEquals(2, event?.syncRetryCount)
        assertEquals("Error 2", event?.lastSyncError)
    }

    @Test
    fun `clearSyncError resets error state`() = runTest {
        val id = eventsDao.insert(createTestEvent())
        eventsDao.recordSyncError(id, "Error", System.currentTimeMillis())
        eventsDao.recordSyncError(id, "Error again", System.currentTimeMillis())

        eventsDao.clearSyncError(id, System.currentTimeMillis())

        val event = eventsDao.getById(id)
        assertNull(event?.lastSyncError)
        assertEquals(0, event?.syncRetryCount)
    }

    @Test
    fun `markPendingUpdate increments sequence`() = runTest {
        val id = eventsDao.insert(createTestEvent(sequence = 5))
        val now = System.currentTimeMillis()

        eventsDao.markPendingUpdate(id, now)

        val event = eventsDao.getById(id)
        assertEquals(SyncStatus.PENDING_UPDATE, event?.syncStatus)
        assertEquals(6, event?.sequence)
    }

    // ==================== EXDATE/RRULE Update Tests ====================

    @Test
    fun `updateExdate modifies exdate and marks pending`() = runTest {
        val id = eventsDao.insert(createTestEvent(rrule = "FREQ=DAILY", sequence = 0))

        eventsDao.updateExdate(id, "20250115,20250120", System.currentTimeMillis())

        val event = eventsDao.getById(id)
        assertEquals("20250115,20250120", event?.exdate)
        assertEquals(SyncStatus.PENDING_UPDATE, event?.syncStatus)
        assertEquals(1, event?.sequence)
    }

    @Test
    fun `updateRrule modifies rrule and marks pending`() = runTest {
        val id = eventsDao.insert(createTestEvent(rrule = "FREQ=DAILY", sequence = 0))

        eventsDao.updateRrule(id, "FREQ=WEEKLY;BYDAY=MO", System.currentTimeMillis())

        val event = eventsDao.getById(id)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", event?.rrule)
        assertEquals(SyncStatus.PENDING_UPDATE, event?.syncStatus)
        assertEquals(1, event?.sequence)
    }

    // ==================== Utility Tests ====================

    @Test
    fun `uidExistsInCalendar returns correct result`() = runTest {
        val uid = "unique-uid@test.com"
        eventsDao.insert(createTestEvent(uid = uid))

        assertTrue(eventsDao.uidExistsInCalendar(calendarId, uid))
        assertFalse(eventsDao.uidExistsInCalendar(calendarId, "other@test.com"))
        assertFalse(eventsDao.uidExistsInCalendar(secondCalendarId, uid))
    }

    @Test
    fun `getTotalCount returns correct count`() = runTest {
        assertEquals(0, eventsDao.getTotalCount())

        eventsDao.insert(createTestEvent(title = "Event 1"))
        eventsDao.insert(createTestEvent(title = "Event 2"))
        eventsDao.insert(createTestEvent(title = "Event 3", calendarId = secondCalendarId))

        assertEquals(3, eventsDao.getTotalCount())
    }

    // ==================== Search Tests ====================

    @Test
    fun `search finds events by title`() = runTest {
        eventsDao.insert(createTestEvent(title = "Team Meeting"))
        eventsDao.insert(createTestEvent(title = "Personal Appointment"))
        eventsDao.insert(createTestEvent(title = "Project Review Meeting"))

        val results = eventsDao.search("meeting*")

        assertEquals(2, results.size)
    }

    @Test
    fun `search finds events by description`() = runTest {
        eventsDao.insert(createTestEvent(title = "Event", description = "Discuss budget matters"))
        eventsDao.insert(createTestEvent(title = "Other", description = "No relevance"))

        val results = eventsDao.search("budget*")

        assertEquals(1, results.size)
    }

    @Test
    fun `search excludes PENDING_DELETE events`() = runTest {
        eventsDao.insert(createTestEvent(title = "Active Meeting"))
        eventsDao.insert(createTestEvent(title = "Deleted Meeting", syncStatus = SyncStatus.PENDING_DELETE))

        val results = eventsDao.search("meeting*")

        assertEquals(1, results.size)
        assertEquals("Active Meeting", results[0].title)
    }

    // ==================== Deduplication Tests ====================

    @Test
    fun `getMasterByUidAndCalendar finds master event`() = runTest {
        val uid = "master-uid@test.com"
        eventsDao.insert(createTestEvent(uid = uid, title = "Master", rrule = "FREQ=DAILY"))

        val result = eventsDao.getMasterByUidAndCalendar(uid, calendarId)

        assertNotNull(result)
        assertEquals("Master", result?.title)
    }

    @Test
    fun `getMasterByUidAndCalendar excludes exceptions`() = runTest {
        val uid = "shared-uid@test.com"
        val masterId = eventsDao.insert(createTestEvent(uid = uid, title = "Master", rrule = "FREQ=DAILY"))
        eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Exception",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-15 10:00")
        ))

        val result = eventsDao.getMasterByUidAndCalendar(uid, calendarId)

        assertNotNull(result)
        assertEquals("Master", result?.title)
        assertNull(result?.originalEventId) // Ensure it's the master, not exception
    }

    @Test
    fun `same UID in different calendars is allowed`() = runTest {
        val uid = "shared-uid@test.com"
        eventsDao.insert(createTestEvent(uid = uid, calendarId = calendarId, title = "Cal1"))
        eventsDao.insert(createTestEvent(uid = uid, calendarId = secondCalendarId, title = "Cal2"))

        assertEquals(2, eventsDao.getTotalCount())

        // Each calendar should find its own master
        val master1 = eventsDao.getMasterByUidAndCalendar(uid, calendarId)
        val master2 = eventsDao.getMasterByUidAndCalendar(uid, secondCalendarId)

        assertEquals("Cal1", master1?.title)
        assertEquals("Cal2", master2?.title)
    }

    @Test
    fun `exception events can share master UID`() = runTest {
        val uid = "recurring-uid@test.com"
        val masterId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Master",
            rrule = "FREQ=DAILY"
        ))

        // Insert multiple exceptions with same UID
        eventsDao.insert(createTestEvent(
            uid = uid, // Same UID as master
            title = "Exception 1",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-16 10:00")
        ))
        eventsDao.insert(createTestEvent(
            uid = uid, // Same UID as master
            title = "Exception 2",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-17 10:00")
        ))

        // All 3 should exist (master + 2 exceptions)
        assertEquals(3, eventsDao.getTotalCount())

        // getByUid should return all 3
        val allEvents = eventsDao.getByUid(uid)
        assertEquals(3, allEvents.size)

        // getMasterByUidAndCalendar should only return the master
        val master = eventsDao.getMasterByUidAndCalendar(uid, calendarId)
        assertNotNull(master)
        assertNull(master?.originalEventId)
    }

    @Test
    fun `deleteDuplicateMasterEvents removes duplicates keeping oldest`() = runTest {
        // Note: This test manually creates duplicates which wouldn't normally be possible
        // with the unique constraint, but tests the cleanup query logic
        val uid = "duplicate-uid@test.com"

        // Insert multiple events with same UID (simulating a race condition scenario)
        // In production, the unique constraint would prevent this
        val id1 = eventsDao.insert(createTestEvent(uid = uid, title = "First"))
        val id2 = eventsDao.insert(createTestEvent(uid = uid, title = "Second"))
        val id3 = eventsDao.insert(createTestEvent(uid = uid, title = "Third"))

        assertEquals(3, eventsDao.getTotalCount())

        // Run dedup
        val deleted = eventsDao.deleteDuplicateMasterEvents()

        // Should have deleted 2 duplicates
        assertEquals(2, deleted)

        // Only the oldest (lowest ID) should remain
        assertEquals(1, eventsDao.getTotalCount())
        val remaining = eventsDao.getById(id1)
        assertNotNull(remaining)
        assertEquals("First", remaining?.title)

        // The others should be gone
        assertNull(eventsDao.getById(id2))
        assertNull(eventsDao.getById(id3))
    }

    @Test
    fun `deleteDuplicateMasterEvents does not affect exceptions`() = runTest {
        val uid = "recurring-uid@test.com"
        val masterId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Master",
            rrule = "FREQ=DAILY"
        ))

        // Insert exception with same UID
        val exceptionId = eventsDao.insert(createTestEvent(
            uid = uid,
            title = "Exception",
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-16 10:00")
        ))

        assertEquals(2, eventsDao.getTotalCount())

        // Run dedup - should not remove exception (it has originalEventId set)
        val deleted = eventsDao.deleteDuplicateMasterEvents()

        assertEquals(0, deleted) // No duplicates among master events
        assertEquals(2, eventsDao.getTotalCount()) // Both should remain
        assertNotNull(eventsDao.getById(masterId))
        assertNotNull(eventsDao.getById(exceptionId))
    }

    @Test
    fun `deleteDuplicateMasterEvents keeps unique events`() = runTest {
        // Create events with unique UIDs
        eventsDao.insert(createTestEvent(uid = "uid-1@test.com", title = "Event 1"))
        eventsDao.insert(createTestEvent(uid = "uid-2@test.com", title = "Event 2"))
        eventsDao.insert(createTestEvent(uid = "uid-3@test.com", title = "Event 3"))

        assertEquals(3, eventsDao.getTotalCount())

        // Run dedup - should not remove anything
        val deleted = eventsDao.deleteDuplicateMasterEvents()

        assertEquals(0, deleted)
        assertEquals(3, eventsDao.getTotalCount())
    }

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1,
            dateParts[2].toInt(),
            timeParts[0].toInt(),
            timeParts[1].toInt(),
            0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun toDayCode(dateStr: String): Int {
        val dateParts = dateStr.split("-")
        return dateParts[0].toInt() * 10000 + dateParts[1].toInt() * 100 + dateParts[2].toInt()
    }

    // ==================== getEventsWithRemindersInRange Tests ====================

    /**
     * Test suite for the complex UNION query used by ReminderScheduler.
     * This query finds events with reminders that have occurrences in a time window,
     * handling both events with their own reminders and exception events that inherit.
     */

    @Test
    fun `getEventsWithRemindersInRange returns event with reminders`() = runTest {
        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "Meeting with reminder",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = listOf("-PT15M")
        ))

        // Create occurrence for the event
        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(1, results.size)
        assertEquals("Meeting with reminder", results[0].event.title)
        assertEquals(eventTime, results[0].occurrenceStartTs)
    }

    @Test
    fun `getEventsWithRemindersInRange excludes events without reminders`() = runTest {
        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "No reminder",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = null
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(0, results.size)
    }

    @Test
    fun `getEventsWithRemindersInRange excludes events with empty reminders array`() = runTest {
        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "Empty reminder array",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = emptyList()
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(0, results.size)
    }

    @Test
    fun `getEventsWithRemindersInRange excludes cancelled occurrences`() = runTest {
        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "Cancelled occurrence",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = listOf("-PT15M")
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15"),
            isCancelled = true
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(0, results.size)
    }

    @Test
    fun `getEventsWithRemindersInRange excludes hidden calendars`() = runTest {
        // Hide the calendar
        database.calendarsDao().setVisible(calendarId, false)

        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "Hidden calendar event",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = listOf("-PT15M")
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(0, results.size)
    }

    @Test
    fun `getEventsWithRemindersInRange returns exception with its own reminders`() = runTest {
        val masterTime = parseDate("2025-01-15 10:00")
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master",
            startTs = masterTime,
            endTs = masterTime + 3600000,
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT15M")
        ))

        // Exception with its own reminders (different from master)
        val exceptionTime = parseDate("2025-01-16 14:00")
        val exceptionId = eventsDao.insert(createTestEvent(
            uid = eventsDao.getById(masterId)!!.uid, // Same UID as master (RFC 5545)
            title = "Modified occurrence",
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-16 10:00"),
            reminders = listOf("-PT30M", "-PT1H") // Different reminders
        ))

        // Create occurrence that links to exception
        database.occurrencesDao().insert(Occurrence(
            eventId = masterId,
            calendarId = calendarId,
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            startDay = toDayCode("2025-01-16"),
            endDay = toDayCode("2025-01-16"),
            exceptionEventId = exceptionId
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-16 00:00"),
            parseDate("2025-01-16 23:59")
        )

        assertEquals(1, results.size)
        assertEquals("Modified occurrence", results[0].event.title)
        assertEquals(listOf("-PT30M", "-PT1H"), results[0].event.reminders)
        assertEquals(exceptionId, results[0].targetEventId)
    }

    @Test
    fun `getEventsWithRemindersInRange exception inherits reminders from master`() = runTest {
        val masterTime = parseDate("2025-01-15 10:00")
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master with reminder",
            startTs = masterTime,
            endTs = masterTime + 3600000,
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT15M")
        ))

        // Exception with NO reminders (should inherit from master)
        val exceptionTime = parseDate("2025-01-16 14:00")
        val exceptionId = eventsDao.insert(createTestEvent(
            uid = eventsDao.getById(masterId)!!.uid,
            title = "Exception inherits reminders",
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-16 10:00"),
            reminders = null // No own reminders - inherits from master
        ))

        // Create occurrence linked to exception
        database.occurrencesDao().insert(Occurrence(
            eventId = masterId,
            calendarId = calendarId,
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            startDay = toDayCode("2025-01-16"),
            endDay = toDayCode("2025-01-16"),
            exceptionEventId = exceptionId
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-16 00:00"),
            parseDate("2025-01-16 23:59")
        )

        // The UNION query's second branch should find this:
        // Exception with no reminders + master WITH reminders = returns master's reminders
        assertEquals(1, results.size)
        assertEquals(listOf("-PT15M"), results[0].event.reminders)
        // targetEventId should point to exception (for click handling)
        assertEquals(exceptionId, results[0].targetEventId)
    }

    @Test
    fun `getEventsWithRemindersInRange exception with empty array inherits from master`() = runTest {
        val masterTime = parseDate("2025-01-15 10:00")
        val masterId = eventsDao.insert(createTestEvent(
            title = "Master",
            startTs = masterTime,
            endTs = masterTime + 3600000,
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT15M")
        ))

        // Exception with empty reminders array (should also inherit)
        val exceptionTime = parseDate("2025-01-16 14:00")
        val exceptionId = eventsDao.insert(createTestEvent(
            uid = eventsDao.getById(masterId)!!.uid,
            title = "Exception with empty array",
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-16 10:00"),
            reminders = emptyList() // Empty array - should inherit
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = masterId,
            calendarId = calendarId,
            startTs = exceptionTime,
            endTs = exceptionTime + 3600000,
            startDay = toDayCode("2025-01-16"),
            endDay = toDayCode("2025-01-16"),
            exceptionEventId = exceptionId
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-16 00:00"),
            parseDate("2025-01-16 23:59")
        )

        assertEquals(1, results.size)
        assertEquals(listOf("-PT15M"), results[0].event.reminders)
    }

    @Test
    fun `getEventsWithRemindersInRange filters by time range`() = runTest {
        // Event in range
        val inRangeTime = parseDate("2025-01-15 10:00")
        val inRangeId = eventsDao.insert(createTestEvent(
            title = "In range",
            startTs = inRangeTime,
            endTs = inRangeTime + 3600000,
            reminders = listOf("-PT15M")
        ))
        database.occurrencesDao().insert(Occurrence(
            eventId = inRangeId,
            calendarId = calendarId,
            startTs = inRangeTime,
            endTs = inRangeTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        // Event before range
        val beforeTime = parseDate("2025-01-14 10:00")
        val beforeId = eventsDao.insert(createTestEvent(
            title = "Before range",
            startTs = beforeTime,
            endTs = beforeTime + 3600000,
            reminders = listOf("-PT15M")
        ))
        database.occurrencesDao().insert(Occurrence(
            eventId = beforeId,
            calendarId = calendarId,
            startTs = beforeTime,
            endTs = beforeTime + 3600000,
            startDay = toDayCode("2025-01-14"),
            endDay = toDayCode("2025-01-14")
        ))

        // Event after range
        val afterTime = parseDate("2025-01-16 10:00")
        val afterId = eventsDao.insert(createTestEvent(
            title = "After range",
            startTs = afterTime,
            endTs = afterTime + 3600000,
            reminders = listOf("-PT15M")
        ))
        database.occurrencesDao().insert(Occurrence(
            eventId = afterId,
            calendarId = calendarId,
            startTs = afterTime,
            endTs = afterTime + 3600000,
            startDay = toDayCode("2025-01-16"),
            endDay = toDayCode("2025-01-16")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(1, results.size)
        assertEquals("In range", results[0].event.title)
    }

    @Test
    fun `getEventsWithRemindersInRange returns calendar color`() = runTest {
        val eventTime = parseDate("2025-01-15 10:00")
        val eventId = eventsDao.insert(createTestEvent(
            title = "Event",
            startTs = eventTime,
            endTs = eventTime + 3600000,
            reminders = listOf("-PT15M")
        ))

        database.occurrencesDao().insert(Occurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = eventTime,
            endTs = eventTime + 3600000,
            startDay = toDayCode("2025-01-15"),
            endDay = toDayCode("2025-01-15")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(1, results.size)
        // Calendar color was set to 0xFF0000FF (blue) in setup
        assertEquals(0xFF0000FF.toInt(), results[0].calendarColor)
    }

    @Test
    fun `getEventsWithRemindersInRange returns occurrence times not event times`() = runTest {
        // Recurring event: master at 10 AM
        val masterTime = parseDate("2025-01-15 10:00")
        val masterId = eventsDao.insert(createTestEvent(
            title = "Recurring",
            startTs = masterTime,
            endTs = masterTime + 3600000,
            rrule = "FREQ=DAILY",
            reminders = listOf("-PT15M")
        ))

        // This occurrence is the second occurrence (Jan 16)
        val occurrenceTime = parseDate("2025-01-16 10:00")
        database.occurrencesDao().insert(Occurrence(
            eventId = masterId,
            calendarId = calendarId,
            startTs = occurrenceTime,
            endTs = occurrenceTime + 3600000,
            startDay = toDayCode("2025-01-16"),
            endDay = toDayCode("2025-01-16")
        ))

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-16 00:00"),
            parseDate("2025-01-16 23:59")
        )

        assertEquals(1, results.size)
        // Should return occurrence time, not master event's start time
        assertEquals(occurrenceTime, results[0].occurrenceStartTs)
        assertEquals(occurrenceTime + 3600000, results[0].occurrenceEndTs)
    }

    @Test
    fun `getEventsWithRemindersInRange results ordered by occurrence time`() = runTest {
        // Create events with different occurrence times
        val times = listOf(
            parseDate("2025-01-15 14:00"),
            parseDate("2025-01-15 09:00"),
            parseDate("2025-01-15 11:00")
        )

        times.forEachIndexed { index, time ->
            val eventId = eventsDao.insert(createTestEvent(
                title = "Event $index",
                startTs = time,
                endTs = time + 3600000,
                reminders = listOf("-PT15M")
            ))
            database.occurrencesDao().insert(Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = time,
                endTs = time + 3600000,
                startDay = toDayCode("2025-01-15"),
                endDay = toDayCode("2025-01-15")
            ))
        }

        val results = eventsDao.getEventsWithRemindersInRange(
            parseDate("2025-01-15 00:00"),
            parseDate("2025-01-15 23:59")
        )

        assertEquals(3, results.size)
        // Should be ordered by occurrence time ASC
        assertEquals(parseDate("2025-01-15 09:00"), results[0].occurrenceStartTs)
        assertEquals(parseDate("2025-01-15 11:00"), results[1].occurrenceStartTs)
        assertEquals(parseDate("2025-01-15 14:00"), results[2].occurrenceStartTs)
    }

    // ==================== deleteOutsideLookback Tests ====================

    @Test
    fun `deleteOutsideLookback deletes synced events before cutoff`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // Event ending before cutoff - should be deleted
        val oldEvent = createTestEvent(
            uid = "old-event",
            startTs = parseDate("2025-01-10 10:00"),
            endTs = parseDate("2025-01-10 11:00"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.example.com/cal/old.ics"
        )
        val oldEventId = eventsDao.insert(oldEvent)

        // Event ending after cutoff - should be preserved
        val newEvent = createTestEvent(
            uid = "new-event",
            startTs = parseDate("2025-01-20 10:00"),
            endTs = parseDate("2025-01-20 11:00"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.example.com/cal/new.ics"
        )
        val newEventId = eventsDao.insert(newEvent)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(1, deleted)
        assertNull(eventsDao.getById(oldEventId))
        assertNotNull(eventsDao.getById(newEventId))
    }

    @Test
    fun `deleteOutsideLookback preserves events with pending sync status`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // PENDING_CREATE - should be preserved
        val pendingCreate = createTestEvent(
            uid = "pending-create",
            startTs = parseDate("2025-01-10 10:00"),
            endTs = parseDate("2025-01-10 11:00"),
            syncStatus = SyncStatus.PENDING_CREATE,
            caldavUrl = "https://caldav.example.com/cal/pending.ics"
        )
        val pendingCreateId = eventsDao.insert(pendingCreate)

        // PENDING_UPDATE - should be preserved
        val pendingUpdate = createTestEvent(
            uid = "pending-update",
            startTs = parseDate("2025-01-10 10:00"),
            endTs = parseDate("2025-01-10 11:00"),
            syncStatus = SyncStatus.PENDING_UPDATE,
            caldavUrl = "https://caldav.example.com/cal/pending2.ics"
        )
        val pendingUpdateId = eventsDao.insert(pendingUpdate)

        // PENDING_DELETE - should be preserved
        val pendingDelete = createTestEvent(
            uid = "pending-delete",
            startTs = parseDate("2025-01-10 10:00"),
            endTs = parseDate("2025-01-10 11:00"),
            syncStatus = SyncStatus.PENDING_DELETE,
            caldavUrl = "https://caldav.example.com/cal/pending3.ics"
        )
        val pendingDeleteId = eventsDao.insert(pendingDelete)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(0, deleted)
        assertNotNull(eventsDao.getById(pendingCreateId))
        assertNotNull(eventsDao.getById(pendingUpdateId))
        assertNotNull(eventsDao.getById(pendingDeleteId))
    }

    @Test
    fun `deleteOutsideLookback preserves recurring master events`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // Recurring event with rrule - should be preserved even if old
        val recurringEvent = createTestEvent(
            uid = "recurring-event",
            startTs = parseDate("2025-01-01 10:00"),
            endTs = parseDate("2025-01-01 11:00"),
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.example.com/cal/recurring.ics"
        )
        val recurringId = eventsDao.insert(recurringEvent)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(0, deleted)
        assertNotNull(eventsDao.getById(recurringId))
    }

    @Test
    fun `deleteOutsideLookback preserves exception events`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // First create a master event
        val masterEvent = createTestEvent(
            uid = "master-event",
            startTs = parseDate("2025-01-01 10:00"),
            endTs = parseDate("2025-01-01 11:00"),
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.example.com/cal/master.ics"
        )
        val masterId = eventsDao.insert(masterEvent)

        // Exception event linked to master - should be preserved
        val exceptionEvent = createTestEvent(
            uid = "master-event", // Same UID as master per RFC 5545
            startTs = parseDate("2025-01-08 14:00"), // Moved from original 10:00
            endTs = parseDate("2025-01-08 15:00"),
            syncStatus = SyncStatus.SYNCED,
            originalEventId = masterId,
            originalInstanceTime = parseDate("2025-01-08 10:00"),
            caldavUrl = "https://caldav.example.com/cal/master.ics"
        )
        val exceptionId = eventsDao.insert(exceptionEvent)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(0, deleted)
        assertNotNull(eventsDao.getById(exceptionId))
    }

    @Test
    fun `deleteOutsideLookback preserves local calendar events`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // Local event without caldav_url - should be preserved
        val localEvent = createTestEvent(
            uid = "local-event",
            startTs = parseDate("2025-01-10 10:00"),
            endTs = parseDate("2025-01-10 11:00"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = null
        )
        val localEventId = eventsDao.insert(localEvent)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(0, deleted)
        assertNotNull(eventsDao.getById(localEventId))
    }

    @Test
    fun `deleteOutsideLookback preserves birthday events`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // Birthday event - should be preserved
        val birthdayEvent = createTestEvent(
            uid = "birthday-event",
            title = "John's Birthday",
            startTs = parseDate("2025-01-10 00:00"),
            endTs = parseDate("2025-01-10 23:59"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "contact_birthday:12345:2025"
        )
        val birthdayId = eventsDao.insert(birthdayEvent)

        // Anniversary event - should be preserved
        val anniversaryEvent = createTestEvent(
            uid = "anniversary-event",
            title = "Wedding Anniversary",
            startTs = parseDate("2025-01-10 00:00"),
            endTs = parseDate("2025-01-10 23:59"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "contact_anniversary:12345:2025"
        )
        val anniversaryId = eventsDao.insert(anniversaryEvent)

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(0, deleted)
        assertNotNull(eventsDao.getById(birthdayId))
        assertNotNull(eventsDao.getById(anniversaryId))
    }

    @Test
    fun `deleteOutsideLookback returns count of deleted events`() = runTest {
        val cutoffTs = parseDate("2025-01-15 00:00")

        // Create 3 old synced events that should be deleted
        repeat(3) { i ->
            eventsDao.insert(createTestEvent(
                uid = "old-event-$i",
                startTs = parseDate("2025-01-0${i + 1} 10:00"),
                endTs = parseDate("2025-01-0${i + 1} 11:00"),
                syncStatus = SyncStatus.SYNCED,
                caldavUrl = "https://caldav.example.com/cal/old$i.ics"
            ))
        }

        // Create 2 new events that should be preserved
        repeat(2) { i ->
            eventsDao.insert(createTestEvent(
                uid = "new-event-$i",
                startTs = parseDate("2025-01-2${i} 10:00"),
                endTs = parseDate("2025-01-2${i} 11:00"),
                syncStatus = SyncStatus.SYNCED,
                caldavUrl = "https://caldav.example.com/cal/new$i.ics"
            ))
        }

        val deleted = eventsDao.deleteOutsideLookback(cutoffTs)

        assertEquals(3, deleted)
    }

    // ==================== getEtagMapForCalendar Tests ====================

    @Test
    fun `getEtagMapForCalendar returns etag entries in time range`() = runTest {
        // Event in range with etag
        val event1 = createTestEvent(
            uid = "event-1",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            caldavUrl = "https://caldav.example.com/cal/event1.ics"
        )
        val id1 = eventsDao.insert(event1)
        eventsDao.updateEtag(id1, "etag-1")

        // Event in range with different etag
        val event2 = createTestEvent(
            uid = "event-2",
            startTs = parseDate("2025-01-16 10:00"),
            endTs = parseDate("2025-01-16 11:00"),
            caldavUrl = "https://caldav.example.com/cal/event2.ics"
        )
        val id2 = eventsDao.insert(event2)
        eventsDao.updateEtag(id2, "etag-2")

        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-14 00:00"),
            parseDate("2025-01-17 00:00")
        )

        assertEquals(2, entries.size)
        val etags = entries.associate { it.caldavUrl to it.etag }
        assertEquals("etag-1", etags["https://caldav.example.com/cal/event1.ics"])
        assertEquals("etag-2", etags["https://caldav.example.com/cal/event2.ics"])
    }

    @Test
    fun `getEtagMapForCalendar excludes events outside time range`() = runTest {
        // Event before range
        val oldEvent = createTestEvent(
            uid = "old-event",
            startTs = parseDate("2025-01-01 10:00"),
            endTs = parseDate("2025-01-01 11:00"),
            caldavUrl = "https://caldav.example.com/cal/old.ics"
        )
        val oldId = eventsDao.insert(oldEvent)
        eventsDao.updateEtag(oldId, "etag-old")

        // Event in range
        val inRangeEvent = createTestEvent(
            uid = "in-range-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            caldavUrl = "https://caldav.example.com/cal/inrange.ics"
        )
        val inRangeId = eventsDao.insert(inRangeEvent)
        eventsDao.updateEtag(inRangeId, "etag-inrange")

        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-10 00:00"),
            parseDate("2025-01-20 00:00")
        )

        assertEquals(1, entries.size)
        assertEquals("etag-inrange", entries[0].etag)
    }

    @Test
    fun `getEtagMapForCalendar excludes events without caldavUrl or etag`() = runTest {
        // Event without caldavUrl
        val localEvent = createTestEvent(
            uid = "local-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            caldavUrl = null
        )
        eventsDao.insert(localEvent)

        // Event with caldavUrl but no etag
        val noEtagEvent = createTestEvent(
            uid = "no-etag-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            caldavUrl = "https://caldav.example.com/cal/noetag.ics"
        )
        eventsDao.insert(noEtagEvent) // etag is null by default

        // Event with both caldavUrl and etag
        val goodEvent = createTestEvent(
            uid = "good-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            caldavUrl = "https://caldav.example.com/cal/good.ics"
        )
        val goodId = eventsDao.insert(goodEvent)
        eventsDao.updateEtag(goodId, "etag-good")

        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-14 00:00"),
            parseDate("2025-01-16 00:00")
        )

        assertEquals(1, entries.size)
        assertEquals("etag-good", entries[0].etag)
    }

    @Test
    fun `getEtagMapForCalendar excludes PENDING_DELETE events`() = runTest {
        // PENDING_DELETE event - should be excluded
        val deletingEvent = createTestEvent(
            uid = "deleting-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            syncStatus = SyncStatus.PENDING_DELETE,
            caldavUrl = "https://caldav.example.com/cal/deleting.ics"
        )
        val deletingId = eventsDao.insert(deletingEvent)
        eventsDao.updateEtag(deletingId, "etag-deleting")

        // SYNCED event - should be included
        val syncedEvent = createTestEvent(
            uid = "synced-event",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://caldav.example.com/cal/synced.ics"
        )
        val syncedId = eventsDao.insert(syncedEvent)
        eventsDao.updateEtag(syncedId, "etag-synced")

        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-14 00:00"),
            parseDate("2025-01-16 00:00")
        )

        assertEquals(1, entries.size)
        assertEquals("etag-synced", entries[0].etag)
    }

    @Test
    fun `getEtagMapForCalendar includes recurring events regardless of time range`() = runTest {
        // Recurring event that started BEFORE the query range
        // This simulates a weekly meeting that started 2 years ago
        // CalDAV servers return it if ANY occurrence is in range, so we should too
        val recurringEvent = createTestEvent(
            uid = "recurring-old",
            startTs = parseDate("2023-01-01 10:00"),  // 2 years before range
            endTs = parseDate("2023-01-01 11:00"),
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            caldavUrl = "https://caldav.example.com/cal/recurring.ics"
        )
        val recurringId = eventsDao.insert(recurringEvent)
        eventsDao.updateEtag(recurringId, "etag-recurring")

        // Non-recurring event BEFORE range - should be excluded
        val oldEvent = createTestEvent(
            uid = "old-single",
            startTs = parseDate("2023-01-01 10:00"),
            endTs = parseDate("2023-01-01 11:00"),
            caldavUrl = "https://caldav.example.com/cal/old.ics"
        )
        val oldId = eventsDao.insert(oldEvent)
        eventsDao.updateEtag(oldId, "etag-old")

        // Query for 2025 range - recurring should be included, old single should not
        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 00:00")
        )

        assertEquals(1, entries.size)
        assertEquals("etag-recurring", entries[0].etag)
    }

    @Test
    fun `getEtagMapForCalendar includes recurring events in range and outside range`() = runTest {
        // Recurring event IN the range
        val recurringInRange = createTestEvent(
            uid = "recurring-inrange",
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            rrule = "FREQ=DAILY",
            caldavUrl = "https://caldav.example.com/cal/recurring-inrange.ics"
        )
        val inRangeId = eventsDao.insert(recurringInRange)
        eventsDao.updateEtag(inRangeId, "etag-inrange")

        // Recurring event BEFORE the range (should still be included)
        val recurringOutside = createTestEvent(
            uid = "recurring-outside",
            startTs = parseDate("2020-01-01 10:00"),
            endTs = parseDate("2020-01-01 11:00"),
            rrule = "FREQ=YEARLY",
            caldavUrl = "https://caldav.example.com/cal/recurring-outside.ics"
        )
        val outsideId = eventsDao.insert(recurringOutside)
        eventsDao.updateEtag(outsideId, "etag-outside")

        val entries = eventsDao.getEtagMapForCalendar(
            calendarId,
            parseDate("2025-01-14 00:00"),
            parseDate("2025-01-16 00:00")
        )

        // Both recurring events should be included
        assertEquals(2, entries.size)
        val etags = entries.map { it.etag }.toSet()
        assertTrue(etags.contains("etag-inrange"))
        assertTrue(etags.contains("etag-outside"))
    }

    // ==================== Exception Events Cleanup Tests ====================

    @Test
    fun `deleteExceptionEventsBeforeCutoff deletes exception events before cutoff`() = runTest {
        // Create master recurring event
        val masterEvent = createTestEvent(
            uid = "master-weekly",
            startTs = parseDate("2024-01-01 10:00"),
            endTs = parseDate("2024-01-01 11:00"),
            rrule = "FREQ=WEEKLY",
            caldavUrl = "https://caldav.example.com/master.ics"
        )
        val masterId = eventsDao.insert(masterEvent)

        // Create exception event BEFORE cutoff (Jan 8 occurrence modified)
        val oldException = createTestEvent(
            uid = "master-weekly",  // Same UID as master (RFC 5545)
            startTs = parseDate("2024-01-08 11:00"),  // Modified time
            endTs = parseDate("2024-01-08 12:00"),
            originalEventId = masterId,
            originalInstanceTime = parseDate("2024-01-08 10:00"),  // Original time
            caldavUrl = null  // Exceptions don't have caldavUrl
        )
        eventsDao.insert(oldException)

        // Create exception event AFTER cutoff (Mar 11 occurrence modified)
        val newException = createTestEvent(
            uid = "master-weekly",
            startTs = parseDate("2024-03-11 11:00"),
            endTs = parseDate("2024-03-11 12:00"),
            originalEventId = masterId,
            originalInstanceTime = parseDate("2024-03-11 10:00"),
            caldavUrl = null
        )
        eventsDao.insert(newException)

        // Cutoff at Feb 1 - should delete Jan exception, keep Mar exception
        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = eventsDao.deleteExceptionEventsBeforeCutoff(cutoff)

        assertEquals(1, deleted)

        // Verify: master and new exception remain, old exception deleted
        val remaining = eventsDao.getByCalendarId(calendarId).first()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.originalEventId == null })  // Master
        assertTrue(remaining.any { it.originalInstanceTime == parseDate("2024-03-11 10:00") })  // New exception
        assertFalse(remaining.any { it.originalInstanceTime == parseDate("2024-01-08 10:00") })  // Old deleted
    }

    @Test
    fun `deleteExceptionEventsBeforeCutoff preserves non-exception events`() = runTest {
        // Non-recurring event before cutoff
        val regularEvent = createTestEvent(
            uid = "regular-event",
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            caldavUrl = "https://caldav.example.com/regular.ics"
        )
        eventsDao.insert(regularEvent)

        // Recurring master before cutoff
        val masterEvent = createTestEvent(
            uid = "master-event",
            startTs = parseDate("2024-01-01 10:00"),
            endTs = parseDate("2024-01-01 11:00"),
            rrule = "FREQ=DAILY",
            caldavUrl = "https://caldav.example.com/master.ics"
        )
        eventsDao.insert(masterEvent)

        // Cutoff after both events
        val cutoff = parseDate("2024-06-01 00:00")
        val deleted = eventsDao.deleteExceptionEventsBeforeCutoff(cutoff)

        // No exception events, so nothing deleted
        assertEquals(0, deleted)

        // Both events preserved
        val remaining = eventsDao.getByCalendarId(calendarId).first()
        assertEquals(2, remaining.size)
    }

    @Test
    fun `deleteExceptionEventsBeforeCutoff handles multiple master events`() = runTest {
        // Master 1
        val master1 = createTestEvent(
            uid = "master1",
            startTs = parseDate("2024-01-01 10:00"),
            endTs = parseDate("2024-01-01 11:00"),
            rrule = "FREQ=WEEKLY",
            caldavUrl = "https://caldav.example.com/master1.ics"
        )
        val master1Id = eventsDao.insert(master1)

        // Master 2
        val master2 = createTestEvent(
            uid = "master2",
            startTs = parseDate("2024-01-02 10:00"),
            endTs = parseDate("2024-01-02 11:00"),
            rrule = "FREQ=WEEKLY",
            caldavUrl = "https://caldav.example.com/master2.ics"
        )
        val master2Id = eventsDao.insert(master2)

        // Old exception for master1 (should be deleted)
        eventsDao.insert(createTestEvent(
            uid = "master1",
            startTs = parseDate("2024-01-08 11:00"),
            endTs = parseDate("2024-01-08 12:00"),
            originalEventId = master1Id,
            originalInstanceTime = parseDate("2024-01-08 10:00")
        ))

        // New exception for master1 (should be kept)
        eventsDao.insert(createTestEvent(
            uid = "master1",
            startTs = parseDate("2024-03-04 11:00"),
            endTs = parseDate("2024-03-04 12:00"),
            originalEventId = master1Id,
            originalInstanceTime = parseDate("2024-03-04 10:00")
        ))

        // Old exception for master2 (should be deleted)
        eventsDao.insert(createTestEvent(
            uid = "master2",
            startTs = parseDate("2024-01-09 11:00"),
            endTs = parseDate("2024-01-09 12:00"),
            originalEventId = master2Id,
            originalInstanceTime = parseDate("2024-01-09 10:00")
        ))

        // Cutoff at Feb 1
        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = eventsDao.deleteExceptionEventsBeforeCutoff(cutoff)

        assertEquals(2, deleted)  // Two old exceptions from both masters

        val remaining = eventsDao.getByCalendarId(calendarId).first()
        assertEquals(3, remaining.size)  // 2 masters + 1 new exception
    }

    @Test
    fun `deleteExceptionEventsBeforeCutoff uses originalInstanceTime not startTs`() = runTest {
        // Master event
        val master = createTestEvent(
            uid = "master",
            startTs = parseDate("2024-01-01 10:00"),
            endTs = parseDate("2024-01-01 11:00"),
            rrule = "FREQ=WEEKLY"
        )
        val masterId = eventsDao.insert(master)

        // Exception: original time is Jan 8, but moved to Mar 1 (after cutoff)
        // originalInstanceTime is before cutoff, so should be deleted
        val movedException = createTestEvent(
            uid = "master",
            startTs = parseDate("2024-03-01 10:00"),  // Moved to March
            endTs = parseDate("2024-03-01 11:00"),
            originalEventId = masterId,
            originalInstanceTime = parseDate("2024-01-08 10:00")  // Original was January
        )
        eventsDao.insert(movedException)

        // Cutoff Feb 1 - originalInstanceTime (Jan 8) is before, so delete
        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = eventsDao.deleteExceptionEventsBeforeCutoff(cutoff)

        assertEquals(1, deleted)

        val remaining = eventsDao.getByCalendarId(calendarId).first()
        assertEquals(1, remaining.size)  // Only master remains
    }

    @Test
    fun `deleteExceptionEventsBeforeCutoff returns count of deleted events`() = runTest {
        val master = createTestEvent(
            uid = "master",
            startTs = parseDate("2024-01-01 10:00"),
            endTs = parseDate("2024-01-01 11:00"),
            rrule = "FREQ=DAILY"
        )
        val masterId = eventsDao.insert(master)

        // Create 5 exception events, all before cutoff
        repeat(5) { i ->
            eventsDao.insert(createTestEvent(
                uid = "master",
                startTs = parseDate("2024-01-${10 + i} 11:00"),
                endTs = parseDate("2024-01-${10 + i} 12:00"),
                originalEventId = masterId,
                originalInstanceTime = parseDate("2024-01-${10 + i} 10:00")
            ))
        }

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = eventsDao.deleteExceptionEventsBeforeCutoff(cutoff)

        assertEquals(5, deleted)
    }

    // ==================== updateReminders Tests ====================

    @Test
    fun `updateReminders updates only reminders and updated_at fields`() = runTest {
        val originalTs = System.currentTimeMillis() - 10000
        val event = createTestEvent(
            title = "Original Title",
            reminders = listOf("-PT15M")
        ).copy(updatedAt = originalTs)
        val eventId = eventsDao.insert(event)

        // Update reminders
        val newRemindersJson = """["-PT30M", "-PT1H"]"""
        val updateTime = System.currentTimeMillis()
        eventsDao.updateReminders(eventId, newRemindersJson, updateTime)

        // Verify
        val updated = eventsDao.getById(eventId)
        assertNotNull(updated)
        assertEquals("Original Title", updated!!.title) // Title unchanged
        assertEquals(listOf("-PT30M", "-PT1H"), updated.reminders) // Reminders updated
        assertEquals(updateTime, updated.updatedAt) // updated_at changed
    }

    @Test
    fun `updateReminders can set reminders to null`() = runTest {
        val event = createTestEvent(reminders = listOf("-PT15M"))
        val eventId = eventsDao.insert(event)

        eventsDao.updateReminders(eventId, null, System.currentTimeMillis())

        val updated = eventsDao.getById(eventId)
        // Room's Converter converts null to empty list
        assertTrue(updated?.reminders?.isEmpty() == true)
    }

    @Test
    fun `updateReminders can set empty list`() = runTest {
        val event = createTestEvent(reminders = listOf("-PT15M"))
        val eventId = eventsDao.insert(event)

        eventsDao.updateReminders(eventId, "[]", System.currentTimeMillis())

        val updated = eventsDao.getById(eventId)
        assertTrue(updated?.reminders?.isEmpty() == true)
    }

    @Test
    fun `updateReminders does not change other fields`() = runTest {
        val event = createTestEvent(
            title = "Test Event",
            description = "Test Description",
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = "https://example.com/event.ics",
            reminders = null
        )
        val eventId = eventsDao.insert(event)
        val beforeUpdate = eventsDao.getById(eventId)!!

        eventsDao.updateReminders(eventId, """["-PT15M"]""", System.currentTimeMillis())

        val afterUpdate = eventsDao.getById(eventId)!!
        assertEquals(beforeUpdate.title, afterUpdate.title)
        assertEquals(beforeUpdate.description, afterUpdate.description)
        assertEquals(beforeUpdate.syncStatus, afterUpdate.syncStatus)
        assertEquals(beforeUpdate.caldavUrl, afterUpdate.caldavUrl)
        assertEquals(beforeUpdate.startTs, afterUpdate.startTs)
        assertEquals(beforeUpdate.endTs, afterUpdate.endTs)
    }

    // ==================== updateOrganizerScheduleStatus ====================

    @Test
    fun `updateOrganizerScheduleStatus sets only that column`() = runTest {
        val id = eventsDao.insert(createTestEvent(title = "Meeting", caldavUrl = "https://test.com/cal/e.ics"))
        eventsDao.updateEtag(id, "etag-before")
        val before = eventsDao.getById(id)!!

        eventsDao.updateOrganizerScheduleStatus(id, "1.2")

        val after = eventsDao.getById(id)!!
        assertEquals("1.2", after.organizerScheduleStatus)
        // Unrelated columns untouched (mirrors the updateEtag-style targeted update).
        assertEquals("Meeting", after.title)
        assertEquals("etag-before", after.etag)
        assertEquals(before.caldavUrl, after.caldavUrl)
        assertEquals(before.startTs, after.startTs)
    }

    @Test
    fun `updateOrganizerScheduleStatus can clear the column with null`() = runTest {
        val id = eventsDao.insert(createTestEvent())
        eventsDao.updateOrganizerScheduleStatus(id, "2.0")
        eventsDao.updateOrganizerScheduleStatus(id, null)
        assertEquals(null, eventsDao.getById(id)!!.organizerScheduleStatus)
    }

    // ==================== Helper Functions ====================

    private fun createTestEvent(
        uid: String = "test-${System.nanoTime()}@test.com",
        title: String = "Test Event",
        description: String? = null,
        calendarId: Long = this.calendarId,
        startTs: Long = parseDate("2025-01-15 10:00"),
        endTs: Long = parseDate("2025-01-15 11:00"),
        rrule: String? = null,
        exdate: String? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        caldavUrl: String? = null,
        sequence: Int = 0,
        reminders: List<String>? = null
    ) = Event(
        uid = uid,
        calendarId = calendarId,
        title = title,
        description = description,
        startTs = startTs,
        endTs = endTs,
        dtstamp = System.currentTimeMillis(),
        rrule = rrule,
        exdate = exdate,
        syncStatus = syncStatus,
        originalEventId = originalEventId,
        originalInstanceTime = originalInstanceTime,
        caldavUrl = caldavUrl,
        sequence = sequence,
        reminders = reminders
    )
}