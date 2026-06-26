package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
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
import org.onekash.kashcal.domain.model.AccountProvider
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
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
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

    @Test
    fun `getOccurrenceNearTime matches a row drifting within the 60-second tolerance`() = runTest {
        // A reminder stores the occurrence time captured at scheduling; a later
        // RRULE re-expansion can shift the row's start_ts by sub-second amounts
        // (second-boundary truncation). The near-time lookup must still find it,
        // unlike the exact-match getOccurrenceAtTime.
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        val found = occurrencesDao.getOccurrenceNearTime(eventId, occurrenceTs + 500)

        assertNotNull(found)
        assertEquals(occurrenceTs, found?.startTs)
    }

    @Test
    fun `getOccurrenceNearTime rejects a row drifting beyond the 60-second tolerance`() = runTest {
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(startTs = occurrenceTs, startDay = 20250122))

        // 90s away — a different instance, not the same slot.
        val found = occurrencesDao.getOccurrenceNearTime(eventId, occurrenceTs + 90_000)

        assertNull(found)
    }

    @Test
    fun `getOccurrenceNearTime returns cancelled rows so the caller can inspect them`() = runTest {
        // The fire-time guard must see is_cancelled = 1 rows (the cancelled-
        // exception representation) to suppress them — so this lookup, unlike the
        // calendar-view queries, does NOT filter out cancelled occurrences.
        val occurrenceTs = parseDate("2025-01-22 10:00")
        occurrencesDao.insert(createOccurrence(
            startTs = occurrenceTs,
            startDay = 20250122,
            isCancelled = true
        ))

        val found = occurrencesDao.getOccurrenceNearTime(eventId, occurrenceTs)

        assertNotNull(found)
        assertTrue(found!!.isCancelled)
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

    // ==================== Delete Before Cutoff Tests ====================

    @Test
    fun `deleteBeforeCutoff deletes occurrences ending before cutoff`() = runTest {
        // Occurrence ending before cutoff
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            startDay = 20240115
        ))

        // Occurrence ending after cutoff
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-03-15 10:00"),
            endTs = parseDate("2024-03-15 11:00"),
            startDay = 20240315
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(1, deleted)
        assertEquals(1, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff preserves occurrences ending exactly at cutoff`() = runTest {
        // Occurrence ending exactly at cutoff (should be preserved)
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-01-31 23:00"),
            endTs = parseDate("2024-02-01 00:00"),  // Ends exactly at cutoff
            startDay = 20240131
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(0, deleted)  // end_ts < cutoff, not <=
        assertEquals(1, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff handles multi-day occurrences correctly`() = runTest {
        // Multi-day event starting before cutoff but ending after
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-01-30 10:00"),
            endTs = parseDate("2024-02-02 18:00"),  // Ends after cutoff
            startDay = 20240130,
            endDay = 20240202
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(0, deleted)  // Preserved because endTs > cutoff
        assertEquals(1, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff deletes cancelled occurrences too`() = runTest {
        // Cancelled occurrence before cutoff
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            startDay = 20240115,
            isCancelled = true
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(1, deleted)
        assertEquals(0, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff deletes occurrences with exception links`() = runTest {
        // Create exception event first (to satisfy FK constraint)
        val exceptionId = eventsDao.insert(Event(
            uid = "exception@test.com",
            calendarId = calendarId,
            title = "Exception Event",
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            syncStatus = SyncStatus.SYNCED
        ))

        // Occurrence with exception event linked
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            startDay = 20240115,
            exceptionEventId = exceptionId
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(1, deleted)
        assertEquals(0, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff returns count of deleted occurrences`() = runTest {
        // Insert 7 occurrences before cutoff
        repeat(7) { i ->
            occurrencesDao.insert(createOccurrence(
                startTs = parseDate("2024-01-${10 + i} 10:00"),
                endTs = parseDate("2024-01-${10 + i} 11:00"),
                startDay = 20240110 + i
            ))
        }

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(7, deleted)
    }

    @Test
    fun `deleteBeforeCutoff with no matching occurrences returns zero`() = runTest {
        // All occurrences after cutoff
        occurrencesDao.insert(createOccurrence(
            startTs = parseDate("2024-03-15 10:00"),
            endTs = parseDate("2024-03-15 11:00"),
            startDay = 20240315
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(0, deleted)
        assertEquals(1, occurrencesDao.getTotalCount())
    }

    @Test
    fun `deleteBeforeCutoff deletes from multiple events and calendars`() = runTest {
        // Occurrence from first event/calendar before cutoff
        occurrencesDao.insert(createOccurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = parseDate("2024-01-10 10:00"),
            endTs = parseDate("2024-01-10 11:00"),
            startDay = 20240110
        ))

        // Occurrence from second event/calendar before cutoff
        occurrencesDao.insert(createOccurrence(
            eventId = secondEventId,
            calendarId = secondCalendarId,
            startTs = parseDate("2024-01-15 10:00"),
            endTs = parseDate("2024-01-15 11:00"),
            startDay = 20240115
        ))

        // Occurrence from first event after cutoff (should remain)
        occurrencesDao.insert(createOccurrence(
            eventId = eventId,
            calendarId = calendarId,
            startTs = parseDate("2024-03-10 10:00"),
            endTs = parseDate("2024-03-10 11:00"),
            startDay = 20240310
        ))

        val cutoff = parseDate("2024-02-01 00:00")
        val deleted = occurrencesDao.deleteBeforeCutoff(cutoff)

        assertEquals(2, deleted)
        assertEquals(1, occurrencesDao.getTotalCount())
    }

    // ==================== Missing Occurrences Detection ====================

    @Test
    fun `getRecurringEventsWithNoOccurrences returns recurring event with zero occurrences`() = runTest {
        // Insert a recurring event (no occurrences inserted)
        val recurringEventId = eventsDao.insert(
            Event(
                uid = "recurring-orphan@test.com",
                calendarId = calendarId,
                title = "Orphaned Recurring",
                startTs = parseDate("2025-03-01 09:00"),
                endTs = parseDate("2025-03-01 10:00"),
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=WEEKLY;BYDAY=MO",
                syncStatus = SyncStatus.SYNCED
            )
        )

        val result = occurrencesDao.getRecurringEventsWithNoOccurrences()

        assertTrue("Should find recurring event with no occurrences", result.contains(recurringEventId))
    }

    @Test
    fun `getRecurringEventsWithNoOccurrences excludes non-recurring events`() = runTest {
        // eventId from setup is non-recurring and has no occurrences
        val result = occurrencesDao.getRecurringEventsWithNoOccurrences()

        assertFalse("Non-recurring event should be excluded", result.contains(eventId))
    }

    @Test
    fun `getRecurringEventsWithNoOccurrences excludes exception events`() = runTest {
        val masterEventId = eventsDao.insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Master Event",
                startTs = parseDate("2025-03-01 09:00"),
                endTs = parseDate("2025-03-01 10:00"),
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=WEEKLY",
                syncStatus = SyncStatus.SYNCED
            )
        )
        // Exception event has originalEventId set
        eventsDao.insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Exception Occurrence",
                startTs = parseDate("2025-03-08 09:00"),
                endTs = parseDate("2025-03-08 10:00"),
                dtstamp = System.currentTimeMillis(),
                originalEventId = masterEventId,
                originalInstanceTime = parseDate("2025-03-08 09:00"),
                syncStatus = SyncStatus.SYNCED
            )
        )

        val result = occurrencesDao.getRecurringEventsWithNoOccurrences()

        // Master should be found (no occurrences), but exception should not
        assertTrue("Master event should be found", result.contains(masterEventId))
        assertEquals("Only master should be in results", 1, result.size)
    }

    @Test
    fun `getRecurringEventsWithNoOccurrences excludes PENDING_DELETE events`() = runTest {
        eventsDao.insert(
            Event(
                uid = "deleted-recurring@test.com",
                calendarId = calendarId,
                title = "Deleted Recurring",
                startTs = parseDate("2025-03-01 09:00"),
                endTs = parseDate("2025-03-01 10:00"),
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY",
                syncStatus = SyncStatus.PENDING_DELETE
            )
        )

        val result = occurrencesDao.getRecurringEventsWithNoOccurrences()

        assertTrue("PENDING_DELETE event should be excluded", result.isEmpty())
    }

    @Test
    fun `getRecurringEventsWithNoOccurrences excludes events that have occurrences`() = runTest {
        val recurringEventId = eventsDao.insert(
            Event(
                uid = "has-occurrences@test.com",
                calendarId = calendarId,
                title = "Has Occurrences",
                startTs = parseDate("2025-03-01 09:00"),
                endTs = parseDate("2025-03-01 10:00"),
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=WEEKLY",
                syncStatus = SyncStatus.SYNCED
            )
        )
        occurrencesDao.insert(createOccurrence(
            eventId = recurringEventId,
            startTs = parseDate("2025-03-01 09:00"),
            startDay = 20250301
        ))

        val result = occurrencesDao.getRecurringEventsWithNoOccurrences()

        assertFalse("Event with occurrences should be excluded", result.contains(recurringEventId))
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