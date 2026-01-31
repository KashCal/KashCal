package org.onekash.kashcal.regression

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
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Regression tests for display-related bugs.
 *
 * These tests ensure bugs that were fixed stay fixed.
 * Each test documents:
 * - The bug description
 * - Version where it was fixed
 * - The root cause
 * - The fix applied
 *
 * CRITICAL: Do not modify or remove these tests without understanding
 * the original bug they are protecting against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DisplayBugRegressionTest {

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

        // Create test hierarchy
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
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

    @After
    fun teardown() {
        database.close()
    }

    // ==================== BUG: All-Day Event Shows on Wrong Day (v3.6.13 Fix) ====================

    /**
     * BUG: All-day events displayed on both the correct day AND the previous day
     *      in negative UTC offset timezones (US timezones like CST, PST).
     *
     * VERSIONS AFFECTED: v3.6.11, v3.6.12
     * FIXED IN: v3.6.13
     *
     * ROOT CAUSE:
     * The HomeViewModel used timestamp-based queries with LOCAL timezone boundaries:
     * ```kotlin
     * val dayStart = calendar.apply { set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
     * val dayEnd = calendar.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
     * eventReader.getVisibleOccurrencesInRange(dayStart, dayEnd)
     * ```
     *
     * For CST (UTC-6), querying Jan 14 local time:
     * - dayEnd = Jan 15 00:00:00 CST = Jan 15 06:00:00 UTC
     * - Event Jan 15 all-day has startTs = Jan 15 00:00:00 UTC
     * - SQL: `WHERE start_ts <= :dayEnd` matched because 00:00 UTC <= 06:00 UTC
     *
     * THE FIX:
     * Use dayCode-based queries instead of timestamp-based queries:
     * ```kotlin
     * val dayCode = localDate.year * 10000 + localDate.monthValue * 100 + localDate.dayOfMonth
     * eventReader.getVisibleOccurrencesForDay(dayCode)
     * ```
     *
     * This test ensures the bug stays fixed.
     */
    @Test
    fun `REGRESSION v3_6_13 - all-day event does not show on previous day in negative UTC offset`() = runTest {
        // Setup: All-day event on Jan 15
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:regression-allday@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:All Day Jan 15
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]
        assertTrue("Event should be all-day", event.isAllDay)

        // Save and generate occurrences
        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-01-01"),
            parseUtcDate("2026-01-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(1, occurrences.size)

        // THE TEST: Query using dayCode (the fix) should return correct results
        // This simulates what HomeViewModel does in v3.6.13+

        val jan14Results = database.occurrencesDao().getForDayOnce(20260114)
        val jan15Results = database.occurrencesDao().getForDayOnce(20260115)
        val jan16Results = database.occurrencesDao().getForDayOnce(20260116)

        assertEquals(
            "REGRESSION CHECK: Event should NOT appear on Jan 14 (previous day)",
            0, jan14Results.size
        )
        assertEquals(
            "Event should appear on Jan 15 (correct day)",
            1, jan15Results.size
        )
        assertEquals(
            "Event should NOT appear on Jan 16 (next day)",
            0, jan16Results.size
        )
    }

    /**
     * Same bug as above but specifically for iCloud birthday events.
     * These are yearly recurring all-day events that were showing on 2 days.
     */
    @Test
    fun `REGRESSION v3_6_13 - iCloud birthday shows on single day only`() = runTest {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud Calendar//EN
            BEGIN:VEVENT
            UID:birthday-regression@icloud.com
            DTSTART;VALUE=DATE:20260106
            DTEND;VALUE=DATE:20260107
            SUMMARY:Ellie Birthday
            RRULE:FREQ=YEARLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-01-01"),
            parseUtcDate("2026-12-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(1, occurrences.size)

        val occ = occurrences[0]

        // CRITICAL: For single-day all-day events, startDay must equal endDay
        assertEquals(
            "REGRESSION CHECK: Birthday should be single day (startDay == endDay)",
            occ.startDay, occ.endDay
        )
        assertEquals(20260106, occ.startDay)

        // Query check
        val jan5Results = database.occurrencesDao().getForDayOnce(20260105)
        val jan6Results = database.occurrencesDao().getForDayOnce(20260106)
        val jan7Results = database.occurrencesDao().getForDayOnce(20260107)

        assertEquals("Should NOT appear on Jan 5", 0, jan5Results.size)
        assertEquals("Should appear on Jan 6", 1, jan6Results.size)
        assertEquals("Should NOT appear on Jan 7", 0, jan7Results.size)
    }

    // ==================== BUG: Multi-Day Event Wrong End Day ====================

    /**
     * BUG: Multi-day all-day events showed on an extra day because
     *      DTEND was not properly adjusted for RFC 5545 exclusive semantics.
     *
     * RFC 5545 says DTEND for VALUE=DATE is exclusive (the day after the last day).
     * So DTSTART=20260115, DTEND=20260118 means Jan 15-17 (3 days), NOT Jan 15-18.
     */
    @Test
    fun `REGRESSION - multi-day all-day event does not show on exclusive end day`() = runTest {
        // 3-day event: Jan 15-17 (DTEND=Jan 18 is exclusive)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:multiday-regression@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260118
            SUMMARY:3-Day Conference
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-01-01"),
            parseUtcDate("2026-01-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(1, occurrences.size)

        val occ = occurrences[0]
        assertEquals(20260115, occ.startDay)
        assertEquals(
            "REGRESSION CHECK: endDay should be Jan 17, NOT Jan 18 (DTEND is exclusive)",
            20260117, occ.endDay
        )

        // Should NOT show on Jan 18
        val jan18Results = database.occurrencesDao().getForDayOnce(20260118)
        assertEquals(
            "REGRESSION CHECK: Should NOT appear on Jan 18 (exclusive end)",
            0, jan18Results.size
        )
    }

    // ==================== BUG: Recurring Event Exception Not Hidden ====================

    /**
     * BUG: When an exception event exists for a recurring occurrence,
     *      the original occurrence should be "cancelled" (hidden) and only
     *      the exception should show.
     */
    @Test
    fun `REGRESSION - cancelled occurrence does not appear in day query`() = runTest {
        // Create master event
        val masterEvent = Event(
            uid = "master@test.com",
            calendarId = testCalendarId,
            title = "Weekly Meeting",
            startTs = parseUtcDateTime("2026-01-12 10:00"), // Monday Jan 12
            endTs = parseUtcDateTime("2026-01-12 11:00"),
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=WEEKLY;COUNT=4",
            syncStatus = SyncStatus.SYNCED
        )

        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.generateOccurrences(
            savedMaster,
            parseUtcDate("2026-01-01"),
            parseUtcDate("2026-02-28")
        )

        // Cancel the Jan 19 occurrence (simulates EXDATE or exception)
        val jan19Ts = parseUtcDateTime("2026-01-19 10:00")
        database.occurrencesDao().markCancelled(masterId, jan19Ts)

        // Verify Jan 19 does not appear in day query
        val jan19Results = database.occurrencesDao().getForDayOnce(20260119)
        assertEquals(
            "REGRESSION CHECK: Cancelled occurrence should NOT appear in day query",
            0, jan19Results.size
        )

        // But Jan 12 and Jan 26 should still appear
        val jan12Results = database.occurrencesDao().getForDayOnce(20260112)
        val jan26Results = database.occurrencesDao().getForDayOnce(20260126)

        assertEquals("Jan 12 should still appear", 1, jan12Results.size)
        assertEquals("Jan 26 should still appear", 1, jan26Results.size)
    }

    // ==================== BUG: Event Dots Wrong Days in Month View ====================

    /**
     * BUG: Event dots in month view showed on wrong days when
     *      getDaysWithEventsInMonth used timestamp ranges instead of day codes.
     *
     * The fix ensures we query by day code, not timestamp ranges.
     */
    @Test
    fun `REGRESSION - event dots use day codes not timestamp ranges`() = runTest {
        // Create events on specific days
        val events = listOf(
            createEvent("Event 1", 20260110),
            createEvent("Event 2", 20260115),
            createEvent("Event 3", 20260120),
            createEvent("Event 4", 20260125)
        )

        for (event in events) {
            val id = database.eventsDao().insert(event)
            occurrenceGenerator.generateOccurrences(
                event.copy(id = id),
                parseUtcDate("2026-01-01"),
                parseUtcDate("2026-01-31")
            )
        }

        // Query for days with events (simulates month view dot calculation)
        val daysWithEvents = mutableSetOf<Int>()
        for (day in 1..31) {
            val dayCode = 20260100 + day
            val results = database.occurrencesDao().getForDayOnce(dayCode)
            if (results.isNotEmpty()) {
                daysWithEvents.add(day)
            }
        }

        assertEquals(
            "REGRESSION CHECK: Should have exactly 4 days with events",
            setOf(10, 15, 20, 25),
            daysWithEvents
        )
    }

    // ==================== BUG: TripIt Multi-Day Event Wrong Days ====================

    /**
     * BUG: TripIt-style multi-day events (hotel stays) showed incorrect day range.
     * TripIt uses standard RFC 5545 with exclusive DTEND.
     */
    @Test
    fun `REGRESSION - TripIt multi-day event shows correct days`() = runTest {
        // TripIt: Oct 11-12 hotel stay (DTEND=Oct 13 is exclusive)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//TripIt//Test//EN
            BEGIN:VEVENT
            UID:tripit-hotel@example.com
            DTSTAMP:20251224T143330Z
            DTSTART;VALUE=DATE:20251011
            DTEND;VALUE=DATE:20251013
            SUMMARY:Green Slide Hotel
            LOCATION:San Antonio, TX
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParserService.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2025-10-01"),
            parseUtcDate("2025-10-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(1, occurrences.size)

        val occ = occurrences[0]
        assertEquals(20251011, occ.startDay)
        assertEquals(20251012, occ.endDay) // NOT Oct 13

        // Verify day queries
        val oct10 = database.occurrencesDao().getForDayOnce(20251010)
        val oct11 = database.occurrencesDao().getForDayOnce(20251011)
        val oct12 = database.occurrencesDao().getForDayOnce(20251012)
        val oct13 = database.occurrencesDao().getForDayOnce(20251013)

        assertEquals("Should NOT show on Oct 10", 0, oct10.size)
        assertEquals("Should show on Oct 11", 1, oct11.size)
        assertEquals("Should show on Oct 12", 1, oct12.size)
        assertEquals("Should NOT show on Oct 13 (exclusive DTEND)", 0, oct13.size)
    }

    // ==================== Helper Functions ====================

    private fun createEvent(title: String, dayCode: Int): Event {
        val year = dayCode / 10000
        val month = (dayCode % 10000) / 100
        val day = dayCode % 100

        val startTs = LocalDate.of(year, month, day)
            .atTime(10, 0)
            .atZone(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        return Event(
            uid = "$title-${dayCode}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }

    private fun parseUtcDate(dateStr: String): Long {
        val parts = dateStr.split("-")
        return LocalDate.of(
            parts[0].toInt(),
            parts[1].toInt(),
            parts[2].toInt()
        ).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun parseUtcDateTime(dateTimeStr: String): Long {
        val parts = dateTimeStr.split(" ")
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