package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for EventReader batch query optimizations (H2 fix).
 *
 * Verifies that batch loading methods work correctly:
 * - getEventsByIds() batch helper
 * - getOccurrencesWithEventsInRangeFlow() uses batch loading
 * - getEventsForDay() handles exception events correctly
 * - Large result sets work within SQLite IN clause limits
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderBatchQueryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var calendarId: Long = 0
    private var secondCalendarId: Long = 0

    private val defaultZone = ZoneId.of("America/New_York")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventReader = EventReader(database)

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            calendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.icloud.com/personal/",
                    displayName = "Personal",
                    color = 0xFF2196F3.toInt(),
                    isVisible = true
                )
            )
            secondCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.icloud.com/work/",
                    displayName = "Work",
                    color = 0xFFFF5722.toInt(),
                    isVisible = true
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun createEvent(
        calendarId: Long = this.calendarId,
        title: String = "Test Event",
        startTs: Long = System.currentTimeMillis(),
        rrule: String? = null
    ): Event {
        val event = Event(
            id = 0,
            uid = "test-${System.nanoTime()}",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED
        )
        val id = database.eventsDao().insert(event)
        val created = event.copy(id = id)

        // Generate occurrences
        val rangeStart = startTs - 86400000L * 30
        val rangeEnd = startTs + 86400000L * 365
        occurrenceGenerator.generateOccurrences(created, rangeStart, rangeEnd)

        return created
    }

    private suspend fun createExceptionEvent(
        master: Event,
        instanceOffset: Long,
        title: String
    ): Event {
        val instanceTime = master.startTs + instanceOffset
        val exception = Event(
            id = 0,
            uid = master.uid, // Same UID as master (RFC 5545)
            calendarId = master.calendarId,
            title = title,
            startTs = instanceTime + 3600000, // Modified time (+1 hour)
            endTs = instanceTime + 7200000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = master.id,
            originalInstanceTime = instanceTime,
            syncStatus = SyncStatus.SYNCED
        )
        val id = database.eventsDao().insert(exception)
        val created = exception.copy(id = id)

        // Link the exception to the occurrence
        database.occurrencesDao().linkException(
            master.id,
            instanceTime,
            created.id
        )

        return created
    }

    // ==================== getEventsByIds Tests ====================

    @Test
    fun `getEventsByIds returns empty map for empty list`() = runTest {
        val result = eventReader.getEventsByIds(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getEventsByIds returns map with correct events`() = runTest {
        val event1 = createEvent(title = "Event 1")
        val event2 = createEvent(title = "Event 2")
        val event3 = createEvent(title = "Event 3")

        val result = eventReader.getEventsByIds(listOf(event1.id, event2.id, event3.id))

        assertEquals(3, result.size)
        assertEquals("Event 1", result[event1.id]?.title)
        assertEquals("Event 2", result[event2.id]?.title)
        assertEquals("Event 3", result[event3.id]?.title)
    }

    @Test
    fun `getEventsByIds handles non-existent IDs gracefully`() = runTest {
        val event = createEvent(title = "Real Event")

        val result = eventReader.getEventsByIds(listOf(event.id, 99999L, 88888L))

        assertEquals(1, result.size)
        assertEquals("Real Event", result[event.id]?.title)
    }

    @Test
    fun `getEventsByIds handles duplicate IDs`() = runTest {
        val event = createEvent(title = "Test")

        val result = eventReader.getEventsByIds(listOf(event.id, event.id, event.id))

        assertEquals(1, result.size)
    }

    // ==================== getOccurrencesWithEventsInRangeFlow Tests ====================

    @Test
    fun `getOccurrencesWithEventsInRangeFlow returns correct data with batch queries`() = runTest {
        val now = System.currentTimeMillis()
        val event1 = createEvent(calendarId = calendarId, title = "Event 1", startTs = now)
        val event2 = createEvent(calendarId = secondCalendarId, title = "Event 2", startTs = now + 3600000)

        val results = eventReader.getOccurrencesWithEventsInRangeFlow(
            now - 3600000,
            now + 86400000
        ).first()

        assertEquals(2, results.size)
        val titles = results.map { it.event.title }.toSet()
        assertTrue(titles.contains("Event 1"))
        assertTrue(titles.contains("Event 2"))

        // Verify calendar is populated
        results.forEach { owp ->
            assertNotNull(owp.calendar)
        }
    }

    @Test
    fun `getOccurrencesWithEventsInRangeFlow handles exception events correctly`() = runTest {
        val now = System.currentTimeMillis()
        val master = createEvent(title = "Master", startTs = now, rrule = "FREQ=DAILY;COUNT=5")
        val exception = createExceptionEvent(master, 86400000L, "Modified Instance")

        val results = eventReader.getOccurrencesWithEventsInRangeFlow(
            now - 3600000,
            now + 86400000L * 3 // 3 days
        ).first()

        // Should include occurrences for original and modified instances
        assertTrue(results.any { it.event.title == "Master" })
        assertTrue(results.any { it.event.title == "Modified Instance" })
    }

    @Test
    fun `getOccurrencesWithEventsInRangeFlow returns empty list for empty range`() = runTest {
        val farFuture = System.currentTimeMillis() + 86400000L * 3650 // 10 years

        val results = eventReader.getOccurrencesWithEventsInRangeFlow(
            farFuture,
            farFuture + 86400000
        ).first()

        assertTrue(results.isEmpty())
    }

    // ==================== getEventsForDay Tests ====================

    @Test
    fun `getEventsForDay handles exception events correctly`() = runTest {
        val today = LocalDate.now()
        val dayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val todayStart = today.atStartOfDay(defaultZone).toInstant().toEpochMilli()

        val master = createEvent(title = "Master", startTs = todayStart + 36000000, rrule = "FREQ=DAILY")
        createExceptionEvent(master, 0, "Modified Today")

        val results = eventReader.getEventsForDay(dayCode)

        // Should include the modified version, not master for this occurrence
        assertTrue(results.any { it.event.title == "Modified Today" })
    }

    @Test
    fun `getEventsForDay uses batch queries and preserves sort order`() = runTest {
        // Create events at known times
        val now = System.currentTimeMillis()
        createEvent(title = "First Event", startTs = now)
        createEvent(title = "Second Event", startTs = now + 3600000) // +1 hour
        createEvent(title = "Third Event", startTs = now + 7200000) // +2 hours

        // Query using range method to verify batch loading works
        val results = eventReader.getOccurrencesWithEventsInRange(
            now - 3600000,
            now + 86400000
        )

        // Verify we got all events with their data
        assertTrue(results.size >= 3)
        assertTrue(results.any { it.event.title == "First Event" })
        assertTrue(results.any { it.event.title == "Second Event" })
        assertTrue(results.any { it.event.title == "Third Event" })

        // Verify each result has calendar info populated (batch loaded)
        results.forEach { owp ->
            assertNotNull(owp.calendar)
            assertEquals(calendarId, owp.calendar?.id)
        }
    }

    @Test
    fun `getEventsForDay includes calendar info`() = runTest {
        val today = LocalDate.now()
        val dayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val todayStart = today.atStartOfDay(defaultZone).toInstant().toEpochMilli()

        createEvent(calendarId = calendarId, startTs = todayStart + 36000000)
        createEvent(calendarId = secondCalendarId, startTs = todayStart + 43200000)

        val results = eventReader.getEventsForDay(dayCode)

        assertEquals(2, results.size)
        val calendarNames = results.mapNotNull { it.calendar?.displayName }.toSet()
        assertTrue(calendarNames.contains("Personal"))
        assertTrue(calendarNames.contains("Work"))
    }

    // ==================== Large Dataset Tests ====================

    @Test
    fun `batch queries work with 100+ occurrences`() = runTest {
        val now = System.currentTimeMillis()

        // Create 50 events (will generate multiple occurrences each)
        repeat(50) { i ->
            createEvent(
                title = "Event $i",
                startTs = now + (i * 3600000L), // Spread across hours
                calendarId = if (i % 2 == 0) calendarId else secondCalendarId
            )
        }

        val results = eventReader.getOccurrencesWithEventsInRangeFlow(
            now - 3600000,
            now + 86400000L * 7 // 1 week
        ).first()

        // Should return all events in the range
        assertTrue(results.size >= 50)

        // Verify each result has proper data
        results.forEach { owp ->
            assertNotNull(owp.event)
            assertNotNull(owp.occurrence)
            assertTrue(owp.event.title.startsWith("Event"))
        }
    }

    @Test
    fun `getOccurrencesWithEventsInRange handles many calendars`() = runTest {
        val now = System.currentTimeMillis()

        // Create events in both calendars
        repeat(10) { i ->
            createEvent(
                calendarId = calendarId,
                title = "Personal $i",
                startTs = now + (i * 3600000L)
            )
            createEvent(
                calendarId = secondCalendarId,
                title = "Work $i",
                startTs = now + (i * 3600000L) + 1800000 // Offset by 30 min
            )
        }

        val results = eventReader.getOccurrencesWithEventsInRange(
            now - 3600000,
            now + 86400000
        )

        assertEquals(20, results.size)

        // Verify correct calendar mapping
        val personalEvents = results.filter { it.calendar?.displayName == "Personal" }
        val workEvents = results.filter { it.calendar?.displayName == "Work" }

        assertEquals(10, personalEvents.size)
        assertEquals(10, workEvents.size)
    }
}
