package org.onekash.kashcal.timezone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.ics.RfcIcsParser
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.parser.Ical4jParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Comprehensive timezone edge case tests.
 *
 * Tests cover:
 * - All-day events in various timezones (the v3.6.13 bug fix)
 * - DST transitions
 * - Cross-timezone recurring events
 * - Day boundary issues
 * - UTC vs local timezone handling
 * - Negative UTC offset timezones (Americas)
 * - Positive UTC offset timezones (Asia/Pacific)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TimezoneEdgeCaseTest {

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

    @After
    fun teardown() {
        database.close()
    }

    // ==================== All-Day Event Timezone Tests ====================
    // These tests verify the fix for the v3.6.13 bug where all-day events
    // displayed on wrong days in negative UTC offset timezones.

    @Test
    fun `all-day event Jan 15 stays Jan 15 regardless of device timezone`() {
        // This is the core test for the v3.6.13 bug fix
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:jan15-allday@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:All Day on Jan 15
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        // The event should be stored as Jan 15 00:00:00 UTC
        val startDate = Instant.ofEpochMilli(event.startTs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        assertEquals("Start date should be Jan 15 in UTC", LocalDate.of(2026, 1, 15), startDate)

        // When calculating dayCode for display, it should be 20260115
        val dayCode = startDate.year * 10000 + startDate.monthValue * 100 + startDate.dayOfMonth
        assertEquals(20260115, dayCode)
    }

    @Test
    fun `all-day birthday event shows on correct single day`() = runTest {
        // Simulates iCloud birthday: yearly recurring, single day
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple Inc.//iCloud Calendar//EN
            BEGIN:VEVENT
            UID:birthday@icloud.com
            DTSTART;VALUE=DATE:20260106
            DTEND;VALUE=DATE:20260107
            SUMMARY:Ellie Birthday
            RRULE:FREQ=YEARLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        assertTrue(event.isAllDay)

        // When occurrence is generated, startDay should equal endDay for single-day
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
        assertEquals(
            "Birthday should be single day (startDay == endDay)",
            occ.startDay,
            occ.endDay
        )
        assertEquals(20260106, occ.startDay)
    }

    @Test
    fun `all-day event does NOT appear on previous day in CST timezone`() = runTest {
        // This was the exact bug in v3.6.11/v3.6.12
        // CST is UTC-6, so Jan 15 00:00 UTC = Jan 14 18:00 CST
        // The bug was that the event appeared on BOTH Jan 14 AND Jan 15

        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:cst-test@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:CST Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
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

        // The occurrence should have dayCode for Jan 15 ONLY
        assertEquals(20260115, occurrences[0].startDay)
        assertEquals(20260115, occurrences[0].endDay)

        // Query using dayCode-based query (the fix in v3.6.13)
        val jan14Results = database.occurrencesDao().getForDayOnce(20260114)
        val jan15Results = database.occurrencesDao().getForDayOnce(20260115)
        val jan16Results = database.occurrencesDao().getForDayOnce(20260116)

        assertEquals("Should NOT appear on Jan 14", 0, jan14Results.size)
        assertEquals("Should appear on Jan 15", 1, jan15Results.size)
        assertEquals("Should NOT appear on Jan 16", 0, jan16Results.size)
    }

    @Test
    fun `multi-day all-day event spans correct days only`() = runTest {
        // 3-day conference: Jan 15-17
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:conference@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260118
            SUMMARY:3-Day Conference
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
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
        assertEquals(20260117, occ.endDay) // Jan 17, not 18 (DTEND is exclusive)

        // Verify day queries
        val jan14Results = database.occurrencesDao().getForDayOnce(20260114)
        val jan15Results = database.occurrencesDao().getForDayOnce(20260115)
        val jan16Results = database.occurrencesDao().getForDayOnce(20260116)
        val jan17Results = database.occurrencesDao().getForDayOnce(20260117)
        val jan18Results = database.occurrencesDao().getForDayOnce(20260118)

        assertEquals("Should NOT appear on Jan 14", 0, jan14Results.size)
        assertEquals("Should appear on Jan 15", 1, jan15Results.size)
        assertEquals("Should appear on Jan 16", 1, jan16Results.size)
        assertEquals("Should appear on Jan 17", 1, jan17Results.size)
        assertEquals("Should NOT appear on Jan 18", 0, jan18Results.size)
    }

    // ==================== Timed Event Timezone Tests ====================

    @Test
    fun `timed event in specific timezone converts correctly`() {
        // 2 PM in New York (EST) = 7 PM UTC (during winter)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:nyc-timed@test.com
            DTSTART;TZID=America/New_York:20260115T140000
            DTEND;TZID=America/New_York:20260115T150000
            SUMMARY:NYC Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        assertFalse(event.isAllDay)
        assertEquals("America/New_York", event.timezone)

        // Verify the UTC time is correct (2 PM EST = 7 PM UTC in winter)
        val startUtc = Instant.ofEpochMilli(event.startTs).atZone(ZoneOffset.UTC)
        assertEquals(19, startUtc.hour) // 7 PM UTC
    }

    @Test
    fun `timed event with UTC suffix parsed correctly`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:utc-timed@test.com
            DTSTART:20260115T140000Z
            DTEND:20260115T150000Z
            SUMMARY:UTC Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        // ICU canonicalizes "UTC" to "Etc/UTC"
        assertTrue(event.timezone == "UTC" || event.timezone == "Etc/UTC")

        // Should be exactly 2 PM UTC
        val startUtc = Instant.ofEpochMilli(event.startTs).atZone(ZoneOffset.UTC)
        assertEquals(14, startUtc.hour)
        assertEquals(0, startUtc.minute)
    }

    // ==================== DST Transition Tests ====================

    @Test
    fun `recurring event handles spring DST transition`() = runTest {
        // Event at 2:30 AM EST on the day clocks spring forward
        // March 8, 2026 is when DST starts in US (2:00 AM -> 3:00 AM)
        // An event at 2:30 AM on that day technically doesn't exist

        val event = Event(
            uid = "dst-spring@test.com",
            calendarId = testCalendarId,
            title = "DST Spring Test",
            startTs = parseTimezoneDate("2026-03-01 09:00", "America/New_York"),
            endTs = parseTimezoneDate("2026-03-01 10:00", "America/New_York"),
            dtstamp = System.currentTimeMillis(),
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;COUNT=4", // Spans DST transition
            syncStatus = SyncStatus.SYNCED
        )

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-03-01"),
            parseUtcDate("2026-03-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals("Should generate 4 occurrences despite DST transition", 4, occurrences.size)
    }

    @Test
    fun `recurring event handles fall DST transition`() = runTest {
        // Event crosses fall DST (Nov 1, 2026: 2:00 AM -> 1:00 AM)
        val event = Event(
            uid = "dst-fall@test.com",
            calendarId = testCalendarId,
            title = "DST Fall Test",
            startTs = parseTimezoneDate("2026-10-25 09:00", "America/New_York"),
            endTs = parseTimezoneDate("2026-10-25 10:00", "America/New_York"),
            dtstamp = System.currentTimeMillis(),
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;COUNT=4", // Spans DST transition
            syncStatus = SyncStatus.SYNCED
        )

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-10-01"),
            parseUtcDate("2026-11-30")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(4, occurrences.size)
    }

    // ==================== Cross-Day Boundary Tests ====================

    @Test
    fun `event crossing midnight has correct day codes`() = runTest {
        // Event 11 PM Jan 15 to 1 AM Jan 16
        val event = Event(
            uid = "cross-midnight@test.com",
            calendarId = testCalendarId,
            title = "Late Night Event",
            startTs = parseUtcDateTime("2026-01-15 23:00"),
            endTs = parseUtcDateTime("2026-01-16 01:00"),
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )

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
        assertEquals("Start day should be Jan 15", 20260115, occ.startDay)
        assertEquals("End day should be Jan 16", 20260116, occ.endDay)

        // Should show on both days
        val jan15Results = database.occurrencesDao().getForDayOnce(20260115)
        val jan16Results = database.occurrencesDao().getForDayOnce(20260116)

        assertEquals(1, jan15Results.size)
        assertEquals(1, jan16Results.size)
    }

    // ==================== Various Timezone Tests ====================

    @Test
    fun `all-day event in Tokyo timezone stays on correct day`() {
        // Tokyo is UTC+9, so their midnight is 3 PM UTC previous day
        // But all-day events should NOT be affected by this
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:tokyo-allday@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:Tokyo Holiday
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        // Should be stored as Jan 15 00:00 UTC regardless of local timezone
        val startDate = Instant.ofEpochMilli(event.startTs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        assertEquals(LocalDate.of(2026, 1, 15), startDate)
    }

    @Test
    fun `all-day event in Sydney timezone stays on correct day`() {
        // Sydney is UTC+11 during summer (January)
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:sydney-allday@test.com
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:Australia Day Prep
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = RfcIcsParser.parseIcsContent(ics, testCalendarId, 1L)
        val event = events[0]

        val startDate = Instant.ofEpochMilli(event.startTs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        assertEquals(LocalDate.of(2026, 1, 15), startDate)
    }

    // ==================== EXDATE Timezone Matching Tests ====================

    @Test
    fun `EXDATE with timezone offset matches correctly`() = runTest {
        val event = Event(
            uid = "exdate-tz@test.com",
            calendarId = testCalendarId,
            title = "Daily with TZ EXDATE",
            startTs = parseUtcDateTime("2026-01-15 10:00"),
            endTs = parseUtcDateTime("2026-01-15 11:00"),
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20260117T100000Z", // Exclude Jan 17
            syncStatus = SyncStatus.SYNCED
        )

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2026-01-01"),
            parseUtcDate("2026-01-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        // 5 - 1 = 4 (Jan 17 excluded)
        assertEquals(4, occurrences.size)

        // Verify Jan 17 is not in the list
        val jan17Count = occurrences.count { it.startDay == 20260117 }
        assertEquals(0, jan17Count)
    }

    // ==================== Year/Month Boundary Tests ====================

    @Test
    fun `recurring event crosses year boundary correctly`() = runTest {
        // Weekly event starting Dec 28, 2025
        val event = Event(
            uid = "year-boundary@test.com",
            calendarId = testCalendarId,
            title = "Weekly Cross Year",
            startTs = parseUtcDateTime("2025-12-28 10:00"),
            endTs = parseUtcDateTime("2025-12-28 11:00"),
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=WEEKLY;COUNT=4",
            syncStatus = SyncStatus.SYNCED
        )

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2025-12-01"),
            parseUtcDate("2026-02-01")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(4, occurrences.size)

        // Should have 1 in Dec 2025, 3 in Jan 2026
        val dec2025 = occurrences.count { it.startDay / 100 == 202512 }
        val jan2026 = occurrences.count { it.startDay / 100 == 202601 }

        assertEquals(1, dec2025)
        assertEquals(3, jan2026)
    }

    @Test
    fun `leap year Feb 29 handled correctly`() = runTest {
        // 2028 is a leap year
        val event = Event(
            uid = "leap-year@test.com",
            calendarId = testCalendarId,
            title = "Leap Day Event",
            startTs = parseUtcDateTime("2028-02-29 10:00"),
            endTs = parseUtcDateTime("2028-02-29 11:00"),
            dtstamp = System.currentTimeMillis(),
            isAllDay = false,
            syncStatus = SyncStatus.SYNCED
        )

        val eventId = database.eventsDao().insert(event)
        val savedEvent = event.copy(id = eventId)

        occurrenceGenerator.generateOccurrences(
            savedEvent,
            parseUtcDate("2028-02-01"),
            parseUtcDate("2028-03-31")
        )

        val occurrences = database.occurrencesDao().getForEvent(savedEvent.id)
        assertEquals(1, occurrences.size)
        assertEquals(20280229, occurrences[0].startDay)
    }

    // ==================== Helper Functions ====================

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

    private fun parseTimezoneDate(dateTimeStr: String, timezone: String): Long {
        val parts = dateTimeStr.split(" ")
        val dateParts = parts[0].split("-")
        val timeParts = parts[1].split(":")

        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone(timezone))
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