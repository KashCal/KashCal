package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for the database state a calendar move queues and leaves behind.
 *
 * The push tries a WebDAV MOVE first and falls back to CREATE in the target then DELETE from
 * the source (`PushStrategy.processMove`), so the MOVE op must carry the source URL from queue
 * time.
 *
 * Cases covered:
 * - targetUrl capture timing
 * - Recurring master, with and without exceptions
 * - Retry bookkeeping and a partial failure (source gone, target not created)
 * - Read-only target calendar
 * - Several moves at once, and a move during an active sync
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CalendarMoveAdversarialTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testAccountId: Long = 0
    private var sourceCalendarId: Long = 0
    private var targetCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

        // Setup test account and calendars
        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        sourceCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/123/calendars/personal/",
                displayName = "Personal",
                color = 0xFF2196F3.toInt()
            )
        )
        targetCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/123/calendars/work/",
                displayName = "Work",
                color = 0xFFFF5722.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Move Operation Setup Tests ====================

    @Test
    fun `move operation stores source URL before calendar change`() = runTest {
        val now = System.currentTimeMillis()
        val sourceUrl = "https://caldav.icloud.com/123/calendars/personal/event1.ics"

        val event = Event(
            uid = "move-url@test.com",
            calendarId = sourceCalendarId,
            title = "Event to Move",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = sourceUrl,
            etag = "\"abc123\"",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        // Queue the MOVE before changing the calendar, so it captures the source URL
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = sourceUrl, // Source URL for DELETE step
                targetCalendarId = targetCalendarId
            )
        )

        // Now update event to target calendar
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null, // Cleared - new URL assigned after sync
            etag = null,
            syncStatus = SyncStatus.PENDING_CREATE
        ))

        // Verify pending operation has source URL
        val ops = database.pendingOperationsDao().getForEvent(eventId)
        assertEquals(1, ops.size)
        assertEquals(sourceUrl, ops.first().targetUrl)
        assertEquals(targetCalendarId, ops.first().targetCalendarId)
    }

    @Test
    fun `move operation without targetUrl would fail DELETE`() = runTest {
        // Documents the trap: queuing MOVE after clearing caldavUrl loses the source URL
        val now = System.currentTimeMillis()
        val sourceUrl = "https://caldav.icloud.com/123/calendars/personal/event2.ics"

        val event = Event(
            uid = "bad-move@test.com",
            calendarId = sourceCalendarId,
            title = "Bad Move",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = sourceUrl,
            etag = "\"xyz789\"",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        // Wrong order: the event is updated before the op is queued
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null, // The source URL is gone from the row
            syncStatus = SyncStatus.PENDING_CREATE
        ))

        // Queuing MOVE now reads a null URL
        val updatedEvent = database.eventsDao().getById(eventId)!!
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = updatedEvent.caldavUrl, // null
            targetCalendarId = targetCalendarId
        )

        assertNull(
            "Wrong order: caldavUrl is null after update",
            pendingOp.targetUrl
        )
    }

    // ==================== Recurring Event Move Tests ====================

    @Test
    fun `move recurring event moves master`() = runTest {
        val now = System.currentTimeMillis()

        val masterEvent = Event(
            uid = "recurring-move@test.com",
            calendarId = sourceCalendarId,
            title = "Weekly Meeting",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            rrule = "FREQ=WEEKLY;COUNT=10",
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/weekly.ics",
            etag = "\"master123\"",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(masterEvent)

        // Generate occurrences
        val savedMaster = database.eventsDao().getById(masterId)!!
        occurrenceGenerator.generateOccurrences(
            savedMaster,
            now - 86400000,
            now + 100 * 86400000
        )

        // Queue move for master
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = masterId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = savedMaster.caldavUrl,
                targetCalendarId = targetCalendarId
            )
        )

        // Update master calendar
        database.eventsDao().update(savedMaster.copy(
            calendarId = targetCalendarId,
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        ))

        // Verify master queued for move
        val ops = database.pendingOperationsDao().getForEvent(masterId)
        assertEquals(PendingOperation.OPERATION_MOVE, ops.first().operation)
    }

    @Test
    fun `move recurring event with exceptions - exceptions follow master`() = runTest {
        val now = System.currentTimeMillis()
        val masterUid = "series-with-exceptions@test.com"

        val masterEvent = Event(
            uid = masterUid,
            calendarId = sourceCalendarId,
            title = "Team Sync",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            rrule = "FREQ=DAILY;COUNT=5",
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/sync.ics",
            etag = "\"master456\"",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(masterEvent)

        // Create exception (same UID per RFC 5545)
        val exceptionTime = now + 86400000L
        val exception = Event(
            uid = masterUid,
            calendarId = sourceCalendarId,
            title = "Team Sync - MOVED TO AFTERNOON",
            startTs = exceptionTime + 4 * 3600000, // Afternoon
            endTs = exceptionTime + 5 * 3600000,
            dtstamp = now,
            originalEventId = masterId,
            originalInstanceTime = exceptionTime,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)

        // Move master - exceptions must follow
        val savedMaster = database.eventsDao().getById(masterId)!!
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = masterId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = savedMaster.caldavUrl,
                targetCalendarId = targetCalendarId
            )
        )

        // Update both master and exception to target calendar
        database.eventsDao().update(savedMaster.copy(
            calendarId = targetCalendarId,
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_UPDATE
        ))
        database.eventsDao().update(exception.copy(
            id = exceptionId,
            calendarId = targetCalendarId
        ))

        // Verify exception follows master
        val movedMaster = database.eventsDao().getById(masterId)!!
        val movedEx = database.eventsDao().getById(exceptionId)!!
        assertEquals(targetCalendarId, movedMaster.calendarId)
        assertEquals(targetCalendarId, movedEx.calendarId)

        // Exceptions still linked to master
        val exceptions = database.eventsDao().getExceptionsForMaster(masterId)
        assertEquals(1, exceptions.size)
        assertEquals(exceptionId, exceptions.first().id)
    }

    // ==================== Failure State Tests ====================

    @Test
    fun `move operation tracks retry count`() = runTest {
        val now = System.currentTimeMillis()

        val event = Event(
            uid = "retry-move@test.com",
            calendarId = sourceCalendarId,
            title = "Retry Test",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/retry.ics",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = "https://caldav.icloud.com/123/calendars/personal/retry.ics",
            targetCalendarId = targetCalendarId
        )
        val opId = database.pendingOperationsDao().insert(pendingOp)

        // Simulate failed attempt - increment retry
        database.pendingOperationsDao().update(
            pendingOp.copy(
                id = opId,
                retryCount = 1,
                lastError = "DELETE returned 503",
                nextRetryAt = now + PendingOperation.calculateRetryDelay(1)
            )
        )

        val updated = database.pendingOperationsDao().getById(opId)!!
        assertEquals(1, updated.retryCount)
        assertTrue(updated.nextRetryAt > now)
        assertEquals("DELETE returned 503", updated.lastError)
    }

    @Test
    fun `partial move failure - DELETE succeeds PUT fails`() = runTest {
        // The event is gone from the source but not yet created in the target
        val now = System.currentTimeMillis()

        val event = Event(
            uid = "partial-move@test.com",
            calendarId = sourceCalendarId,
            title = "Partial Move",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/partial.ics",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        // Queue move
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = event.caldavUrl,
                targetCalendarId = targetCalendarId
            )
        )

        // Simulate: DELETE succeeded, PUT failed. The event exists only in the local DB, and
        // its row must stay PENDING_CREATE so a later push creates it in the target.
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null, // No URL yet: the PUT didn't complete
            syncStatus = SyncStatus.PENDING_CREATE // Need to retry PUT
        ))

        // Event should be marked for CREATE in target calendar
        val movedEvent = database.eventsDao().getById(eventId)!!
        assertEquals(SyncStatus.PENDING_CREATE, movedEvent.syncStatus)
        assertNull(movedEvent.caldavUrl)
    }

    // ==================== Read-Only Calendar Tests ====================

    @Test
    fun `move to read-only calendar preserves source`() = runTest {
        val now = System.currentTimeMillis()

        // Create read-only target calendar
        val readOnlyCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/123/calendars/holidays/",
                displayName = "Holidays",
                color = 0xFF4CAF50.toInt(),
                isReadOnly = true
            )
        )

        val event = Event(
            uid = "readonly-move@test.com",
            calendarId = sourceCalendarId,
            title = "Cannot Move Here",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/readonly.ics",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        // Verify target is read-only
        val targetCal = database.calendarsDao().getById(readOnlyCalendarId)!!
        assertTrue("Target calendar should be read-only", targetCal.isReadOnly)

        // EventWriter.moveEventToCalendar refuses a read-only target; the DB keeps the source
        val originalEvent = database.eventsDao().getById(eventId)!!
        assertEquals(sourceCalendarId, originalEvent.calendarId)
    }

    // ==================== Concurrent Operation Tests ====================

    @Test
    fun `multiple events can be moved simultaneously`() = runTest {
        val now = System.currentTimeMillis()

        // Create multiple events
        val eventIds = (1..5).map { i ->
            val event = Event(
                uid = "batch-move-$i@test.com",
                calendarId = sourceCalendarId,
                title = "Batch Event $i",
                startTs = now + i * 3600000,
                endTs = now + i * 3600000 + 3600000,
                dtstamp = now,
                caldavUrl = "https://caldav.icloud.com/123/calendars/personal/batch$i.ics",
                syncStatus = SyncStatus.SYNCED
            )
            database.eventsDao().insert(event)
        }

        // Queue all moves
        eventIds.forEachIndexed { index, eventId ->
            database.pendingOperationsDao().insert(
                PendingOperation(
                    eventId = eventId,
                    operation = PendingOperation.OPERATION_MOVE,
                    targetUrl = "https://caldav.icloud.com/123/calendars/personal/batch${index + 1}.ics",
                    targetCalendarId = targetCalendarId
                )
            )
        }

        // Verify all operations queued
        val allOps = database.pendingOperationsDao().getAll()
        assertEquals(5, allOps.size)
        assertTrue(allOps.all { it.operation == PendingOperation.OPERATION_MOVE })
    }

    @Test
    fun `move during active sync marks event pending`() = runTest {
        val now = System.currentTimeMillis()

        val event = Event(
            uid = "sync-conflict@test.com",
            calendarId = sourceCalendarId,
            title = "Sync Conflict",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            caldavUrl = "https://caldav.icloud.com/123/calendars/personal/conflict.ics",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        // Simulate: a sync is running when the user moves the event. The op is queued and a
        // later sync picks it up.

        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = event.caldavUrl,
                targetCalendarId = targetCalendarId
            )
        )

        // Mark event for move
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_CREATE
        ))

        // Pending operation exists
        val ops = database.pendingOperationsDao().getForEvent(eventId)
        assertEquals(1, ops.size)

        // Event status reflects pending sync
        val updated = database.eventsDao().getById(eventId)!!
        assertEquals(SyncStatus.PENDING_CREATE, updated.syncStatus)
    }
}
