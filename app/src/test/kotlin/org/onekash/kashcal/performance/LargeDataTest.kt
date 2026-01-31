package org.onekash.kashcal.performance

import android.util.Log
import androidx.room.Room
import io.mockk.*
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Large data volume tests for performance validation.
 *
 * Tests verify that the app handles large datasets without:
 * - Unacceptable delays (defined thresholds per operation)
 * - Memory issues (OOM)
 * - Database timeouts
 *
 * These are not strict performance benchmarks, but sanity checks
 * to catch regressions that would impact user experience.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LargeDataTest {

    private lateinit var database: KashCalDatabase
    private val baseTimestamp = 1704067200000L // 2024-01-01 00:00:00 UTC
    private val baseDayCode = 20240101
    private var testAccountId: Long = 0L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            KashCalDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        // Insert a test account (required for Calendar FK)
        kotlinx.coroutines.runBlocking {
            testAccountId = database.accountsDao().insert(
                Account(
                    provider = AccountProvider.LOCAL,
                    email = "test@test.com",
                    displayName = "Test Account"
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    // ==================== Insert Performance Tests ====================

    @Test
    fun `insert 100 events completes in reasonable time`() = runTest {
        val calendar = insertCalendar()

        val events = (1..100).map { i ->
            createEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i",
                startTs = baseTimestamp + (i * 3600000L)
            )
        }

        val elapsed = measureTimeMillis {
            events.forEach { database.eventsDao().insert(it) }
        }

        assertTrue("Insert 100 events should complete in < 5s, took ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `insert 500 events completes in reasonable time`() = runTest {
        val calendar = insertCalendar()

        val events = (1..500).map { i ->
            createEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i",
                startTs = baseTimestamp + (i * 3600000L)
            )
        }

        val elapsed = measureTimeMillis {
            events.forEach { database.eventsDao().insert(it) }
        }

        assertTrue("Insert 500 events should complete in < 10s, took ${elapsed}ms", elapsed < 10000)
    }

    @Test
    fun `insert 1000 occurrences completes in reasonable time`() = runTest {
        val calendar = insertCalendar()

        val event = insertEvent(calendarId = calendar.id, uid = "recurring-event")

        val occurrences = (1..1000).map { i ->
            val dayOffset = i - 1
            val occTs = baseTimestamp + (dayOffset * 86400000L)
            createOccurrence(
                eventId = event.id,
                calendarId = calendar.id,
                startTs = occTs,
                endTs = occTs + 3600000L,
                startDay = baseDayCode + dayOffset,
                endDay = baseDayCode + dayOffset
            )
        }

        val elapsed = measureTimeMillis {
            database.occurrencesDao().insertAll(occurrences)
        }

        assertTrue("Insert 1000 occurrences should complete in < 5s, took ${elapsed}ms", elapsed < 5000)
    }

    // ==================== Query Performance Tests ====================

    @Test
    fun `query day with 50 occurrences returns in reasonable time`() = runTest {
        val calendar = insertCalendar()

        // Create 50 events for the same day
        val events = (1..50).map { i ->
            insertEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i",
                startTs = baseTimestamp + (i * 1800000L) // 30 min apart
            )
        }

        // Create occurrences all on the same day
        val occurrences = events.mapIndexed { idx, event ->
            createOccurrence(
                eventId = event.id,
                calendarId = calendar.id,
                startTs = baseTimestamp + (idx * 1800000L),
                endTs = baseTimestamp + (idx * 1800000L) + 1800000L,
                startDay = baseDayCode,
                endDay = baseDayCode
            )
        }
        database.occurrencesDao().insertAll(occurrences)

        val elapsed = measureTimeMillis {
            val results = database.occurrencesDao().getForDayOnce(baseDayCode)
            assertEquals(50, results.size)
        }

        assertTrue("Query day should complete in < 500ms, took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun `query month range with 200 occurrences returns in reasonable time`() = runTest {
        val calendar = insertCalendar()

        // Create 200 events spread across a month
        val events = (1..200).map { i ->
            insertEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i",
                startTs = baseTimestamp + (i * 3600000L * 4) // 4 hours apart
            )
        }

        val occurrences = events.mapIndexed { idx, event ->
            val dayOffset = idx / 7 // Spread across ~30 days
            createOccurrence(
                eventId = event.id,
                calendarId = calendar.id,
                startTs = baseTimestamp + (idx * 3600000L * 4),
                endTs = baseTimestamp + (idx * 3600000L * 4) + 3600000L,
                startDay = baseDayCode + dayOffset,
                endDay = baseDayCode + dayOffset
            )
        }
        database.occurrencesDao().insertAll(occurrences)

        // Calculate range to include all 200 occurrences
        // Last occurrence at index 199: baseTimestamp + 199 * 4 hours + 1 hour
        val rangeEnd = baseTimestamp + (200L * 4 * 3600000L)

        val elapsed = measureTimeMillis {
            val results = database.occurrencesDao().getInRangeOnce(baseTimestamp, rangeEnd)
            assertEquals("Should find all 200 occurrences", 200, results.size)
        }

        assertTrue("Query month should complete in < 2s, took ${elapsed}ms", elapsed < 2000)
    }

    @Test
    fun `FTS search across 500 events returns in reasonable time`() = runTest {
        val calendar = insertCalendar()

        // Create 500 events with searchable content
        val events = (1..500).map { i ->
            createEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = if (i % 10 == 0) "Meeting with Team Alpha" else "Event $i",
                description = "Description for event $i with some searchable content"
            )
        }
        events.forEach { database.eventsDao().insert(it) }

        val elapsed = measureTimeMillis {
            val results = database.eventsDao().search("Meeting")
            // Should find the 50 events with "Meeting with Team Alpha"
            assertTrue("Should find matching events", results.size >= 40)
        }

        assertTrue("FTS search should complete in < 1s, took ${elapsed}ms", elapsed < 1000)
    }

    // ==================== Delete Performance Tests ====================

    @Test
    fun `cascade delete calendar with 100 events completes in reasonable time`() = runTest {
        val calendar = insertCalendar()

        // Create 100 events
        val events = (1..100).map { i ->
            insertEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i"
            )
        }

        // Create occurrences for each event
        val occurrences = events.flatMapIndexed { eventIdx, event ->
            (1..3).map { i ->
                val dayOffset = eventIdx * 3 + i
                createOccurrence(
                    eventId = event.id,
                    calendarId = calendar.id,
                    startTs = baseTimestamp + (dayOffset * 86400000L),
                    endTs = baseTimestamp + (dayOffset * 86400000L) + 3600000L,
                    startDay = baseDayCode + dayOffset,
                    endDay = baseDayCode + dayOffset
                )
            }
        }
        database.occurrencesDao().insertAll(occurrences)

        val elapsed = measureTimeMillis {
            database.calendarsDao().delete(calendar)
        }

        assertTrue("Cascade delete should complete in < 5s, took ${elapsed}ms", elapsed < 5000)

        // Verify cascade worked
        val remainingCount = database.eventsDao().getCountByCalendar(calendar.id)
        assertEquals("All events should be deleted", 0, remainingCount)
    }

    @Test
    fun `batch delete 200 occurrences completes in reasonable time`() = runTest {
        val calendar = insertCalendar()

        val event = insertEvent(calendarId = calendar.id, uid = "recurring-event")

        val occurrences = (1..200).map { i ->
            val dayOffset = i - 1
            createOccurrence(
                eventId = event.id,
                calendarId = calendar.id,
                startTs = baseTimestamp + (dayOffset * 86400000L),
                endTs = baseTimestamp + (dayOffset * 86400000L) + 3600000L,
                startDay = baseDayCode + dayOffset,
                endDay = baseDayCode + dayOffset
            )
        }
        database.occurrencesDao().insertAll(occurrences)

        val elapsed = measureTimeMillis {
            database.occurrencesDao().deleteForEvent(event.id)
        }

        assertTrue("Batch delete should complete in < 2s, took ${elapsed}ms", elapsed < 2000)
    }

    // ==================== Flow Performance Tests ====================

    @Test
    fun `Flow observation with 100 events emits quickly`() = runTest {
        val calendar = insertCalendar()

        val events = (1..100).map { i ->
            insertEvent(
                calendarId = calendar.id,
                uid = "event-$i",
                title = "Event $i",
                startTs = baseTimestamp + (i * 3600000L)
            )
        }

        val occurrences = events.mapIndexed { idx, event ->
            val dayOffset = idx / 4 // ~4 per day
            createOccurrence(
                eventId = event.id,
                calendarId = calendar.id,
                startTs = baseTimestamp + (idx * 3600000L),
                endTs = baseTimestamp + (idx * 3600000L) + 3600000L,
                startDay = baseDayCode + dayOffset,
                endDay = baseDayCode + dayOffset
            )
        }
        database.occurrencesDao().insertAll(occurrences)

        val rangeEnd = baseTimestamp + (200 * 3600000L)

        val elapsed = measureTimeMillis {
            val flow = database.occurrencesDao().getInRange(baseTimestamp, rangeEnd)
            val results = flow.first()
            assertEquals(100, results.size)
        }

        assertTrue("Flow first emission should be < 1s, took ${elapsed}ms", elapsed < 1000)
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    fun `multiple calendars with many events query correctly`() = runTest {
        // Create 5 calendars with 50 events each = 250 events total
        val calendars = (1..5).map { i ->
            insertCalendar(name = "Calendar $i")
        }

        calendars.forEach { calendar ->
            val events = (1..50).map { i ->
                insertEvent(
                    calendarId = calendar.id,
                    uid = "cal${calendar.id}-event-$i",
                    title = "Calendar ${calendar.id} Event $i",
                    startTs = baseTimestamp + (i * 3600000L)
                )
            }

            val occurrences = events.mapIndexed { idx, event ->
                val dayOffset = idx / 4
                createOccurrence(
                    eventId = event.id,
                    calendarId = calendar.id,
                    startTs = baseTimestamp + (idx * 3600000L),
                    endTs = baseTimestamp + (idx * 3600000L) + 3600000L,
                    startDay = baseDayCode + dayOffset,
                    endDay = baseDayCode + dayOffset
                )
            }
            database.occurrencesDao().insertAll(occurrences)
        }

        val elapsed = measureTimeMillis {
            val rangeEnd = baseTimestamp + (100 * 3600000L)
            val results = database.occurrencesDao().getInRangeOnce(baseTimestamp, rangeEnd)
            assertEquals(250, results.size)
        }

        assertTrue("Query across calendars should be < 1s, took ${elapsed}ms", elapsed < 1000)
    }

    // ==================== Helper Methods ====================

    private suspend fun insertCalendar(name: String = "Test Calendar"): Calendar {
        val uniqueUrl = "local://${UUID.randomUUID()}"
        val calendar = Calendar(
            id = 0L,
            accountId = testAccountId,
            caldavUrl = uniqueUrl,
            displayName = name,
            color = 0xFF5733.toInt(),
            isVisible = true,
            isReadOnly = false,
            ctag = null,
            syncToken = null
        )
        val id = database.calendarsDao().insert(calendar)
        return calendar.copy(id = id)
    }

    private fun createCalendar(
        id: Long = 0L,  // Auto-generate
        name: String = "Test Calendar"
    ) = Calendar(
        id = id,
        accountId = testAccountId,
        caldavUrl = "local://default",
        displayName = name,
        color = 0xFF5733.toInt(),
        isVisible = true,
        isReadOnly = false,
        ctag = null,
        syncToken = null
    )

    private suspend fun insertEvent(
        calendarId: Long,
        uid: String = UUID.randomUUID().toString(),
        title: String = "Test Event",
        description: String? = null,
        startTs: Long = baseTimestamp,
        endTs: Long = baseTimestamp + 3600000L
    ): Event {
        val event = Event(
            id = 0L,
            calendarId = calendarId,
            uid = uid,
            title = title,
            location = null,
            description = description,
            startTs = startTs,
            endTs = endTs,
            isAllDay = false,
            timezone = null,
            rrule = null,
            exdate = null,
            dtstamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            sequence = 0,
            syncStatus = SyncStatus.SYNCED,
            caldavUrl = null,
            etag = null
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }

    private fun createEvent(
        calendarId: Long = 1L,
        uid: String = UUID.randomUUID().toString(),
        title: String = "Test Event",
        description: String? = null,
        startTs: Long = baseTimestamp,
        endTs: Long = baseTimestamp + 3600000L
    ) = Event(
        id = 0L, // Auto-generate
        calendarId = calendarId,
        uid = uid,
        title = title,
        location = null,
        description = description,
        startTs = startTs,
        endTs = endTs,
        isAllDay = false,
        timezone = null,
        rrule = null,
        exdate = null,
        dtstamp = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        sequence = 0,
        syncStatus = SyncStatus.SYNCED,
        caldavUrl = null,
        etag = null
    )

    private fun createOccurrence(
        eventId: Long,
        calendarId: Long,
        startTs: Long,
        endTs: Long,
        startDay: Int,
        endDay: Int
    ) = Occurrence(
        id = 0L, // Auto-generate
        eventId = eventId,
        calendarId = calendarId,
        startTs = startTs,
        endTs = endTs,
        startDay = startDay,
        endDay = endDay,
        isCancelled = false,
        exceptionEventId = null
    )
}
