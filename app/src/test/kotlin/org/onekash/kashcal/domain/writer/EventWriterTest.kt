package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Comprehensive tests for EventWriter.
 *
 * Tests cover:
 * - Create events (single and recurring)
 * - Update events
 * - Delete events (soft delete for sync)
 * - Edit single occurrence (exception creation)
 * - Delete single occurrence (EXDATE)
 * - Split series (this and all future)
 * - Move event to different calendar
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var iCloudCalendar2Id: Long = 0
    private var localCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Create test accounts and calendars
        runTest {
            val testAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/test/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
            iCloudCalendar2Id = database.calendarsDao().insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/work/",
                    displayName = "Work Calendar",
                    color = 0xFFFF5722.toInt()
                )
            )

            val localAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            localCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = localAccountId,
                    caldavUrl = "local://default",
                    displayName = "Local",
                    color = 0xFF4CAF50.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Create Event ==========

    @Test
    fun `createEvent generates UID if not provided`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event)

        assertTrue(created.uid.isNotBlank())
        assertTrue(created.uid.contains("@kashcal.onekash.org"))
    }

    @Test
    fun `createEvent preserves provided UID`() = runTest {
        val event = createBaseEvent().copy(uid = "custom-uid@example.com")

        val created = eventWriter.createEvent(event)

        assertEquals("custom-uid@example.com", created.uid)
    }

    @Test
    fun `createEvent sets PENDING_CREATE status for CalDAV calendar`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event, isLocal = false)

        assertEquals(SyncStatus.PENDING_CREATE, created.syncStatus)
    }

    @Test
    fun `createEvent sets SYNCED status for local calendar`() = runTest {
        val event = createBaseEvent().copy(calendarId = localCalendarId)

        val created = eventWriter.createEvent(event, isLocal = true)

        assertEquals(SyncStatus.SYNCED, created.syncStatus)
    }

    @Test
    fun `createEvent generates single occurrence for non-recurring event`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(1, occurrences.size)
        assertEquals(created.startTs, occurrences[0].startTs)
    }

    @Test
    fun `createEvent generates multiple occurrences for recurring event`() = runTest {
        val event = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10")

        val created = eventWriter.createEvent(event)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(10, occurrences.size)
    }

    @Test
    fun `createEvent queues pending operation for CalDAV calendar`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event, isLocal = false)

        val pendingOps = database.pendingOperationsDao().getForEvent(created.id)
        assertEquals(1, pendingOps.size)
        assertEquals(PendingOperation.OPERATION_CREATE, pendingOps[0].operation)
    }

    @Test
    fun `createEvent does not queue operation for local calendar`() = runTest {
        val event = createBaseEvent().copy(calendarId = localCalendarId)

        val created = eventWriter.createEvent(event, isLocal = true)

        val pendingOps = database.pendingOperationsDao().getForEvent(created.id)
        assertEquals(0, pendingOps.size)
    }

    // ========== Update Event ==========

    @Test
    fun `updateEvent changes event fields`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated Title", location = "New Location"),
            isLocal = true
        )

        val fromDb = database.eventsDao().getById(updated.id)
        assertEquals("Updated Title", fromDb?.title)
        assertEquals("New Location", fromDb?.location)
    }

    @Test
    fun `updateEvent increments sequence when RRULE changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY"),
            isLocal = true
        )
        assertEquals(0, original.sequence)

        val updated = eventWriter.updateEvent(
            original.copy(rrule = "FREQ=WEEKLY"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when timing changes`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(startTs = original.startTs + 3600000),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent does not increment sequence for title change`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(title = "New Title"),
            isLocal = true
        )

        assertEquals(0, updated.sequence)
    }

    @Test
    fun `updateEvent regenerates occurrences when RRULE changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertEquals(5, database.occurrencesDao().getForEvent(original.id).size)

        eventWriter.updateEvent(
            original.copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )

        assertEquals(10, database.occurrencesDao().getForEvent(original.id).size)
    }

    @Test
    fun `updateEvent sets PENDING_UPDATE for synced event`() = runTest {
        // Create and mark as synced
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(original.id, "etag123", System.currentTimeMillis())

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated"),
            isLocal = false
        )

        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `updateEvent keeps PENDING_CREATE if never synced`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, original.syncStatus)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated"),
            isLocal = false
        )

        assertEquals(SyncStatus.PENDING_CREATE, updated.syncStatus)
    }

    // ========== Delete Event ==========

    @Test
    fun `deleteEvent hard deletes local-only event`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(calendarId = localCalendarId),
            isLocal = true
        )

        eventWriter.deleteEvent(event.id, isLocal = true)

        assertNull(database.eventsDao().getById(event.id))
    }

    @Test
    fun `deleteEvent soft deletes CalDAV event`() = runTest {
        // Create and mark as synced
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(event.id, "etag123", System.currentTimeMillis())

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNotNull(deleted)
        assertEquals(SyncStatus.PENDING_DELETE, deleted?.syncStatus)
    }

    @Test
    fun `deleteEvent hard deletes never-synced CalDAV event`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        eventWriter.deleteEvent(event.id, isLocal = false)

        assertNull(database.eventsDao().getById(event.id))
    }

    @Test
    fun `deleteEvent removes occurrences`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertEquals(5, database.occurrencesDao().getForEvent(event.id).size)

        eventWriter.deleteEvent(event.id, isLocal = true)

        assertEquals(0, database.occurrencesDao().getForEvent(event.id).size)
    }

    @Test
    fun `deleteEvent queues DELETE operation for synced event`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(event.id, "etag123", System.currentTimeMillis())

        eventWriter.deleteEvent(event.id, isLocal = false)

        val pendingOps = database.pendingOperationsDao().getForEvent(event.id)
        assertTrue(pendingOps.any { it.operation == PendingOperation.OPERATION_DELETE })
    }

    // ========== Edit Single Occurrence (Exception) ==========

    @Test
    fun `editSingleOccurrence creates exception event`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val targetOccurrence = occurrences[2] // 3rd occurrence

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = Event(
                uid = "",
                calendarId = master.calendarId,
                title = "Modified Occurrence",
                startTs = targetOccurrence.startTs + 3600000, // 1 hour later
                endTs = targetOccurrence.endTs + 3600000,
                dtstamp = System.currentTimeMillis()
            ),
            isLocal = true
        )

        assertEquals("Modified Occurrence", exception.title)
        assertEquals(master.id, exception.originalEventId)
        assertEquals(targetOccurrence.startTs, exception.originalInstanceTime)
        assertNull(exception.rrule) // Exception cannot have RRULE
    }

    @Test
    fun `editSingleOccurrence links occurrence to exception`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = true
        )

        // v15.0.6: Find by exceptionEventId since occurrence times are updated to exception's times
        val updatedOccurrence = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull(updatedOccurrence)
        assertEquals(exception.id, updatedOccurrence?.exceptionEventId)
        // Verify times were updated to exception event's times
        assertEquals(exception.startTs, updatedOccurrence?.startTs)
        assertEquals(exception.endTs, updatedOccurrence?.endTs)
    }

    @Test
    fun `editSingleOccurrence throws for non-recurring event`() = runTest {
        val singleEvent = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        try {
            eventWriter.editSingleOccurrence(
                masterEventId = singleEvent.id,
                occurrenceTimeMs = singleEvent.startTs,
                modifiedEvent = createBaseEvent(),
                isLocal = true
            )
            assertTrue("Should have thrown exception", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    @Test
    fun `editSingleOccurrence exception has same UID as master`() = runTest {
        // RFC 5545: Exception MUST have same UID as master, distinguished by RECURRENCE-ID
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = true
        )

        // Exception UID must equal master UID (not master.uid-timestamp)
        assertEquals(master.uid, exception.uid)
        assertFalse(exception.uid.contains("-${targetOccurrence.startTs}"))
    }

    @Test
    fun `editSingleOccurrence queues UPDATE on master not CREATE on exception`() = runTest {
        // Create synced master event
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        // Mark master as synced with server URL
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/test/master.ics",
            "etag123",
            System.currentTimeMillis()
        )
        // Clear any pending operations from creation
        database.pendingOperationsDao().deleteForEvent(master.id)

        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = false
        )

        // Pending operation should be on MASTER (UPDATE), not exception (CREATE)
        val masterOps = database.pendingOperationsDao().getForEvent(master.id)
        assertEquals(1, masterOps.size)
        assertEquals(PendingOperation.OPERATION_UPDATE, masterOps[0].operation)

        // Exception should have NO pending operations
        val exceptionOps = database.pendingOperationsDao().getForEvent(exception.id)
        assertEquals(0, exceptionOps.size)
    }

    @Test
    fun `editSingleOccurrence exception has SYNCED status`() = runTest {
        // Exception is bundled with master for sync, so should be SYNCED locally
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/test/master.ics",
            "etag123",
            System.currentTimeMillis()
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = false
        )

        // Exception is bundled with master, so it's marked SYNCED locally
        assertEquals(SyncStatus.SYNCED, exception.syncStatus)
    }

    @Test
    fun `editSingleOccurrence re-editing exception preserves UID`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Once"),
            isLocal = true
        )

        // Second edit of same occurrence
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Twice"),
            isLocal = true
        )

        // Should be same event with preserved UID
        assertEquals(exception1.id, exception2.id)
        assertEquals(exception1.uid, exception2.uid)
        assertEquals(master.uid, exception2.uid)
        assertEquals("Modified Twice", exception2.title)
    }

    // ========== Delete Single Occurrence (EXDATE) ==========

    @Test
    fun `deleteSingleOccurrence adds EXDATE to master`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertNull(master.exdate)

        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]
        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        val updated = database.eventsDao().getById(master.id)
        assertNotNull(updated?.exdate)
        assertTrue(updated!!.exdate!!.isNotBlank())
    }

    @Test
    fun `deleteSingleOccurrence marks occurrence as cancelled`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        val updated = database.occurrencesDao().getOccurrenceAtTime(master.id, targetOccurrence.startTs)
        assertTrue(updated?.isCancelled == true)
    }

    @Test
    fun `deleteSingleOccurrence keeps other occurrences intact`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        // All 5 still exist, just one is cancelled
        val allOccurrences = database.occurrencesDao().getForEvent(master.id)
        assertEquals(5, allOccurrences.size)
        assertEquals(1, allOccurrences.count { it.isCancelled })
    }

    // ========== Split Series ==========

    @Test
    fun `splitSeries truncates master and creates new event`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitPoint = occurrences[5].startTs // Split at 6th occurrence

        val newEvent = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitPoint,
            modifiedEvent = createBaseEvent().copy(
                title = "Future Series",
                rrule = "FREQ=DAILY;COUNT=5"
            ),
            isLocal = true
        )

        // Master should have UNTIL
        val updatedMaster = database.eventsDao().getById(master.id)
        assertTrue(updatedMaster?.rrule?.contains("UNTIL=") == true)

        // New event should exist
        assertNotNull(newEvent)
        assertEquals("Future Series", newEvent.title)
        assertNull(newEvent.originalEventId) // Not an exception
    }

    @Test
    fun `splitSeries removes occurrences after split point`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitPoint = occurrences[5].startTs

        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitPoint,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )

        // Master should have fewer occurrences
        val masterOccurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue(masterOccurrences.size < 10)
        assertTrue(masterOccurrences.all { it.startTs < splitPoint })
    }

    // ========== Delete This and Future ==========

    @Test
    fun `deleteThisAndFuture truncates series`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val deleteFrom = occurrences[5].startTs

        eventWriter.deleteThisAndFuture(master.id, deleteFrom, isLocal = true)

        // Master should have UNTIL
        val updated = database.eventsDao().getById(master.id)
        assertTrue(updated?.rrule?.contains("UNTIL=") == true)

        // Fewer occurrences
        val remaining = database.occurrencesDao().getForEvent(master.id)
        assertTrue(remaining.size < 10)
        assertTrue(remaining.all { it.startTs < deleteFrom })
    }

    @Test
    fun `deleteThisAndFuture deletes entire event if from first occurrence`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )

        eventWriter.deleteThisAndFuture(master.id, master.startTs, isLocal = true)

        // Event should be deleted
        assertNull(database.eventsDao().getById(master.id))
    }

    // ========== Move Calendar ==========

    @Test
    fun `moveEventToCalendar updates calendar ID`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = true)
        assertEquals(testCalendarId, event.calendarId)

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val moved = database.eventsDao().getById(event.id)
        assertEquals(localCalendarId, moved?.calendarId)
    }

    @Test
    fun `moveEventToCalendar updates occurrence calendar IDs`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=3"),
            isLocal = true
        )

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertTrue(occurrences.all { it.calendarId == localCalendarId })
    }

    @Test
    fun `moveEventToCalendar does not change occurrence count`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val originalCount = database.occurrencesDao().getForEvent(event.id).size
        assertEquals(5, originalCount)

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val afterMoveCount = database.occurrencesDao().getForEvent(event.id).size
        assertEquals(originalCount, afterMoveCount)
    }

    @Test
    fun `moveEventToCalendar preserves occurrence IDs`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=3"),
            isLocal = true
        )
        val originalIds = database.occurrencesDao().getForEvent(event.id).map { it.id }.sorted()

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val afterMoveIds = database.occurrencesDao().getForEvent(event.id).map { it.id }.sorted()
        assertEquals(originalIds, afterMoveIds)
    }

    @Test
    fun `moveEventToCalendar iCloud to iCloud queues MOVE operation`() = runTest {
        // Create synced event with caldavUrl
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        // Simulate that event was synced to server
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        // Clear any pending CREATE from initial create
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to different iCloud calendar (auto-detects both are CalDAV)
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify MOVE operation queued with correct data
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals("https://caldav.icloud.com/test/event123.ics", ops[0].targetUrl)
        assertEquals(iCloudCalendar2Id, ops[0].targetCalendarId)
    }

    @Test
    fun `moveEventToCalendar local to iCloud queues CREATE only`() = runTest {
        // Create local event (never synced)
        val event = eventWriter.createEvent(createBaseEvent().copy(
            calendarId = localCalendarId
        ), isLocal = true)

        // Move to iCloud calendar (auto-detects local→synced)
        eventWriter.moveEventToCalendar(event.id, testCalendarId)

        // Verify CREATE operation queued (no MOVE since no old URL)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_CREATE, ops[0].operation)
        assertNull(ops[0].targetUrl)  // No old URL for local events
    }

    @Test
    fun `moveEventToCalendar iCloud to local queues DELETE`() = runTest {
        // Create synced event with caldavUrl
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to local calendar (auto-detects synced→local, queues DELETE)
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        // Verify DELETE operation queued with sourceCalendarId
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_DELETE, ops[0].operation)
        assertEquals(testCalendarId, ops[0].sourceCalendarId)
    }

    @Test
    fun `moveEventToCalendar cancels existing pending operations`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        // Simulate pending UPDATE
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE
            )
        )

        // Move to different calendar
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify old UPDATE is gone, replaced with CREATE or MOVE
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertTrue(ops[0].operation == PendingOperation.OPERATION_CREATE ||
                   ops[0].operation == PendingOperation.OPERATION_MOVE)
    }

    @Test
    fun `moveEventToCalendar clears caldavUrl and etag`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )

        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        val moved = database.eventsDao().getById(event.id)
        assertNull(moved?.caldavUrl)
        assertNull(moved?.etag)
        assertEquals(SyncStatus.PENDING_CREATE, moved?.syncStatus)
    }

    @Test
    fun `moveEventToCalendar stores targetUrl in MOVE operation`() = runTest {
        val oldUrl = "https://caldav.icloud.com/test/specific-event.ics"
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(event.id, oldUrl, "etag", System.currentTimeMillis())
        database.pendingOperationsDao().deleteForEvent(event.id)

        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify the old URL is stored in the operation for DELETE
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals(oldUrl, ops[0].targetUrl)  // Critical: URL captured before cleared
    }

    // ========== Helper Functions ==========

    private fun createBaseEvent(): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = now + 86400000, // Tomorrow
            endTs = now + 86400000 + 3600000, // Tomorrow + 1 hour
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
    }
}
