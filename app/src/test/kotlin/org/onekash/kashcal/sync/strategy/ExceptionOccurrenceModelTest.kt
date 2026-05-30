package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Tests for occurrence model consistency when syncing exception events.
 *
 * This test verifies the hypothesis that PullStrategy creates Model A occurrences
 * (separate occurrence with eventId = exception.id) but doesn't normalize to Model B
 * (linked occurrence on master with exceptionEventId = exception.id), which could
 * cause duplicates if both models coexist.
 *
 * Model A (PullStrategy): Exception has its own occurrence
 *   - Occurrence(eventId = exception.id, exceptionEventId = null)
 *
 * Model B (EventWriter): Master's occurrence links to exception
 *   - Occurrence(eventId = master.id, exceptionEventId = exception.id)
 *
 * Run: ./gradlew testDebugUnitTest --tests "*ExceptionOccurrenceModelTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionOccurrenceModelTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var testAccountId: Long = 0

    companion object {
        // Test timestamps (UTC)
        // Master: Jan 20 2025, 10:00 UTC - weekly recurring
        // Exception: Jan 27 2025, 14:00 UTC (moved from 10:00)
        private const val MASTER_START = 1737363600000L  // Jan 20 2025 10:00 UTC
        private const val MASTER_END = 1737367200000L    // Jan 20 2025 11:00 UTC
        private const val ORIGINAL_INSTANCE_TIME = 1737968400000L  // Jan 27 2025 10:00 UTC
        private const val EXCEPTION_START = 1737982800000L  // Jan 27 2025 14:00 UTC
        private const val EXCEPTION_END = 1737986400000L    // Jan 27 2025 15:00 UTC

        // Range for queries (Jan 2025)
        private const val RANGE_START = 1735689600000L  // Jan 1 2025 00:00 UTC
        private const val RANGE_END = 1738368000000L    // Feb 1 2025 00:00 UTC
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

        testAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = testAccountId,
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

    // ==================== Model A vs Model B Tests ====================

    @Test
    fun `Model A - PullStrategy creates separate occurrence for exception`() = runTest {
        // This simulates what PullStrategy does:
        // 1. Create master event with occurrences
        // 2. Create exception event
        // 3. Call regenerateOccurrences(exception) - creates separate occurrence
        // 4. Call cancelOccurrence(master, originalTime) - marks original as cancelled

        val masterUid = UUID.randomUUID().toString()

        // Step 1: Create master recurring event
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // Generate occurrences for master
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Verify master has occurrences including Jan 27
        val masterOccurrences = database.occurrencesDao().getForEvent(masterId)
        println("=== Master Occurrences (before exception) ===")
        masterOccurrences.forEach { occ ->
            println("  startTs=${occ.startTs}, eventId=${occ.eventId}, exceptionEventId=${occ.exceptionEventId}, isCancelled=${occ.isCancelled}")
        }
        assertTrue("Master should have occurrences", masterOccurrences.isNotEmpty())

        // Step 2: Create exception event (as PullStrategy does)
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,  // Same UID as master
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,  // Moved to 14:00
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,  // Was at 10:00
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Step 3: PullStrategy calls regenerateOccurrences(exception)
        // This creates Model A: occurrence with eventId = exception.id
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Step 4: PullStrategy calls cancelOccurrence(master, originalTime)
        occurrenceGenerator.cancelOccurrence(masterId, ORIGINAL_INSTANCE_TIME)

        // Now query all occurrences
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model A) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time (Jan 27 14:00)
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // ASSERTION: There should be exactly ONE occurrence at the exception time
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model A: eventId=exception)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's the Model A occurrence (eventId = exception.id)
        val exceptionOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model A: eventId should be exception", exceptionId, exceptionOccurrence.eventId)
        assertFalse("Model A: should not have exceptionEventId", exceptionOccurrence.exceptionEventId != null)
    }

    @Test
    fun `Model B - linkException creates linked occurrence on master`() = runTest {
        // This simulates what OccurrenceGenerator.linkException(masterEventId, time, exceptionEvent) does:
        // 1. Delete Model A occurrence (if exists)
        // 2. Update master's occurrence with exceptionEventId link

        val masterUid = UUID.randomUUID().toString()

        // Step 1: Create master recurring event
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // Generate occurrences for master
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Step 2: Create exception event
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Step 3: Use linkException to create Model B
        // This deletes any Model A occurrence and links master's occurrence to exception
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        // Query all occurrences
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model B) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // ASSERTION: There should be exactly ONE occurrence at the exception time
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model B: linked)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's the Model B occurrence (eventId = master.id, exceptionEventId = exception.id)
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model B: eventId should be master", masterId, linkedOccurrence.eventId)
        assertEquals("Model B: exceptionEventId should be exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    @Test
    fun `linkException normalizes Model A to Model B preventing duplicates`() = runTest {
        // This test verifies that linkException correctly normalizes:
        // 1. If Model A exists (separate occurrence), it gets deleted
        // 2. Model B is created/updated (linked occurrence on master)
        // 3. No duplicates in query results

        val masterUid = UUID.randomUUID().toString()

        // Create master
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly Meeting",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // Create exception
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Simulate old behavior: PullStrategy creating Model A
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Verify Model A exists before normalization
        val beforeNormalization = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END).first()
        val modelABefore = beforeNormalization.filter { it.eventId == exceptionId }
        println("=== Before linkException: Model A occurrences = ${modelABefore.size} ===")

        // Now call linkException to normalize (this is what PullStrategy should do)
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        // Query all occurrences after normalization
        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (after linkException normalization) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Count occurrences at the exception time
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) after normalization ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.id=${data.event.id}, event.title=${data.event.title}")
        }

        // ASSERTION: linkException should normalize to exactly 1 occurrence (Model B)
        assertEquals(
            "linkException should normalize to exactly 1 occurrence (Model B)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Verify it's Model B (linked occurrence on master)
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Should be Model B: eventId = master", masterId, linkedOccurrence.eventId)
        assertEquals("Should be Model B: exceptionEventId = exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    /**
     * Repro for the user-observed bug:
     *   "On Jun 01, KashCal shows 2 events — one exception, one master 19:00."
     *
     * Sequence on iCloud (verified server-side):
     *   1. Master DAILY;COUNT=10 starting May 29 19:00 CT
     *   2. Edit Jun 01 occurrence → bundled exception VEVENT
     *      (RECURRENCE-ID = Jun 01 19:00, modified = Jun 01 10:00)
     *   3. THIS_AND_FUTURE split from Jun 02 → master truncated to COUNT=4
     *
     * Server-stored ICS now has:
     *   - master VEVENT: COUNT=4, expands to May 29, 30, 31, Jun 01 19:00
     *   - exception VEVENT: RECURRENCE-ID=Jun 01 19:00, DTSTART=Jun 01 10:00
     *
     * Per RFC 5545 §3.8.4.4 the exception REPLACES the Jun 01 19:00 instance.
     * KashCal must end up with exactly ONE occurrence row on Jun 01, at the
     * exception's modified time, linked via exceptionEventId.
     *
     * Bug surfaces if linkException's UPDATE matches 0 rows (e.g., timezone
     * drift between master expansion and originalInstanceTime > 60s) and
     * Step 4 inserts a NEW row instead — both rows survive on the same day.
     */
    @Test
    fun `truncated master plus past exception leaves exactly one occurrence on exception day`() = runTest {
        val masterUid = UUID.randomUUID().toString()

        // Master truncated to COUNT=4 (post-split shape).
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "recur 10 days",
            rrule = "FREQ=DAILY;COUNT=4",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // PullStrategy pass 1: regenerate master's occurrences from RRULE.
        occurrenceGenerator.regenerateOccurrences(savedMaster)
        val masterOccurrencesPass1 = database.occurrencesDao().getForEvent(masterId)
        assertEquals("Master COUNT=4 should produce 4 occurrences", 4, masterOccurrencesPass1.size)

        // The 4th occurrence (index 3) is the exception's day. RECURRENCE-ID
        // references this RRULE-generated time.
        val recurrenceIdTime = masterOccurrencesPass1.sortedBy { it.startTs }[3].startTs
        // Exception shifts the time-of-day -9h (matches the user's repro:
        // Jun 01 19:00 → Jun 01 10:00 stays on same calendar day).
        val exceptionStart = recurrenceIdTime - 9 * 3600_000L
        val exceptionEnd = exceptionStart + 30 * 60_000L

        // PullStrategy pass 2: ingest bundled exception VEVENT as separate
        // Event row with originalEventId/originalInstanceTime set.
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid, // same UID as master per RFC 5545
            title = "recur 10 days (edited)",
            startTs = exceptionStart,
            endTs = exceptionEnd,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = recurrenceIdTime,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // PullStrategy calls linkException(masterId, originalInstanceTime, savedException)
        // — the rich variant that should normalize to Model B.
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        // ===== Assertions =====
        val editedDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(exceptionStart, false)

        val masterRowsOnEditedDay = database.occurrencesDao().getForEvent(masterId)
            .filter { it.startDay == editedDayCode }
        val exceptionRowsOnEditedDay = database.occurrencesDao().getForEvent(exceptionId)
            .filter { it.startDay == editedDayCode }
        val totalRowsOnEditedDay = masterRowsOnEditedDay.size + exceptionRowsOnEditedDay.size

        assertEquals(
            "edited day must have exactly ONE occurrence row total " +
                "(master rows on day: ${masterRowsOnEditedDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }}, " +
                "exception rows on day: ${exceptionRowsOnEditedDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }})",
            1,
            totalRowsOnEditedDay,
        )
        assertEquals(
            "the row must live on master (Model B), not on exception (Model A leftover)",
            1,
            masterRowsOnEditedDay.size,
        )
        val theRow = masterRowsOnEditedDay.single()
        assertEquals(
            "the surviving row's exceptionEventId must point at the exception",
            exceptionId,
            theRow.exceptionEventId,
        )
        assertEquals(
            "the surviving row's start_ts must be the exception's modified time",
            exceptionStart,
            theRow.startTs,
        )
    }

    /**
     * Repro for the user-observed duplicate after THIS_AND_FUTURE on a
     * recurring event with an existing exception:
     *
     * Post-split state in Room (writer-side, before any pull):
     *   - Master with COUNT=4 RRULE
     *   - Exception event row (originalInstanceTime = Jun 01 19:00)
     *   - Master occurrences: May 29, 30, 31, Jun 01 10:00 (linked to exception)
     *
     * If sync pull triggers a master REGEN (e.g., etag mismatch from CDN
     * or forceFullSync), we get:
     *   - existingOccurrences captured (includes Jun 01 10:00 linked row)
     *   - exceptionLinks map = { Jun 01 10:00 -> ExceptionLinkData(exception.id) }
     *   - delete master rows
     *   - insert 4 NEW rows from RRULE expansion (Jun 01 19:00 here, NOT 10:00)
     *   - restoreExceptionLink runs, calls linkException(masterId,
     *     recurrenceIdTime=Jun 01 19:00, savedException) — should update
     *     the new Jun 01 19:00 row to 10:00 with link.
     *
     * If linkException's UPDATE matches 0 rows (e.g., the
     * exceptionEvent.startTs in Step 2's conflict check accidentally
     * matched ANOTHER row, or the 60-second tolerance fails on a
     * timezone edge), Step 4 inserts a NEW row at the exception's time
     * — leaving BOTH Jun 01 19:00 (from RRULE) and Jun 01 10:00 (from
     * Step 4) in the table.
     */
    @Test
    fun `master regen after split keeps single Jun 01 row when exception is restored`() = runTest {
        val masterUid = UUID.randomUUID().toString()

        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "recur 10 days",
            rrule = "FREQ=DAILY;COUNT=4",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)

        // Initial regen → 4 master-time rows
        occurrenceGenerator.regenerateOccurrences(savedMaster)
        val masterOccs = database.occurrencesDao().getForEvent(masterId).sortedBy { it.startTs }
        assertEquals(4, masterOccs.size)
        val recurrenceIdTime = masterOccs[3].startTs

        // Insert the exception (e.g., from a prior linkSingleOccurrence edit)
        val exceptionStart = recurrenceIdTime - 9 * 3600_000L
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "recur 10 days (edited)",
            startTs = exceptionStart,
            endTs = exceptionStart + 30 * 60_000L,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = recurrenceIdTime,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // First link — normalizes to Model B (master row updated to 10:00 with link)
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        val editedDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(exceptionStart, false)
        val initialRowsOnDay = database.occurrencesDao().getForEvent(masterId)
            .filter { it.startDay == editedDayCode }
        assertEquals("after link, 1 row on edited day", 1, initialRowsOnDay.size)
        assertEquals(exceptionId, initialRowsOnDay.single().exceptionEventId)

        // SECOND REGEN — simulates sync pull that triggers master regen
        // (etag mismatch, forceFullSync, etc.). regenerateOccurrences
        // captures the existing linked Jun 01 10:00 row, deletes it,
        // expands RRULE to 4 master-time rows (incl. Jun 01 19:00),
        // then restoreExceptionLink should re-link.
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        val finalRowsOnDay = database.occurrencesDao().getForEvent(masterId)
            .filter { it.startDay == editedDayCode }
        assertEquals(
            "after master regen, edited day must still have ONE row " +
                "(post-regen rows: ${finalRowsOnDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }})",
            1,
            finalRowsOnDay.size,
        )
        assertEquals(
            "the surviving row's exceptionEventId must point at the exception",
            exceptionId,
            finalRowsOnDay.single().exceptionEventId,
        )
    }

    /**
     * End-to-end test for the value-type normalization path: when an
     * exception arrives with `RECURRENCE-ID;VALUE=DATE` against a timed
     * master, the originalInstanceTime stored in Room must equal the
     * master's RRULE-expanded instance time on that calendar day, so
     * that linkException's UPDATE matches the master's regenerated
     * occurrence. Without normalization the exception time would land
     * at midnight UTC and linkException would insert a duplicate row.
     *
     * The mapper normalization is unit-tested in
     * RecurrenceIdNormalizationTest. This test confirms that when
     * normalization runs and originalInstanceTime is set correctly,
     * linkException leaves exactly one occurrence row on the day.
     */
    @Test
    fun `mismatched RECURRENCE-ID with normalization leaves single Jun 01 occurrence`() = runTest {
        val masterUid = UUID.randomUUID().toString()

        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "DAILY 10:00 master",
            rrule = "FREQ=DAILY;COUNT=4",
            startTs = MASTER_START, // Jan 20 2025 10:00 UTC
            endTs = MASTER_END,
            timezone = "UTC",
            isAllDay = false,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)
        occurrenceGenerator.regenerateOccurrences(savedMaster)

        // The exception's RECURRENCE-ID is `Jan 23 2025` (date-form).
        // Normalization promotes it to Jan 23 10:00 UTC — matching the
        // master's RRULE-expanded instance on that day.
        val recurrenceIdNormalizedTs = MASTER_START + 3 * 86400_000L
        // Exception's modified time: Jan 23 14:00 UTC (4h shift).
        val exceptionStart = recurrenceIdNormalizedTs + 4 * 3600_000L
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "shifted to afternoon",
            startTs = exceptionStart,
            endTs = exceptionStart + 3600_000L,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = recurrenceIdNormalizedTs,
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)
        occurrenceGenerator.linkException(masterId, recurrenceIdNormalizedTs, savedException)

        val editedDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(exceptionStart, false)
        val masterRowsOnDay = database.occurrencesDao().getForEvent(masterId)
            .filter { it.startDay == editedDayCode }
        val exceptionRowsOnDay = database.occurrencesDao().getForEvent(exceptionId)
            .filter { it.startDay == editedDayCode }
        assertEquals(
            "edited day must have exactly ONE occurrence row total " +
                "(master rows: ${masterRowsOnDay.size}, exception rows: ${exceptionRowsOnDay.size})",
            1,
            masterRowsOnDay.size + exceptionRowsOnDay.size,
        )
        assertEquals(exceptionId, masterRowsOnDay.single().exceptionEventId)
        assertEquals(exceptionStart, masterRowsOnDay.single().startTs)
    }

    /**
     * Repro for orphan-exception loss (CalDAV pull):
     *
     * On initial sync (or any sync where the master is outside the lookback
     * window), the server can return an exception VEVENT whose master VEVENT
     * isn't in the same response. PullStrategy's pass 3 master lookup returns
     * null, the exception is dropped with a warning, and the user-visible
     * effect is that an edited occurrence "disappears" — the master's RRULE
     * expansion (when the master eventually shows up) renders the original
     * unedited occurrence at the wrong time.
     *
     * The fix mirrors the ICS-import pattern (issue #227): synthesize a
     * placeholder master row tagged with `X-KASHCAL-SYNTHETIC-MASTER`,
     * `rrule = null`, `status = "CANCELLED"`. The exception links to it via
     * `originalEventId`. When the real master arrives in a later sync, the
     * UID-keyed @Upsert mutates the synthetic in place — same row id, real
     * RRULE populated, sentinel cleared — so existing exception FKs survive.
     *
     * This test exercises the synthesis helper directly. The PullStrategy
     * integration is asserted by the live wire harness.
     */
    @Test
    fun `synthesizeMasterForOrphanException creates placeholder master with sentinel`() = runTest {
        val orphanUid = UUID.randomUUID().toString()
        val orphanRecurrenceIdMs = ORIGINAL_INSTANCE_TIME

        val syntheticMaster = org.onekash.kashcal.sync.strategy.synthesizeMasterForOrphanException(
            uid = orphanUid,
            calendarId = testCalendarId,
            recurrenceIdMs = orphanRecurrenceIdMs,
            placeholderTitle = "Recurring meeting",
        )

        // Sentinel must mark it as synthetic so the FTS-search exclusion
        // (already wired up for ICS-import synthetics in `EventsDao`) hides
        // it from search/suggest surfaces.
        assertEquals(
            "true",
            syntheticMaster.extraProperties?.get("X-KASHCAL-SYNTHETIC-MASTER"),
        )
        // Synthetic must have no RRULE so OccurrenceGenerator never produces
        // a phantom occurrence for it. The exception's row carries its own
        // occurrence; the synthetic exists purely as an FK target.
        assertEquals(null, syntheticMaster.rrule)
        // CANCELLED status is RFC 5545 valid and prevents day-card render.
        assertEquals("CANCELLED", syntheticMaster.status)
        // Identity invariants for the upsert-by-UID path.
        assertEquals(orphanUid, syntheticMaster.uid)
        assertEquals(testCalendarId, syntheticMaster.calendarId)
        // syncStatus must be SYNCED so the synthetic isn't queued for push.
        assertEquals(SyncStatus.SYNCED, syntheticMaster.syncStatus)
    }

    @Test
    fun `synthetic master allows exception to link via FK without duplicate occurrence`() = runTest {
        // End-to-end: synthesize → insert → upsert exception with originalEventId
        // pointing at synthetic → linkException. Day must have exactly ONE
        // occurrence row (the exception's), no phantom from the synthetic.
        val orphanUid = UUID.randomUUID().toString()

        val syntheticMaster = org.onekash.kashcal.sync.strategy.synthesizeMasterForOrphanException(
            uid = orphanUid,
            calendarId = testCalendarId,
            recurrenceIdMs = ORIGINAL_INSTANCE_TIME,
            placeholderTitle = "Edited occurrence",
        )
        val syntheticId = database.eventsDao().upsert(syntheticMaster)
        assertTrue("Synthetic master must insert with a real id", syntheticId > 0)

        // Pull-side path also skips regenerateOccurrences for synthetic
        // masters — but verify defensively that the synthetic itself
        // produces zero occurrences if regen ever runs on it.
        occurrenceGenerator.regenerateOccurrences(syntheticMaster.copy(id = syntheticId))
        val syntheticOccs = database.occurrencesDao().getForEvent(syntheticId)
        assertEquals(
            "Synthetic master with rrule=null must NOT generate any occurrence " +
                "(regen path must guard the sentinel)",
            0,
            syntheticOccs.size,
        )

        // Now ingest the orphan exception linked to the synthetic.
        val exception = Event(
            calendarId = testCalendarId,
            uid = orphanUid,
            title = "Edited occurrence",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = syntheticId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED,
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)
        occurrenceGenerator.linkException(syntheticId, ORIGINAL_INSTANCE_TIME, savedException)

        val exceptionDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(EXCEPTION_START, false)
        val rowsOnDay = (
            database.occurrencesDao().getForEvent(syntheticId) +
                database.occurrencesDao().getForEvent(exceptionId)
            ).filter { it.startDay == exceptionDayCode }
        assertEquals(
            "Day must have exactly ONE occurrence row (exception's). " +
                "Synthetic must contribute zero. Rows: ${rowsOnDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }}",
            1,
            rowsOnDay.size,
        )
    }

    @Test
    fun `synthetic master mutates in place when real master arrives via @Upsert`() = runTest {
        // Three-state lifecycle test:
        // State 1: Orphan arrives → synthetic created, exception linked
        // State 2: Real master with same UID arrives → @Upsert mutates row in place
        // State 3: Synthetic id == real master id; exception FKs survive
        val uid = UUID.randomUUID().toString()

        // State 1
        val synthetic = org.onekash.kashcal.sync.strategy.synthesizeMasterForOrphanException(
            uid = uid,
            calendarId = testCalendarId,
            recurrenceIdMs = ORIGINAL_INSTANCE_TIME,
            placeholderTitle = "(placeholder)",
        )
        val syntheticId = database.eventsDao().upsert(synthetic)
        val exception = Event(
            calendarId = testCalendarId,
            uid = uid,
            title = "edited",
            startTs = EXCEPTION_START,
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = syntheticId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,
            syncStatus = SyncStatus.SYNCED,
        )
        val exceptionId = database.eventsDao().insert(exception)

        // State 2: real master arrives with same UID (later sync, e.g. window
        // expanded). PullStrategy's pass 2 looks up the existing master by
        // (uid, calendarId, original_event_id IS NULL) — which finds the
        // synthetic — and copies its id into the new event before upsert,
        // mirroring IcsSubscriptionRepository.upsertEvent. The result is
        // an in-place mutation: same row id, real RRULE populated, sentinel
        // cleared. Exception FKs survive untouched.
        val existingForRealMaster = database.eventsDao().getMasterByUidAndCalendar(uid, testCalendarId)
        assertNotNull("Pass-2 lookup must find the synthetic by UID", existingForRealMaster)
        val realMaster = Event(
            id = existingForRealMaster!!.id,
            uid = uid,
            calendarId = testCalendarId,
            title = "Weekly meeting",
            rrule = "FREQ=WEEKLY;COUNT=10",
            status = "CONFIRMED",
            startTs = MASTER_START,
            endTs = MASTER_END,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
            extraProperties = null, // sentinel cleared on real master ingest
        )
        val upsertResult = database.eventsDao().upsert(realMaster)
        // @Upsert returns -1 when the operation was an update (not an
        // insert). The mutated row's id is the existingForRealMaster.id
        // we passed in via .copy(id = existingForRealMaster.id).
        val realMasterId = if (upsertResult == -1L) existingForRealMaster.id else upsertResult

        // State 3 assertions
        assertEquals(
            "Real master upsert must preserve the synthetic's row id (no FK churn)",
            syntheticId,
            realMasterId,
        )
        val storedMaster = database.eventsDao().getById(realMasterId)
        assertNotNull(storedMaster)
        assertEquals("FREQ=WEEKLY;COUNT=10", storedMaster!!.rrule)
        assertEquals("CONFIRMED", storedMaster.status)
        assertEquals(
            "Sentinel must be cleared on real master ingest",
            null,
            storedMaster.extraProperties?.get("X-KASHCAL-SYNTHETIC-MASTER"),
        )
        // Exception's FK still points at the same row id.
        val storedException = database.eventsDao().getById(exceptionId)
        assertEquals(realMasterId, storedException?.originalEventId)
    }

    /**
     * Self-heal coverage for the residual atomicity-gap window: a prior
     * pull crashed/was killed mid-link, leaving the master's RRULE-expanded
     * occurrence at the exception's instance time WITHOUT an exception_event_id
     * link, while the exception row itself still exists in Room.
     *
     * On the next pull: master etag matches local etag → pass 2 skips →
     * `uidsWithRegeneratedMaster` does NOT include the UID → pass 3's
     * etag-relink branch (which fires only when masterRegenerated=true)
     * also doesn't fire → exception is fully skipped → broken state stays.
     *
     * Without the heal-on-skip patch: stuck until master's etag changes.
     * With the patch: pass 3 detects the unlinked occurrence and re-runs
     * linkException to repair.
     *
     * This unit test exercises the repair logic at the helper level — the
     * full pull path is harder to set up. The actual "if-occurrence-is-
     * unlinked-then-relink" guard lives in PullStrategy and is exercised
     * indirectly when this test simulates the broken state and the heal
     * call.
     */
    @Test
    fun `linkException repairs unlinked master occurrence when exception row exists`() = runTest {
        val masterUid = UUID.randomUUID().toString()
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "Weekly meeting",
            rrule = "FREQ=WEEKLY;COUNT=4",
            startTs = MASTER_START,
            endTs = MASTER_END,
            timezone = "UTC",
            isAllDay = false,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val masterId = database.eventsDao().insert(master)
        val savedMaster = master.copy(id = masterId)
        occurrenceGenerator.regenerateOccurrences(savedMaster)
        val occurrences = database.occurrencesDao().getForEvent(masterId).sortedBy { it.startTs }
        val recurrenceIdTime = occurrences[2].startTs

        // Insert an exception row but DO NOT call linkException.
        // This mirrors the post-crash state: master's RRULE-expanded
        // occurrence at recurrenceIdTime has exception_event_id = NULL,
        // and the exception row exists separately.
        val exceptionStartTs = recurrenceIdTime + 4 * 3600_000L
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "moved later",
            startTs = exceptionStartTs,
            endTs = exceptionStartTs + 3600_000L,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = recurrenceIdTime,
            syncStatus = SyncStatus.SYNCED,
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Sanity: the master's occurrence at recurrenceIdTime is unlinked.
        val occBefore = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, recurrenceIdTime)
        assertNotNull(occBefore)
        assertEquals(null, occBefore!!.exceptionEventId)

        // The heal-on-skip patch does:
        //   if (occ != null && occ.exceptionEventId == null) {
        //     linkException(masterId, recurrenceIdTime, savedException)
        //   }
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        // After heal: occurrence at the exception's modified time, linked.
        val occAfter = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, exceptionStartTs)
        assertNotNull("Occurrence at exception's modified time must exist", occAfter)
        assertEquals(exceptionId, occAfter!!.exceptionEventId)
        // And the unlinked row at recurrenceIdTime is gone.
        val phantom = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, recurrenceIdTime)
        assertEquals("Unlinked row must be replaced, not duplicated", null, phantom)
    }

    @Test
    fun `linkException is no-op when occurrence is already linked correctly`() = runTest {
        // Idempotency: re-running linkException on already-linked state
        // must not duplicate or churn the row.
        val masterUid = UUID.randomUUID().toString()
        val master = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "weekly",
            rrule = "FREQ=WEEKLY;COUNT=4",
            startTs = MASTER_START,
            endTs = MASTER_END,
            timezone = "UTC",
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
        )
        val masterId = database.eventsDao().insert(master)
        occurrenceGenerator.regenerateOccurrences(master.copy(id = masterId))
        val occurrences = database.occurrencesDao().getForEvent(masterId).sortedBy { it.startTs }
        val recurrenceIdTime = occurrences[2].startTs
        val exceptionStartTs = recurrenceIdTime + 4 * 3600_000L
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,
            title = "moved",
            startTs = exceptionStartTs,
            endTs = exceptionStartTs + 3600_000L,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = recurrenceIdTime,
            syncStatus = SyncStatus.SYNCED,
        )
        val exceptionId = database.eventsDao().insert(exception)
        val saved = exception.copy(id = exceptionId)
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, saved)

        val rowsBefore = database.occurrencesDao().getForEvent(masterId)
            .filter { it.exceptionEventId == exceptionId }
        assertEquals(1, rowsBefore.size)

        // Run again — must remain a single row.
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, saved)

        val rowsAfter = database.occurrencesDao().getForEvent(masterId)
            .filter { it.exceptionEventId == exceptionId }
        assertEquals(
            "Re-running linkException must be idempotent — exactly ONE linked row",
            1,
            rowsAfter.size,
        )
        // End state: row is at the exception's modified time and points
        // at the exception event id. (Step 2's conflict-deletion may
        // recycle the SQLite rowid; what matters is the unique linked row.)
        assertEquals(exceptionStartTs, rowsAfter.single().startTs)
        assertEquals(exceptionId, rowsAfter.single().exceptionEventId)
    }

    // ==================== Helper Methods ====================

    private fun Long.toDateString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(this))
    }
}
