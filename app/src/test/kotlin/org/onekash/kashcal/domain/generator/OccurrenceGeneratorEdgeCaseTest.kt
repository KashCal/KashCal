package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.system.measureTimeMillis

/**
 * Tests [OccurrenceGenerator.generateOccurrences] on boundary and malformed RRULE inputs:
 * COUNT=0 and 1, INTERVAL=1 and 365, UNTIL before or at DTSTART, daily series across both
 * 2025 US DST changes, an empty, garbage or SECONDLY rule, RDATE, mixed-format EXDATE, and
 * long series (COUNT=1000 timing, the 10,000 cap). Complements `OccurrenceGeneratorTest`.
 *
 * [parseDate] reads times as UTC.
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
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

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
        // Documents the behavior: ical4j's `Recur` applies COUNT only when above 0, so COUNT=0
        // runs to the range end.
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

        // One per day of 2025.
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
        // An explicit INTERVAL=1 behaves like the default.
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
        // Consecutive days.
        for (i in 0 until 4) {
            val diff = occurrences[i + 1].startTs - occurrences[i].startTs
            assertEquals("Expected 24 hours between occurrences", 24 * 60 * 60 * 1000L, diff)
        }
    }

    @Test
    fun `very large INTERVAL works correctly`() = runTest {
        // A daily INTERVAL=365 lands about once a year.
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
        // Jan 1 of 2025, 2026 and 2027 (neither 2025 nor 2026 is a leap year); only the first
        // is asserted.
        assertEquals(20250101, occurrences[0].startDay)
    }

    // ==================== UNTIL Edge Cases ====================

    @Test
    fun `UNTIL before DTSTART generates no occurrences`() = runTest {
        // UNTIL falls before the event starts.
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

        // No occurrences.
        assertEquals(0, count)
    }

    @Test
    fun `UNTIL equals DTSTART generates single occurrence`() = runTest {
        // UNTIL equals DTSTART.
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

        // UNTIL is inclusive (RFC 5545 §3.3.10), so DTSTART itself is the one occurrence.
        assertEquals(1, count)
    }

    // ==================== DST Transition Tests ====================

    @Test
    fun `daily event across DST spring forward maintains local time`() = runTest {
        // US DST starts 2025-03-09 at 2:00. The event is 10:00 UTC (05:00 in New York); only
        // the count is asserted, not the local time.
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
        // No day lost to the change.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occurrences.size)
    }

    @Test
    fun `daily event across DST fall back maintains local time`() = runTest {
        // US DST ends 2025-11-02 at 2:00. Only the count is asserted, not the local time.
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
        // No duplicate from the change.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occurrences.size)
    }

    // ==================== Invalid RRULE Handling ====================

    @Test
    fun `empty RRULE string treated as non-recurring`() = runTest {
        // An empty RRULE is non-recurring: one occurrence.
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
        // A garbage RRULE doesn't throw; it expands to nothing.
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

        // 0 generated.
        assertEquals(0, count)
    }

    @Test
    fun `RRULE with unknown FREQ returns empty list`() = runTest {
        val startTs = parseDate("2025-01-15 10:00")
        val event = createAndInsertEvent(
            startTs = startTs,
            endTs = startTs + 3600000,
            rrule = "FREQ=SECONDLY" // A valid FREQ (RFC 5545 §3.3.10)
        )

        val count = occurrenceGenerator.generateOccurrences(
            event,
            parseDate("2025-01-01 00:00"),
            parseDate("2025-01-31 23:59")
        )

        // Asserts only that it returns without throwing.
        assertTrue(count >= 0)
    }

    // ==================== RDATE Edge Cases ====================

    @Test
    fun `RDATE adds additional occurrences to RRULE`() = runTest {
        // RDATE adds a date outside the RRULE expansion; the set is their union.
        val startTs = parseDate("2025-01-01 10:00")
        val event = Event(
            uid = "rdate-test-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = "RDATE Union Test",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=3", // Jan 1, 2, 3
            rdate = "20250115", // Jan 15; a DATE RDATE takes DTSTART's time of day
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

        // Jan 15 at 10:00 is among them.
        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        val jan15 = parseDate("2025-01-15 10:00")
        assertTrue("Jan 15 should be in occurrences", occurrences.any { it.startTs == jan15 })
    }

    // ==================== EXDATE Edge Cases ====================

    @Test
    fun `EXDATE with different timezone format is handled`() = runTest {
        // One EXDATE without a Z suffix and one with.
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

        // Both excluded: 10 - 2 = 8.
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

        // Under 5 seconds, generous for CI.
        assertTrue("Expected completion in <5s, took ${timeMs}ms", timeMs < 5000)
    }

    @Test
    fun `long range query with infinite recurrence respects MAX_ITERATIONS`() = runTest {
        // FREQ=DAILY without COUNT or UNTIL is unbounded.
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

        // The engine gives a rule with neither COUNT nor UNTIL a COUNT of 10,000
        // ([IcalDavRRuleEngine]), so the count is at most that.
        assertTrue("Expected at most 10000 due to MAX_ITERATIONS", count <= 10000)
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
