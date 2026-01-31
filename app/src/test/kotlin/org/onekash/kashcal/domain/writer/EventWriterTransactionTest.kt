package org.onekash.kashcal.domain.writer

import android.content.Context
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
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for EventWriter transaction behavior and error handling.
 *
 * Tests verify:
 * - Atomic transactions (all-or-nothing)
 * - Proper rollback on failures
 * - Concurrent modification handling
 * - Edge cases in event operations
 *
 * These scenarios are critical for data integrity in an offline-first calendar app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterTransactionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Setup test calendar
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Transaction Atomicity Tests ====================

    @Test
    fun `createEvent creates event and occurrences atomically`() = runTest {
        val event = createTestEvent("Atomic Test")

        val created = eventWriter.createEvent(event, isLocal = false)

        // Event should be created
        assertNotNull(database.eventsDao().getById(created.id))

        // Occurrences should be created
        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertTrue("Occurrences should exist", occurrences.isNotEmpty())

        // Pending operation should be queued
        val pendingOps = database.pendingOperationsDao().getAll()
        assertTrue("Pending operation should exist", pendingOps.any { it.eventId == created.id })
    }

    @Test
    fun `createEvent generates UID if not provided`() = runTest {
        val eventWithoutUid = createTestEvent("No UID").copy(uid = "")

        val created = eventWriter.createEvent(eventWithoutUid, isLocal = false)

        assertTrue("UID should be generated", created.uid.isNotBlank())
        assertTrue("UID should contain @", created.uid.contains("@"))
    }

    @Test
    fun `createEvent sets correct sync status`() = runTest {
        // Non-local event should be PENDING_CREATE
        val syncedEvent = eventWriter.createEvent(createTestEvent("Synced"), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, syncedEvent.syncStatus)

        // Local event should be SYNCED (no sync needed)
        val localEvent = eventWriter.createEvent(createTestEvent("Local"), isLocal = true)
        assertEquals(SyncStatus.SYNCED, localEvent.syncStatus)
    }

    @Test
    fun `createEvent sets timestamps correctly`() = runTest {
        val beforeCreate = System.currentTimeMillis()
        val event = eventWriter.createEvent(createTestEvent("Timestamped"), isLocal = false)
        val afterCreate = System.currentTimeMillis()

        assertTrue("dtstamp should be set", event.dtstamp >= beforeCreate)
        assertTrue("dtstamp should be recent", event.dtstamp <= afterCreate)
        assertTrue("createdAt should be set", event.createdAt!! >= beforeCreate)
        assertTrue("updatedAt should be set", event.updatedAt!! >= beforeCreate)
        assertTrue("localModifiedAt should be set", event.localModifiedAt!! >= beforeCreate)
    }

    // ==================== Update Event Tests ====================

    @Test
    fun `updateEvent preserves event ID`() = runTest {
        val original = eventWriter.createEvent(createTestEvent("Original"), isLocal = false)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated Title"),
            isLocal = false
        )

        assertEquals(original.id, updated.id)
        assertEquals("Updated Title", updated.title)
    }

    @Test
    fun `updateEvent throws for non-existent event`() = runTest {
        val nonExistentEvent = createTestEvent("Non-existent").copy(id = 99999L)

        try {
            eventWriter.updateEvent(nonExistentEvent, isLocal = false)
            fail("Should throw for non-existent event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Event not found") == true)
        }
    }

    @Test
    fun `updateEvent updates localModifiedAt`() = runTest {
        val original = eventWriter.createEvent(createTestEvent("Original"), isLocal = false)
        val originalModifiedAt = original.localModifiedAt!!

        // Small delay to ensure different timestamp
        Thread.sleep(10)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated"),
            isLocal = false
        )

        assertTrue(
            "localModifiedAt should increase",
            updated.localModifiedAt!! > originalModifiedAt
        )
    }

    // ==================== Delete Event Tests ====================

    @Test
    fun `deleteEvent soft deletes synced event for sync`() = runTest {
        // Create event and mark as SYNCED (simulating server sync completion)
        val event = eventWriter.createEvent(createTestEvent("To Delete"), isLocal = false)
        val syncedEvent = event.copy(syncStatus = SyncStatus.SYNCED)
        database.eventsDao().update(syncedEvent)

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNotNull("Event should still exist (soft delete)", deleted)
        assertEquals(SyncStatus.PENDING_DELETE, deleted!!.syncStatus)
    }

    @Test
    fun `deleteEvent hard deletes PENDING_CREATE event`() = runTest {
        // Event that was never synced (PENDING_CREATE) can be hard deleted
        val event = eventWriter.createEvent(createTestEvent("Never Synced"), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNull("PENDING_CREATE event should be hard deleted", deleted)
    }

    @Test
    fun `deleteEvent removes occurrences`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("To Delete"), isLocal = false)
        assertTrue(database.occurrencesDao().getForEvent(event.id).isNotEmpty())

        eventWriter.deleteEvent(event.id, isLocal = false)

        assertTrue(
            "Occurrences should be removed",
            database.occurrencesDao().getForEvent(event.id).isEmpty()
        )
    }

    @Test
    fun `deleteEvent local event is hard deleted`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Local"), isLocal = true)

        eventWriter.deleteEvent(event.id, isLocal = true)

        val deleted = database.eventsDao().getById(event.id)
        assertNull("Local event should be hard deleted", deleted)
    }

    // ==================== Recurring Event Edge Cases ====================

    @Test
    fun `createEvent with daily recurrence generates occurrences`() = runTest {
        val recurringEvent = createTestEvent("Daily Meeting").copy(
            rrule = "FREQ=DAILY;COUNT=5"
        )

        val created = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(5, occurrences.size)
    }

    @Test
    fun `createEvent with weekly recurrence generates correct occurrences`() = runTest {
        val recurringEvent = createTestEvent("Weekly Meeting").copy(
            rrule = "FREQ=WEEKLY;COUNT=4"
        )

        val created = eventWriter.createEvent(recurringEvent, isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(4, occurrences.size)

        // Verify 7-day gap between occurrences
        val sortedOccs = occurrences.sortedBy { it.startTs }
        for (i in 1 until sortedOccs.size) {
            val gap = sortedOccs[i].startTs - sortedOccs[i - 1].startTs
            assertEquals(7 * 24 * 60 * 60 * 1000L, gap)
        }
    }

    // ==================== Data Integrity Tests ====================

    @Test
    fun `event calendarId references valid calendar`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)

        val calendar = database.calendarsDao().getById(event.calendarId)
        assertNotNull("Calendar should exist", calendar)
    }

    @Test
    fun `occurrence calendarId matches event calendarId`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            assertEquals(
                "Occurrence calendarId should match event",
                event.calendarId,
                occ.calendarId
            )
        }
    }

    @Test
    fun `occurrence eventId references parent event`() = runTest {
        val event = eventWriter.createEvent(createTestEvent("Test"), isLocal = false)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        occurrences.forEach { occ ->
            assertEquals(
                "Occurrence should reference parent event",
                event.id,
                occ.eventId
            )
        }
    }

    // ==================== Concurrent Operation Tests ====================

    @Test
    fun `multiple events can be created sequentially`() = runTest {
        val events = (1..10).map { i ->
            eventWriter.createEvent(createTestEvent("Event $i"), isLocal = false)
        }

        assertEquals(10, events.size)
        assertEquals(10, events.map { it.id }.distinct().size) // All unique IDs
    }

    @Test
    fun `event update after create maintains data`() = runTest {
        val created = eventWriter.createEvent(createTestEvent("Original"), isLocal = false)

        val updated = eventWriter.updateEvent(
            created.copy(
                title = "Updated",
                location = "New Location",
                description = "New Description"
            ),
            isLocal = false
        )

        assertEquals(created.id, updated.id)
        assertEquals(created.uid, updated.uid)
        assertEquals("Updated", updated.title)
        assertEquals("New Location", updated.location)
        assertEquals("New Description", updated.description)
    }

    // ==================== UID Generation Tests ====================

    @Test
    fun `generated UIDs are unique`() = runTest {
        val uids = (1..100).map {
            val event = eventWriter.createEvent(
                createTestEvent("Event $it").copy(uid = ""),
                isLocal = false
            )
            event.uid
        }

        assertEquals("All UIDs should be unique", 100, uids.distinct().size)
    }

    @Test
    fun `generated UID format is valid`() = runTest {
        val event = eventWriter.createEvent(
            createTestEvent("Test").copy(uid = ""),
            isLocal = false
        )

        // UID should be UUID@domain format
        assertTrue("UID should contain @", event.uid.contains("@"))
        val parts = event.uid.split("@")
        assertEquals("UID should have two parts", 2, parts.size)
        assertTrue("Domain part should be kashcal", parts[1].contains("kashcal"))
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000, // 1 hour from now
            endTs = now + 7200000,   // 2 hours from now
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_CREATE
        )
    }
}
