package org.onekash.kashcal.sync.parser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that validate our code handles real ICS exception patterns correctly.
 *
 * These tests use ICS files that represent real-world patterns from iCloud and Nextcloud.
 * The tests verify:
 * - Unique constraint on occurrences (event_id, start_ts) works correctly
 * - linkException handles various exception scenarios
 * - No duplicate occurrences are created
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsExceptionPatternTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var icalParser: ICalParser
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        icalParser = ICalParser()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://caldav.icloud.com/test/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Pattern: Multiple Exceptions Same UID ====================

    @Test
    fun `iCloud pattern - multiple exceptions with same UID creates unique occurrences`() = runTest {
        // This pattern is from multiple_exceptions_modified.ics
        // Master: Weekly at 14:00
        // Exception 1: Dec 23 moved to 10:00
        // Exception 2: Dec 30 extended duration

        val icsContent = loadIcsFixture("ical/exceptions/multiple_exceptions_modified.ics")
        assertNotNull("ICS fixture should exist", icsContent)

        val parseResult = icalParser.parseAllEvents(icsContent!!)
        assertTrue("Should parse successfully", parseResult is ParseResult.Success)

        val parsedEvents = (parseResult as ParseResult.Success).value
        assertTrue("Should parse multiple events", parsedEvents.size >= 2)

        // Find master and exceptions by checking recurrenceId
        val master = parsedEvents.find { it.recurrenceId == null }
        val exceptions = parsedEvents.filter { it.recurrenceId != null }

        assertNotNull("Should have master event", master)
        assertTrue("Should have exceptions", exceptions.isNotEmpty())

        // All should have same UID (RFC 5545 requirement)
        val masterUid = master!!.uid
        exceptions.forEach { exception ->
            assertEquals("Exception should have same UID as master", masterUid, exception.uid)
        }

        // Convert and insert master
        val masterEvent = ICalEventMapper.toEntity(master, icsContent, testCalendarId, null, null)
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.regenerateOccurrences(savedMaster)

        val occurrencesBefore = database.occurrencesDao().getForEvent(masterId)
        assertTrue("Should have occurrences", occurrencesBefore.isNotEmpty())

        // Insert exceptions and link them
        // RFC 5545: When multiple exceptions have the same RECURRENCE-ID, keep highest SEQUENCE
        val exceptionsByRecurrenceId = exceptions.groupBy { it.recurrenceId?.timestamp }
        val uniqueExceptions = exceptionsByRecurrenceId.mapNotNull { (_, excs) ->
            excs.maxByOrNull { it.sequence }
        }

        for (exception in uniqueExceptions) {
            val exceptionEvent = ICalEventMapper.toEntity(exception, null, testCalendarId, null, null)
                .copy(originalEventId = masterId)
            val exceptionId = database.eventsDao().insert(exceptionEvent)
            val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)

            // Link exception to master's occurrence using recurrenceId timestamp
            val originalTime = exception.recurrenceId?.timestamp
            if (originalTime != null) {
                occurrenceGenerator.linkException(masterId, originalTime, savedExceptionEvent)
            }
        }

        // Verify no duplicate occurrences
        val occurrencesAfter = database.occurrencesDao().getForEvent(masterId)
        val uniqueStartTimes = occurrencesAfter.map { it.startTs }.toSet()

        assertEquals(
            "No duplicate start times should exist",
            occurrencesAfter.size,
            uniqueStartTimes.size
        )
    }

    @Test
    fun `iCloud pattern - single exception modifies one occurrence`() = runTest {
        val icsContent = loadIcsFixture("ical/exceptions/recurrence_id_single.ics")
        assertNotNull("ICS fixture should exist", icsContent)

        val parseResult = icalParser.parseAllEvents(icsContent!!)
        assertTrue("Should parse successfully", parseResult is ParseResult.Success)

        val parsedEvents = (parseResult as ParseResult.Success).value
        val master = parsedEvents.find { it.recurrenceId == null }
        val exception = parsedEvents.find { it.recurrenceId != null }

        assertNotNull("Should have master", master)
        assertNotNull("Should have exception", exception)

        // Insert master
        val masterEvent = ICalEventMapper.toEntity(master!!, icsContent, testCalendarId, null, null)
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.regenerateOccurrences(savedMaster)

        val occurrencesBefore = database.occurrencesDao().getForEvent(masterId)
        val originalCount = occurrencesBefore.size

        // Insert and link exception
        val exceptionEvent = ICalEventMapper.toEntity(exception!!, null, testCalendarId, null, null)
            .copy(originalEventId = masterId)
        val exceptionId = database.eventsDao().insert(exceptionEvent)
        val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)

        val originalTime = exception.recurrenceId?.timestamp
        assertNotNull("Exception should have recurrenceId", originalTime)
        occurrenceGenerator.linkException(masterId, originalTime!!, savedExceptionEvent)

        // Occurrence count should remain the same (linked, not added)
        val occurrencesAfter = database.occurrencesDao().getForEvent(masterId)
        assertEquals("Occurrence count should be same after linking", originalCount, occurrencesAfter.size)

        // Find the linked occurrence
        val linkedOccurrence = occurrencesAfter.find { it.exceptionEventId == exceptionId }
        assertNotNull("Should have linked occurrence", linkedOccurrence)
    }

    @Test
    fun `exception moved to same time as another occurrence - conflict handled`() = runTest {
        // This tests the edge case where an exception is moved to overlap with another occurrence
        // Our unique constraint (event_id, start_ts) should handle this

        // Create a weekly event
        val now = System.currentTimeMillis()

        val masterEvent = Event(
            calendarId = testCalendarId,
            uid = "conflict-test@kashcal.test",
            title = "Weekly Meeting",
            startTs = now,
            endTs = now + 3600000,
            timezone = "UTC",
            rrule = "FREQ=WEEKLY;COUNT=5",
            syncStatus = SyncStatus.SYNCED,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )

        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.regenerateOccurrences(savedMaster)

        val occurrences = database.occurrencesDao().getForEvent(masterId)
        assertTrue("Should have at least 3 occurrences", occurrences.size >= 3)

        // Create exception for occurrence[1], moving it to occurrence[2]'s time
        val originalOccurrence = occurrences[1]
        val targetTime = occurrences[2].startTs

        val exceptionEvent = Event(
            calendarId = testCalendarId,
            uid = savedMaster.uid,
            title = "Moved Meeting",
            startTs = targetTime,  // Intentionally same as occurrence[2]
            endTs = targetTime + 3600000,
            timezone = "UTC",
            originalEventId = masterId,
            originalInstanceTime = originalOccurrence.startTs,
            syncStatus = SyncStatus.SYNCED,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )

        val exceptionId = database.eventsDao().insert(exceptionEvent)
        val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)

        // This should handle the conflict gracefully
        occurrenceGenerator.linkException(masterId, originalOccurrence.startTs, savedExceptionEvent)

        // Verify no constraint violation and occurrences are valid
        val occurrencesAfter = database.occurrencesDao().getForEvent(masterId)
        val uniqueStartTimes = occurrencesAfter.map { it.startTs }.toSet()

        assertEquals(
            "No duplicate start times after conflict resolution",
            occurrencesAfter.size,
            uniqueStartTimes.size
        )
    }

    @Test
    fun `creating many exceptions preserves unique occurrences`() = runTest {
        // Simulates sync scenario where multiple exceptions are pulled from server

        val now = System.currentTimeMillis()
        val masterEvent = Event(
            calendarId = testCalendarId,
            uid = "many-exceptions@kashcal.test",
            title = "Daily Standup",
            startTs = now,
            endTs = now + 1800000, // 30 min
            timezone = "UTC",
            rrule = "FREQ=DAILY;COUNT=7",  // Use 7 to stay within expansion window
            syncStatus = SyncStatus.SYNCED,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )

        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.regenerateOccurrences(savedMaster)

        val occurrences = database.occurrencesDao().getForEvent(masterId)
        assertTrue("Should have at least 5 occurrences", occurrences.size >= 5)
        val occurrenceCount = occurrences.size

        // Create exceptions for first 5 occurrences (title changes only, no time changes)
        val duration = savedMaster.endTs - savedMaster.startTs
        val exceptionCount = minOf(5, occurrences.size)
        repeat(exceptionCount) { i ->
            val occ = occurrences[i]
            val exceptionEvent = Event(
                calendarId = testCalendarId,
                uid = savedMaster.uid,
                title = "Standup - Day ${i + 1}",
                startTs = occ.startTs,  // Same time as occurrence
                endTs = occ.startTs + duration,
                timezone = "UTC",
                originalEventId = masterId,
                originalInstanceTime = occ.startTs,
                syncStatus = SyncStatus.SYNCED,
                createdAt = now,
                updatedAt = now,
                dtstamp = now
            )
            val exceptionId = database.eventsDao().insert(exceptionEvent)
            val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)

            occurrenceGenerator.linkException(masterId, occ.startTs, savedExceptionEvent)
        }

        // Verify same occurrence count (linking doesn't change count)
        val finalOccurrences = database.occurrencesDao().getForEvent(masterId)
        assertEquals("Should have same occurrence count", occurrenceCount, finalOccurrences.size)

        val linkedCount = finalOccurrences.count { it.exceptionEventId != null }
        assertEquals("Should have $exceptionCount linked occurrences", exceptionCount, linkedCount)

        // Verify no duplicates
        val uniqueStartTimes = finalOccurrences.map { it.startTs }.toSet()
        assertEquals("All start times should be unique", finalOccurrences.size, uniqueStartTimes.size)
    }

    // ==================== Helper Methods ====================

    private fun loadIcsFixture(path: String): String? {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
    }
}
