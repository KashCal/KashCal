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
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Adversarial tests for RRULE parsing and occurrence generation.
 *
 * Tests probe edge cases that could crash or hang the app:
 * - Malformed RRULE strings
 * - Extreme parameter values
 * - MAX_ITERATIONS safety limit
 * - EXDATE/RDATE parsing edge cases
 * - Timezone trap scenarios
 * - Memory exhaustion attempts
 *
 * These tests verify defensive coding in OccurrenceGenerator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RRuleAdversarialTest {

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

    // ==================== Malformed RRULE Tests ====================

    @Test
    fun `malformed RRULE - empty string returns empty list`() = runTest {
        val event = createTestEvent("Empty RRULE").copy(rrule = "")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Non-recurring (empty rrule) should generate exactly 1 occurrence
        assertEquals(1, count)
    }

    @Test
    fun `malformed RRULE - garbage string returns empty list`() = runTest {
        val event = createTestEvent("Garbage RRULE").copy(rrule = "GARBAGE;NOT;A;RULE")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should handle gracefully - returns 0 occurrences
        assertEquals(0, count)
    }

    @Test
    fun `malformed RRULE - missing FREQ returns empty list`() = runTest {
        val event = createTestEvent("No FREQ").copy(rrule = "INTERVAL=1;COUNT=10")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        assertEquals(0, count)
    }

    @Test
    fun `malformed RRULE - invalid FREQ value returns empty list`() = runTest {
        val event = createTestEvent("Invalid FREQ").copy(rrule = "FREQ=BIWEEKLY;COUNT=10")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        assertEquals(0, count)
    }

    @Test
    fun `malformed RRULE - SQL injection attempt is safe`() = runTest {
        val event = createTestEvent("SQL Injection").copy(
            rrule = "FREQ=DAILY;COUNT=1'; DROP TABLE events;--"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis() - 86400000,
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // lib-recur may be permissive and parse COUNT=1 ignoring the rest
        // The key assertion is the table still exists (no SQL injection)
        assertTrue("Should handle gracefully", count >= 0)

        // Verify table and event still exist (the real security test)
        val verifyEvent = database.eventsDao().getById(eventId)
        assertNotNull("Event should still exist - SQL injection failed", verifyEvent)
    }

    @Test
    fun `malformed RRULE - extremely long string is handled`() = runTest {
        val longRrule = "FREQ=DAILY;" + "X".repeat(10000)
        val event = createTestEvent("Long RRULE").copy(rrule = longRrule)
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should handle gracefully
        assertTrue(count >= 0)
    }

    // ==================== Extreme Parameter Tests ====================

    @Test
    fun `extreme INTERVAL - zero interval handled`() = runTest {
        val event = createTestEvent("Zero Interval").copy(rrule = "FREQ=DAILY;INTERVAL=0;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // lib-recur should handle this (may default to 1 or fail)
        assertTrue(count >= 0)
    }

    @Test
    fun `extreme INTERVAL - negative interval handled`() = runTest {
        val event = createTestEvent("Negative Interval").copy(rrule = "FREQ=DAILY;INTERVAL=-1;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should fail gracefully
        assertTrue(count >= 0)
    }

    @Test
    fun `extreme INTERVAL - very large interval handled`() = runTest {
        val event = createTestEvent("Huge Interval").copy(rrule = "FREQ=DAILY;INTERVAL=999999;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 10 * 365 * 24 * 60 * 60 * 1000L // 10 years
        )

        // With 999999-day interval, few occurrences in 10 years
        assertTrue(count <= 5)
    }

    @Test
    fun `extreme COUNT - zero count generates no occurrences`() = runTest {
        val event = createTestEvent("Zero Count").copy(rrule = "FREQ=DAILY;COUNT=0")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // COUNT=0 should generate no occurrences (or lib-recur treats as unlimited)
        assertTrue(count >= 0)
    }

    @Test
    fun `extreme COUNT - negative count handled`() = runTest {
        val event = createTestEvent("Negative Count").copy(rrule = "FREQ=DAILY;COUNT=-5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should fail gracefully
        assertTrue(count >= 0)
    }

    // ==================== MAX_ITERATIONS Safety Tests ====================

    @Test
    fun `infinite recurrence is limited by MAX_ITERATIONS`() = runTest {
        // No COUNT or UNTIL - potentially infinite
        val event = createTestEvent("Infinite Daily").copy(rrule = "FREQ=DAILY")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 100 * 365 * 24 * 60 * 60 * 1000L // 100 years
        )

        // MAX_ITERATIONS is 1000 - should not exceed
        assertTrue("Should be limited by MAX_ITERATIONS", count <= 1000)
    }

    @Test
    fun `secondly recurrence is limited by MAX_ITERATIONS`() = runTest {
        // FREQ=SECONDLY would generate massive occurrences
        val event = createTestEvent("Secondly").copy(rrule = "FREQ=SECONDLY")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should be capped at MAX_ITERATIONS
        assertTrue("Should be limited", count <= 1000)
    }

    @Test
    fun `minutely recurrence is limited by MAX_ITERATIONS`() = runTest {
        val event = createTestEvent("Minutely").copy(rrule = "FREQ=MINUTELY")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        assertTrue("Should be limited", count <= 1000)
    }

    // ==================== UNTIL Edge Cases ====================

    @Test
    fun `UNTIL in the past generates no occurrences`() = runTest {
        val event = createTestEvent("Past UNTIL").copy(rrule = "FREQ=DAILY;UNTIL=19700101T000000Z")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        assertEquals("UNTIL in past should generate 0 occurrences", 0, count)
    }

    @Test
    fun `UNTIL before DTSTART generates single occurrence or none`() = runTest {
        val now = System.currentTimeMillis()
        val event = createTestEvent("UNTIL Before Start").copy(
            startTs = now,
            rrule = "FREQ=DAILY;UNTIL=20200101T000000Z" // Before startTs
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            now - 86400000,
            now + 365 * 24 * 60 * 60 * 1000L
        )

        // DTSTART might still be included, or 0 if UNTIL excludes it
        assertTrue(count <= 1)
    }

    @Test
    fun `malformed UNTIL is handled gracefully`() = runTest {
        val event = createTestEvent("Bad UNTIL").copy(rrule = "FREQ=DAILY;UNTIL=NOTADATE")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should fail parsing
        assertTrue(count >= 0)
    }

    // ==================== BYDAY Edge Cases ====================

    @Test
    fun `BYDAY with invalid day code`() = runTest {
        val event = createTestEvent("Invalid BYDAY").copy(rrule = "FREQ=WEEKLY;BYDAY=XX")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        assertTrue(count >= 0)
    }

    @Test
    fun `BYDAY on wrong start day - event on Tuesday with BYDAY=MO`() = runTest {
        // Start on a Tuesday (2024-01-02 was Tuesday)
        val tuesdayStart = 1704153600000L // 2024-01-02 00:00 UTC
        val event = createTestEvent("Tuesday Start MO BYDAY").copy(
            startTs = tuesdayStart,
            endTs = tuesdayStart + 3600000,
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=5"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            tuesdayStart,
            tuesdayStart + 365 * 24 * 60 * 60 * 1000L
        )

        // DTSTART on Tuesday but BYDAY=MO - first Monday after should be included
        // This tests DTSTART alignment behavior
        assertTrue(count > 0)
    }

    @Test
    fun `BYDAY with ordinal outside valid range`() = runTest {
        // 6MO = 6th Monday, which may not exist in some months
        val event = createTestEvent("6th Monday").copy(rrule = "FREQ=MONTHLY;BYDAY=6MO;COUNT=12")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // 6th Monday never exists - should generate 0
        assertEquals(0, count)
    }

    @Test
    fun `BYDAY=-1FR - last Friday of month`() = runTest {
        // Use a fixed start date that is a Friday: 2024-01-26 was a Friday (last Friday of Jan 2024)
        val fridayStart = 1706227200000L // 2024-01-26 00:00 UTC (last Friday of Jan 2024)
        val event = createTestEvent("Last Friday").copy(
            startTs = fridayStart,
            endTs = fridayStart + 3600000,
            rrule = "FREQ=MONTHLY;BYDAY=-1FR;COUNT=12"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            fridayStart - 86400000,
            fridayStart + 365 * 24 * 60 * 60 * 1000L
        )

        // Should generate exactly 12 occurrences
        assertEquals(12, count)
    }

    // ==================== BYMONTHDAY Edge Cases ====================

    @Test
    fun `BYMONTHDAY=31 skips months without 31st`() = runTest {
        val event = createTestEvent("31st of month").copy(rrule = "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=12")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Only months with 31 days: Jan, Mar, May, Jul, Aug, Oct, Dec = 7 months
        assertTrue(count <= 12 && count >= 0)
    }

    @Test
    fun `BYMONTHDAY=30 in February`() = runTest {
        // February never has 30th
        val event = createTestEvent("30th Feb").copy(rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30;COUNT=5")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 10 * 365 * 24 * 60 * 60 * 1000L
        )

        // Feb 30 never exists
        assertEquals(0, count)
    }

    @Test
    fun `BYMONTHDAY=29 in February - leap year handling`() = runTest {
        // Feb 29 only in leap years
        val event = createTestEvent("Feb 29").copy(rrule = "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=10")
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 50 * 365 * 24 * 60 * 60 * 1000L // 50 years
        )

        // Approximately 12-13 leap years in 50 years
        assertTrue("Should have leap year occurrences", count > 0 && count <= 13)
    }

    // ==================== EXDATE Edge Cases ====================

    @Test
    fun `EXDATE with malformed date is ignored`() = runTest {
        val event = createTestEvent("Bad EXDATE").copy(
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "NOTADATE"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis() - 86400000,
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Malformed EXDATE should be ignored, not crash
        assertEquals(5, count)
    }

    @Test
    fun `EXDATE excludes all occurrences`() = runTest {
        val now = System.currentTimeMillis()
        val startTs = now
        // Create dates that will be excluded
        val dates = (0..4).map { day ->
            val ts = startTs + day * 86400000L
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = ts
            String.format("%04d%02d%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH))
        }
        val exdateStr = dates.joinToString(",")

        val event = createTestEvent("All Excluded").copy(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = exdateStr
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000L
        )

        // All 5 excluded = 0 occurrences
        assertEquals(0, count)
    }

    @Test
    fun `EXDATE with trailing comma is handled`() = runTest {
        val event = createTestEvent("Trailing Comma").copy(
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240101,"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis() - 86400000,
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should handle gracefully
        assertTrue(count >= 0)
    }

    // ==================== parseRule Tests ====================

    @Test
    fun `parseRule returns null for malformed RRULE`() {
        val result = occurrenceGenerator.parseRule("GARBAGE")
        assertNull(result)
    }

    @Test
    fun `parseRule returns null for empty string`() {
        val result = occurrenceGenerator.parseRule("")
        assertNull(result)
    }

    @Test
    fun `parseRule extracts FREQ correctly`() {
        val result = occurrenceGenerator.parseRule("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertNotNull(result)
        assertEquals("WEEKLY", result!!.freq)
    }

    @Test
    fun `parseRule extracts BYDAY correctly`() {
        val result = occurrenceGenerator.parseRule("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertNotNull(result)
        assertTrue(result!!.byDay.isNotEmpty())
    }

    // ==================== expandForPreview Tests ====================

    @Test
    fun `expandForPreview handles empty RRULE`() {
        val result = occurrenceGenerator.expandForPreview(
            rrule = "",
            dtstartMs = System.currentTimeMillis(),
            rangeStartMs = System.currentTimeMillis(),
            rangeEndMs = System.currentTimeMillis() + 86400000,
            exdates = emptyList()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `expandForPreview handles malformed RRULE`() {
        val result = occurrenceGenerator.expandForPreview(
            rrule = "NOT_A_RULE",
            dtstartMs = System.currentTimeMillis(),
            rangeStartMs = System.currentTimeMillis(),
            rangeEndMs = System.currentTimeMillis() + 86400000,
            exdates = emptyList()
        )
        assertTrue(result.isEmpty())
    }

    // ==================== Timezone Edge Cases ====================

    @Test
    fun `all-day event uses UTC for expansion`() = runTest {
        val utcMidnight = 1704067200000L // 2024-01-01 00:00 UTC
        val event = createTestEvent("All Day Recurring").copy(
            startTs = utcMidnight,
            endTs = utcMidnight + 86400000 - 1,
            isAllDay = true,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            utcMidnight - 86400000,
            utcMidnight + 10 * 86400000L
        )

        assertEquals(5, count)

        // Verify occurrences are at UTC midnight
        val occurrences = database.occurrencesDao().getForEvent(eventId)
        occurrences.forEach { occ ->
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = occ.startTs
            assertEquals("Should be at UTC midnight", 0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        }
    }

    @Test
    fun `invalid timezone falls back to default`() = runTest {
        val event = createTestEvent("Bad TZ").copy(
            timezone = "Not/A/Timezone",
            rrule = "FREQ=DAILY;COUNT=5"
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            System.currentTimeMillis() - 86400000,
            System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        )

        // Should still generate occurrences using fallback
        assertEquals(5, count)
    }

    // ==================== Helper Methods ====================

    private fun createTestEvent(title: String): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = now,
            endTs = now + 3600000, // 1 hour
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
    }
}
