package org.onekash.kashcal.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for EXDATE format handling.
 *
 * BUG REPORT (v21.1.1):
 * - User deletes occurrence on KashCal
 * - Syncs to iCloud
 * - EXDATE shows 1969 date instead of correct date
 * - iPhone doesn't recognize the deleted occurrence
 *
 * ROOT CAUSE:
 * - EventWriter.addToExdate stored day codes (YYYYMMDD like "20260120")
 * - IcsPatcher.parseExdates expected milliseconds
 * - "20260120" interpreted as 20,260,120 milliseconds from epoch = Dec 31, 1969
 *
 * ALSO AFFECTED:
 * - Server-synced EXDATE (milliseconds) not recognized by OccurrenceGenerator
 *   which expected day codes (YYYYMMDD)
 *
 * FIX:
 * - Unify on milliseconds format
 * - EventWriter.addToExdate stores milliseconds
 * - OccurrenceGenerator.parseMultiValueField handles both formats (backward compat)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExdateFormatRegressionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Setup test calendar
        val accountId = database.accountsDao().insert(
            Account(provider = "icloud", email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://caldav.icloud.com/test/",
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== EventWriter.addToExdate Tests ====================

    @Test
    fun `deleteSingleOccurrence stores milliseconds format`() = runTest {
        // Create recurring event
        val startTs = 1768867200000L // Jan 20, 2026 00:00 UTC
        val event = eventWriter.createEvent(
            Event(
                uid = "exdate-format-test@kashcal.test",
                calendarId = testCalendarId,
                title = "Weekly Meeting",
                startTs = startTs,
                endTs = startTs + 3600000, // 1 hour
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5",
                syncStatus = SyncStatus.SYNCED
            ),
            isLocal = false
        )

        // Delete the second occurrence (Jan 27, 2026)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals("Should have 5 occurrences", 5, occurrences.size)

        val targetOccurrence = occurrences[1] // Second occurrence
        eventWriter.deleteSingleOccurrence(event.id, targetOccurrence.startTs, isLocal = false)

        // Verify exdate is stored as milliseconds
        val updated = database.eventsDao().getById(event.id)
        assertNotNull("Event should exist", updated)
        assertNotNull("Exdate should be set", updated!!.exdate)

        // Key assertion: exdate should be milliseconds, not day code
        val exdateValue = updated.exdate!!

        // Milliseconds are 13 digits (for dates around 2026)
        // Day codes are 8 digits (YYYYMMDD)
        assertTrue(
            "EXDATE should be milliseconds format (>= 10 digits), got: $exdateValue",
            exdateValue.length >= 10
        )

        // Should be parseable as Long
        val timestamp = exdateValue.toLongOrNull()
        assertNotNull("EXDATE should be parseable as Long", timestamp)

        // Should be a reasonable timestamp (after year 2000)
        assertTrue(
            "EXDATE timestamp should be after year 2000",
            timestamp!! > 946684800000L // Jan 1, 2000
        )

        // Should NOT be a 1969/1970 date (the bug symptom)
        assertTrue(
            "EXDATE should not be near Unix epoch (the bug)",
            timestamp > 86400000L * 365 // More than 1 year from epoch
        )
    }

    @Test
    fun `exdate from deleteSingleOccurrence works with IcsPatcher`() = runTest {
        // Create recurring event with rawIcal
        val startTs = 1768867200000L // Jan 20, 2026 00:00 UTC
        val rawIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:round-trip@kashcal.test
            DTSTAMP:20260120T000000Z
            DTSTART:20260120T000000Z
            DTEND:20260120T010000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Round Trip Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = eventWriter.createEvent(
            Event(
                uid = "round-trip@kashcal.test",
                calendarId = testCalendarId,
                title = "Round Trip Test",
                startTs = startTs,
                endTs = startTs + 3600000,
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5",
                rawIcal = rawIcs,
                syncStatus = SyncStatus.SYNCED
            ),
            isLocal = false
        )

        // Delete the second occurrence
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val targetTs = occurrences[1].startTs
        eventWriter.deleteSingleOccurrence(event.id, targetTs, isLocal = false)

        // Get updated event and generate ICS via IcsPatcher
        val updated = database.eventsDao().getById(event.id)!!
        val patchedIcs = IcsPatcher.patch(rawIcs, updated)

        // The patched ICS should have a valid EXDATE (not 1969)
        assertFalse(
            "Patched ICS should not contain 1969 date (the bug)",
            patchedIcs.contains("1969")
        )

        // Should contain EXDATE with 2026 date
        assertTrue(
            "Patched ICS should contain EXDATE",
            patchedIcs.contains("EXDATE")
        )

        // EXDATE should reference January 2026
        assertTrue(
            "Patched ICS EXDATE should be in 2026, got:\n$patchedIcs",
            patchedIcs.contains("EXDATE") && patchedIcs.contains("2026")
        )
    }

    // ==================== OccurrenceGenerator Tests ====================

    @Test
    fun `OccurrenceGenerator handles milliseconds format EXDATE`() = runTest {
        // Use June 2024 dates (matching working OccurrenceEdgeCasesTest)
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC
        val exdateMs = 1718496000000L // June 16, 2024 00:00 UTC (second occurrence)

        val event = Event(
            uid = "ms-exdate@kashcal.test",
            calendarId = testCalendarId,
            title = "Milliseconds EXDATE Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = exdateMs.toString(), // Milliseconds format (from ICalEventMapper)
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from RRULE - 1 from EXDATE = 4
        assertEquals("Should have 4 occurrences (5 - 1 EXDATE)", 4, count)
    }

    @Test
    fun `OccurrenceGenerator handles day code format EXDATE for backward compat`() = runTest {
        // Use June 2024 dates (matching working OccurrenceEdgeCasesTest)
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "daycode-exdate@kashcal.test",
            calendarId = testCalendarId,
            title = "Day Code EXDATE Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240616", // Day code format - June 16, 2024
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from RRULE - 1 from EXDATE = 4
        assertEquals("Should have 4 occurrences (5 - 1 EXDATE)", 4, count)
    }

    @Test
    fun `OccurrenceGenerator handles mixed format EXDATE`() = runTest {
        // Use June 2024 dates (matching working OccurrenceEdgeCasesTest)
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC
        val exdateMs = 1718496000000L // June 16, 2024 00:00 UTC (milliseconds)

        val event = Event(
            uid = "mixed-exdate@kashcal.test",
            calendarId = testCalendarId,
            title = "Mixed Format EXDATE Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "$exdateMs,20240618", // Mixed: milliseconds (June 16) + day code (June 18)
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
        assertEquals("Should have 3 occurrences (5 - 2 EXDATE)", 3, count)
    }

    // ==================== Full Round-Trip Test ====================

    @Test
    fun `full round trip - delete occurrence locally and verify ICS output`() = runTest {
        val startTs = 1768867200000L // Jan 20, 2026 00:00 UTC
        val rawIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//KashCal 2.0//EN
            BEGIN:VEVENT
            UID:full-roundtrip@kashcal.test
            DTSTAMP:20260120T000000Z
            DTSTART:20260120T140000Z
            DTEND:20260120T150000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Weekly Team Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // 1. Create recurring event (simulating server sync)
        val event = eventWriter.createEvent(
            Event(
                uid = "full-roundtrip@kashcal.test",
                calendarId = testCalendarId,
                title = "Weekly Team Meeting",
                startTs = startTs + 14 * 3600000, // 14:00 UTC
                endTs = startTs + 15 * 3600000, // 15:00 UTC
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5",
                rawIcal = rawIcs,
                syncStatus = SyncStatus.SYNCED
            ),
            isLocal = false
        )

        // 2. Delete second occurrence (Jan 27, 2026 14:00 UTC)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val targetTs = occurrences[1].startTs
        eventWriter.deleteSingleOccurrence(event.id, targetTs, isLocal = false)

        // 3. Generate ICS for push to server
        val updated = database.eventsDao().getById(event.id)!!
        val patchedIcs = IcsPatcher.patch(rawIcs, updated)

        // 4. Verify the ICS
        println("Generated ICS:\n$patchedIcs")

        // Should have EXDATE
        assertTrue("Should contain EXDATE", patchedIcs.contains("EXDATE"))

        // Should NOT have 1969 date (the original bug)
        assertFalse("Should not contain 1969 (bug symptom)", patchedIcs.contains("1969"))

        // Should have 2026 date in EXDATE
        val exdateLine = patchedIcs.lines().find { it.startsWith("EXDATE") }
        assertNotNull("Should have EXDATE line", exdateLine)
        assertTrue(
            "EXDATE should reference 2026: $exdateLine",
            exdateLine!!.contains("2026")
        )
    }
}
