package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for OccurrencesDao.
 *
 * Tests cover:
 * - Range queries (timestamp and day-based)
 * - Calendar-specific queries
 * - Event-specific queries
 * - Insert and batch insert
 * - Exception linking
 * - Cancellation (EXDATE)
 * - Calendar move operations
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrencesDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrencesDao: OccurrencesDao
    private lateinit var eventsDao: EventsDao
    private var calendarId: Long = 0
    private var secondCalendarId: Long = 0
    private var eventId: Long = 0
    private var secondEventId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrencesDao = database.occurrencesDao()
        eventsDao = database.eventsDao()

        // Setup test data hierarchy
        val accountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
        secondCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Second Calendar",
                color = 0xFF00FF00.toInt()
            )
        )
        eventId = eventsDao.insert(
            Event(
                uid = "event-1@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = parseDate("2025-01-15 10:00"),
                endTs = parseDate("2025-01-15 11:00"),
                dtstamp = System.currentTimeMillis(),
                syncStatus = SyncStatus.SYNCED
            )
        )
        secondEventId = eventsDao.insert(
            Event(
                uid = "event-2@test.com",
                calendarId = secondCalendarId,
                title = "Second Event",
                startTs = parseDate("2025-01-20 14:00"),
                endTs = parseDate("2025-01-20 15:00"),
                dtstamp = System.currentTimeMillis(),
                syncStatus = SyncStatus.SYNCED
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Insert Tests ====================

    @Test
    fun `insert creates occurrence and returns ID`() = runTest {
        val occurrence = createOccurrence(
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            startDay = 20250115,
            endDay = 20250115
        )

        val id = occurrencesDao.insert(occurrence)

        assertTrue(id > 0)
        val occurrences = occurrencesDao.getForEvent(eventId)
        assertEquals(1, occurrences.size)
    }

    @Test
    fun `insertAll creates multiple occurrences`() = runTest {
        val occurrences = listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-16 10:00"), startDay = 20250116),
            createOccurrence(startTs = parseDate("2025-01-17 10:00"), startDay = 20250117)
        )

        occurrencesDao.insertAll(occurrences)

        val saved = occurrencesDao.getForEvent(eventId)
        assertEquals(3, saved.size)
    }

    @Test
    fun `insert with REPLACE replaces existing occurrence`() = runTest {
        val startTs = parseDate("2025-01-15 10:00")
        val id = occurrencesDao.insert(createOccurrence(startTs = startTs, startDay = 20250115))

        // Insert with same primary key should replace
        occurrencesDao.insert(createOccurrence(
            id = id,
            startTs = startTs,
            startDay = 20250115,
            isCancelled = true
        ))

        val occurrences = occurrencesDao.getForEvent(eventId)
        assertEquals(1, occurrences.size)
        assertTrue(occurrences[0].isCancelled)
    }

    // ==================== Range Query Tests ====================

    @Test
    fun `getInRange returns occurrences in time window`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-10 10:00"), startDay = 20250110),
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-20 10:00"), startDay = 20250120),
            createOccurrence(startTs = parseDate("2025-01-25 10:00"), startDay = 20250125)
        ))

        val results = occurrencesDao.getInRangeOnce(
            parseDate("2025-01-12 00:00"),
            parseDate("2025-01-22 23:59")
        )

        assertEquals(2, results.size)
        assertEquals(20250115, results[0].startDay)
        assertEquals(20250120, results[1].startDay)
    }

    @Test
    fun `getInRange excludes cancelled occurrences`() = runTest {
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115,
            isCancelled = false
        ))
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-16 10:00"),
            startDay = 20250116,
            isCancelled = true
        ))

        val results = occurrencesDao.getInRangeOnce(
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        assertEquals(1, results.size)
        assertEquals(20250115, results[0].startDay)
    }

    @Test
    fun `getInRange Flow emits updates`() = runTest {
        occurrencesDao.getInRange(
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        ).test(timeout = 5.seconds) {
            assertEquals(0, awaitItem().size)

            occurrencesDao.insert(createOccurrence(
                startTs = parseDate("2025-01-15 10:00"),
                startDay = 20250115
            ))
            assertEquals(1, awaitItem().size)

            occurrencesDao.insert(createOccurrence(
                startTs = parseDate("2025-01-20 10:00"),
                startDay = 20250120
            ))
            assertEquals(2, awaitItem().size)

            cancel()
        }
    }

    // ==================== Calendar-Specific Query Tests ====================

    @Test
    fun `getForCalendarInRange filters by calendar`() = runTest {
        occurrencesDao.insert(createOccurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115
        ))
        occurrencesDao.insert(createOccurrence(
            eventId = secondEventId,
            calendarId = secondCalendarId,
            startTs = parseDate("2025-01-15 14:00"),
            startDay = 20250115
        ))

        val calendarResults = occurrencesDao.getForCalendarInRangeOnce(
            calendarId,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        assertEquals(1, calendarResults.size)
        assertEquals(calendarId, calendarResults[0].calendarId)
    }

    // ==================== Day-Based Query Tests ====================

    @Test
    fun `getForDay returns occurrences for specific day`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 09:00"), startDay = 20250115, endDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-15 14:00"), startDay = 20250115, endDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-16 10:00"), startDay = 20250116, endDay = 20250116)
        ))

        val results = occurrencesDao.getForDayOnce(20250115)

        assertEquals(2, results.size)
    }

    @Test
    fun `getForDay includes multi-day events spanning the day`() = runTest {
        // Multi-day event Jan 14-17
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-14 00:00"),
            endTs = parseDate("2025-01-17 23:59"),
            startDay = 20250114,
            endDay = 20250117
        ))
        // Single-day event Jan 15
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-15 10:00"),
            endTs = parseDate("2025-01-15 11:00"),
            startDay = 20250115,
            endDay = 20250115
        ))

        // Query for Jan 15 - should include both
        val results = occurrencesDao.getForDayOnce(20250115)

        assertEquals(2, results.size)
    }

    @Test
    fun `getForDay uses day code correctly for all-day events`() = runTest {
        // All-day event on Jan 15
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-15 00:00"), // UTC midnight
            endTs = parseDate("2025-01-15 23:59"),
            startDay = 20250115,
            endDay = 20250115
        ))

        val jan14Results = occurrencesDao.getForDayOnce(20250114)
        val jan15Results = occurrencesDao.getForDayOnce(20250115)
        val jan16Results = occurrencesDao.getForDayOnce(20250116)

        assertEquals("Should NOT show on Jan 14", 0, jan14Results.size)
        assertEquals("Should show on Jan 15", 1, jan15Results.size)
        assertEquals("Should NOT show on Jan 16", 0, jan16Results.size)
    }

    @Test
    fun `getForCalendarOnDay filters by both calendar and day`() = runTest {
        occurrencesDao.insert(createOccurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115
        ))
        occurrencesDao.insert(createOccurrence(
            eventId = secondEventId,
            calendarId = secondCalendarId,
            startTs = parseDate("2025-01-15 14:00"),
            startDay = 20250115
        ))

        val results = occurrencesDao.getForCalendarOnDay(calendarId, 20250115)

        assertEquals(1, results.size)
        assertEquals(calendarId, results[0].calendarId)
    }

    // ==================== Event-Specific Query Tests ====================

    @Test
    fun `getForEvent returns all occurrences for event`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122),
            createOccurrence(startTs = parseDate("2025-01-29 10:00"), startDay = 20250129)
        ))

        val results = occurrencesDao.getForEvent(eventId)

        assertEquals(3, results.size)
    }

    @Test
    fun `getCountForEvent returns correct count`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122)
        ))

        val count = occurrencesDao.getCountForEvent(eventId)

        assertEquals(2, count)
    }

    @Test
    fun `getMaxStartTs returns latest occurrence time`() = runTest {
        val latestTs = parseDate("2025-01-29 10:00")
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = latestTs, startDay = 20250129),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122)
        ))

        val maxTs = occurrencesDao.getMaxStartTs(eventId)

        assertEquals(latestTs, maxTs)
    }

    @Test
    fun `getOccurrenceAtTime finds specific occurrence`() = runTest {
        val targetTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = targetTs, startDay = 20250122),
            createOccurrence(startTs = parseDate("2025-01-29 10:00"), startDay = 20250129)
        ))

        val occurrence = occurrencesDao.getOccurrenceAtTime(eventId, targetTs)

        assertNotNull(occurrence)
        assertEquals(targetTs, occurrence?.startTs)
    }

    // ==================== Delete Tests ====================

    @Test
    fun `deleteForEvent removes all event occurrences`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122)
        ))
        assertEquals(2, occurrencesDao.getCountForEvent(eventId))

        occurrencesDao.deleteForEvent(eventId)

        assertEquals(0, occurrencesDao.getCountForEvent(eventId))
    }

    @Test
    fun `deleteForEventAfter removes occurrences after time`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122),
            createOccurrence(startTs = parseDate("2025-01-29 10:00"), startDay = 20250129)
        ))

        occurrencesDao.deleteForEventAfter(eventId, parseDate("2025-01-20 00:00"))

        val remaining = occurrencesDao.getForEvent(eventId)
        assertEquals(1, remaining.size)
        assertEquals(20250115, remaining[0].startDay)
    }

    @Test
    fun `deleteForCalendar removes all calendar occurrences`() = runTest {
        occurrencesDao.insert(createOccurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115
        ))
        occurrencesDao.insert(createOccurrence(
            eventId = secondEventId,
            calendarId = secondCalendarId,
            startTs = parseDate("2025-01-20 10:00"),
            startDay = 20250120
        ))

        occurrencesDao.deleteForCalendar(calendarId)

        assertEquals(0, occurrencesDao.getForCalendarInRangeOnce(
            calendarId,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        ).size)
        assertEquals(1, occurrencesDao.getForCalendarInRangeOnce(
            secondCalendarId,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        ).size)
    }

    // ==================== Exception Linking Tests ====================

    @Test
    fun `linkException sets exception event ID`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        // Create exception event
        val exceptionId = eventsDao.insert(Event(
            uid = "exception@test.com",
            calendarId = calendarId,
            title = "Exception Event",
            startTs = occurrenceTs + 3600000, // Modified time
            endTs = occurrenceTs + 7200000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            originalInstanceTime = occurrenceTs,
            syncStatus = SyncStatus.SYNCED
        ))

        occurrencesDao.linkException(eventId, occurrenceTs, exceptionId)

        val occurrence = occurrencesDao.getOccurrenceAtTime(eventId, occurrenceTs)
        assertEquals(exceptionId, occurrence?.exceptionEventId)
    }

    @Test
    fun `linkException uses 60-second tolerance for DST edge cases`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        // Create a real exception event to satisfy FK constraint
        val exceptionId = eventsDao.insert(Event(
            uid = "exception@test.com",
            calendarId = calendarId,
            title = "Exception Event",
            startTs = occurrenceTs,
            endTs = occurrenceTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            syncStatus = SyncStatus.SYNCED
        ))

        // Link with 30 seconds offset (within 60s tolerance)
        occurrencesDao.linkException(eventId, occurrenceTs + 30000, exceptionId)

        val occurrence = occurrencesDao.getOccurrenceAtTime(eventId, occurrenceTs)
        assertEquals(exceptionId, occurrence?.exceptionEventId)
    }

    @Test
    fun `unlinkException removes exception link`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")

        // Create a real exception event to satisfy FK constraint
        val exceptionId = eventsDao.insert(Event(
            uid = "exception2@test.com",
            calendarId = calendarId,
            title = "Exception Event",
            startTs = occurrenceTs,
            endTs = occurrenceTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            syncStatus = SyncStatus.SYNCED
        ))

        val occurrenceId = occurrencesDao.insert(createOccurrence(
            startTs = occurrenceTs,
            startDay = 20250122,
            exceptionEventId = exceptionId
        ))

        occurrencesDao.unlinkException(exceptionId)

        val occurrence = occurrencesDao.getOccurrenceAtTime(eventId, occurrenceTs)
        assertNull(occurrence?.exceptionEventId)
    }

    // ==================== Cancellation Tests ====================

    @Test
    fun `markCancelled sets cancelled flag`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        occurrencesDao.markCancelled(eventId, occurrenceTs)

        val occurrence = occurrencesDao.getForEvent(eventId)[0]
        assertTrue(occurrence.isCancelled)
    }

    @Test
    fun `markCancelled uses 60-second tolerance`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        // Cancel with 45 second offset (within 60s tolerance)
        occurrencesDao.markCancelled(eventId, occurrenceTs + 45000)

        val occurrence = occurrencesDao.getForEvent(eventId)[0]
        assertTrue(occurrence.isCancelled)
    }

    @Test
    fun `unmarkCancelled clears cancelled flag`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(
            startTs = occurrenceTs,
            startDay = 20250122,
            isCancelled = true
        ))

        occurrencesDao.unmarkCancelled(eventId, occurrenceTs)

        val occurrence = occurrencesDao.getForEvent(eventId)[0]
        assertFalse(occurrence.isCancelled)
    }

    // ==================== Calendar Move Tests ====================

    @Test
    fun `updateCalendarIdForEvent moves all occurrences`() = runTest {
        occurrencesDao.insertAll(listOf(
            createOccurrence(calendarId = calendarId, startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(calendarId = calendarId, startTs = parseDate("2025-01-22 10:00"), startDay = 20250122)
        ))

        occurrencesDao.updateCalendarIdForEvent(eventId, secondCalendarId)

        val occurrences = occurrencesDao.getForEvent(eventId)
        assertTrue(occurrences.all { it.calendarId == secondCalendarId })
    }

    // ==================== Utility Tests ====================

    @Test
    fun `getTotalCount returns total occurrences`() = runTest {
        assertEquals(0, occurrencesDao.getTotalCount())

        occurrencesDao.insertAll(listOf(
            createOccurrence(startTs = parseDate("2025-01-15 10:00"), startDay = 20250115),
            createOccurrence(startTs = parseDate("2025-01-22 10:00"), startDay = 20250122)
        ))

        assertEquals(2, occurrencesDao.getTotalCount())
    }

    @Test
    fun `hasOccurrencesInRange returns true when occurrences exist`() = runTest {
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115
        ))

        assertTrue(occurrencesDao.hasOccurrencesInRange(
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        ))
        assertFalse(occurrencesDao.hasOccurrencesInRange(
            parseDate("2025-02-01 00:00"),
            parseDate("2025-02-28 23:59")
        ))
    }

    @Test
    fun `hasOccurrencesInRange ignores cancelled occurrences`() = runTest {
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2025-01-15 10:00"),
            startDay = 20250115,
            isCancelled = true
        ))

        assertFalse(occurrencesDao.hasOccurrencesInRange(
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        ))
    }

    // ==================== Helper Functions ====================

    private fun createOccurrence(
        id: Long = 0,
        eventId: Long = this.eventId,
        calendarId: Long = this.calendarId,
        startTs: Long,
        endTs: Long = startTs + 3600000,
        startDay: Int,
        endDay: Int = startDay,
        isCancelled: Boolean = false,
        exceptionEventId: Long? = null
    ) = Occurrence(
        id = id,
        eventId = eventId,
        calendarId = calendarId,
        startTs = startTs,
        endTs = endTs,
        startDay = startDay,
        endDay = endDay,
        isCancelled = isCancelled,
        exceptionEventId = exceptionEventId
    )

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1,
            dateParts[2].toInt(),
            timeParts[0].toInt(),
            timeParts[1].toInt(),
            0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}