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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests exception linking for ICS exception patterns (for example first or last occurrence,
 * moved, all-day, timezone, UNTIL, monthly) and its survival across regeneration.
 *
 * These ICS files were created by interacting with a real iCloud CalDAV server. Each test
 * parses the ICS, inserts the events, generates occurrences and checks how the exceptions
 * link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsIterationTest {

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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
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

    // ==================== Iteration 1: First Occurrence Exception ====================

    @Test
    fun `iter1 - exception at first occurrence parses and links correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter1_first_occurrence.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 1 exception", 2, result!!.totalEvents)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)

        // The first occurrence is linked to the exception
        val firstOcc = occurrences.minByOrNull { it.startTs }!!
        assertNotNull("First occurrence should have exception link", firstOcc.exceptionEventId)

        // No duplicates
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("All occurrences should have unique times", occurrences.size, uniqueTimes.size)
    }

    // ==================== Iteration 2: Last Occurrence Exception ====================

    @Test
    fun `iter2 - exception at last occurrence parses and links correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter2_last_occurrence.ics")

        assertNotNull("Should parse successfully", result)

        val occurrences = database.occurrencesDao().getForEvent(result!!.masterId)

        // COUNT occurrences (5)
        assertEquals("Should have 5 occurrences", 5, occurrences.size)

        // The last occurrence is linked to the exception
        val lastOcc = occurrences.maxByOrNull { it.startTs }!!
        assertNotNull("Last occurrence should have exception link", lastOcc.exceptionEventId)
    }

    // ==================== Iteration 3: Overlap Exception ====================

    @Test
    fun `iter3 - exception moved to overlap another occurrence handled correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter3_overlap.ics")

        assertNotNull("Should parse successfully", result)

        val occurrences = database.occurrencesDao().getForEvent(result!!.masterId)

        // Feb 8 is moved to Feb 15's time, which must not cause a duplicate. linkException
        // deletes the master's Feb 15 occurrence and moves the Feb 8 row there, linked to
        // the exception.
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times after overlap handling", occurrences.size, uniqueTimes.size)

        // One exception linked
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have exactly 1 exception linked", 1, linkedCount)
    }

    // ==================== Iteration 4: All-Day Exception ====================

    @Test
    fun `iter4 - all-day event exception parses correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter4_allday.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have master and exception", 2, result!!.totalEvents)

        // The master is all-day
        val master = database.eventsDao().getById(result.masterId)!!
        assertTrue("Master should be all-day", master.isAllDay)

        // The exception is all-day too
        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
        assertTrue("Exception should be all-day", exceptions.first().isAllDay)
    }

    // ==================== Iteration 5: Recreate Pattern ====================

    @Test
    fun `iter5 - recreate pattern parses correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter5_recreate.ics")

        assertNotNull("Should parse successfully", result)

        val occurrences = database.occurrencesDao().getForEvent(result!!.masterId)

        // Feb 3 is re-added as an exception at a different time
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have exactly 1 exception linked", 1, linkedCount)

        // No duplicates
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times", occurrences.size, uniqueTimes.size)
    }

    // ==================== Iteration 6: Multiple Consecutive Exceptions ====================

    @Test
    fun `iter6 - three consecutive exceptions all link correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter6_consecutive.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 3 exceptions", 4, result!!.totalEvents)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }

        assertEquals("Should have exactly 3 exceptions linked", 3, linkedCount)

        // No duplicates
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times", occurrences.size, uniqueTimes.size)
    }

    // ==================== Iteration 7: Duration Change Only ====================

    @Test
    fun `iter7 - duration-only change links correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter7_duration.ics")

        assertNotNull("Should parse successfully", result)

        val occurrences = database.occurrencesDao().getForEvent(result!!.masterId)
        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)

        assertEquals("Should have 1 exception", 1, exceptions.size)

        // The exception runs 3 hours instead of 1
        val exception = exceptions.first()
        val durationMs = exception.endTs - exception.startTs
        assertEquals("Exception should have 3-hour duration", 3 * 60 * 60 * 1000L, durationMs)

        // Only the duration changed, not the start; the occurrence is linked
        val linkedOcc = occurrences.find { it.exceptionEventId == exception.id }
        assertNotNull("Exception should be linked to occurrence", linkedOcc)
    }

    // ==================== Iteration 8: Timezone Exception ====================

    @Test
    fun `iter8 - timezone in RECURRENCE-ID parses correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter8_timezone.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertEquals("Master should have America/New_York timezone", "America/New_York", master.timezone)

        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
        assertEquals("Should have 1 exception", 1, exceptions.size)

        // The exception has the same timezone
        assertEquals("Exception should have same timezone", "America/New_York", exceptions.first().timezone)
    }

    // ==================== Iteration 9: UNTIL-based Recurrence ====================

    @Test
    fun `iter9 - UNTIL recurrence with exception parses correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter9_until.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertTrue("Master should have UNTIL in RRULE", master.rrule!!.contains("UNTIL"))

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)

        // Occurrences up to the UNTIL date
        assertTrue("Should have multiple occurrences", occurrences.size >= 3)

        // The exception is linked
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 1 exception linked", 1, linkedCount)
    }

    // ==================== Iteration 10: Monthly with Multiple Exceptions ====================

    @Test
    fun `iter10 - monthly recurrence with 2 exceptions links correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter10_monthly.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 2 exceptions", 3, result!!.totalEvents)

        val master = database.eventsDao().getById(result.masterId)!!
        assertTrue("Master should have MONTHLY RRULE", master.rrule!!.contains("MONTHLY"))

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }

        assertEquals("Should have 2 exceptions linked", 2, linkedCount)

        // No duplicates
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times", occurrences.size, uniqueTimes.size)
    }

    // ==================== Iteration 11: Many Exceptions (5) ====================

    @Test
    fun `iter11 - event with 5 exceptions all link correctly`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter11_many_exceptions.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 5 exceptions", 6, result!!.totalEvents)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }

        assertEquals("Should have 5 exceptions linked", 5, linkedCount)

        // All exceptions have different times
        val exceptionOccs = occurrences.filter { it.exceptionEventId != null }
        val uniqueExceptionTimes = exceptionOccs.map { it.startTs }.toSet()
        assertEquals("All exceptions should have unique times", 5, uniqueExceptionTimes.size)

        // No duplicates overall
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times", occurrences.size, uniqueTimes.size)
    }

    // ==================== RRULE Regeneration Tests ====================

    @Test
    fun `exceptions survive RRULE regeneration without duplicates`() = runTest {
        val result = parseAndProcess("ical/exceptions/iterations/iter6_consecutive.ics")
        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!

        // Regenerate occurrences (simulates server sync updating master)
        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)

        // All 3 exceptions stay linked
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("All 3 exceptions should survive regeneration", 3, linkedCount)

        // No duplicates
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals("No duplicate times after regeneration", occurrences.size, uniqueTimes.size)
    }

    @Test
    fun `moved exception survives RRULE regeneration without creating original occurrence`() = runTest {
        // Regeneration must not recreate the occurrence at a moved exception's original
        // time (v21.5.2)
        val result = parseAndProcess("ical/exceptions/iterations/iter1_first_occurrence.ics")
        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
        assertEquals("Should have 1 exception", 1, exceptions.size)

        val exception = exceptions.first()
        val originalTime = exception.originalInstanceTime
        assertNotNull("Exception should have originalInstanceTime", originalTime)

        occurrenceGenerator.regenerateOccurrences(master)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)

        // No unlinked occurrence at the original time
        val atOriginalTime = occurrences.filter {
            kotlin.math.abs(it.startTs - originalTime!!) < 60000 && it.exceptionEventId == null
        }
        assertEquals(
            "Should NOT have unlinked occurrence at original time",
            0, atOriginalTime.size
        )

        // The exception is still linked
        val linkedOcc = occurrences.find { it.exceptionEventId == exception.id }
        assertNotNull("Exception should still be linked after regeneration", linkedOcc)
    }

    // ==================== Helper Methods ====================

    data class ProcessResult(
        val masterId: Long,
        val totalEvents: Int
    )

    private suspend fun parseAndProcess(path: String): ProcessResult? {
        val icsContent = loadIcsFixture(path) ?: return null

        val parseResult = icalParser.parseAllEvents(icsContent)
        if (parseResult !is ParseResult.Success) return null

        val parsedEvents = parseResult.value
        if (parsedEvents.isEmpty()) return null

        val master = parsedEvents.find { it.recurrenceId == null } ?: return null
        val exceptions = parsedEvents.filter { it.recurrenceId != null }

        // Of exceptions sharing a RECURRENCE-ID, keep the highest SEQUENCE, the latest
        // revision (RFC 5545 §3.8.7.4)
        val uniqueExceptions = exceptions
            .groupBy { it.recurrenceId?.timestamp }
            .mapNotNull { (_, excs) -> excs.maxByOrNull { it.sequence } }

        val masterEvent = ICalEventMapper.toEntity(master, icsContent, testCalendarId, null, null).event
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        occurrenceGenerator.regenerateOccurrences(savedMaster)

        for (exception in uniqueExceptions) {
            val exceptionEvent = ICalEventMapper.toEntity(exception, null, testCalendarId, null, null).event
                .copy(originalEventId = masterId)
            val exceptionId = database.eventsDao().insert(exceptionEvent)
            val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)

            // Link at the RECURRENCE-ID time
            val originalTime = exception.recurrenceId?.timestamp
            if (originalTime != null) {
                occurrenceGenerator.linkException(masterId, originalTime, savedExceptionEvent)
            }
        }

        return ProcessResult(masterId, 1 + uniqueExceptions.size)
    }

    private fun loadIcsFixture(path: String): String? {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
    }
}
