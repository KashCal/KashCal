package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.onekash.kashcal.domain.writer.EventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for calendar move operation fix (v21.6.0).
 *
 * This test suite verifies the fixes for two bugs:
 * 1. Synced → Local: Moving from iCloud to local calendar doesn't DELETE from server
 * 2. Cross-Account: Moving between accounts (iCloud → Nextcloud) fails to DELETE
 *
 * Solution: Hybrid approach with sourceCalendarId for DELETE filtering
 * - Same account: WebDAV MOVE (atomic)
 * - Cross account: CREATE + DELETE (independent operations)
 * - Synced → Local: DELETE only (with sourceCalendarId)
 * - Local → Synced: CREATE only
 *
 * Key fix: Add sourceCalendarId field to PendingOperation for DELETE filtering
 * during cross-account moves, since event.calendarId is updated to target
 * before push completes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CalendarMoveOperationFixTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    // Account IDs
    private var iCloudAccountId: Long = 0
    private var nextcloudAccountId: Long = 0
    private var localAccountId: Long = 0

    // Calendar IDs
    private var iCloudPersonalCalendarId: Long = 0
    private var iCloudWorkCalendarId: Long = 0
    private var nextcloudCalendarId: Long = 0
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
            // iCloud account with two calendars (same account)
            iCloudAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            iCloudPersonalCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/123/personal/",
                    displayName = "Personal",
                    color = 0xFF0000FF.toInt()
                )
            )
            iCloudWorkCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/123/work/",
                    displayName = "Work",
                    color = 0xFFFF5722.toInt()
                )
            )

            // Nextcloud account (different account - uses CALDAV provider)
            nextcloudAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.local")
            )
            nextcloudCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = nextcloudAccountId,
                    caldavUrl = "https://nextcloud.local/remote.php/dav/calendars/user/personal/",
                    displayName = "Nextcloud Personal",
                    color = 0xFF4CAF50.toInt()
                )
            )

            // Local account
            localAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            localCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = localAccountId,
                    caldavUrl = "local://default",
                    displayName = "Local",
                    color = 0xFF9E9E9E.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Scenario Tests ====================

    @Test
    fun `same-account move queues MOVE operation with sourceCalendarId`() = runTest {
        // Create synced event in iCloud Personal calendar
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Move to iCloud Work calendar (same account)
        eventWriter.moveEventToCalendar(event.id, iCloudWorkCalendarId)

        // Verify MOVE operation queued with sourceCalendarId
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have one operation", 1, ops.size)
        assertEquals("Operation should be MOVE", PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals("sourceCalendarId should be set", iCloudPersonalCalendarId, ops[0].sourceCalendarId)
        assertEquals("targetCalendarId should be set", iCloudWorkCalendarId, ops[0].targetCalendarId)
        assertEquals("targetUrl should capture old URL", "https://caldav.icloud.com/123/personal/event.ics", ops[0].targetUrl)
    }

    @Test
    fun `cross-account move queues CREATE then DELETE operations`() = runTest {
        // Create synced event in iCloud calendar
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Move to Nextcloud calendar (different account)
        eventWriter.moveEventToCalendar(event.id, nextcloudCalendarId)

        // Verify both CREATE and DELETE operations queued
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have two operations", 2, ops.size)

        // Find CREATE and DELETE operations
        val createOp = ops.find { it.operation == PendingOperation.OPERATION_CREATE }
        val deleteOp = ops.find { it.operation == PendingOperation.OPERATION_DELETE }

        assertNotNull("Should have CREATE operation", createOp)
        assertNotNull("Should have DELETE operation", deleteOp)

        // DELETE should have sourceCalendarId pointing to iCloud calendar
        assertEquals("DELETE sourceCalendarId should be iCloud calendar",
            iCloudPersonalCalendarId, deleteOp!!.sourceCalendarId)
        assertEquals("DELETE targetUrl should capture old URL",
            "https://caldav.icloud.com/123/personal/event.ics", deleteOp.targetUrl)

        // CREATE should NOT have sourceCalendarId
        assertNull("CREATE should not have sourceCalendarId", createOp!!.sourceCalendarId)
    }

    @Test
    fun `synced-to-local move queues DELETE only with sourceCalendarId`() = runTest {
        // Create synced event in iCloud calendar
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Move to local calendar
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        // Verify only DELETE operation queued
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have one operation", 1, ops.size)
        assertEquals("Operation should be DELETE", PendingOperation.OPERATION_DELETE, ops[0].operation)
        assertEquals("sourceCalendarId should be set", iCloudPersonalCalendarId, ops[0].sourceCalendarId)
        assertEquals("targetUrl should capture old URL", "https://caldav.icloud.com/123/personal/event.ics", ops[0].targetUrl)

        // Event should be marked SYNCED (local)
        val movedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event should be SYNCED", SyncStatus.SYNCED, movedEvent?.syncStatus)
    }

    @Test
    fun `local-to-synced move queues CREATE only`() = runTest {
        // Create local event (no caldavUrl)
        val event = createLocalEvent(localCalendarId)

        // Move to iCloud calendar
        eventWriter.moveEventToCalendar(event.id, iCloudPersonalCalendarId)

        // Verify only CREATE operation queued
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have one operation", 1, ops.size)
        assertEquals("Operation should be CREATE", PendingOperation.OPERATION_CREATE, ops[0].operation)
        assertNull("targetUrl should be null for local source", ops[0].targetUrl)
        assertNull("sourceCalendarId should be null for local source", ops[0].sourceCalendarId)

        // Event should be marked PENDING_CREATE
        val movedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event should be PENDING_CREATE", SyncStatus.PENDING_CREATE, movedEvent?.syncStatus)
    }

    @Test
    fun `local-to-local move queues nothing`() = runTest {
        // Create local event
        val event = createLocalEvent(localCalendarId)

        // Create second local calendar
        val localCalendar2Id = database.calendarsDao().insert(
            Calendar(
                accountId = localAccountId,
                caldavUrl = "local://second",
                displayName = "Local 2",
                color = 0xFF607D8B.toInt()
            )
        )

        // Move to second local calendar
        eventWriter.moveEventToCalendar(event.id, localCalendar2Id)

        // Verify no operations queued
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have no operations", 0, ops.size)

        // Event should be SYNCED (local)
        val movedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event should be SYNCED", SyncStatus.SYNCED, movedEvent?.syncStatus)
        assertEquals("Calendar ID should be updated", localCalendar2Id, movedEvent?.calendarId)
    }

    // ==================== Filtering Tests ====================

    @Test
    fun `DELETE filters by sourceCalendarId when present`() = runTest {
        // This test verifies that PushStrategy can filter DELETE operations
        // by sourceCalendarId to run on the correct calendar's sync cycle

        // Create synced event and move to local (queues DELETE with sourceCalendarId)
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        // Get the DELETE operation
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val deleteOp = ops.find { it.operation == PendingOperation.OPERATION_DELETE }
        assertNotNull("Should have DELETE operation", deleteOp)

        // Verify filtering criteria
        // After move, event.calendarId = localCalendarId (target)
        // But DELETE should run on iCloudPersonalCalendarId (source) sync cycle
        val movedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event calendarId should be target", localCalendarId, movedEvent?.calendarId)
        assertEquals("DELETE sourceCalendarId should be source", iCloudPersonalCalendarId, deleteOp!!.sourceCalendarId)

        // When filtering: use sourceCalendarId if present, else event.calendarId
        val effectiveCalendarId = deleteOp.sourceCalendarId ?: movedEvent?.calendarId
        assertEquals("Effective filter calendar should be source", iCloudPersonalCalendarId, effectiveCalendarId)
    }

    @Test
    fun `DELETE filters by event calendarId when sourceCalendarId null`() = runTest {
        // Regular DELETE (not from MOVE) should fall back to event.calendarId

        // Create synced event
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Delete the event (not a move - regular delete)
        eventWriter.deleteEvent(event.id, isLocal = false)

        // Get the DELETE operation
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val deleteOp = ops.find { it.operation == PendingOperation.OPERATION_DELETE }
        assertNotNull("Should have DELETE operation", deleteOp)

        // Regular DELETE should NOT have sourceCalendarId
        assertNull("Regular DELETE should not have sourceCalendarId", deleteOp!!.sourceCalendarId)

        // Filtering should use event.calendarId
        val softDeletedEvent = database.eventsDao().getById(event.id)
        assertNotNull("Event should exist (soft delete)", softDeletedEvent)
        assertEquals("Event calendarId unchanged", iCloudPersonalCalendarId, softDeletedEvent?.calendarId)

        // When filtering: use sourceCalendarId if present, else event.calendarId
        val effectiveCalendarId = deleteOp.sourceCalendarId ?: softDeletedEvent?.calendarId
        assertEquals("Effective filter calendar should be event's calendar", iCloudPersonalCalendarId, effectiveCalendarId)
    }

    @Test
    fun `MOVE DELETE phase filters by sourceCalendarId`() = runTest {
        // Same-account MOVE: DELETE phase should filter by sourceCalendarId
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Move to Work calendar (same account - queues MOVE)
        eventWriter.moveEventToCalendar(event.id, iCloudWorkCalendarId)

        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val moveOp = ops.find { it.operation == PendingOperation.OPERATION_MOVE }
        assertNotNull("Should have MOVE operation", moveOp)
        assertEquals("MOVE phase should be DELETE (0)", PendingOperation.MOVE_PHASE_DELETE, moveOp!!.movePhase)

        // Verify filtering for DELETE phase
        assertEquals("MOVE sourceCalendarId should be source", iCloudPersonalCalendarId, moveOp.sourceCalendarId)

        // Event is already in target calendar
        val movedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event calendarId should be target", iCloudWorkCalendarId, movedEvent?.calendarId)

        // DELETE phase runs on source calendar's sync cycle
        // Filtering: MOVE + phase 0 (DELETE) → use sourceCalendarId
        val deletePhaseCalendar = moveOp.sourceCalendarId
        assertEquals("DELETE phase should run on source calendar", iCloudPersonalCalendarId, deletePhaseCalendar)
    }

    @Test
    fun `MOVE CREATE phase filters by targetCalendarId`() = runTest {
        // After DELETE phase completes, MOVE advances to CREATE phase
        // CREATE phase should filter by targetCalendarId

        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")

        // Move to Work calendar
        eventWriter.moveEventToCalendar(event.id, iCloudWorkCalendarId)

        val ops = database.pendingOperationsDao().getForEvent(event.id)
        val moveOp = ops.find { it.operation == PendingOperation.OPERATION_MOVE }!!

        // Simulate advancing to CREATE phase
        database.pendingOperationsDao().advanceToCreatePhase(moveOp.id, System.currentTimeMillis())

        // Re-fetch updated operation
        val advancedOp = database.pendingOperationsDao().getById(moveOp.id)
        assertNotNull("Operation should still exist", advancedOp)
        assertEquals("Phase should be CREATE (1)", PendingOperation.MOVE_PHASE_CREATE, advancedOp!!.movePhase)

        // CREATE phase runs on target calendar's sync cycle
        // Filtering: MOVE + phase 1 (CREATE) → use targetCalendarId
        val createPhaseCalendar = advancedOp.targetCalendarId
        assertEquals("CREATE phase should run on target calendar", iCloudWorkCalendarId, createPhaseCalendar)
    }

    // ==================== Exception Event Tests ====================

    @Test
    fun `exception events cannot be moved directly`() = runTest {
        // Create recurring event with exception
        val master = eventWriter.createEvent(
            createBaseEvent(iCloudPersonalCalendarId).copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/123/personal/recurring.ics",
            "etag123",
            System.currentTimeMillis()
        )

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[2].startTs,
            modifiedEvent = createBaseEvent(iCloudPersonalCalendarId).copy(title = "Modified"),
            isLocal = false
        )

        // Try to move exception directly - should throw
        try {
            eventWriter.moveEventToCalendar(exception.id, iCloudWorkCalendarId)
            assertTrue("Should have thrown exception", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exception") == true ||
                       e.message?.contains("originalEventId") == true)
        }
    }

    @Test
    fun `master move updates all exception calendarIds in transaction`() = runTest {
        // Create recurring event with multiple exceptions
        val master = eventWriter.createEvent(
            createBaseEvent(iCloudPersonalCalendarId).copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/123/personal/recurring.ics",
            "etag123",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(master.id)

        val occurrences = database.occurrencesDao().getForEvent(master.id)

        // Create two exceptions
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[1].startTs,
            modifiedEvent = createBaseEvent(iCloudPersonalCalendarId).copy(title = "Exception 1"),
            isLocal = false
        )
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[3].startTs,
            modifiedEvent = createBaseEvent(iCloudPersonalCalendarId).copy(title = "Exception 2"),
            isLocal = false
        )
        database.pendingOperationsDao().deleteForEvent(master.id)

        // Move master to different calendar
        eventWriter.moveEventToCalendar(master.id, iCloudWorkCalendarId)

        // Verify master moved
        val movedMaster = database.eventsDao().getById(master.id)
        assertEquals("Master should be in target calendar", iCloudWorkCalendarId, movedMaster?.calendarId)

        // Verify exceptions also moved
        val movedEx1 = database.eventsDao().getById(exception1.id)
        val movedEx2 = database.eventsDao().getById(exception2.id)
        assertEquals("Exception 1 should be in target calendar", iCloudWorkCalendarId, movedEx1?.calendarId)
        assertEquals("Exception 2 should be in target calendar", iCloudWorkCalendarId, movedEx2?.calendarId)
    }

    @Test
    fun `master move does not queue operations for exceptions`() = runTest {
        // Create recurring event with exception
        val master = eventWriter.createEvent(
            createBaseEvent(iCloudPersonalCalendarId).copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/123/personal/recurring.ics",
            "etag123",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(master.id)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[2].startTs,
            modifiedEvent = createBaseEvent(iCloudPersonalCalendarId).copy(title = "Exception"),
            isLocal = false
        )
        database.pendingOperationsDao().deleteForEvent(master.id)

        // Move master
        eventWriter.moveEventToCalendar(master.id, iCloudWorkCalendarId)

        // Check operations - should only be for master, not exception
        val masterOps = database.pendingOperationsDao().getForEvent(master.id)
        val exceptionOps = database.pendingOperationsDao().getForEvent(exception.id)

        assertEquals("Master should have 1 operation", 1, masterOps.size)
        assertEquals("Exception should have 0 operations (bundled with master)", 0, exceptionOps.size)
    }

    // ==================== Account Detection Tests ====================

    @Test
    fun `same-account detected when accountIds match`() = runTest {
        // Both iCloud calendars have same accountId
        val calendar1 = database.calendarsDao().getById(iCloudPersonalCalendarId)
        val calendar2 = database.calendarsDao().getById(iCloudWorkCalendarId)

        assertEquals("Both calendars should have same accountId",
            calendar1?.accountId, calendar2?.accountId)
        assertEquals("AccountId should be iCloud account",
            iCloudAccountId, calendar1?.accountId)
    }

    @Test
    fun `cross-account detected when accountIds differ`() = runTest {
        // iCloud and Nextcloud have different accountIds
        val iCloudCal = database.calendarsDao().getById(iCloudPersonalCalendarId)
        val nextcloudCal = database.calendarsDao().getById(nextcloudCalendarId)

        assertTrue("Calendars should have different accountIds",
            iCloudCal?.accountId != nextcloudCal?.accountId)
    }

    @Test
    fun `local detected from AccountProvider LOCAL`() = runTest {
        // Local account has provider = LOCAL
        val localAccount = database.accountsDao().getById(localAccountId)
        assertEquals("Local account should have LOCAL provider",
            AccountProvider.LOCAL, localAccount?.provider)

        // iCloud account has provider = ICLOUD
        val iCloudAccount = database.accountsDao().getById(iCloudAccountId)
        assertEquals("iCloud account should have ICLOUD provider",
            AccountProvider.ICLOUD, iCloudAccount?.provider)
    }

    // ==================== Idempotency Tests ====================

    @Test
    fun `MOVE to same calendar is no-op`() = runTest {
        val event = createSyncedEvent(iCloudPersonalCalendarId, "https://caldav.icloud.com/123/personal/event.ics")
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to same calendar (no-op)
        eventWriter.moveEventToCalendar(event.id, iCloudPersonalCalendarId)

        // Verify no operations queued
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals("Should have no operations for same-calendar move", 0, ops.size)

        // Event unchanged
        val unchangedEvent = database.eventsDao().getById(event.id)
        assertEquals("Event calendar unchanged", iCloudPersonalCalendarId, unchangedEvent?.calendarId)
        assertNotNull("caldavUrl should be preserved", unchangedEvent?.caldavUrl)
    }

    // ==================== Helper Functions ====================

    private suspend fun createSyncedEvent(calendarId: Long, caldavUrl: String): Event {
        val event = eventWriter.createEvent(createBaseEvent(calendarId), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            caldavUrl,
            "etag-${System.currentTimeMillis()}",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(event.id)
        return database.eventsDao().getById(event.id)!!
    }

    private suspend fun createLocalEvent(calendarId: Long): Event {
        return eventWriter.createEvent(createBaseEvent(calendarId), isLocal = true)
    }

    private fun createBaseEvent(calendarId: Long): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "",
            calendarId = calendarId,
            title = "Test Event ${System.nanoTime()}",
            startTs = now + 86400000,
            endTs = now + 86400000 + 3600000,
            dtstamp = now
        )
    }
}
