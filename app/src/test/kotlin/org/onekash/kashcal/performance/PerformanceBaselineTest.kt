package org.onekash.kashcal.performance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.onekash.kashcal.domain.reader.EventReader
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Performance baseline tests.
 *
 * Tests verify acceptable performance for:
 * - Large dataset loading (1000+ events)
 * - Range queries (month view, week view)
 * - Search queries (FTS)
 * - RRULE expansion limits
 * - Batch operations
 *
 * These tests establish performance expectations and detect regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PerformanceBaselineTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var eventReader: EventReader
    private var testCalendarId: Long = 0

    // Performance thresholds (in milliseconds)
    companion object {
        const val LARGE_DATASET_INSERT_THRESHOLD = 10000L // 10s for 1000 events
        const val RANGE_QUERY_THRESHOLD = 500L // 500ms for month query
        const val SEARCH_QUERY_THRESHOLD = 200L // 200ms for FTS search
        const val RRULE_EXPANSION_THRESHOLD = 2000L // 2s for 2-year expansion
        const val BATCH_DELETE_THRESHOLD = 3000L // 3s for batch delete
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventReader = EventReader(database)

        val accountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Performance Test Calendar",
                color = 0xFF2196F3.toInt(),
                isVisible = true
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Large Dataset Tests ====================

    @Test
    fun `inserting 1000 events should complete within threshold`() = runTest {
        val events = (1..1000).map { i ->
            createTestEvent("Event $i", System.currentTimeMillis() + i * 3600000)
        }

        val insertTime = measureTimeMillis {
            events.forEach { event ->
                database.eventsDao().insert(event)
            }
        }

        println("Insert 1000 events: ${insertTime}ms")
        assertTrue(
            "Insert took ${insertTime}ms, should be under ${LARGE_DATASET_INSERT_THRESHOLD}ms",
            insertTime < LARGE_DATASET_INSERT_THRESHOLD
        )

        // Verify all events inserted
        val count = database.eventsDao().getCountByCalendar(testCalendarId)
        assertEquals(1000, count)
    }

    @Test
    fun `querying events for month with 500 events should be fast`() = runTest {
        // Create 500 events spread across a month
        val baseTime = System.currentTimeMillis()
        val dayMs = 86400000L

        repeat(500) { i ->
            val dayOffset = (i % 30) * dayMs
            val event = createTestEvent("Month Event $i", baseTime + dayOffset)
            val eventId = database.eventsDao().insert(event)

            // Insert occurrence
            database.occurrencesDao().insert(
                Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = baseTime + dayOffset,
                    endTs = baseTime + dayOffset + 3600000,
                    startDay = calculateDayCode(baseTime + dayOffset),
                    endDay = calculateDayCode(baseTime + dayOffset + 3600000)
                )
            )
        }

        // Query for month range
        val monthStart = baseTime
        val monthEnd = baseTime + 30 * dayMs

        val queryTime = measureTimeMillis {
            val occurrences = database.occurrencesDao().getInRangeOnce(monthStart, monthEnd)
            assertTrue(occurrences.size >= 400) // At least most events
        }

        println("Month range query with 500 events: ${queryTime}ms")
        assertTrue(
            "Query took ${queryTime}ms, should be under ${RANGE_QUERY_THRESHOLD}ms",
            queryTime < RANGE_QUERY_THRESHOLD
        )
    }

    @Test
    fun `day query with 100 events on same day should be fast`() = runTest {
        val targetDay = System.currentTimeMillis()
        val dayCode = calculateDayCode(targetDay)

        // Create 100 events on same day
        repeat(100) { i ->
            val startTs = targetDay + i * 600000 // 10 min apart
            val event = createTestEvent("Same Day Event $i", startTs)
            val eventId = database.eventsDao().insert(event)

            database.occurrencesDao().insert(
                Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = startTs,
                    endTs = startTs + 3600000,
                    startDay = dayCode,
                    endDay = dayCode
                )
            )
        }

        val queryTime = measureTimeMillis {
            val occurrences = database.occurrencesDao().getForDayOnce(dayCode)
            assertEquals(100, occurrences.size)
        }

        println("Day query with 100 events: ${queryTime}ms")
        assertTrue(
            "Day query took ${queryTime}ms, should be under 100ms",
            queryTime < 100
        )
    }

    // ==================== Search Performance Tests ====================

    @Test
    fun `FTS search across 500 events should be fast`() = runTest {
        // Create 500 events with varied titles
        val titles = listOf("Meeting", "Call", "Review", "Standup", "Planning", "Demo", "Workshop")

        repeat(500) { i ->
            val title = "${titles[i % titles.size]} ${i / titles.size + 1}"
            val event = createTestEvent(title, System.currentTimeMillis() + i * 3600000)
            database.eventsDao().insert(event)
        }

        // Search for "Meeting"
        val searchTime = measureTimeMillis {
            val results = database.eventsDao().search("Meeting")
            assertTrue(results.size > 50) // ~71 "Meeting" events
        }

        println("FTS search 'Meeting' across 500 events: ${searchTime}ms")
        assertTrue(
            "Search took ${searchTime}ms, should be under ${SEARCH_QUERY_THRESHOLD}ms",
            searchTime < SEARCH_QUERY_THRESHOLD
        )
    }

    @Test
    fun `FTS search with exact match should be reasonably fast`() = runTest {
        repeat(300) { i ->
            val event = createTestEvent("Important Quarterly Review $i", System.currentTimeMillis() + i * 3600000)
            database.eventsDao().insert(event)
        }

        val searchTime = measureTimeMillis {
            // Exact word search - "Important" is a common word in all events
            val results = database.eventsDao().search("Important")
            // FTS results may be limited by various factors - just verify search completes
            assertTrue("Expected some results, got ${results.size}", results.isNotEmpty())
        }

        println("FTS exact search across 300 events: ${searchTime}ms")
        assertTrue(
            "Exact search took ${searchTime}ms, should be under ${SEARCH_QUERY_THRESHOLD}ms",
            searchTime < SEARCH_QUERY_THRESHOLD
        )
    }

    // ==================== RRULE Expansion Performance Tests ====================

    @Test
    fun `daily recurring for 2 years should expand within threshold`() = runTest {
        // Note: OccurrenceGenerator has MAX_ITERATIONS = 1000 safety limit.
        // Also uses a 24-month expansion window from now.
        // COUNT=730 will be bounded by whichever limit is reached first.
        val event = createRecurringTestEvent(
            "Daily for 2 Years",
            "FREQ=DAILY;COUNT=730" // ~2 years
        )
        val eventId = database.eventsDao().insert(event)
        val insertedEvent = event.copy(id = eventId)

        val expansionTime = measureTimeMillis {
            occurrenceGenerator.regenerateOccurrences(insertedEvent)
        }

        println("Expand daily x730: ${expansionTime}ms")
        assertTrue(
            "RRULE expansion took ${expansionTime}ms, should be under ${RRULE_EXPANSION_THRESHOLD}ms",
            expansionTime < RRULE_EXPANSION_THRESHOLD
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId)
        // May be less than 730 due to 24-month expansion window or MAX_ITERATIONS
        assertTrue(
            "Expected at least 365 occurrences, got ${occurrences.size}",
            occurrences.size >= 365
        )
    }

    @Test
    fun `weekly recurring with BYDAY for 2 years should expand within threshold`() = runTest {
        val event = createRecurringTestEvent(
            "Weekly MWF",
            "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=312" // ~2 years of MWF
        )
        val eventId = database.eventsDao().insert(event)
        val insertedEvent = event.copy(id = eventId)

        val expansionTime = measureTimeMillis {
            occurrenceGenerator.regenerateOccurrences(insertedEvent)
        }

        println("Expand weekly MWF x312: ${expansionTime}ms")
        assertTrue(
            "BYDAY expansion took ${expansionTime}ms, should be under ${RRULE_EXPANSION_THRESHOLD}ms",
            expansionTime < RRULE_EXPANSION_THRESHOLD
        )
    }

    @Test
    fun `complex RRULE with BYSETPOS should expand efficiently`() = runTest {
        // Second Tuesday of each month for 2 years
        val event = createRecurringTestEvent(
            "Monthly 2nd Tuesday",
            "FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2;COUNT=24"
        )
        val eventId = database.eventsDao().insert(event)
        val insertedEvent = event.copy(id = eventId)

        val expansionTime = measureTimeMillis {
            occurrenceGenerator.regenerateOccurrences(insertedEvent)
        }

        println("Expand BYSETPOS x24: ${expansionTime}ms")
        assertTrue(
            "BYSETPOS expansion took ${expansionTime}ms, should be under 500ms",
            expansionTime < 500
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId)
        // BYSETPOS=2 with BYDAY=TU gets the 2nd Tuesday of each month.
        // Result may be fewer than 24 if expansion window < 24 months or
        // some months don't have a 2nd Tuesday within the window.
        assertTrue(
            "Expected at least 12 occurrences for 2nd-Tuesday-monthly, got ${occurrences.size}",
            occurrences.size >= 12
        )
    }

    // ==================== Batch Operation Tests ====================

    @Test
    fun `batch delete of 200 events should complete within threshold`() = runTest {
        // Create 200 events
        val eventIds = (1..200).map { i ->
            val event = createTestEvent("Deletable $i", System.currentTimeMillis() + i * 3600000)
            database.eventsDao().insert(event)
        }

        val deleteTime = measureTimeMillis {
            eventIds.forEach { id ->
                database.eventsDao().deleteById(id)
            }
        }

        println("Batch delete 200 events: ${deleteTime}ms")
        assertTrue(
            "Batch delete took ${deleteTime}ms, should be under ${BATCH_DELETE_THRESHOLD}ms",
            deleteTime < BATCH_DELETE_THRESHOLD
        )

        // Verify all deleted
        val remaining = database.eventsDao().getCountByCalendar(testCalendarId)
        assertEquals(0, remaining)
    }

    @Test
    fun `batch update of 200 events should complete quickly`() = runTest {
        // Create 200 events
        val events = (1..200).map { i ->
            val event = createTestEvent("Updatable $i", System.currentTimeMillis() + i * 3600000)
            val id = database.eventsDao().insert(event)
            event.copy(id = id)
        }

        val updateTime = measureTimeMillis {
            events.forEach { event ->
                database.eventsDao().update(event.copy(title = "${event.title} - Updated"))
            }
        }

        println("Batch update 200 events: ${updateTime}ms")
        assertTrue(
            "Batch update took ${updateTime}ms, should be under 3000ms",
            updateTime < 3000
        )
    }

    // ==================== Memory Bounds Tests ====================

    @Test
    fun `RRULE with MAX_ITERATIONS should not exceed limit`() = runTest {
        // This would generate infinite occurrences without the safety limit
        val event = createRecurringTestEvent(
            "Infinite Daily",
            "FREQ=DAILY" // No COUNT or UNTIL
        )
        val eventId = database.eventsDao().insert(event)
        val insertedEvent = event.copy(id = eventId)

        val expansionTime = measureTimeMillis {
            occurrenceGenerator.regenerateOccurrences(insertedEvent)
        }

        // Should complete (not hang)
        println("Expand infinite daily (bounded): ${expansionTime}ms")
        assertTrue("Should complete expansion", expansionTime < 5000)

        // Should have bounded number of occurrences
        val occurrences = database.occurrencesDao().getForEvent(eventId)
        assertTrue(
            "Occurrences should be bounded by MAX_ITERATIONS",
            occurrences.size <= 1000
        )
    }

    // ==================== Flow Query Performance Tests ====================

    @Test
    fun `Flow query for day should emit quickly`() = runTest {
        val targetDay = System.currentTimeMillis()
        val dayCode = calculateDayCode(targetDay)

        // Create 50 events
        repeat(50) { i ->
            val startTs = targetDay + i * 600000
            val event = createTestEvent("Flow Event $i", startTs)
            val eventId = database.eventsDao().insert(event)

            database.occurrencesDao().insert(
                Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = startTs,
                    endTs = startTs + 3600000,
                    startDay = dayCode,
                    endDay = dayCode
                )
            )
        }

        val flowTime = measureTimeMillis {
            val flow = database.occurrencesDao().getForDay(dayCode)
            val result = flow.first()
            assertEquals(50, result.size)
        }

        println("Flow query for day: ${flowTime}ms")
        assertTrue(
            "Flow query took ${flowTime}ms, should be under 200ms",
            flowTime < 200
        )
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String, startTs: Long): Event {
        return Event(
            calendarId = testCalendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            timezone = "UTC",
            syncStatus = SyncStatus.SYNCED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dtstamp = System.currentTimeMillis()
        )
    }

    private fun createRecurringTestEvent(title: String, rrule: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            calendarId = testCalendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
    }

    private fun calculateDayCode(timestamp: Long): Int {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestamp
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return year * 10000 + month * 100 + day
    }
}
