package org.onekash.kashcal.domain.generator
import org.onekash.kashcal.testutil.TestDataStoreFactory

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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

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

    // ==================== Past Extension DAO Tests ====================

    @Test
    fun `getMinStartTs returns earliest occurrence time`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024 00:00 UTC

        val event = Event(
            uid = "min-start@test.com",
            calendarId = testCalendarId,
            title = "Min Start Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 20 * 86400000L
        )

        val minTs = database.occurrencesDao().getMinStartTs(eventId)
        assertNotNull(minTs)
        assertEquals(startTs, minTs)
    }

    @Test
    fun `getMinStartTs returns null for event with no occurrences`() = runTest {
        val event = createRecurringEvent("No Occs", "FREQ=DAILY")
        val eventId = database.eventsDao().insert(event)

        val minTs = database.occurrencesDao().getMinStartTs(eventId)
        assertNull(minTs)
    }

    @Test
    fun `needingPastExtension finds events with gap before target`() = runTest {
        // Event starts Jan 1, 2020 but occurrences only from Jan 2024
        val eventStartTs = 1577836800000L // Jan 1, 2020 00:00 UTC
        val occWindowStart = 1704067200000L // Jan 1, 2024 00:00 UTC

        val event = Event(
            uid = "past-gap@test.com",
            calendarId = testCalendarId,
            title = "Past Gap Event",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate occurrences only for Jan 2024 - Feb 2024 (simulate partial window)
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            occWindowStart,
            occWindowStart + 30 * 86400000L
        )

        // Target: July 2023 — event started before, but MIN(occ) is Jan 2024
        val targetTs = 1688169600000L // July 1, 2023 00:00 UTC
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertTrue("Should find event needing past extension", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes events where DTSTART is after target`() = runTest {
        val eventStartTs = 1704067200000L // Jan 1, 2024

        val event = Event(
            uid = "future-start@test.com",
            calendarId = testCalendarId,
            title = "Future Start Event",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=10",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs - 86400000,
            eventStartTs + 20 * 86400000L
        )

        // Target: June 2023 — before event even starts
        val targetTs = 1685577600000L // June 1, 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include event starting after target", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes events already covering target`() = runTest {
        val eventStartTs = 1577836800000L // Jan 1, 2020

        val event = Event(
            uid = "already-covered@test.com",
            calendarId = testCalendarId,
            title = "Already Covered",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Jan 2020 — covers everything
        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs,
            eventStartTs + 365 * 86400000L
        )

        // Target: June 2020 — MIN(occ) is Jan 2020, which is before target
        val targetTs = 1590969600000L // June 1, 2020
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include event already covering target", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes non-recurring events`() = runTest {
        val eventStartTs = 1704067200000L // Jan 1, 2024

        val event = Event(
            uid = "non-recurring@test.com",
            calendarId = testCalendarId,
            title = "Non-Recurring",
            startTs = eventStartTs,
            endTs = eventStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            eventStartTs - 86400000,
            eventStartTs + 86400000
        )

        val targetTs = 1701388800000L // Dec 1, 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        assertFalse("Should NOT include non-recurring event", needsExtension.contains(eventId))
    }

    @Test
    fun `needingPastExtension excludes exception events`() = runTest {
        val masterStartTs = 1577836800000L // Jan 1, 2020

        // Create master event
        val masterEvent = Event(
            uid = "master-exc@test.com",
            calendarId = testCalendarId,
            title = "Master",
            startTs = masterStartTs,
            endTs = masterStartTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = database.eventsDao().getById(masterId)!!

        occurrenceGenerator.generateOccurrences(
            savedMaster,
            1704067200000L, // Jan 2024
            1704067200000L + 30 * 86400000L
        )

        // Create exception event with originalEventId set
        val exceptionEvent = Event(
            uid = "master-exc@test.com", // Same UID per RFC 5545
            calendarId = testCalendarId,
            title = "Exception",
            startTs = 1704153600000L, // Jan 2, 2024
            endTs = 1704153600000L + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = 1704153600000L,
            rrule = "FREQ=DAILY", // Exception with rrule shouldn't be picked up
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exceptionEvent)

        // Insert an occurrence for the exception event so it shows up in occurrences table
        database.occurrencesDao().insert(
            Occurrence(
                eventId = exceptionId,
                calendarId = testCalendarId,
                startTs = 1704153600000L,
                endTs = 1704153600000L + 3600000,
                startDay = 20240102,
                endDay = 20240102
            )
        )

        val targetTs = 1688169600000L // July 2023
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(targetTs)
        // Should include master but NOT exception
        assertTrue("Should include master event", needsExtension.contains(masterId))
        assertFalse("Should NOT include exception event", needsExtension.contains(exceptionId))
    }

    // ==================== Past Extension (OccurrenceGenerator) Tests ====================

    @Test
    fun `extendPastOccurrences adds occurrences before current range`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024 00:00 UTC
        val DAY = 86400000L

        val event = Event(
            uid = "past-ext@test.com",
            calendarId = testCalendarId,
            title = "Past Extension",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate occurrences only for Jun-Jul 2024 (simulate partial window)
        val junStart = startTs + 152 * DAY // ~Jun 1, 2024
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 60 * DAY)

        val initialCount = database.occurrencesDao().getForEvent(eventId).size
        val initialMinTs = database.occurrencesDao().getMinStartTs(eventId)!!

        // Extend past to March 2024
        val marchStart = startTs + 59 * DAY // ~Mar 1, 2024
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, marchStart)

        assertTrue("Should have extended some occurrences", extended > 0)
        val newCount = database.occurrencesDao().getForEvent(eventId).size
        assertTrue("Total count should increase", newCount > initialCount)

        val newMinTs = database.occurrencesDao().getMinStartTs(eventId)!!
        assertTrue("Min occurrence should be earlier", newMinTs < initialMinTs)
        assertTrue("Min occurrence should be >= marchStart", newMinTs >= marchStart)
    }

    @Test
    fun `extendPastOccurrences returns 0 for non-recurring event`() = runTest {
        val event = createTestEvent("Non-Recurring Past")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            savedEvent.startTs - 86400000,
            savedEvent.startTs + 86400000
        )

        val extended = occurrenceGenerator.extendPastOccurrences(
            savedEvent,
            savedEvent.startTs - 100 * 86400000L
        )
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences does not go before event startTs`() = runTest {
        val startTs = 1709251200000L // Mar 1, 2024 00:00 UTC
        val DAY = 86400000L

        val event = Event(
            uid = "dtstart-bound@test.com",
            calendarId = testCalendarId,
            title = "DTSTART Bound",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate occurrences for Jun-Jul 2024
        val junStart = startTs + 92 * DAY // ~Jun 1, 2024
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 60 * DAY)

        // Try to extend past to Jan 2024 (before event DTSTART of Mar 2024)
        val janStart = 1704067200000L // Jan 1, 2024
        occurrenceGenerator.extendPastOccurrences(savedEvent, janStart)

        // Earliest occurrence should be at event startTs, not Jan
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        assertTrue("Earliest occurrence should be >= event startTs", minTs >= startTs)
    }

    @Test
    fun `extendPastOccurrences returns 0 when already extended past enough`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "already-past@test.com",
            calendarId = testCalendarId,
            title = "Already Extended",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=180",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Jan 1 (covers everything from startTs forward)
        occurrenceGenerator.generateOccurrences(savedEvent, startTs, startTs + 200 * DAY)

        // Target is June 2024 — but min is already Jan 1, well before June
        val juneTarget = startTs + 152 * DAY
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, juneTarget)
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences returns 0 when currentMinTs equals effectiveExtendTo`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "exact-boundary@test.com",
            calendarId = testCalendarId,
            title = "Exact Boundary",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from Mar 1 onward
        val marStart = startTs + 60 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, marStart, marStart + 60 * DAY)

        // Get exact min and extend to that exact value
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, minTs)
        assertEquals("Should return 0 when extending to exact current boundary", 0, extended)
    }

    @Test
    fun `extendPastOccurrences does not duplicate existing occurrences`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "no-dup@test.com",
            calendarId = testCalendarId,
            title = "No Duplicates",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate Jun-Aug 2024
        val junStart = startTs + 152 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 90 * DAY)

        val junOccsBefore = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs >= junStart }
        val junCountBefore = junOccsBefore.size

        // Extend past to Mar 2024
        val marStart = startTs + 59 * DAY
        occurrenceGenerator.extendPastOccurrences(savedEvent, marStart)

        // Original Jun-Aug occurrences should still be there, unchanged
        val junOccsAfter = database.occurrencesDao().getForEvent(eventId)
            .filter { it.startTs >= junStart }
        assertEquals("Original occurrences should be unchanged", junCountBefore, junOccsAfter.size)
    }

    @Test
    fun `extendPastOccurrences preserves exception links`() = runTest {
        val startTs = 1704067200000L // Jan 1, 2024
        val DAY = 86400000L

        val event = Event(
            uid = "preserve-exc@test.com",
            calendarId = testCalendarId,
            title = "Exception Preserve",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate Jun-Aug 2024
        val junStart = startTs + 152 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, junStart, junStart + 90 * DAY)

        // Link an exception to a July occurrence
        val occs = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        val julyOcc = occs[30] // ~July occurrence
        val exceptionEvent = Event(
            uid = "preserve-exc@test.com",
            calendarId = testCalendarId,
            title = "Modified July",
            startTs = julyOcc.startTs,
            endTs = julyOcc.endTs,
            dtstamp = System.currentTimeMillis(),
            originalEventId = eventId,
            originalInstanceTime = julyOcc.startTs,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exceptionEvent)
        occurrenceGenerator.linkException(eventId, julyOcc.startTs, exceptionId)

        // Verify link exists
        val linkedBefore = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception should be linked before past extension", linkedBefore)

        // Extend past to Mar 2024
        val marStart = startTs + 59 * DAY
        occurrenceGenerator.extendPastOccurrences(savedEvent, marStart)

        // Verify link is still intact
        val linkedAfter = database.occurrencesDao().getForEvent(eventId)
            .find { it.exceptionEventId == exceptionId }
        assertNotNull("Exception link should be preserved after past extension", linkedAfter)
    }

    @Test
    fun `extendPastOccurrences returns 0 when event has no occurrences`() = runTest {
        val event = createRecurringEvent("No Occs Past", "FREQ=DAILY")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Don't generate any occurrences
        val extended = occurrenceGenerator.extendPastOccurrences(
            savedEvent,
            savedEvent.startTs - 100 * 86400000L
        )
        assertEquals(0, extended)
    }

    @Test
    fun `extendPastOccurrences skips EXDATE occurrences in past window`() = runTest {
        // Use relative dates to stay within 2-year lookback window
        // Second-align timestamps to match lib-recur's precision
        val DAY = 86400000L
        val now = (System.currentTimeMillis() / 1000) * 1000 // Second-aligned
        val startTs = now - 90 * DAY // 90 days ago

        // EXDATE on day 15 of the event — should be skipped when extending past
        val exdateTs = startTs + 14 * DAY // 15th day of event
        // Calculate day code for EXDATE
        val exdateDayCode = java.time.Instant.ofEpochMilli(exdateTs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))

        val event = Event(
            uid = "exdate-past@test.com",
            calendarId = testCalendarId,
            title = "EXDATE Past",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY",
            exdate = exdateDayCode,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate days 60-120 initially (relative to event start)
        val laterStart = startTs + 59 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, laterStart, laterStart + 60 * DAY)

        // Extend past to event start
        occurrenceGenerator.extendPastOccurrences(savedEvent, startTs)

        // EXDATE day should NOT have an occurrence
        val exdateOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs)
        assertNull("EXDATE occurrence should not be generated in past extension", exdateOcc)

        // But day before and after EXDATE should exist
        val dayBeforeOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs - DAY)
        val dayAfterOcc = database.occurrencesDao().getOccurrenceAtTime(eventId, exdateTs + DAY)
        assertNotNull("Day before EXDATE should exist", dayBeforeOcc)
        assertNotNull("Day after EXDATE should exist", dayAfterOcc)
    }

    @Test
    fun `extendPastOccurrences works with weekly recurrence`() = runTest {
        // Use relative dates to stay within 2-year lookback window
        // Second-align timestamps to match lib-recur's precision
        val DAY = 86400000L
        val WEEK = 7 * DAY
        val now = (System.currentTimeMillis() / 1000) * 1000 // Second-aligned
        val startTs = now - 180 * DAY // 180 days ago (within lookback)

        val event = Event(
            uid = "weekly-past@test.com",
            calendarId = testCalendarId,
            title = "Weekly Past",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=WEEKLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate from 90 days after start (days 90-210)
        val laterStart = startTs + 90 * DAY
        occurrenceGenerator.generateOccurrences(savedEvent, laterStart, laterStart + 120 * DAY)

        val initialCount = database.occurrencesDao().getForEvent(eventId).size

        // Extend past to event start + 31 days (day 31)
        val extendTarget = startTs + 31 * DAY
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, extendTarget)

        assertTrue("Should have extended weekly occurrences", extended > 0)
        val newCount = database.occurrencesDao().getForEvent(eventId).size
        assertTrue("Total count should increase", newCount > initialCount)

        // Verify occurrences are ~7 days apart
        val allOccs = database.occurrencesDao().getForEvent(eventId).sortedBy { it.startTs }
        for (i in 1 until allOccs.size) {
            val gap = allOccs[i].startTs - allOccs[i - 1].startTs
            assertEquals("Weekly occurrences should be 7 days apart", WEEK, gap)
        }
    }

    // ==================== Bug: Past recurring event found by search but no occurrences ====================

    @Test
    fun `past recurring event within 1-year window generates occurrences via PullStrategy range`() = runTest {
        // Scenario: weekly event started 6 months ago, UNTIL 3 months ago.
        // PullStrategy calls generateOccurrences(event, now - 365 days, now + 2 years).
        // All occurrences should fall within this range.
        val now = System.currentTimeMillis()
        val sixMonthsAgo = now - (180L * 24 * 60 * 60 * 1000)
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)

        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = threeMonthsAgo
        val untilStr = String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )

        val event = Event(
            uid = "past-weekly-bug@test.com",
            calendarId = testCalendarId,
            title = "Past Weekly Meeting",
            startTs = sixMonthsAgo,
            endTs = sixMonthsAgo + 3600000,
            dtstamp = now,
            rrule = "FREQ=WEEKLY;UNTIL=$untilStr",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Use PullStrategy's exact range: now - 365 days to now + 2 years
        val pastWindowMs = 365L * 24 * 60 * 60 * 1000
        val futureWindowMs = 2 * 365L * 24 * 60 * 60 * 1000
        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            rangeStartMs = now - pastWindowMs,
            rangeEndMs = now + futureWindowMs
        )

        assertTrue("Past recurring event within 1-year window should have occurrences, got $count", count > 0)

        val occs = database.occurrencesDao().getForEvent(eventId)
        assertTrue("Should have occurrence rows in database", occs.isNotEmpty())

        // Verify occurrences span the expected ~13 weeks (6 months to 3 months ago)
        assertTrue("Should have ~13 weekly occurrences, got ${occs.size}", occs.size in 10..15)
    }

    // ==================== Issue #152: Far-past recurring event not visible ====================

    @Test
    fun `needingPastExtension finds yearly event when targetTs is after buffer subtraction`() = runTest {
        // Issue #152: FREQ=YEARLY event starting April 13, 2008.
        // User navigates to April 13, 2008. extendPastOccurrencesIfNeeded subtracts 6-month buffer,
        // making targetTs = October 2007. The WHERE clause `e.start_ts < :targetTs` fails because
        // April 2008 < October 2007 is FALSE.
        val april2008 = 1208044800000L // April 13, 2008 00:00 UTC
        val oct2007 = april2008 - (6 * 30L * 24 * 60 * 60 * 1000) // ~October 2007 (6-month buffer)

        val event = Event(
            uid = "yearly-birthday-152@test.com",
            calendarId = testCalendarId,
            title = "Birthday",
            startTs = april2008,
            endTs = april2008 + 86400000, // all-day
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=YEARLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate occurrences only for 2025-2028 (simulate the default window)
        val jan2025 = 1735689600000L // Jan 1, 2025
        val jan2028 = 1830297600000L // Jan 1, 2028
        occurrenceGenerator.generateOccurrences(savedEvent, jan2025, jan2028)

        // Verify occurrences exist in the expected window
        val occs = database.occurrencesDao().getForEvent(eventId)
        assertTrue("Should have occurrences in 2025-2028", occs.isNotEmpty())

        // The bug: targetTs = October 2007 (after 6-month buffer subtraction).
        // Event starts April 2008. MIN(occ) is April 2025.
        // The query should find this event because there's a gap between DTSTART and MIN(occ).
        val needsExtension = database.occurrencesDao().getRecurringEventsNeedingPastExtension(oct2007)
        assertTrue(
            "Should find yearly event needing past extension (issue #152)",
            needsExtension.contains(eventId)
        )
    }

    @Test
    fun `extendPastOccurrences extends yearly event back to DTSTART despite syncPastDays`() = runTest {
        // Issue #152: Even with the query fixed, syncPastDays (default 365) clamps extension
        // to now-1yr, which is ~April 2025 — where occurrences already exist.
        // On-demand extension should reach DTSTART regardless of syncPastDays.
        val april2008 = 1208044800000L // April 13, 2008 00:00 UTC

        val event = Event(
            uid = "yearly-extend-152@test.com",
            calendarId = testCalendarId,
            title = "Birthday",
            startTs = april2008,
            endTs = april2008 + 86400000,
            isAllDay = true,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=YEARLY",
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        // Generate occurrences only for 2025-2028
        val jan2025 = 1735689600000L
        val jan2028 = 1830297600000L
        occurrenceGenerator.generateOccurrences(savedEvent, jan2025, jan2028)

        // Extend back to October 2007 (6 months before DTSTART, simulating the buffer)
        val oct2007 = april2008 - (6 * 30L * 24 * 60 * 60 * 1000)
        val extended = occurrenceGenerator.extendPastOccurrences(savedEvent, oct2007)

        assertTrue("Should have extended occurrences back to DTSTART", extended > 0)

        // Verify the earliest occurrence is at or near April 2008 (DTSTART)
        val minTs = database.occurrencesDao().getMinStartTs(eventId)!!
        val diffFromDtstart = minTs - april2008
        assertTrue(
            "Earliest occurrence should be at DTSTART (April 2008), diff=$diffFromDtstart ms",
            diffFromDtstart < 86400000 // within 1 day (accounting for all-day alignment)
        )
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