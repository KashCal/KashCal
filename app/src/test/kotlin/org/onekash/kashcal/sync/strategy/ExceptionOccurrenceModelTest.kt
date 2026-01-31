package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Tests for occurrence model consistency when syncing exception events.
 *
 * This test verifies the hypothesis that PullStrategy creates Model A occurrences
 * (separate occurrence with eventId = exception.id) but doesn't normalize to Model B
 * (linked occurrence on master with exceptionEventId = exception.id), which could
 * cause duplicates if both models coexist.
 *
 * Model A (PullStrategy): Exception has its own occurrence
 *   - Occurrence(eventId = exception.id, exceptionEventId = null)
 *
 * Model B (EventWriter): Master's occurrence links to exception
 *   - Occurrence(eventId = master.id, exceptionEventId = exception.id)
 *
 * Run: ./gradlew testDebugUnitTest --tests "*ExceptionOccurrenceModelTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionOccurrenceModelTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var testAccountId: Long = 0

    companion object {
        // Test timestamps (UTC)
        // Master: Jan 20 2025, 10:00 UTC - weekly recurring
        // Exception: Jan 27 2025, 14:00 UTC (moved from 10:00)
        private const val MASTER_START = 1737363600000L  // Jan 20 2025 10:00 UTC
        private const val MASTER_END = 1737367200000L    // Jan 20 2025 11:00 UTC
        private const val ORIGINAL_INSTANCE_TIME = 1737968400000L  // Jan 27 2025 10:00 UTC
        private const val EXCEPTION_START = 1737982800000L  // Jan 27 2025 14:00 UTC
        private const val EXCEPTION_END = 1737986400000L    // Jan 27 2025 15:00 UTC

        // Range for queries (Jan 2025)
        private const val RANGE_START = 1735689600000L  // Jan 1 2025 00:00 UTC
        private const val RANGE_END = 1738368000000L    // Feb 1 2025 00:00 UTC
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "https://caldav.icloud.com/test/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Model A vs Model B Tests ====================

    @Test
    fun `Model A - PullStrategy creates separate occurrence for exception`() = runTest {
        // This simulates what PullStrategy does:
        // 1. Create master event with occurrences
        // 2. Create exception event
        // 3. Call regenerateOccurrences(exception) - creates separate occurrence
        // 4. Call cancelOccurrence(master, originalTime) - marks original as cancelled

        val masterUid = UUID.randomUUID().toString()

        // Step 1: Create master recurring event
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // Generate occurrences for master
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Verify master has occurrences including Jan 27
        val masterOccurrences = database.occurrencesDao().getForEvent(masterId)
        println("=== Master Occurrences (before exception) ===")
        masterOccurrences.forEach { occ ->
            println("  startTs=${occ.startTs}, eventId=${occ.eventId}, exceptionEventId=${occ.exceptionEventId}, isCancelled=${occ.isCancelled}")
        }
        assertTrue("Master should have occurrences", masterOccurrences.isNotEmpty())

        // Step 2: Create exception event (as PullStrategy does)
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,  // Same UID as master
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,  // Moved to 14:00
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,  // Was at 10:00
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Step 3: PullStrategy calls regenerateOccurrences(exception)
        // This creates Model A: occurrence with eventId = exception.id
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Step 4: PullStrategy calls cancelOccurrence(master, originalTime)
        occurrenceGenerator.cancelOccurrence(masterId, ORIGINAL_INSTANCE_TIME)

        // Now query all occurrences
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model A) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time (Jan 27 14:00)
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // ASSERTION: There should be exactly ONE occurrence at the exception time
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model A: eventId=exception)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's the Model A occurrence (eventId = exception.id)
        val exceptionOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model A: eventId should be exception", exceptionId, exceptionOccurrence.eventId)
        assertFalse("Model A: should not have exceptionEventId", exceptionOccurrence.exceptionEventId != null)
    }

    @Test
    fun `Model B - linkException creates linked occurrence on master`() = runTest {
        // This simulates what OccurrenceGenerator.linkException(masterEventId, time, exceptionEvent) does:
        // 1. Delete Model A occurrence (if exists)
        // 2. Update master's occurrence with exceptionEventId link

        val masterUid = UUID.randomUUID().toString()

        // Step 1: Create master recurring event
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // Generate occurrences for master
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Step 2: Create exception event
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Step 3: Use linkException to create Model B
        // This deletes any Model A occurrence and links master's occurrence to exception
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        // Query all occurrences
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model B) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // ASSERTION: There should be exactly ONE occurrence at the exception time
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model B: linked)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's the Model B occurrence (eventId = master.id, exceptionEventId = exception.id)
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model B: eventId should be master", masterId, linkedOccurrence.eventId)
        assertEquals("Model B: exceptionEventId should be exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    @Test
    fun `linkException normalizes Model A to Model B preventing duplicates`() = runTest {
        // This test verifies that linkException correctly normalizes:
        // 1. If Model A exists (separate occurrence), it gets deleted
        // 2. Model B is created/updated (linked occurrence on master)
        // 3. No duplicates in query results

        val masterUid = UUID.randomUUID().toString()

        // Create master
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Create exception
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Simulate old behavior: PullStrategy creating Model A
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Verify Model A exists before normalization
        val beforeNormalization = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END).first()
        val modelABefore = beforeNormalization.filter { it.eventId == exceptionId }
        println("=== Before linkException: Model A occurrences = ${modelABefore.size} ===")

        // Now call linkException to normalize (this is what PullStrategy should do)
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        // Query all occurrences after normalization
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (after linkException normalization) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) after normalization ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.id=${data.event.id}, event.title=${data.event.title}")
        }

        // ASSERTION: linkException should normalize to exactly 1 occurrence (Model B)
        assertEquals(
            "linkException should normalize to exactly 1 occurrence (Model B)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's Model B (linked occurrence on master)
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Should be Model B: eventId = master", masterId, linkedOccurrence.eventId)
        assertEquals("Should be Model B: exceptionEventId = exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    // ==================== Helper Methods ====================

    private fun Long.toDateString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(this))
    }
}
