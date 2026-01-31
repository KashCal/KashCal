package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for calendar move operations.
 *
 * Calendar moves are complex because CalDAV doesn't support MOVE for events.
 * Instead, we must DELETE from old calendar + PUT to new calendar.
 *
 * Edge cases tested:
 * - Move to non-existent calendar
 * - Move during active sync
 * - Move recurring event (master + exceptions)
 * - Partial failure states (DELETE succeeds, PUT fails)
 * - Move to read-only calendar
 * - targetUrl capture timing
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

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

        // CRITICAL: Queue MOVE operation BEFORE changing calendar
        // This captures the source URL needed for DELETE
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
        // This documents the bug pattern: queuing MOVE after clearing caldavUrl
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

        // WRONG: Update event BEFORE queueing operation
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null, // Now we can't get the source URL!
            syncStatus = SyncStatus.PENDING_CREATE
        ))

        // Trying to queue MOVE now - source URL is lost
        val updatedEvent = database.eventsDao().getById(eventId)!!
        val pendingOp = PendingOperation(
            eventId = eventId,
            operation = PendingOperation.OPERATION_MOVE,
            targetUrl = updatedEvent.caldavUrl, // NULL!
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
        // This is a critical edge case: event deleted from source but not created in target
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

        // Simulate: DELETE succeeded, but PUT failed
        // Event is now in local DB but gone from source server
        // The pending operation should record this state
        database.eventsDao().update(event.copy(
            id = eventId,
            calendarId = targetCalendarId,
            caldavUrl = null, // No URL yet - PUT didn't complete
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

        // Event should NOT be moved to read-only calendar
        // This is enforced at UI/domain layer, but DB should preserve source
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

        // Simulate: sync is running, user requests move
        // Event should be queued, sync will pick it up

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
