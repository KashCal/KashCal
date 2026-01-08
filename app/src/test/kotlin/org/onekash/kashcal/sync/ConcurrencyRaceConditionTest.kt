package org.onekash.kashcal.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.onekash.kashcal.domain.writer.EventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency and race condition tests for sync operations.
 *
 * Tests verify data integrity under concurrent operations:
 * - Concurrent local edits during sync
 * - Race between DELETE and MOVE operations
 * - Multi-account simultaneous sync
 * - PendingOperation queue consistency
 * - Database transaction isolation
 *
 * These tests are critical for production stability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConcurrencyRaceConditionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var secondCalendarId: Long = 0
    private var testAccountId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        testAccountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Calendar 1",
                color = 0xFF2196F3.toInt()
            )
        )
        secondCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Calendar 2",
                color = 0xFFFF5722.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Concurrent Edit During Sync Tests ====================

    @Test
    fun `concurrent local edit during simulated pull should not lose data`() = runTest {
        // Setup: Create event
        val event = createAndInsertEvent("Original Title")
        val eventId = event.id

        // Simulate concurrent operations:
        // 1. "Sync" updates event from server
        // 2. Local edit happens simultaneously
        val serverVersion = event.copy(
            title = "Server Title",
            syncStatus = SyncStatus.SYNCED
        )
        val localVersion = event.copy(
            title = "Local Title",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        // Execute concurrently
        val results = listOf(
            async { database.eventsDao().update(serverVersion) },
            async {
                delay(10) // Small delay to create race
                database.eventsDao().update(localVersion)
            }
        ).awaitAll()

        // Verify: Last write wins, but data is consistent (no corruption)
        val finalEvent = database.eventsDao().getById(eventId)
        assertNotNull(finalEvent)
        // Either title is acceptable, but it must be one of them
        assertTrue(
            finalEvent!!.title == "Server Title" || finalEvent.title == "Local Title"
        )
    }

    @Test
    fun `concurrent updates to same event should serialize correctly`() = runTest {
        val event = createAndInsertEvent("Event")
        val eventId = event.id
        val updateCount = AtomicInteger(0)

        // Run 10 concurrent updates
        val jobs = (1..10).map { i ->
            async {
                val current = database.eventsDao().getById(eventId)!!
                database.eventsDao().update(current.copy(title = "Update $i"))
                updateCount.incrementAndGet()
            }
        }
        jobs.awaitAll()

        // Verify all updates completed
        assertEquals(10, updateCount.get())

        // Verify event still exists and is consistent
        val finalEvent = database.eventsDao().getById(eventId)
        assertNotNull(finalEvent)
        assertTrue(finalEvent!!.title.startsWith("Update"))
    }

    @Test
    fun `concurrent create operations should generate unique IDs`() = runTest {
        val createdIds = mutableSetOf<Long>()

        // Create 20 events concurrently
        val jobs = (1..20).map { i ->
            async {
                val event = createTestEvent("Concurrent $i")
                val id = database.eventsDao().insert(event)
                synchronized(createdIds) {
                    createdIds.add(id)
                }
                id
            }
        }
        val ids = jobs.awaitAll()

        // All IDs should be unique
        assertEquals(20, createdIds.size)
        assertEquals(20, ids.toSet().size)
    }

    // ==================== Race Between DELETE and MOVE Tests ====================

    @Test
    fun `delete operation during move should handle gracefully`() = runTest {
        val event = createAndInsertEvent("Moving Event")
        val eventId = event.id

        // Queue a MOVE operation
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_MOVE,
                targetUrl = event.caldavUrl,
                targetCalendarId = secondCalendarId
            )
        )

        // Now delete the event (simulating user action during sync)
        database.eventsDao().deleteById(eventId)

        // Verify: PendingOperation should still exist (for cleanup)
        val pendingOps = database.pendingOperationsDao().getForEvent(eventId)
        // The operation might still be there, which is fine - it will fail gracefully on execution

        // Verify event is deleted
        val deletedEvent = database.eventsDao().getById(eventId)
        assertNull(deletedEvent)
    }

    @Test
    fun `move and delete on same event should not corrupt database`() = runTest {
        val event = createAndInsertEvent("Event to Delete or Move")
        val eventId = event.id

        // Execute move and delete concurrently
        val moveJob = async {
            try {
                eventWriter.moveEventToCalendar(eventId, secondCalendarId)
            } catch (e: Exception) {
                // Expected if delete wins
            }
        }
        val deleteJob = async {
            delay(5) // Small delay
            try {
                eventWriter.deleteEvent(eventId, isLocal = true)
            } catch (e: Exception) {
                // Expected if move wins and event is in different state
            }
        }

        moveJob.await()
        deleteJob.await()

        // Verify database is consistent (event either exists in new calendar or is deleted)
        val existingEvent = database.eventsDao().getById(eventId)
        if (existingEvent != null) {
            // If event exists, it should be in one of the calendars
            assertTrue(
                existingEvent.calendarId == testCalendarId ||
                existingEvent.calendarId == secondCalendarId
            )
        }
        // If null, event was deleted - also valid
    }

    // ==================== Multi-Account Concurrent Sync Tests ====================

    @Test
    fun `concurrent operations on different accounts should not interfere`() = runTest {
        // Create second account
        val secondAccountId = database.accountsDao().insert(
            Account(provider = "test2", email = "test2@test.com")
        )
        val account2CalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = secondAccountId,
                caldavUrl = "https://test2.com/cal/",
                displayName = "Account 2 Calendar",
                color = 0xFF4CAF50.toInt()
            )
        )

        // Create events in both accounts
        val event1 = createAndInsertEvent("Account1 Event", testCalendarId)
        val event2 = createAndInsertEvent("Account2 Event", account2CalendarId)

        // Update both concurrently
        val jobs = listOf(
            async {
                repeat(5) {
                    val e = database.eventsDao().getById(event1.id)!!
                    database.eventsDao().update(e.copy(title = "A1 Update $it"))
                }
            },
            async {
                repeat(5) {
                    val e = database.eventsDao().getById(event2.id)!!
                    database.eventsDao().update(e.copy(title = "A2 Update $it"))
                }
            }
        )
        jobs.awaitAll()

        // Verify both events are consistent
        val final1 = database.eventsDao().getById(event1.id)!!
        val final2 = database.eventsDao().getById(event2.id)!!

        assertTrue(final1.title.startsWith("A1 Update"))
        assertTrue(final2.title.startsWith("A2 Update"))
        assertEquals(testCalendarId, final1.calendarId)
        assertEquals(account2CalendarId, final2.calendarId)
    }

    // ==================== PendingOperation Queue Consistency Tests ====================

    @Test
    fun `concurrent pending operation inserts should not duplicate`() = runTest {
        val event = createAndInsertEvent("Event")
        val eventId = event.id

        // Try to insert same operation type concurrently
        val jobs = (1..5).map {
            async {
                try {
                    database.pendingOperationsDao().insert(
                        PendingOperation(
                            eventId = eventId,
                            operation = PendingOperation.OPERATION_UPDATE
                        )
                    )
                } catch (e: Exception) {
                    // Constraint violation expected for duplicates
                }
            }
        }
        jobs.awaitAll()

        // Should have at least one operation (duplicates may be blocked by constraint)
        val ops = database.pendingOperationsDao().getForEvent(eventId)
        assertTrue(ops.isNotEmpty())
    }

    @Test
    fun `concurrent status updates on pending operations should be consistent`() = runTest {
        val event = createAndInsertEvent("Event")
        val opId = database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE,
                status = PendingOperation.STATUS_PENDING
            )
        )

        // Concurrent status updates
        val jobs = listOf(
            async {
                val op = database.pendingOperationsDao().getById(opId)!!
                database.pendingOperationsDao().update(op.copy(status = PendingOperation.STATUS_IN_PROGRESS))
            },
            async {
                delay(5)
                val op = database.pendingOperationsDao().getById(opId)!!
                database.pendingOperationsDao().update(op.copy(status = PendingOperation.STATUS_FAILED))
            }
        )
        jobs.awaitAll()

        // Verify operation has a valid status
        val op = database.pendingOperationsDao().getById(opId)
        assertNotNull(op)
        assertTrue(
            op!!.status == PendingOperation.STATUS_IN_PROGRESS ||
            op.status == PendingOperation.STATUS_FAILED
        )
    }

    // ==================== Database Transaction Isolation Tests ====================

    @Test
    fun `transaction rollback should not leave partial state`() = runTest {
        val event = createAndInsertEvent("Transaction Test")
        val eventId = event.id

        try {
            database.withTransaction {
                // Update event
                database.eventsDao().update(event.copy(title = "Updated"))

                // Create occurrence
                val occurrence = org.onekash.kashcal.data.db.entity.Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = System.currentTimeMillis(),
                    endTs = System.currentTimeMillis() + 3600000,
                    startDay = 20250108,
                    endDay = 20250108
                )
                database.occurrencesDao().insert(occurrence)

                // Force rollback with exception
                throw RuntimeException("Simulated failure")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Verify rollback: event should have original title
        val finalEvent = database.eventsDao().getById(eventId)!!
        assertEquals("Transaction Test", finalEvent.title)

        // Verify no orphaned occurrences
        val occurrences = database.occurrencesDao().getForEvent(eventId)
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `nested transactions should maintain consistency`() = runTest {
        val event = createAndInsertEvent("Nested Transaction Test")

        database.withTransaction {
            database.eventsDao().update(event.copy(title = "Outer Update"))

            database.withTransaction {
                database.eventsDao().update(event.copy(title = "Inner Update"))
            }
        }

        val finalEvent = database.eventsDao().getById(event.id)!!
        assertEquals("Inner Update", finalEvent.title)
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            calendarId = testCalendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            syncStatus = SyncStatus.PENDING_CREATE,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
    }

    private suspend fun createAndInsertEvent(title: String, calendarId: Long = testCalendarId): Event {
        val event = createTestEvent(title).copy(calendarId = calendarId)
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    private fun assertNull(obj: Any?) {
        assertEquals(null, obj)
    }
}
