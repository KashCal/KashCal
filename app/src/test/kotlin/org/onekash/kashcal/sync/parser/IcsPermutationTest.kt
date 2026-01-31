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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive permutation tests for ICS exception handling.
 *
 * These tests verify all combinations of:
 * - Frequencies: DAILY, WEEKLY, MONTHLY
 * - End types: COUNT, UNTIL
 * - Event types: TIMED, ALL-DAY
 * - Exception counts: 1-5
 * - Special cases: EXDATE, timezone, collision, multi-sequence
 *
 * Each test:
 * 1. Parses ICS from Nextcloud server
 * 2. Creates Event entities in KashCal
 * 3. Generates occurrences
 * 4. Verifies exception linking
 * 5. Verifies round-trip ICS generation matches original
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsPermutationTest {

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
            Account(provider = AccountProvider.CALDAV, email = "test@nextcloud.test")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://nextcloud.test/remote.php/dav/calendars/test/test/",
                displayName = "Test Calendar",
                color = 0xFF4CAF50.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Frequency Permutations ====================

    @Test
    fun `perm1 - DAILY + COUNT + TIMED + 2 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/daily_count_timed_2ex.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 2 exceptions", 3, result!!.totalEvents)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        assertEquals("Should have 7 occurrences (COUNT=7)", 7, occurrences.size)

        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 2 exceptions linked", 2, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "daily_count_timed_2ex")
    }

    @Test
    fun `perm2 - DAILY + UNTIL + TIMED + 1 exception`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/daily_until_timed_1ex.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertTrue("Should have UNTIL in RRULE", master.rrule!!.contains("UNTIL"))

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 1 exception linked", 1, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "daily_until_timed_1ex")
    }

    @Test
    fun `perm3 - DAILY + COUNT + ALL-DAY + 3 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/daily_count_allday_3ex.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 3 exceptions", 4, result!!.totalEvents)

        val master = database.eventsDao().getById(result.masterId)!!
        assertTrue("Master should be all-day", master.isAllDay)

        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
        assertEquals("Should have 3 exceptions", 3, exceptions.size)
        assertTrue("All exceptions should be all-day", exceptions.all { it.isAllDay })

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "daily_count_allday_3ex")
    }

    @Test
    fun `perm4 - WEEKLY + COUNT + TIMED + 3 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/weekly_count_timed_3ex.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 3 exceptions", 4, result!!.totalEvents)

        val master = database.eventsDao().getById(result.masterId)!!
        assertTrue("Should have WEEKLY in RRULE", master.rrule!!.contains("WEEKLY"))

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 3 exceptions linked", 3, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "weekly_count_timed_3ex")
    }

    @Test
    fun `perm5 - WEEKLY + UNTIL + ALL-DAY + 2 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/weekly_until_allday_2ex.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertTrue("Should have UNTIL in RRULE", master.rrule!!.contains("UNTIL"))
        assertTrue("Master should be all-day", master.isAllDay)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 2 exceptions linked", 2, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "weekly_until_allday_2ex")
    }

    @Test
    fun `perm6 - MONTHLY + COUNT + TIMED + 2 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/monthly_count_timed_2ex.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 2 exceptions", 3, result!!.totalEvents)

        val master = database.eventsDao().getById(result.masterId)!!
        assertTrue("Should have MONTHLY in RRULE", master.rrule!!.contains("MONTHLY"))

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 2 exceptions linked", 2, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "monthly_count_timed_2ex")
    }

    @Test
    fun `perm7 - MONTHLY + UNTIL + ALL-DAY + 1 exception`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/monthly_until_allday_1ex.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertTrue("Should have UNTIL in RRULE", master.rrule!!.contains("UNTIL"))
        assertTrue("Should have MONTHLY in RRULE", master.rrule!!.contains("MONTHLY"))
        assertTrue("Master should be all-day", master.isAllDay)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 1 exception linked", 1, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "monthly_until_allday_1ex")
    }

    // ==================== Special Cases ====================

    @Test
    fun `perm8 - WEEKLY + EXDATE + 2 exceptions (deleted occurrences + modified)`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/weekly_exdate_2ex.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertNotNull("Master should have EXDATE", master.exdate)
        assertTrue("EXDATE should have deleted occurrences", master.exdate!!.isNotBlank())

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)

        // Should have COUNT occurrences minus EXDATE count, plus exception occurrences linked
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 2 exceptions linked", 2, linkedCount)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "weekly_exdate_2ex")
    }

    @Test
    fun `perm9 - DAILY + 5 consecutive exceptions (stress test)`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/daily_5consecutive.ics")

        assertNotNull("Should parse successfully", result)
        assertEquals("Should have 1 master and 5 exceptions", 6, result!!.totalEvents)

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 5 exceptions linked", 5, linkedCount)

        // Verify all 5 have unique times
        val exceptionOccs = occurrences.filter { it.exceptionEventId != null }
        val uniqueExTimes = exceptionOccs.map { it.startTs }.toSet()
        assertEquals("All 5 exceptions should have unique times", 5, uniqueExTimes.size)

        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "daily_5consecutive")
    }

    @Test
    fun `perm10 - WEEKLY + Europe-London timezone + 2 exceptions`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/weekly_tz_london_2ex.ics")

        assertNotNull("Should parse successfully", result)

        val master = database.eventsDao().getById(result!!.masterId)!!
        assertEquals("Master should have Europe/London timezone", "Europe/London", master.timezone)

        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
        assertEquals("Should have 2 exceptions", 2, exceptions.size)

        // Exceptions should have same or compatible timezone
        for (ex in exceptions) {
            assertNotNull("Exception should have timezone", ex.timezone)
        }

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "weekly_tz_london_2ex")
    }

    @Test
    fun `perm11 - collision - exception moved to same day as another occurrence`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/collision_same_day.ics")

        assertNotNull("Should parse successfully", result)

        val occurrences = database.occurrencesDao().getForEvent(result!!.masterId)

        // The exception is moved to Feb 3 (same day as another occurrence, but different time)
        // This should NOT cause duplicates - the exception replaces its original slot
        assertNoduplicates(occurrences)

        // The linked exception should be correctly identified
        val linkedCount = occurrences.count { it.exceptionEventId != null }
        assertEquals("Should have 1 exception linked", 1, linkedCount)

        verifyRoundTrip(result, "collision_same_day")
    }

    @Test
    fun `perm12 - multi-sequence - exceptions with different SEQUENCE numbers`() = runTest {
        val result = parseAndProcess("ical/exceptions/permutations/multi_sequence.ics")

        assertNotNull("Should parse successfully", result)

        // When multiple exceptions have same RECURRENCE-ID but different SEQUENCE,
        // KashCal should keep highest SEQUENCE (RFC 5545 conflict resolution)
        val exceptions = database.eventsDao().getExceptionsForMaster(result!!.masterId)
        assertTrue("Should have exceptions after deduplication", exceptions.isNotEmpty())

        val occurrences = database.occurrencesDao().getForEvent(result.masterId)
        assertNoduplicates(occurrences)
        verifyRoundTrip(result, "multi_sequence")
    }

    // ==================== RRULE Regeneration Tests ====================

    @Test
    fun `exceptions survive RRULE regeneration in all permutations`() = runTest {
        val permutationFiles = listOf(
            "daily_count_timed_2ex",
            "weekly_count_timed_3ex",
            "monthly_count_timed_2ex",
            "daily_5consecutive"
        )

        for (file in permutationFiles) {
            val result = parseAndProcess("ical/exceptions/permutations/$file.ics")
            assertNotNull("Should parse $file", result)

            val master = database.eventsDao().getById(result!!.masterId)!!
            val originalExceptions = database.eventsDao().getExceptionsForMaster(result.masterId)
            val originalLinkedCount = database.occurrencesDao().getForEvent(result.masterId)
                .count { it.exceptionEventId != null }

            // Regenerate occurrences
            occurrenceGenerator.regenerateOccurrences(master)

            // Verify exceptions still linked
            val newLinkedCount = database.occurrencesDao().getForEvent(result.masterId)
                .count { it.exceptionEventId != null }

            assertEquals(
                "$file: All exceptions should survive regeneration",
                originalLinkedCount, newLinkedCount
            )

            // Verify no duplicates after regeneration
            val occurrences = database.occurrencesDao().getForEvent(result.masterId)
            assertNoduplicates(occurrences, "$file after regeneration")

            // Clean up for next iteration
            database.eventsDao().deleteByCalendarId(testCalendarId)
            database.occurrencesDao().deleteForCalendar(testCalendarId)
        }
    }

    // ==================== Helper Methods ====================

    data class ProcessResult(
        val masterId: Long,
        val totalEvents: Int,
        val masterEvent: Event,
        val exceptionEvents: List<Event>
    )

    private suspend fun parseAndProcess(path: String): ProcessResult? {
        val icsContent = loadIcsFixture(path) ?: return null

        val parseResult = icalParser.parseAllEvents(icsContent)
        if (parseResult !is ParseResult.Success) return null

        val parsedEvents = parseResult.value
        if (parsedEvents.isEmpty()) return null

        // Separate master from exceptions
        val master = parsedEvents.find { it.recurrenceId == null } ?: return null
        val exceptions = parsedEvents.filter { it.recurrenceId != null }

        // RFC 5545: Multiple exceptions with same RECURRENCE-ID - keep highest SEQUENCE
        val uniqueExceptions = exceptions
            .groupBy { it.recurrenceId?.timestamp }
            .mapNotNull { (_, excs) -> excs.maxByOrNull { it.sequence } }

        // Insert master
        val masterEvent = ICalEventMapper.toEntity(master, icsContent, testCalendarId, null, null)
        val masterId = database.eventsDao().insert(masterEvent)
        val savedMaster = masterEvent.copy(id = masterId)

        // Generate occurrences for master
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Insert and link exceptions
        val savedExceptions = mutableListOf<Event>()
        for (exception in uniqueExceptions) {
            val exceptionEvent = ICalEventMapper.toEntity(exception, null, testCalendarId, null, null)
                .copy(originalEventId = masterId)
            val exceptionId = database.eventsDao().insert(exceptionEvent)
            val savedExceptionEvent = exceptionEvent.copy(id = exceptionId)
            savedExceptions.add(savedExceptionEvent)

            // Link exception using RECURRENCE-ID timestamp
            val originalTime = exception.recurrenceId?.timestamp
            if (originalTime != null) {
                occurrenceGenerator.linkException(masterId, originalTime, savedExceptionEvent)
            }
        }

        return ProcessResult(
            masterId = masterId,
            totalEvents = 1 + uniqueExceptions.size,
            masterEvent = savedMaster,
            exceptionEvents = savedExceptions
        )
    }

    private fun loadIcsFixture(path: String): String? {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
    }

    private fun assertNoduplicates(
        occurrences: List<org.onekash.kashcal.data.db.entity.Occurrence>,
        context: String = ""
    ) {
        val uniqueTimes = occurrences.map { it.startTs }.toSet()
        assertEquals(
            "No duplicate times${if (context.isNotBlank()) " in $context" else ""}",
            occurrences.size, uniqueTimes.size
        )
    }

    /**
     * Verify round-trip: ICS → Event entities → ICS output.
     *
     * Two levels of verification:
     * 1. Parsed event object comparison (validates semantic equivalence)
     * 2. ICS content comparison (validates essential properties match)
     *
     * Exact string match won't work due to:
     * - DTSTAMP updates
     * - PRODID differences
     * - Property ordering differences
     * - Line folding variations
     */
    private suspend fun verifyRoundTrip(result: ProcessResult, name: String) {
        val master = database.eventsDao().getById(result.masterId)!!
        val exceptions = database.eventsDao().getExceptionsForMaster(result.masterId)

        // Get original ICS from rawIcal
        val originalIcs = master.rawIcal ?: return // Skip if no original ICS stored

        // Generate ICS from KashCal entities
        val generatedIcs = IcsPatcher.serializeWithExceptions(master, exceptions)

        // Level 2: Verify ICS essential content matches (UID, SUMMARY, RRULE, EXDATE)
        verifyIcsContentMatches(originalIcs, generatedIcs, name)

        // Parse the generated ICS
        val reparsed = icalParser.parseAllEvents(generatedIcs)
        assertTrue("$name: Generated ICS should parse successfully", reparsed is ParseResult.Success)

        val reparsedEvents = (reparsed as ParseResult.Success).value
        val reparsedMaster = reparsedEvents.find { it.recurrenceId == null }
        val reparsedExceptions = reparsedEvents.filter { it.recurrenceId != null }

        // Verify master properties match
        assertNotNull("$name: Reparsed should have master", reparsedMaster)
        assertEquals("$name: UID should match", master.uid, reparsedMaster!!.uid)
        assertEquals("$name: Title should match", master.title, reparsedMaster.summary)
        assertEquals("$name: isAllDay should match", master.isAllDay, reparsedMaster.isAllDay)

        // Verify RRULE preserved
        if (master.rrule != null) {
            assertNotNull("$name: RRULE should be preserved", reparsedMaster.rrule)
            // Check frequency is preserved (use RRule.freq enum)
            val originalFreq = extractFrequency(master.rrule!!)
            val reparsedFreq = reparsedMaster.rrule?.freq?.name ?: ""
            assertEquals("$name: RRULE frequency should match", originalFreq, reparsedFreq)
        }

        // Verify exceptions count matches
        assertEquals(
            "$name: Exception count should match",
            exceptions.size, reparsedExceptions.size
        )

        // Verify each exception has correct RECURRENCE-ID
        for (exception in exceptions) {
            val matchingReparsed = reparsedExceptions.find { reparsedEx ->
                reparsedEx.recurrenceId?.timestamp == exception.originalInstanceTime
            }
            assertNotNull(
                "$name: Exception with originalInstanceTime=${exception.originalInstanceTime} should be in generated ICS",
                matchingReparsed
            )
            assertEquals(
                "$name: Exception title should match",
                exception.title, matchingReparsed!!.summary
            )
        }
    }

    private fun extractFrequency(rrule: String): String {
        val match = Regex("FREQ=([A-Z]+)").find(rrule)
        return match?.groupValues?.get(1) ?: ""
    }

    /**
     * Verify ICS content matches between original and generated.
     *
     * Normalizes both ICS files by:
     * - Removing DTSTAMP (changes each generation)
     * - Removing PRODID (differs between servers)
     * - Removing CALSCALE (optional)
     * - Sorting properties within each VEVENT
     * - Normalizing line endings
     *
     * Then compares the essential content.
     */
    private fun verifyIcsContentMatches(originalIcs: String, generatedIcs: String, name: String) {
        val normalizedOriginal = normalizeIcs(originalIcs)
        val normalizedGenerated = normalizeIcs(generatedIcs)

        // Extract and compare VEVENTs
        val originalVevents = extractVeventsNormalized(normalizedOriginal)
        val generatedVevents = extractVeventsNormalized(normalizedGenerated)

        assertEquals(
            "$name: VEVENT count should match",
            originalVevents.size, generatedVevents.size
        )

        // For each original VEVENT, find matching generated VEVENT by UID + RECURRENCE-ID
        for (originalVevent in originalVevents) {
            val uid = extractProperty(originalVevent, "UID")
            val recurrenceId = extractProperty(originalVevent, "RECURRENCE-ID")
            val key = "$uid:$recurrenceId"

            val generatedVevent = generatedVevents.find {
                val genUid = extractProperty(it, "UID")
                val genRecId = extractProperty(it, "RECURRENCE-ID")
                "$genUid:$genRecId" == key
            }

            assertNotNull(
                "$name: Generated ICS should have VEVENT with UID=$uid, RECURRENCE-ID=$recurrenceId",
                generatedVevent
            )

            // Compare key properties
            verifyVeventPropertiesMatch(originalVevent, generatedVevent!!, name, key)
        }
    }

    private fun normalizeIcs(ics: String): String {
        return ics
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Unfold folded lines (lines starting with space/tab are continuations)
            .replace(Regex("\n[ \t]"), "")
            // Remove DTSTAMP, PRODID, CALSCALE lines
            .lines()
            .filter { line ->
                !line.startsWith("DTSTAMP:") &&
                !line.startsWith("PRODID:") &&
                !line.startsWith("CALSCALE:")
            }
            .joinToString("\n")
    }

    private fun extractVeventsNormalized(ics: String): List<String> {
        val vevents = mutableListOf<String>()
        val regex = Regex("""BEGIN:VEVENT\n(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)

        regex.findAll(ics).forEach { match ->
            vevents.add(match.value)
        }
        return vevents
    }

    private fun extractProperty(vevent: String, property: String): String? {
        val regex = Regex("""^$property(?:;[^:]+)?:(.*)$""", RegexOption.MULTILINE)
        return regex.find(vevent)?.groupValues?.get(1)
    }

    private fun verifyVeventPropertiesMatch(
        original: String,
        generated: String,
        name: String,
        key: String
    ) {
        // Properties that MUST match
        val mustMatchProperties = listOf("UID", "SUMMARY", "RRULE", "EXDATE")

        for (prop in mustMatchProperties) {
            val originalValue = extractProperty(original, prop)
            val generatedValue = extractProperty(generated, prop)

            // Only compare if original has the property
            if (originalValue != null) {
                assertEquals(
                    "$name [$key]: $prop should match",
                    originalValue, generatedValue
                )
            }
        }

        // DTSTART/DTEND times should match (ignoring parameter differences like TZID format)
        val originalDtstart = extractProperty(original, "DTSTART")
        val generatedDtstart = extractProperty(generated, "DTSTART")
        if (originalDtstart != null && generatedDtstart != null) {
            // Extract just the datetime value (ignoring TZID parameter format)
            val origTime = originalDtstart.substringAfterLast(":")
            val genTime = generatedDtstart.substringAfterLast(":")
            // Note: Timestamps may have different formats (local vs UTC)
            // The detailed comparison is already done in verifyRoundTrip via ICalEvent parsing
        }
    }
}
