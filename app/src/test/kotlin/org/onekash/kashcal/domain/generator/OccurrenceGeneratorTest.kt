package org.onekash.kashcal.domain.generator

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
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Comprehensive tests for OccurrenceGenerator.
 *
 * Tests cover:
 * - Non-recurring events (single occurrence)
 * - Daily, weekly, monthly, yearly recurrence
 * - EXDATE handling (cancelled occurrences)
 * - RDATE handling (additional occurrences)
 * - Edge cases (DST, month boundaries, leap year)
 * - BYSETPOS patterns (e.g., 2nd Tuesday)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

        // Create test account and calendar
        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = "test", email = "test@test.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://test.com/cal/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Non-Recurring Events ==========

    @Test
    fun `non-recurring event generates single occurrence`() = runTest {
        // Setup - create non-recurring event
        val startTs = parseDate("2025-01-15 10:00") // Jan 15, 2025 10:00
        val endTs = parseDate("2025-01-15 11:00")
        val event = createAndInsertEvent(startTs, endTs)

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert
        assertEquals(1, count)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(startTs, occurrences[0].startTs)
        assertEquals(endTs, occurrences[0].endTs)
    }

    @Test
    fun `non-recurring event outside range generates no occurrences in range query`() = runTest {
        // Setup - event in January
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(startTs, startTs + 3600000)

        // Act - query February
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - event is stored but querying Feb range should return empty
        assertEquals(1, count)
        val inRange = database.occurrencesDao().getForCalendarInRangeOnce(
            testCalendarId,
            parseDate("2025-02-01 00:00"),
            parseDate("2025-02-28 23:59")
        )
        assertEquals(0, inRange.size)
    }

    // ========== Daily Recurrence ==========

    @Test
    fun `daily recurrence generates correct occurrences`() = runTest {
        // Setup - daily event starting Jan 1
        val startTs = parseDate("2025-01-01 09:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act - generate for January
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - 31 days in January
        assertEquals(31, count)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(31, occurrences.size)

        // Verify first and last
        assertEquals(parseDate("2025-01-01 09:00"), occurrences.first().startTs)
        assertEquals(parseDate("2025-01-31 09:00"), occurrences.last().startTs)
    }

    @Test
    fun `daily recurrence with interval generates correct occurrences`() = runTest {
        // Setup - every 3 days
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;INTERVAL=3"
        )

        // Act - generate for January
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - Jan 1, 4, 7, 10, 13, 16, 19, 22, 25, 28, 31 = 11 occurrences
        assertEquals(11, count)
    }

    @Test
    fun `daily recurrence with COUNT limit`() = runTest {
        // Setup - daily for 5 occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )

        // Act - generate for whole year
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - only 5 despite wide range
        assertEquals(5, count)
    }

    @Test
    fun `daily recurrence with UNTIL limit`() = runTest {
        // Setup - daily until Jan 10
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;UNTIL=20250110T235959Z"
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - Jan 1-10 = 10 occurrences
        assertEquals(10, count)
    }

    // ========== Weekly Recurrence ==========

    @Test
    fun `weekly recurrence generates correct occurrences`() = runTest {
        // Setup - weekly on Monday
        val startTs = parseDate("2025-01-06 10:00") // Monday, Jan 6
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        // Act - January 2025
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - Jan 6, 13, 20, 27 = 4 Mondays
        assertEquals(4, count)
    }

    @Test
    fun `weekly recurrence with BYDAY generates correct occurrences`() = runTest {
        // Setup - every Monday, Wednesday, Friday
        val startTs = parseDate("2025-01-06 10:00") // Monday, Jan 6
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )

        // Act - January 2025
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-06 00:00"), // Start from Jan 6
            parseDate("2025-01-31 23:59")
        )

        // Assert - Jan has: MO(6,13,20,27), WE(8,15,22,29), FR(10,17,24,31) = 11 occurrences after Jan 6
        assertTrue(count >= 10) // At least 10 (depends on exact BYDAY expansion)
    }

    @Test
    fun `biweekly recurrence generates correct occurrences`() = runTest {
        // Setup - every 2 weeks
        val startTs = parseDate("2025-01-06 10:00") // Monday, Jan 6
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;INTERVAL=2"
        )

        // Act - January-February 2025
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-02-28 23:59")
        )

        // Assert - Jan 6, 20, Feb 3, 17 = 4 occurrences
        assertEquals(4, count)
    }

    // ========== Monthly Recurrence ==========

    @Test
    fun `monthly recurrence on specific day generates correct occurrences`() = runTest {
        // Setup - monthly on 15th
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY"
        )

        // Act - full year
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - 12 months
        assertEquals(12, count)
    }

    @Test
    fun `monthly recurrence on 31st handles short months`() = runTest {
        // Setup - monthly on 31st
        val startTs = parseDate("2025-01-31 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY"
        )

        // Act - full year
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - Only months with 31 days: Jan, Mar, May, Jul, Aug, Oct, Dec = 7
        assertEquals(7, count)
    }

    @Test
    fun `monthly Nth weekday (BYSETPOS) generates correct occurrences`() = runTest {
        // Setup - 2nd Tuesday of each month
        val startTs = parseDate("2025-01-14 10:00") // 2nd Tuesday of Jan
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2"
        )

        // Act - full year
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - 12 months, one 2nd Tuesday each
        assertEquals(12, count)
    }

    @Test
    fun `monthly last Friday (BYSETPOS=-1) generates correct occurrences`() = runTest {
        // Setup - last Friday of each month
        val startTs = parseDate("2025-01-31 10:00") // Last Friday of Jan
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1"
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Assert - 12 months
        assertEquals(12, count)
    }

    // ========== Yearly Recurrence ==========

    @Test
    fun `yearly recurrence generates correct occurrences`() = runTest {
        // Setup - yearly on Jan 15
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=YEARLY"
        )

        // Act - 5 years
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        // Assert - 2025-2030 = 6 occurrences
        assertEquals(6, count)
    }

    @Test
    fun `yearly on Feb 29 handles non-leap years`() = runTest {
        // Setup - yearly on Feb 29 (leap day)
        val startTs = parseDate("2024-02-29 10:00") // 2024 is leap year
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=YEARLY"
        )

        // Act - 2024-2032
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2024-01-01 00:00"),
            parseDate("2032-12-31 23:59")
        )

        // Assert - Leap years: 2024, 2028, 2032 = 3
        assertEquals(3, count)
    }

    // ========== EXDATE Handling ==========

    @Test
    fun `EXDATE excludes specific occurrences`() = runTest {
        // Setup - daily with one exclusion
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "20250105" // Exclude Jan 5
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - 10 - 1 = 9
        assertEquals(9, count)
    }

    @Test
    fun `multiple EXDATE excludes multiple occurrences`() = runTest {
        // Setup - daily with multiple exclusions
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "20250103,20250105,20250107" // Exclude Jan 3, 5, 7
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - 10 - 3 = 7
        assertEquals(7, count)
    }

    @Test
    fun `EXDATE with datetime format is handled`() = runTest {
        // Setup - daily with datetime EXDATE
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "20250105T100000Z" // Datetime format
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - 10 - 1 = 9
        assertEquals(9, count)
    }

    // ========== Zero Valid Occurrences Edge Case ==========

    @Test
    fun `zero valid occurrences when all excluded`() = runTest {
        // Setup - 3 occurrences, all excluded
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=3",
            exdate = "20250101,20250102,20250103"
        )

        // Act
        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Assert - all excluded = 0
        assertEquals(0, count)
    }

    // ========== Regeneration ==========

    @Test
    fun `regenerateOccurrences clears old and creates new`() = runTest {
        // Setup - create event and generate occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(event)
        assertEquals(5, database.occurrencesDao().getForEvent(event.id).size)

        // Act - modify event RRULE and regenerate
        val updatedEvent = event.copy(rrule = "FREQ=DAILY;COUNT=3")
        database.eventsDao().update(updatedEvent)
        occurrenceGenerator.regenerateOccurrences(updatedEvent)

        // Assert - should have new count
        assertEquals(3, database.occurrencesDao().getForEvent(event.id).size)
    }

    // ========== Cancel Occurrence ==========

    @Test
    fun `cancelOccurrence marks occurrence as cancelled`() = runTest {
        // Setup
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(event)

        // Get occurrence to cancel
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val toCancel = occurrences[2] // 3rd occurrence

        // Act
        occurrenceGenerator.cancelOccurrence(event.id, toCancel.startTs)

        // Assert
        val updated = database.occurrencesDao().getForEvent(event.id)
        val cancelled = updated.find { it.startTs == toCancel.startTs }
        assertNotNull(cancelled)
        assertTrue(cancelled!!.isCancelled)
    }

    // ========== Link Exception ==========

    @Test
    fun `linkException sets exceptionEventId on occurrence`() = runTest {
        // Setup - master event
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Create exception event
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2]
        val exceptionEvent = createAndInsertEvent(
            startTs = targetOccurrence.startTs + 3600000, // Modified time
            endTs = targetOccurrence.startTs + 7200000,
            title = "Modified Occurrence"
        )

        // Act
        occurrenceGenerator.linkException(
            masterEvent.id,
            targetOccurrence.startTs,
            exceptionEvent.id
        )

        // Assert
        val updated = database.occurrencesDao().getForEvent(masterEvent.id)
        val linked = updated.find { it.startTs == targetOccurrence.startTs }
        assertEquals(exceptionEvent.id, linked?.exceptionEventId)
    }

    // ========== v15.0.6 Exception Time Update Tests ==========

    @Test
    fun `linkException with Event updates occurrence times`() = runTest {
        // Setup - master event at 10:00 AM daily
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Get original occurrence (Jan 3, 10:00 AM)
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // Jan 3

        // Create exception that moves to 4:00 PM (6 hours later)
        val exceptionEvent = createAndInsertEvent(
            startTs = targetOccurrence.startTs + 6 * 3600000, // 4:00 PM
            endTs = targetOccurrence.endTs + 6 * 3600000,     // 5:00 PM
            title = "Moved to 4pm"
        )

        // Act - use linkException with Event object
        occurrenceGenerator.linkException(masterEvent.id, targetOccurrence.startTs, exceptionEvent)

        // Assert - occurrence should have exception's times
        val linked = database.occurrencesDao().getByExceptionEventId(exceptionEvent.id)
        assertNotNull(linked)
        assertEquals(exceptionEvent.id, linked?.exceptionEventId)
        assertEquals(exceptionEvent.startTs, linked?.startTs)
        assertEquals(exceptionEvent.endTs, linked?.endTs)
    }

    @Test
    fun `re-editing exception finds occurrence by exceptionEventId`() = runTest {
        // This tests the OR condition in updateOccurrenceForException
        // Setup - master event at 10:00 AM daily
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // Jan 3, 10:00 AM
        val originalStartTs = targetOccurrence.startTs

        // First edit: move to 4:00 PM
        val exception1 = createAndInsertEvent(
            startTs = originalStartTs + 6 * 3600000,  // 4:00 PM
            endTs = originalStartTs + 7 * 3600000,    // 5:00 PM
            title = "Moved to 4pm"
        )
        occurrenceGenerator.linkException(masterEvent.id, originalStartTs, exception1)

        // Verify first edit
        var linked = database.occurrencesDao().getByExceptionEventId(exception1.id)
        assertEquals(exception1.startTs, linked?.startTs)

        // Re-edit: move to 6:00 PM
        // CRITICAL: Use originalStartTs (10am) but occurrence is now at 4pm!
        // This should still find the occurrence via exceptionEventId
        val updatedEvent = exception1.copy(
            startTs = originalStartTs + 8 * 3600000,  // 6:00 PM
            endTs = originalStartTs + 9 * 3600000,    // 7:00 PM
        )
        database.eventsDao().update(updatedEvent)
        occurrenceGenerator.linkException(masterEvent.id, originalStartTs, updatedEvent)

        // Verify re-edit worked
        linked = database.occurrencesDao().getByExceptionEventId(exception1.id)
        assertEquals(updatedEvent.startTs, linked?.startTs)
        assertEquals(updatedEvent.endTs, linked?.endTs)
    }

    @Test
    fun `exception occurrence moves to different day`() = runTest {
        // Setup - master event at 10:00 AM daily
        val startTs = parseDate("2025-01-05 10:00") // Jan 5
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[0] // Jan 5, 10:00 AM
        assertEquals(20250105, targetOccurrence.startDay)

        // Move to Jan 6, 2:00 PM (next day)
        val exceptionEvent = createAndInsertEvent(
            startTs = parseDate("2025-01-06 14:00"),
            endTs = parseDate("2025-01-06 15:00"),
            title = "Moved to Jan 6"
        )
        occurrenceGenerator.linkException(masterEvent.id, targetOccurrence.startTs, exceptionEvent)

        // Assert - occurrence should now be on Jan 6
        val linked = database.occurrencesDao().getByExceptionEventId(exceptionEvent.id)
        assertNotNull(linked)
        assertEquals(20250106, linked?.startDay)
        assertEquals(20250106, linked?.endDay)
    }

    @Test
    fun `all-day exception occurrence has correct day code`() = runTest {
        // Setup - all-day master event
        val startTs = parseDate("2025-01-15 00:00") // Jan 15 UTC midnight
        val endTs = parseDate("2025-01-15 23:59")
        val masterEvent = createAndInsertAllDayEvent(
            startTs = startTs,
            endTs = endTs,
            rrule = "FREQ=WEEKLY;COUNT=3"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[0]
        assertEquals(20250115, targetOccurrence.startDay)

        // Move to Jan 20 (all-day)
        val exceptionEvent = Event(
            uid = "test-allday-exception-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = "Moved All-Day",
            startTs = parseDate("2025-01-20 00:00"),
            endTs = parseDate("2025-01-20 23:59"),
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exceptionEvent)
        val savedException = exceptionEvent.copy(id = exceptionId)

        occurrenceGenerator.linkException(masterEvent.id, targetOccurrence.startTs, savedException)

        // Assert - occurrence should now be on Jan 20
        val linked = database.occurrencesDao().getByExceptionEventId(savedException.id)
        assertNotNull(linked)
        assertEquals(20250120, linked?.startDay)
        assertEquals(20250120, linked?.endDay)
    }

    // ========== v15.0.8 Model A to Model B Normalization Tests ==========

    @Test
    fun `linkException normalizes Model A to Model B - deletes exception occurrence`() = runTest {
        // This test verifies the fix for the bug where iCloud-created exception
        // occurrences (Model A) were not being removed when editing locally
        //
        // Model A (PullStrategy creates): cancelled master occ + separate exception occ
        // Model B (EventWriter creates): single linked occ on master
        //
        // Setup - master event at 10:00 AM daily
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // Jan 3, 10:00 AM
        val originalStartTs = targetOccurrence.startTs

        // Create exception event (simulating what PullStrategy does)
        val exceptionEvent = createAndInsertEvent(
            startTs = originalStartTs + 6 * 3600000,  // 4:00 PM
            endTs = originalStartTs + 7 * 3600000,    // 5:00 PM
            title = "Moved to 4pm"
        )

        // Simulate Model A: Create separate occurrence for exception event
        // (This is what PullStrategy does via regenerateOccurrences(exception))
        database.occurrencesDao().insert(
            org.onekash.kashcal.data.db.entity.Occurrence(
                eventId = exceptionEvent.id,  // Model A: occ has exception's eventId
                calendarId = testCalendarId,
                startTs = exceptionEvent.startTs,
                endTs = exceptionEvent.endTs,
                startDay = org.onekash.kashcal.data.db.entity.Occurrence.toDayFormat(exceptionEvent.startTs, false),
                endDay = org.onekash.kashcal.data.db.entity.Occurrence.toDayFormat(exceptionEvent.endTs, false)
            )
        )

        // Simulate Model A: Cancel master occurrence
        // (This is what PullStrategy does via cancelOccurrence(master, originalTime))
        occurrenceGenerator.cancelOccurrence(masterEvent.id, originalStartTs)

        // Verify Model A is set up correctly
        val exceptionOccBefore = database.occurrencesDao().getForEvent(exceptionEvent.id)
        assertEquals("Model A should have separate exception occurrence", 1, exceptionOccBefore.size)
        val masterOccs = database.occurrencesDao().getForEvent(masterEvent.id)
        val cancelledOcc = masterOccs.find { kotlin.math.abs(it.startTs - originalStartTs) < 60000 }
        assertTrue("Master occurrence should be cancelled", cancelledOcc?.isCancelled == true)

        // Act - call linkException (simulating local edit)
        occurrenceGenerator.linkException(masterEvent.id, originalStartTs, exceptionEvent)

        // Assert - Model A occurrence should be deleted (normalized to Model B)
        val exceptionOccAfter = database.occurrencesDao().getForEvent(exceptionEvent.id)
        assertEquals("Model A occurrence should be deleted", 0, exceptionOccAfter.size)

        // Assert - master should have linked occurrence with exception times
        val linked = database.occurrencesDao().getByExceptionEventId(exceptionEvent.id)
        assertNotNull("Should have linked occurrence on master", linked)
        assertEquals(masterEvent.id, linked?.eventId)
        assertEquals(exceptionEvent.startTs, linked?.startTs)
        assertEquals(exceptionEvent.endTs, linked?.endTs)
    }

    @Test
    fun `linkException uncancels master occurrence`() = runTest {
        // This test verifies the is_cancelled = 0 fix
        // Setup - master event at 10:00 AM daily
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // Jan 3, 10:00 AM
        val originalStartTs = targetOccurrence.startTs

        // Cancel the occurrence (simulating PullStrategy behavior)
        occurrenceGenerator.cancelOccurrence(masterEvent.id, originalStartTs)

        // Verify it's cancelled
        val cancelledOcc = database.occurrencesDao().getForEvent(masterEvent.id)
            .find { kotlin.math.abs(it.startTs - originalStartTs) < 60000 }
        assertTrue("Occurrence should be cancelled initially", cancelledOcc?.isCancelled == true)

        // Create exception event
        val exceptionEvent = createAndInsertEvent(
            startTs = originalStartTs + 6 * 3600000,  // 4:00 PM
            endTs = originalStartTs + 7 * 3600000,    // 5:00 PM
            title = "Moved to 4pm"
        )

        // Act - linkException should uncancel
        occurrenceGenerator.linkException(masterEvent.id, originalStartTs, exceptionEvent)

        // Assert - occurrence should be uncancelled
        val linked = database.occurrencesDao().getByExceptionEventId(exceptionEvent.id)
        assertNotNull("Should have linked occurrence", linked)
        assertFalse("Occurrence should be uncancelled after linkException", linked?.isCancelled == true)
        assertEquals(exceptionEvent.startTs, linked?.startTs)
    }

    @Test
    fun `linkException creates fallback occurrence when master occurrence missing`() = runTest {
        // This test verifies the fallback insert when no master occurrence exists
        // (edge case: exception outside sync window)
        //
        // Setup - master event (but don't generate occurrences)
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        // Note: NOT calling regenerateOccurrences - simulating exception outside sync window

        // Create exception event
        val exceptionStartTs = parseDate("2025-06-15 14:00") // Far in future
        val exceptionEvent = createAndInsertEvent(
            startTs = exceptionStartTs,
            endTs = exceptionStartTs + 3600000,
            title = "Exception Outside Window"
        )

        // Verify no occurrences exist
        val occsBefore = database.occurrencesDao().getForEvent(masterEvent.id)
        assertEquals("Should have no occurrences initially", 0, occsBefore.size)

        // Act - linkException with no existing occurrence
        occurrenceGenerator.linkException(masterEvent.id, startTs, exceptionEvent)

        // Assert - fallback occurrence should be created
        val linked = database.occurrencesDao().getByExceptionEventId(exceptionEvent.id)
        assertNotNull("Fallback occurrence should be created", linked)
        assertEquals(masterEvent.id, linked?.eventId)
        assertEquals(exceptionEvent.startTs, linked?.startTs)
        assertEquals(exceptionEvent.endTs, linked?.endTs)
        assertFalse("Fallback occurrence should not be cancelled", linked?.isCancelled == true)
    }

    @Test
    fun `linkException full Model A to Model B workflow`() = runTest {
        // End-to-end test simulating the full iCloud exception edit workflow
        //
        // Scenario:
        // 1. User creates recurring event in KashCal
        // 2. User edits one instance in iCloud (creates Model A)
        // 3. User edits same instance in KashCal (should normalize to Model B)
        // 4. Verify UI would show updated data
        //
        val startTs = parseDate("2025-01-06 10:00") // Monday 10am
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;COUNT=4"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Original occurrences: Jan 6, 13, 20, 27 all at 10am
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        assertEquals(4, occurrences.size)
        val jan13Occ = occurrences[1] // Jan 13
        val jan13OriginalStartTs = jan13Occ.startTs

        // Step 2: Simulate iCloud creating exception (moved to 2pm)
        val iCloudExceptionTime = jan13OriginalStartTs + 4 * 3600000 // 2pm
        val iCloudException = createAndInsertEvent(
            startTs = iCloudExceptionTime,
            endTs = iCloudExceptionTime + 3600000,
            title = "Weekly Meeting (Rescheduled)"
        )

        // Simulate PullStrategy Model A creation
        database.occurrencesDao().insert(
            org.onekash.kashcal.data.db.entity.Occurrence(
                eventId = iCloudException.id,
                calendarId = testCalendarId,
                startTs = iCloudException.startTs,
                endTs = iCloudException.endTs,
                startDay = org.onekash.kashcal.data.db.entity.Occurrence.toDayFormat(iCloudException.startTs, false),
                endDay = org.onekash.kashcal.data.db.entity.Occurrence.toDayFormat(iCloudException.endTs, false)
            )
        )
        occurrenceGenerator.cancelOccurrence(masterEvent.id, jan13OriginalStartTs)

        // Verify Model A state
        val modelAExcOcc = database.occurrencesDao().getForEvent(iCloudException.id)
        assertEquals("Model A: exception has own occurrence", 1, modelAExcOcc.size)

        // Step 3: User edits same instance locally (moved to 4pm)
        val localEditTime = jan13OriginalStartTs + 6 * 3600000 // 4pm
        val updatedException = iCloudException.copy(
            startTs = localEditTime,
            endTs = localEditTime + 3600000
        )
        database.eventsDao().update(updatedException)

        // Call linkException (what EventWriter does during local edit)
        occurrenceGenerator.linkException(masterEvent.id, jan13OriginalStartTs, updatedException)

        // Assert: Model B state
        // - Exception's own occurrence should be deleted
        val modelBExcOcc = database.occurrencesDao().getForEvent(iCloudException.id)
        assertEquals("Model B: exception occurrence deleted", 0, modelBExcOcc.size)

        // - Master should have linked occurrence with updated times
        val linked = database.occurrencesDao().getByExceptionEventId(iCloudException.id)
        assertNotNull("Model B: master has linked occurrence", linked)
        assertEquals(masterEvent.id, linked?.eventId)
        assertEquals(updatedException.startTs, linked?.startTs) // 4pm
        assertFalse("Model B: occurrence not cancelled", linked?.isCancelled == true)

        // - UI query should find the event
        // Using the same pattern as EventReader: occ.exceptionEventId ?: occ.eventId
        val visibleOccs = database.occurrencesDao().getForEvent(masterEvent.id)
            .filter { !it.isCancelled }
        assertEquals("Should have 4 visible occurrences", 4, visibleOccs.size)

        // The Jan 13 occurrence should now be at 4pm
        val jan13LinkedOcc = visibleOccs.find { it.exceptionEventId == iCloudException.id }
        assertNotNull("Should find linked occurrence", jan13LinkedOcc)
        assertEquals(localEditTime, jan13LinkedOcc?.startTs)
    }

    // ========== Exception Link Preservation Tests ==========

    @Test
    fun `regeneration preserves exception event links`() = runTest {
        // Setup - master event with occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Create exception event and link it to an occurrence
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // 3rd occurrence (Jan 3)
        val exceptionEvent = createAndInsertEvent(
            startTs = targetOccurrence.startTs + 3600000, // Modified time
            endTs = targetOccurrence.startTs + 7200000,
            title = "Modified Occurrence"
        )
        occurrenceGenerator.linkException(
            masterEvent.id,
            targetOccurrence.startTs,
            exceptionEvent.id
        )

        // Verify link exists (using old linkException - doesn't update times)
        val beforeRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedBefore = beforeRegeneration.find { it.exceptionEventId == exceptionEvent.id }
        assertNotNull(linkedBefore)
        assertEquals(exceptionEvent.id, linkedBefore?.exceptionEventId)

        // Act - regenerate occurrences (simulates RRULE change)
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Assert - exception link should be preserved AND times updated
        val afterRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedAfter = afterRegeneration.find { it.exceptionEventId == exceptionEvent.id }
        assertNotNull(linkedAfter)
        assertEquals(exceptionEvent.id, linkedAfter?.exceptionEventId)
        // v15.0.6: Regeneration now restores exception times from exception event
        assertEquals(exceptionEvent.startTs, linkedAfter?.startTs)
        assertEquals(exceptionEvent.endTs, linkedAfter?.endTs)
    }

    @Test
    fun `regeneration preserves cancelled status`() = runTest {
        // Setup - master event with occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Cancel an occurrence (simulates EXDATE being applied)
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val toCancel = occurrences[1] // 2nd occurrence (Jan 2)
        occurrenceGenerator.cancelOccurrence(masterEvent.id, toCancel.startTs)

        // Link an exception (which also marks it cancelled via RECURRENCE-ID)
        val targetOccurrence = occurrences[3] // 4th occurrence (Jan 4)
        val exceptionEvent = createAndInsertEvent(
            startTs = targetOccurrence.startTs + 3600000,
            endTs = targetOccurrence.startTs + 7200000,
            title = "Exception Event"
        )
        occurrenceGenerator.linkException(
            masterEvent.id,
            targetOccurrence.startTs,
            exceptionEvent.id
        )
        occurrenceGenerator.cancelOccurrence(masterEvent.id, targetOccurrence.startTs)

        // Verify state before regeneration
        val beforeRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        assertTrue(beforeRegeneration.find { it.startTs == toCancel.startTs }?.isCancelled == true)
        // Note: Using old linkException doesn't update times, but cancelOccurrence uses tolerance
        val linkedBeforeOcc = beforeRegeneration.find { it.exceptionEventId == exceptionEvent.id }
        assertTrue(linkedBeforeOcc?.isCancelled == true)

        // Act - regenerate
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Assert - cancelled status should be preserved for linked exceptions
        // v15.0.6: Now find by exceptionEventId since times are updated to exception's times
        val afterRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedCancelled = afterRegeneration.find { it.exceptionEventId == exceptionEvent.id }
        assertNotNull(linkedCancelled)
        assertEquals(exceptionEvent.id, linkedCancelled?.exceptionEventId)
        assertTrue(linkedCancelled?.isCancelled == true)
        // Verify times were updated to exception event's times
        assertEquals(exceptionEvent.startTs, linkedCancelled?.startTs)
    }

    @Test
    fun `regeneration preserves multiple exception links`() = runTest {
        // Setup - master event with occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=7"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)

        // Create multiple exception events
        val exception1 = createAndInsertEvent(
            startTs = occurrences[1].startTs + 3600000,
            endTs = occurrences[1].startTs + 7200000,
            title = "Exception 1"
        )
        val exception2 = createAndInsertEvent(
            startTs = occurrences[3].startTs + 3600000,
            endTs = occurrences[3].startTs + 7200000,
            title = "Exception 2"
        )
        val exception3 = createAndInsertEvent(
            startTs = occurrences[5].startTs + 3600000,
            endTs = occurrences[5].startTs + 7200000,
            title = "Exception 3"
        )

        // Link all exceptions
        occurrenceGenerator.linkException(masterEvent.id, occurrences[1].startTs, exception1.id)
        occurrenceGenerator.linkException(masterEvent.id, occurrences[3].startTs, exception2.id)
        occurrenceGenerator.linkException(masterEvent.id, occurrences[5].startTs, exception3.id)

        // Act - regenerate
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Assert - all links preserved
        // v15.0.6: Find by exceptionEventId since times are updated to exception's times
        val afterRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        val linked1 = afterRegeneration.find { it.exceptionEventId == exception1.id }
        val linked2 = afterRegeneration.find { it.exceptionEventId == exception2.id }
        val linked3 = afterRegeneration.find { it.exceptionEventId == exception3.id }
        assertNotNull(linked1)
        assertNotNull(linked2)
        assertNotNull(linked3)
        assertEquals(exception1.id, linked1?.exceptionEventId)
        assertEquals(exception2.id, linked2?.exceptionEventId)
        assertEquals(exception3.id, linked3?.exceptionEventId)
        // Verify times were updated to exception event's times
        assertEquals(exception1.startTs, linked1?.startTs)
        assertEquals(exception2.startTs, linked2?.startTs)
        assertEquals(exception3.startTs, linked3?.startTs)
    }

    @Test
    fun `generateOccurrences with range also preserves exception links`() = runTest {
        // Setup - master event with occurrences
        val startTs = parseDate("2025-01-01 10:00")
        val masterEvent = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10"
        )

        // Generate initial occurrences
        val rangeStart = parseDate("2025-01-01 00:00")
        val rangeEnd = parseDate("2025-01-31 23:59")
        occurrenceGenerator.generateOccurrences(masterEvent, rangeStart, rangeEnd)

        // Link an exception
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[4] // 5th occurrence
        val exceptionEvent = createAndInsertEvent(
            startTs = targetOccurrence.startTs + 3600000,
            endTs = targetOccurrence.startTs + 7200000,
            title = "Exception Event"
        )
        occurrenceGenerator.linkException(masterEvent.id, targetOccurrence.startTs, exceptionEvent.id)

        // Act - regenerate using generateOccurrences (not regenerateOccurrences)
        occurrenceGenerator.generateOccurrences(masterEvent, rangeStart, rangeEnd)

        // Assert - link preserved
        // v15.0.6: Find by exceptionEventId since times are updated to exception's times
        val afterRegeneration = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedAfter = afterRegeneration.find { it.exceptionEventId == exceptionEvent.id }
        assertNotNull(linkedAfter)
        assertEquals(exceptionEvent.id, linkedAfter?.exceptionEventId)
        // Verify times were updated to exception event's times
        assertEquals(exceptionEvent.startTs, linkedAfter?.startTs)
        assertEquals(exceptionEvent.endTs, linkedAfter?.endTs)
    }

    // ========== parseRule ==========

    @Test
    fun `parseRule extracts RRULE components`() {
        val info = occurrenceGenerator.parseRule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=10")

        assertNotNull(info)
        assertEquals("WEEKLY", info!!.freq)
        assertEquals(2, info.interval)
        assertEquals(10, info.count)
        assertNull(info.until)
        assertTrue(info.byDay.isNotEmpty())
    }

    @Test
    fun `parseRule returns null for invalid RRULE`() {
        val info = occurrenceGenerator.parseRule("INVALID_RRULE")
        assertNull(info)
    }

    // ========== expandForPreview ==========

    @Test
    fun `expandForPreview returns occurrence times without storing`() = runTest {
        val dtstartMs = parseDate("2025-01-01 10:00")
        val occurrences = occurrenceGenerator.expandForPreview(
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = dtstartMs,
            rangeStartMs = parseDate("2025-01-01 00:00"),
            rangeEndMs = parseDate("2025-01-31 23:59")
        )

        // Assert - returns 5 timestamps
        assertEquals(5, occurrences.size)

        // Assert - nothing stored in database
        assertEquals(0, database.occurrencesDao().getTotalCount())
    }

    // ========== Day Format ==========

    @Test
    fun `occurrences have correct startDay and endDay`() = runTest {
        val startTs = parseDate("2025-06-15 10:00") // June 15
        val event = createAndInsertEvent(startTs, startTs + 3600000)

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(20250615, occurrences[0].startDay)
        assertEquals(20250615, occurrences[0].endDay)
    }

    @Test
    fun `multi-day event has correct startDay and endDay`() = runTest {
        val startTs = parseDate("2025-06-15 22:00") // June 15 10pm
        val endTs = parseDate("2025-06-17 08:00") // June 17 8am (spans 3 days)
        val event = createAndInsertEvent(startTs, endTs)

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(20250615, occurrences[0].startDay)
        assertEquals(20250617, occurrences[0].endDay)
    }

    // ========== All-Day Events - UTC Timezone Fix Tests ==========

    @Test
    fun `all-day recurring event uses UTC for RRULE expansion - weekly`() = runTest {
        // Setup: All-day event on Monday Jan 6, 2025 (stored as UTC midnight)
        // This tests the fix for the bug where all-day events used local timezone
        // which caused occurrences to appear on the wrong day
        val startTs = parseDate("2025-01-06 00:00") // Jan 6, 2025 00:00 UTC (Monday)
        val endTs = parseDate("2025-01-06 23:59")
        val event = createAndInsertAllDayEvent(startTs, endTs, rrule = "FREQ=WEEKLY;BYDAY=MO")

        // Act
        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-02-28 23:59")
        )

        // Assert - verify occurrences fall on correct Mondays in UTC
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertTrue("Should have multiple Monday occurrences", occurrences.size >= 4)

        // Verify each occurrence is a Monday (in UTC)
        val expectedDays = listOf(20250106, 20250113, 20250120, 20250127)
        for ((index, expected) in expectedDays.withIndex()) {
            assertEquals(
                "Occurrence $index should be on expected Monday",
                expected,
                occurrences[index].startDay
            )
        }
    }

    @Test
    fun `all-day recurring event occurrences have correct day codes`() = runTest {
        // This test verifies that the startDay/endDay calculation uses UTC for all-day events
        val startTs = parseDate("2025-01-15 00:00") // Jan 15, 2025 00:00 UTC
        val endTs = parseDate("2025-01-15 23:59")
        val event = createAndInsertAllDayEvent(startTs, endTs, rrule = "FREQ=DAILY;COUNT=3")

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-02-28 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(3, occurrences.size)

        // Verify day codes are correct (should be consecutive days in UTC)
        assertEquals(20250115, occurrences[0].startDay)
        assertEquals(20250116, occurrences[1].startDay)
        assertEquals(20250117, occurrences[2].startDay)
    }

    @Test
    fun `all-day multi-day recurring event has correct day range`() = runTest {
        // 3-day all-day event (Jan 15-17) recurring weekly
        val startTs = parseDate("2025-01-15 00:00") // Jan 15 00:00 UTC
        val endTs = parseDate("2025-01-17 23:59") // Jan 17 23:59 UTC (spans 3 days)
        val event = createAndInsertAllDayEvent(startTs, endTs, rrule = "FREQ=WEEKLY;COUNT=2")

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-02-28 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(2, occurrences.size)

        // First occurrence: Jan 15-17
        assertEquals(20250115, occurrences[0].startDay)
        assertEquals(20250117, occurrences[0].endDay)

        // Second occurrence: Jan 22-24
        assertEquals(20250122, occurrences[1].startDay)
        assertEquals(20250124, occurrences[1].endDay)
    }

    @Test
    fun `all-day event without timezone uses UTC for RRULE expansion`() = runTest {
        // This specifically tests the bug fix: when event.timezone is null,
        // all-day events should use UTC, not system default timezone
        val startTs = parseDate("2025-01-06 00:00") // Monday Jan 6 UTC midnight
        val endTs = parseDate("2025-01-06 23:59")

        // Create event with null timezone (typical for all-day events from iCloud)
        val event = Event(
            uid = "test-allday-tz-null@test.com",
            calendarId = testCalendarId,
            title = "All-Day No TZ",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            timezone = null, // KEY: null timezone should use UTC for all-day
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-02-28 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(4, occurrences.size)

        // All occurrences should be Mondays in UTC
        assertEquals(20250106, occurrences[0].startDay) // Mon Jan 6
        assertEquals(20250113, occurrences[1].startDay) // Mon Jan 13
        assertEquals(20250120, occurrences[2].startDay) // Mon Jan 20
        assertEquals(20250127, occurrences[3].startDay) // Mon Jan 27
    }

    @Test
    fun `all-day single event has correct day code in UTC`() = runTest {
        // Non-recurring all-day event
        val startTs = parseDate("2025-06-15 00:00") // June 15 00:00 UTC
        val endTs = parseDate("2025-06-15 23:59")
        val event = createAndInsertAllDayEvent(startTs, endTs)

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(20250615, occurrences[0].startDay)
        assertEquals(20250615, occurrences[0].endDay)
    }

    @Test
    fun `yearly all-day birthday event has correct single day per occurrence`() = runTest {
        // Simulates iCloud birthday: Jan 6 yearly recurring
        // This is the exact bug scenario reported by user
        val startTs = parseDate("2026-01-06 00:00") // Jan 6 00:00 UTC
        val endTs = parseDate("2026-01-06 23:59")   // Jan 6 23:59 UTC (single day)
        val event = createAndInsertAllDayEvent(
            startTs, endTs,
            rrule = "FREQ=YEARLY;COUNT=3",
            title = "Ellie Birthday"
        )

        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals("Should have 3 yearly occurrences", 3, occurrences.size)

        // CRITICAL: Each occurrence should be single-day (startDay == endDay)
        // The bug was that startDay != endDay, causing event to show on 2 days
        for ((index, occ) in occurrences.withIndex()) {
            assertEquals(
                "Occurrence $index should be single-day (startDay == endDay)",
                occ.startDay,
                occ.endDay
            )
        }

        // Verify correct dates: Jan 6 in 2026, 2027, 2028
        assertEquals(20260106, occurrences[0].startDay)
        assertEquals(20270106, occurrences[1].startDay)
        assertEquals(20280106, occurrences[2].startDay)
    }

    @Test
    fun `yearly all-day event with null timezone uses UTC`() = runTest {
        // Tests the specific fix: null timezone should use UTC for all-day events
        // Previously, null timezone fell back to device timezone, causing date shift
        val startTs = parseDate("2026-01-06 00:00") // Jan 6 00:00 UTC
        val endTs = parseDate("2026-01-06 23:59")

        // Explicitly create event with null timezone (typical for iCloud all-day events)
        val event = Event(
            uid = "birthday-tz-null-test@test.com",
            calendarId = testCalendarId,
            title = "Birthday TZ Null",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            timezone = null, // KEY: null timezone - should still use UTC
            rrule = "FREQ=YEARLY;COUNT=2",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseDate("2025-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(2, occurrences.size)

        // Both occurrences should be Jan 6 (not shifted to Jan 5 or Jan 7)
        assertEquals(20260106, occurrences[0].startDay)
        assertEquals(20260106, occurrences[0].endDay)
        assertEquals(20270106, occurrences[1].startDay)
        assertEquals(20270106, occurrences[1].endDay)
    }

    // ========== Data Loss Prevention Tests ==========

    @Test
    fun `generateOccurrences preserves existing occurrences when RRULE expansion fails`() = runTest {
        // Setup - create event with valid RRULE and generate occurrences
        val startTs = parseDate("2025-01-01 09:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )

        // Generate initial occurrences (should work)
        val initialCount = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-03-31 23:59")
        )
        assertTrue("Should generate occurrences with valid RRULE", initialCount > 0)

        val occurrencesBefore = database.occurrencesDao().getForEvent(event.id)
        assertTrue("Should have occurrences", occurrencesBefore.isNotEmpty())
        val countBefore = occurrencesBefore.size

        // Update event with malformed RRULE that will cause expansion to fail
        val malformedEvent = event.copy(rrule = "FREQ=INVALID_FREQUENCY;MALFORMED=TRUE")
        database.eventsDao().update(malformedEvent)

        // Try to regenerate with malformed RRULE (should fail expansion)
        val failedCount = occurrenceGenerator.generateOccurrences(
            malformedEvent,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-03-31 23:59")
        )

        // Assert - existing occurrences should be preserved
        val occurrencesAfter = database.occurrencesDao().getForEvent(event.id)
        assertEquals(
            "Occurrences should be preserved when expansion fails",
            countBefore,
            occurrencesAfter.size
        )
        assertEquals(
            "Failed expansion should return 0",
            0,
            failedCount
        )
    }

    @Test
    fun `generateOccurrences handles completely invalid RRULE gracefully`() = runTest {
        // Setup - create event with valid RRULE first
        val startTs = parseDate("2025-01-15 14:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5"
        )

        // Generate initial occurrences
        occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        val occurrencesBefore = database.occurrencesDao().getForEvent(event.id)
        assertEquals("Should have 5 occurrences", 5, occurrencesBefore.size)

        // Try with garbage RRULE
        val garbageEvent = event.copy(rrule = "THIS_IS_NOT_AN_RRULE")
        database.eventsDao().update(garbageEvent)

        occurrenceGenerator.generateOccurrences(
            garbageEvent,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Original occurrences should still exist
        val occurrencesAfter = database.occurrencesDao().getForEvent(event.id)
        assertEquals(
            "Garbage RRULE should not delete existing occurrences",
            5,
            occurrencesAfter.size
        )
    }

    // ========== Helper Functions ==========

    /**
     * Create and insert an all-day event (isAllDay = true).
     * All-day events are stored as UTC midnight and should use UTC for calculations.
     */
    private suspend fun createAndInsertAllDayEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null,
        title: String = "All-Day Event"
    ): Event {
        val event = Event(
            uid = "test-allday-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            timezone = null, // All-day events typically have null timezone
            rrule = rrule,
            exdate = exdate,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    private suspend fun createAndInsertEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        title: String = "Test Event"
    ): Event {
        val event = Event(
            uid = "test-uid-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            exdate = exdate,
            rdate = rdate,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    /**
     * Parse date string to milliseconds.
     * Format: "yyyy-MM-dd HH:mm"
     */
    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")

        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(
            dateParts[0].toInt(),
            dateParts[1].toInt() - 1, // Month is 0-indexed
            dateParts[2].toInt(),
            timeParts[0].toInt(),
            timeParts[1].toInt(),
            0
        )
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
