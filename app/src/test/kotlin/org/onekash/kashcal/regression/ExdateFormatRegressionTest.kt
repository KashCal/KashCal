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
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the stored EXDATE format (v21.1.1).
 *
 * `Event.exdate` is comma-separated epoch milliseconds: a single-occurrence delete in [EventWriter]
 * appends milliseconds, the pull stores them, and [IcsPatcher] reads them on push. A YYYYMMDD day
 * code read as milliseconds ("20260120" is 20,260,120 ms) lands near the epoch, so the EXDATE
 * pushed to iCloud names a 1969 or 1970 date and other clients keep showing the deleted occurrence.
 * Expansion ([OccurrenceGenerator], through the engine adapter's `parseCsvDates`) also accepts
 * legacy day codes, alone or mixed with milliseconds.
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Setup test calendar
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
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

    // ==================== EXDATE Stored by a Single-Occurrence Delete ====================

    @Test
    fun `deleteSingleOccurrence stores milliseconds format`() = runTest {
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

        // Delete the second occurrence (Jan 21, 2026).
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals("Should have 5 occurrences", 5, occurrences.size)

        val targetOccurrence = occurrences[1]
        eventWriter.deleteSingleOccurrence(event.id, targetOccurrence.startTs, isLocal = false)

        val updated = database.eventsDao().getById(event.id)
        assertNotNull("Event should exist", updated)
        assertNotNull("Exdate should be set", updated!!.exdate)

        // Milliseconds, not a day code.
        val exdateValue = updated.exdate!!

        // Milliseconds for 2026 have 13 digits; a YYYYMMDD day code has 8.
        assertTrue(
            "EXDATE should be milliseconds format (>= 10 digits), got: $exdateValue",
            exdateValue.length >= 10
        )

        val timestamp = exdateValue.toLongOrNull()
        assertNotNull("EXDATE should be parseable as Long", timestamp)

        assertTrue(
            "EXDATE timestamp should be after year 2000",
            timestamp!! > 946684800000L // Jan 1, 2000
        )

        // Not near the epoch, where a day code read as milliseconds lands.
        assertTrue(
            "EXDATE should not be near Unix epoch (the bug)",
            timestamp > 86400000L * 365 // More than 1 year from epoch
        )
    }

    @Test
    fun `exdate from deleteSingleOccurrence works with IcsPatcher`() = runTest {
        // A recurring event with rawIcal, so the push patches it.
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

        // Delete the second occurrence.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val targetTs = occurrences[1].startTs
        eventWriter.deleteSingleOccurrence(event.id, targetTs, isLocal = false)

        val updated = database.eventsDao().getById(event.id)!!
        val patchedIcs = IcsPatcher.patch(rawIcs, updated)

        assertFalse(
            "Patched ICS should not contain 1969 date (the bug)",
            patchedIcs.contains("1969")
        )

        assertTrue(
            "Patched ICS should contain EXDATE",
            patchedIcs.contains("EXDATE")
        )

        // Only checks that the ICS contains EXDATE and "2026" somewhere.
        assertTrue(
            "Patched ICS EXDATE should be in 2026, got:\n$patchedIcs",
            patchedIcs.contains("EXDATE") && patchedIcs.contains("2026")
        )
    }

    // ==================== EXDATE Formats in Expansion ====================

    @Test
    fun `OccurrenceGenerator handles milliseconds format EXDATE`() = runTest {
        // June 2024 dates, as in OccurrenceEdgeCasesTest.
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
            exdate = exdateMs.toString(), // Milliseconds, the format the pull stores.
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from the RRULE minus 1 EXDATE.
        assertEquals("Should have 4 occurrences (5 - 1 EXDATE)", 4, count)
    }

    @Test
    fun `OccurrenceGenerator handles day code format EXDATE for backward compat`() = runTest {
        // June 2024 dates, as in OccurrenceEdgeCasesTest.
        val startTs = 1718409600000L // June 15, 2024 00:00 UTC

        val event = Event(
            uid = "daycode-exdate@kashcal.test",
            calendarId = testCalendarId,
            title = "Day Code EXDATE Event",
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY;COUNT=5",
            exdate = "20240616", // Legacy day code: June 16, 2024.
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from the RRULE minus 1 EXDATE.
        assertEquals("Should have 4 occurrences (5 - 1 EXDATE)", 4, count)
    }

    @Test
    fun `OccurrenceGenerator handles mixed format EXDATE`() = runTest {
        // June 2024 dates, as in OccurrenceEdgeCasesTest.
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
            exdate = "$exdateMs,20240618", // Milliseconds (June 16) and a day code (June 18).
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        val savedEvent = database.eventsDao().getById(eventId)!!

        val count = occurrenceGenerator.generateOccurrences(
            savedEvent,
            startTs - 86400000,
            startTs + 10 * 86400000
        )

        // 5 from the RRULE minus 2 EXDATEs.
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

        // 1. A recurring event carrying its server ICS in rawIcal, standing in for a pulled one.
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

        // 2. Delete the second occurrence (Jan 21, 2026 14:00 UTC).
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val targetTs = occurrences[1].startTs
        eventWriter.deleteSingleOccurrence(event.id, targetTs, isLocal = false)

        // 3. Patch the ICS the push would send.
        val updated = database.eventsDao().getById(event.id)!!
        val patchedIcs = IcsPatcher.patch(rawIcs, updated)

        println("Generated ICS:\n$patchedIcs")

        assertTrue("Should contain EXDATE", patchedIcs.contains("EXDATE"))

        assertFalse("Should not contain 1969 (bug symptom)", patchedIcs.contains("1969"))

        // The EXDATE line names a 2026 date.
        val exdateLine = patchedIcs.lines().find { it.startsWith("EXDATE") }
        assertNotNull("Should have EXDATE line", exdateLine)
        assertTrue(
            "EXDATE should reference 2026: $exdateLine",
            exdateLine!!.contains("2026")
        )
    }
}
