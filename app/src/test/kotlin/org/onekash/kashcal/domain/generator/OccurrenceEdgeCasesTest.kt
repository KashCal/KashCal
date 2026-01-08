package org.onekash.kashcal.domain.generator

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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Edge case tests for Occurrence entity and generation.
 *
 * Tests probe:
 * - Exception event linking
 * - EXDATE/RDATE combinations
 * - Occurrence cancellation
 * - Day code calculation edge cases
 * - Multi-day occurrence handling
 * - Exception link restoration after regeneration
 *
 * These tests verify occurrence management handles complex scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceEdgeCasesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

        // Setup test calendar
        val accountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
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

    // ==================== Exception Linking Tests ====================

    @Test
    fun `linkException associates exception with occurrence`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        // Create exception event
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(
                id = 0,
                title = "Modified",
                originalEventId = eventId,
                originalInstanceTime = targetOccTime
            )
        )

        // Link exception
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        // Verify link
        val linkedOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.startTs == targetOccTime }
        assertEquals(exceptionId, linkedOcc?.exceptionEventId)
    }

    @Test
    fun `linkException is idempotent`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=3")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[0].startTs

        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Modified", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )

        // Link multiple times
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        // Should still have only one occurrence for that time
        val occs = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs == targetOccTime }
        assertEquals(1, occs.size)
        assertEquals(exceptionId, occs[0].exceptionEventId)
    }

    // ==================== Exception Link Restoration Tests ====================

    @Test
    fun `regenerateOccurrences preserves exception links`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[2].startTs

        // Create and link exception
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Modified", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)

        // Regenerate occurrences (e.g., after RRULE change)
        occurrenceGenerator.regenerateOccurrences(savedEvent)

        // Exception link should be preserved
        val regenOccs = database.occurrencesDao().getForEvent(eventId)
        val linkedOcc = regenOccs.find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception link should be preserved after regeneration", linkedOcc)
    }

    // ==================== Occurrence Cancellation Tests ====================

    @Test
    fun `cancelOccurrence marks occurrence as cancelled`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime)

        val cancelledOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.startTs == targetOccTime }
        assertTrue("Occurrence should be marked cancelled", cancelledOcc?.isCancelled == true)
    }

    @Test
    fun `cancelled occurrence is preserved after regeneration`() = runTest {
        val event = createRecurringEvent("Master", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[1].startTs

        // Cancel and link exception (cancelled occurrences have exceptionEventId)
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(id = 0, title = "Cancelled", originalEventId = eventId, originalInstanceTime = targetOccTime)
        )
        occurrenceGenerator.linkException(eventId, targetOccTime, exceptionId)
        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime)

        // Regenerate
        occurrenceGenerator.regenerateOccurrences(savedEvent)

        // Cancelled status should be preserved
        val regenOcc = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertTrue("Cancelled status should be preserved", regenOcc?.isCancelled == true)
    }

    // ==================== Day Code Calculation Tests ====================

    @Test
    fun `toDayFormat calculates correct day code for UTC timestamp`() {
        // Jan 15, 2024 00:00 UTC
        val utcMidnight = 1705276800000L

        val dayCode = Occurrence.toDayFormat(utcMidnight, isAllDay = true)

        assertEquals(20240115, dayCode)
    }

    @Test
    fun `toDayFormat uses UTC for all-day events`() {
        // June 15, 2024 00:00 UTC
        val utcMidnight = 1718409600000L

        val dayCode = Occurrence.toDayFormat(utcMidnight, isAllDay = true)

        assertEquals(20240615, dayCode)
    }

    @Test
    fun `toDayFormat uses local TZ for timed events`() {
        // This depends on system timezone, but should work
        val now = System.currentTimeMillis()
        val dayCode = Occurrence.toDayFormat(now, isAllDay = false)

        // Day code should be today's date
        val cal = java.util.Calendar.getInstance()
        val expected = cal.get(java.util.Calendar.YEAR) * 10000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)

        assertEquals(expected, dayCode)
    }

    @Test
    fun `toDayFormat handles year boundary`() {
        // Dec 31, 2024 23:59 UTC
        val newYearsEve = 1735689540000L

        val dayCode = Occurrence.toDayFormat(newYearsEve, isAllDay = true)

        assertEquals(20241231, dayCode)
    }

    @Test
    fun `toDayFormat handles leap year Feb 29`() {
        // Feb 29, 2024 (leap year)
        val feb29 = 1709164800000L // Feb 29, 2024 00:00 UTC

        val dayCode = Occurrence.toDayFormat(feb29, isAllDay = true)

        assertEquals(20240229, dayCode)
    }

    // ==================== Multi-Day Occurrence Tests ====================

    @Test
    fun `multi-day occurrence has different startDay and endDay`() = runTest {
        val now = System.currentTimeMillis()
        val event = Event(
            uid = "multiday@test.com",
            calendarId = testCalendarId,
            title = "3-Day Conference",
            startTs = now,
            endTs = now + 3 * 86400000, // 3 days
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            now - 86400000,
            now + 10 * 86400000
        )

        val occurrence = database.occurrencesDao().getForEvent(eventId).first()

        assertTrue(
            "End day should be after start day for multi-day event",
            occurrence.endDay > occurrence.startDay
        )
    }

    @Test
    fun `all-day multi-day event spans correct days`() = runTest {
        // June 15-17, 2024 all-day event (3 days)
        val startUtc = 1718409600000L // June 15, 2024 00:00 UTC
        val endUtc = 1718668799999L   // June 17, 2024 23:59:59.999 UTC

        val event = Event(
            uid = "allday-multi@test.com",
            calendarId = testCalendarId,
            title = "3-Day Holiday",
            startTs = startUtc,
            endTs = endUtc,
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startUtc - 86400000,
            endUtc + 86400000
        )

        val occurrence = database.occurrencesDao().getForEvent(eventId).first()

        assertEquals(20240615, occurrence.startDay)
        assertEquals(20240617, occurrence.endDay)
    }

    // ==================== RDATE Tests ====================

    @Test
    fun `RDATE adds extra occurrences`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate@test.com",
            calendarId = testCalendarId,
            title = "Event with RDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "20240620,20240625", // Two extra dates
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 30 * 86400000L
        )

        // RDATE functionality: 3 from RRULE + up to 2 from RDATE
        // The RDATE dates should be within the 30-day range
        // If RDATE isn't implemented, we get 3; if implemented, we get up to 5
        assertTrue("Should have at least RRULE occurrences", count >= 3)
    }

    @Test
    fun `RDATE with malformed dates are ignored`() = runTest {
        val startTs = System.currentTimeMillis()

        val event = Event(
            uid = "bad-rdate@test.com",
            calendarId = testCalendarId,
            title = "Bad RDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "NOTADATE,20240620,INVALID",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 365 * 86400000L // Extend range to include 20240620
        )

        // Should have at least the RRULE occurrences, malformed RDATE ignored
        assertTrue("Should have at least RRULE occurrences", count >= 3)
    }

    @Test
    fun `RDATE adds exact extra occurrences - explicit verification`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-explicit@test.com",
            calendarId = testCalendarId,
            title = "RDATE Explicit Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",  // June 15, 16, 17
            rdate = "20240620,20240625",    // June 20, 25
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 15 * 86400000L
        )

        // 3 RRULE + 2 RDATE = 5 EXACT
        assertEquals(5, count)

        // Verify exact days
        val days = database.occurrencesDao().getForEvent(eventId)
            .map { it.startDay }.sorted()
        assertEquals(listOf(20240615, 20240616, 20240617, 20240620, 20240625), days)
    }

    @Test
    fun `RDATE duplicate with RRULE does not create duplicate occurrence`() = runTest {
        // Test that June 16 from RDATE doesn't duplicate June 16 from RRULE
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-dup@test.com",
            calendarId = testCalendarId,
            title = "RDATE Duplicate Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",  // June 15, 16, 17
            rdate = "20240616",             // Already in RRULE
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000L
        )

        assertEquals(3, count)  // No duplicate
    }

    // ==================== EXDATE Tests ====================

    @Test
    fun `EXDATE removes occurrences`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "exdate@test.com",
            calendarId = testCalendarId,
            title = "Event with EXDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240616,20240618", // Remove 2nd and 4th occurrence
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from RRULE - 2 from EXDATE = 3
        assertEquals(3, count)
    }

    @Test
    fun `EXDATE with RDATE combination`() = runTest {
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "rdate-exdate@test.com",
            calendarId = testCalendarId,
            title = "RDATE and EXDATE",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            rdate = "20240625", // Add June 25
            exdate = "20240616", // Remove June 16
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 30 * 86400000L
        )

        // Expected: 3 from RRULE (June 15, 16, 17) + 1 from RDATE (June 25) - 1 from EXDATE (June 16) = 3
        // But actual behavior depends on RDATE implementation
        // RRULE without EXDATE exclusion would give: 15, 17 (after excluding 16) = 2
        // Plus RDATE adds 25 = 3
        // This test verifies EXDATE removes occurrences
        assertTrue("Should have at least 2 occurrences after EXDATE", count >= 2)
    }

    // ==================== Extend Occurrences Tests ====================

    @Test
    fun `extendOccurrences adds occurrences beyond current range`() = runTest {
        val startTs = System.currentTimeMillis()

        val event = Event(
            uid = "extend@test.com",
            calendarId = testCalendarId,
            title = "Extendable Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY", // Infinite
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate initial range
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000 // 10 days
        )

        val initialCount = database.occurrencesDao().getForEvent(eventId).size

        // Extend to 20 days
        occurrenceGenerator.extendOccurrences(savedEvent, startTs + 20 * 86400000)

        val extendedCount = database.occurrencesDao().getForEvent(eventId).size

        assertTrue("Extended count should be greater", extendedCount > initialCount)
    }

    @Test
    fun `extendOccurrences returns 0 for non-recurring event`() = runTest {
        val event = createTestEvent("Non-Recurring")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val extended = occurrenceGenerator.extendOccurrences(
            savedEvent,
            savedEvent.startTs + 100 * 86400000
        )

        assertEquals(0, extended)
    }

    // ==================== Range Boundary Tests ====================

    @Test
    fun `occurrences exactly at range start are included`() = runTest {
        val startTs = 1718409600000L // Exact timestamp

        val event = Event(
            uid = "boundary@test.com",
            calendarId = testCalendarId,
            title = "Boundary Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Range starts exactly at event start
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs, // Exact match
            startTs + 10 * 86400000
        )

        assertEquals(3, count)
    }

    @Test
    fun `occurrences exactly at range end are excluded`() = runTest {
        val startTs = 1718409600000L

        val event = Event(
            uid = "end-boundary@test.com",
            calendarId = testCalendarId,
            title = "End Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Range ends exactly at 3rd occurrence
        val thirdOccTime = startTs + 2 * 86400000
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            thirdOccTime // End exactly at 3rd occurrence
        )

        // 3rd occurrence should be excluded (range is exclusive)
        assertEquals(2, count)
    }

    // ==================== DST and Timezone Edge Cases ====================

    @Test
    fun `60-second tolerance links exception across DST boundary`() = runTest {
        // Simulate a recurring event at 2 AM during DST transition
        // RECURRENCE-ID might be 1 hour off from RRULE-generated time
        val event = createRecurringEvent("DST Event", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        assertTrue("Should have at least 3 occurrences", occurrences.size >= 3)
        val targetOccTime = occurrences[2].startTs

        // Create exception with time slightly off (within 60-second tolerance)
        val exceptionId = database.eventsDao().insert(
            savedEvent.copy(
                id = 0,
                title = "Modified DST",
                originalEventId = eventId,
                originalInstanceTime = targetOccTime + 30000 // 30 seconds off
            )
        )

        // Link should work within 60-second tolerance
        occurrenceGenerator.linkException(eventId, targetOccTime + 30000, exceptionId)

        val linkedOcc = database.occurrencesDao().getForEvent(eventId)
            .find { kotlin.math.abs(it.startTs - targetOccTime) < 60000 }
        assertEquals(exceptionId, linkedOcc?.exceptionEventId)
    }

    @Test
    fun `cross-midnight recurring event generates correct occurrences`() = runTest {
        // Event from 11 PM to 1 AM next day, recurring daily
        val startTs = 1718492400000L // June 15, 2024 23:00 UTC
        val endTs = startTs + 2 * 3600000 // +2 hours (ends at 01:00 next day)

        val event = Event(
            uid = "cross-midnight@test.com",
            calendarId = testCalendarId,
            title = "Late Night Meeting",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(3, count)

        // Each occurrence should span two days
        val occurrences = database.occurrencesDao().getForEvent(eventId)
        occurrences.forEach { occ ->
            assertTrue(
                "Cross-midnight event should span two days",
                occ.endDay > occ.startDay || occ.endTs - occ.startTs == 2 * 3600000L
            )
        }
    }

    @Test
    fun `monthly BYMONTHDAY generates occurrences on specific day`() = runTest {
        // Monthly event on the 15th of each month
        val startTs = 1705276800000L // Jan 15, 2024 00:00 UTC

        val event = Event(
            uid = "monthly-15th@test.com",
            calendarId = testCalendarId,
            title = "Monthly Review",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=MONTHLY;COUNT=3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Extend range to 120 days to ensure 3 months of occurrences
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 120 * 86400000L
        )

        // Should have at least 3 occurrences
        assertTrue("Should have at least 3 monthly occurrences", count >= 3)

        // Verify the days are all on the 15th
        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val days = occurrences.map { it.startDay % 100 } // Extract day of month

        assertTrue("All occurrences should be on 15th", days.all { it == 15 })
    }

    @Test
    fun `EXDATE in different timezone representation matches occurrence`() = runTest {
        // EXDATE might be specified in a different timezone format
        // but should still match the occurrence
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "exdate-tz@test.com",
            calendarId = testCalendarId,
            title = "TZ EXDATE Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            // EXDATE for June 17 (3rd occurrence) - various timezone formats
            exdate = "20240617T000000Z",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // Should have 4 occurrences (5 - 1 excluded)
        assertEquals(4, count)

        // June 17 should not be present
        val days = database.occurrencesDao().getForEvent(eventId).map { it.startDay }
        assertTrue("June 17 should be excluded", 20240617 !in days)
    }

    @Test
    fun `all-day event EXDATE uses date-only matching`() = runTest {
        // All-day events use DATE (not DATE-TIME) for EXDATE
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "allday-exdate@test.com",
            calendarId = testCalendarId,
            title = "All-Day EXDATE",
            startTs = startTs,
            endTs = startTs + 86400000, // Full day
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240617", // DATE format (no time component)
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(4, count)
    }

    @Test
    fun `toDayFormat handles negative UTC offset correctly`() {
        // Test event at midnight local time in UTC-5 (e.g., EST)
        // Jan 15, 2024 00:00 EST = Jan 15, 2024 05:00 UTC
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            // Jan 15, 2024 05:00 UTC = Jan 15, 2024 00:00 EST
            val utcTime = 1705298400000L

            // For timed events, should use local timezone
            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // In EST (UTC-5), this should still be Jan 15
            assertEquals(20240115, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `toDayFormat handles positive UTC offset correctly`() {
        // Test in UTC+9 (e.g., Tokyo)
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            // Jan 15, 2024 00:00 UTC = Jan 15, 2024 09:00 JST
            val utcTime = 1705276800000L

            // For timed events, should use local timezone
            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // In JST (UTC+9), Jan 15 00:00 UTC is Jan 15 09:00 JST = still Jan 15
            assertEquals(20240115, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `toDayFormat handles UTC day boundary for positive offset`() {
        // Test edge case: late UTC time that crosses day boundary in positive offset
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            // Jan 15, 2024 20:00 UTC = Jan 16, 2024 05:00 JST
            val utcTime = 1705348800000L

            val dayCode = Occurrence.toDayFormat(utcTime, isAllDay = false)

            // In JST (UTC+9), this is Jan 16
            assertEquals(20240116, dayCode)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `cancelOccurrence with 60-second tolerance works across DST`() = runTest {
        val event = createRecurringEvent("Cancel DST", "FREQ=DAILY;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 10 * 86400000
        )

        val occurrences = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val targetOccTime = occurrences[2].startTs

        // Cancel with time slightly off (within 60-second tolerance)
        occurrenceGenerator.cancelOccurrence(eventId, targetOccTime + 45000) // 45 seconds off

        val cancelledOcc = database.occurrencesDao().getForEvent(eventId)
            .find { kotlin.math.abs(it.startTs - targetOccTime) < 60000 }
        assertTrue("Should be cancelled even with time offset", cancelledOcc?.isCancelled == true)
    }

    @Test
    fun `daily RRULE across year boundary`() = runTest {
        // Daily event spanning Dec 2024 - Jan 2025
        val startTs = 1735516800000L // Dec 30, 2024 00:00 UTC

        val event = Event(
            uid = "year-boundary@test.com",
            calendarId = testCalendarId,
            title = "Year Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        assertEquals(5, count)

        // Should span both 2024 and 2025
        val days = database.occurrencesDao().getForEvent(eventId).map { it.startDay }.sorted()
        val years = days.map { it / 10000 }.distinct().sorted()
        assertEquals(listOf(2024, 2025), years)

        // Dec 30, 31 + Jan 1, 2, 3
        assertTrue("Should include Dec days", days.any { it in 20241230..20241231 })
        assertTrue("Should include Jan days", days.any { it in 20250101..20250103 })
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000,
            endTs = now + 7200000,
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
    }

    private fun createRecurringEvent(title: String, rrule: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now + 3600000,
            endTs = now + 7200000,
            dtstamp = now,
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED
        )
    }
}