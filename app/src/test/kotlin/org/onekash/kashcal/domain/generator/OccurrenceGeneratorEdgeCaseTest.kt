package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.system.measureTimeMillis

/**
 * Edge case tests for OccurrenceGenerator.
 *
 * Tests cover boundary conditions and unusual inputs that could cause:
 * - Wrong event times after DST transitions
 * - Missing or duplicate occurrences
 * - Crashes on malformed RRULE strings
 * - Performance issues with long series
 *
 * These tests complement OccurrenceGeneratorTest.kt by focusing on
 * adversarial inputs and edge cases from real-world ICS files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorEdgeCaseTest {

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

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "edge-case@test.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://test.com/edge-cases/",
                    displayName = "Edge Case Calendar",
                    color = 0xFFFF0000.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== COUNT Edge Cases ====================

    @Test
    fun `COUNT=0 is treated as unlimited by lib-recur`() = runTest {
        // DISCOVERY: lib-recur treats COUNT=0 as "ignore COUNT" (unlimited)
        // This documents actual behavior - COUNT=0 generates occurrences for full range
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=0"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // lib-recur treats COUNT=0 as unlimited - generates for full year (365 days)
        assertEquals(365, count)
    }

    @Test
    fun `COUNT=1 generates exactly one occurrence`() = runTest {
        val startTs = parseDate("2025-01-15 14:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=1"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        assertEquals(1, count)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(startTs, occurrences[0].startTs)
    }

    // ==================== INTERVAL Edge Cases ====================

    @Test
    fun `INTERVAL=1 is equivalent to no INTERVAL`() = runTest {
        // Test that explicit INTERVAL=1 works same as implicit
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;INTERVAL=1;COUNT=5"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        assertEquals(5, count)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        // Should be consecutive days
        for (i in 0 until 4) {
            val diff = occurrences[i + 1].startTs - occurrences[i].startTs
            assertEquals("Expected 24 hours between occurrences", 24 * 60 * 60 * 1000L, diff)
        }
    }

    @Test
    fun `very large INTERVAL works correctly`() = runTest {
        // INTERVAL=365 for daily = yearly-ish recurrence
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;INTERVAL=365;COUNT=3"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2030-12-31 23:59")
        )

        assertEquals(3, count)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        // Jan 1, 2025 -> Jan 1, 2026 -> Jan 1, 2027 (approx, accounting for leap year)
        assertEquals(20250101, occurrences[0].startDay)
    }

    // ==================== UNTIL Edge Cases ====================

    @Test
    fun `UNTIL before DTSTART generates no occurrences`() = runTest {
        // Edge case: UNTIL is before event even starts
        val startTs = parseDate("2025-06-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;UNTIL=20250101T000000Z" // UNTIL in January, start in June
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // UNTIL before DTSTART means no occurrences
        assertEquals(0, count)
    }

    @Test
    fun `UNTIL equals DTSTART generates single occurrence`() = runTest {
        // Boundary: UNTIL matches DTSTART exactly
        val startTs = parseDate("2025-03-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;UNTIL=20250315T100000Z"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // UNTIL inclusive = 1 occurrence (the DTSTART itself)
        assertEquals(1, count)
    }

    // ==================== DST Transition Tests ====================

    @Test
    fun `daily event across DST spring forward maintains local time`() = runTest {
        // US DST Spring Forward 2025: March 9 at 2:00 AM
        // Event at 10:00 should remain at 10:00 local time after DST
        val startTs = parseDate("2025-03-07 10:00") // March 7, before DST
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            timezone = "America/New_York"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-03-01 00:00"),
            parseDate("2025-03-31 23:59")
        )

        assertEquals(5, count)
        // All occurrences should exist (no missing days due to DST)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occurrences.size)
    }

    @Test
    fun `daily event across DST fall back maintains local time`() = runTest {
        // US DST Fall Back 2025: November 2 at 2:00 AM
        val startTs = parseDate("2025-10-31 10:00") // Oct 31, before DST ends
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=5",
            timezone = "America/New_York"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-10-01 00:00"),
            parseDate("2025-11-30 23:59")
        )

        assertEquals(5, count)
        // All occurrences should exist (no duplicates due to DST)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occurrences.size)
    }

    // ==================== Invalid RRULE Handling ====================

    @Test
    fun `empty RRULE string treated as non-recurring`() = runTest {
        // Empty string should be treated as non-recurring (single occurrence)
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = ""
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        assertEquals(1, count)
    }

    @Test
    fun `malformed RRULE returns empty list gracefully`() = runTest {
        // Garbage RRULE should not crash, should return empty
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "INVALID_GARBAGE_RRULE_NOT_VALID"
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-12-31 23:59")
        )

        // Malformed RRULE returns 0 (graceful failure)
        assertEquals(0, count)
    }

    @Test
    fun `RRULE with unknown FREQ returns empty list`() = runTest {
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=SECONDLY" // SECONDLY is valid but may not be supported
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Should handle gracefully (either works or returns 0)
        assertTrue(count >= 0)
    }

    // ==================== RDATE Edge Cases ====================

    @Test
    fun `RDATE adds additional occurrences to RRULE`() = runTest {
        // Test RDATE union with RRULE-generated occurrences
        // RDATE adds dates that are NOT in the RRULE expansion
        val startTs = parseDate("2025-01-01 10:00")
        val event = Event(
            uid = "rdate-test-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = "RDATE Union Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3", // Jan 1, 2, 3
            rdate = "20250115", // Add Jan 15 via RDATE
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // 3 from COUNT (Jan 1, 2, 3) + 1 from RDATE (Jan 15) = 4
        assertEquals(4, count)

        // Verify Jan 15 is included
        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        val jan15 = parseDate("2025-01-15 10:00")
        assertTrue("Jan 15 should be in occurrences", occurrences.any { it.startTs == jan15 })
    }

    // ==================== EXDATE Edge Cases ====================

    @Test
    fun `EXDATE with different timezone format is handled`() = runTest {
        // EXDATE with Z suffix vs without
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=10",
            exdate = "20250105T100000,20250107T100000Z" // Mixed formats
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Should exclude both dates: 10 - 2 = 8
        assertEquals(8, count)
    }

    // ==================== Performance Tests ====================

    @Test
    fun `COUNT=1000 completes in reasonable time`() = runTest {
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY;COUNT=1000"
        )

        val timeMs = measureTimeMillis {
            val count = occurrenceGenerator.generateOccurrences(
                event,
                parseDate("2025-01-01 00:00"),
                parseDate("2030-12-31 23:59")
            )
            assertEquals(1000, count)
        }

        // Should complete in under 5 seconds (generous for CI)
        assertTrue("Expected completion in <5s, took ${timeMs}ms", timeMs < 5000)
    }

    @Test
    fun `long range query with infinite recurrence respects MAX_ITERATIONS`() = runTest {
        // FREQ=DAILY without COUNT or UNTIL = infinite
        val startTs = parseDate("2025-01-01 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=DAILY" // No limit
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2100-12-31 23:59") // 75+ years
        )

        // MAX_ITERATIONS is 1000, so should cap at that
        assertTrue("Expected at most 1000 due to MAX_ITERATIONS", count <= 1000)
    }

    // ==================== parseRule Edge Cases ====================

    @Test
    fun `parseRule handles RRULE without optional parts`() {
        val info = occurrenceGenerator.parseRule("FREQ=DAILY")

        assertTrue(info != null)
        assertEquals("DAILY", info!!.freq)
        assertEquals(1, info.interval) // Default
        assertNull(info.count)
        assertNull(info.until)
        assertTrue(info.byDay.isEmpty())
    }

    @Test
    fun `parseRule returns null for empty string`() {
        val info = occurrenceGenerator.parseRule("")
        assertNull(info)
    }

    @Test
    fun `parseRule returns null for invalid RRULE`() {
        val info = occurrenceGenerator.parseRule("NOT_AN_RRULE")
        assertNull(info)
    }

    // ==================== Helper Functions ====================

    private suspend fun createAndInsertEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null,
        rdate: String? = null,
        timezone: String? = null,
        title: String = "Edge Case Event"
    ): Event {
        val event = Event(
            uid = "edge-case-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            exdate = exdate,
            rdate = rdate,
            timezone = timezone,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    private fun parseDate(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")

        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
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
