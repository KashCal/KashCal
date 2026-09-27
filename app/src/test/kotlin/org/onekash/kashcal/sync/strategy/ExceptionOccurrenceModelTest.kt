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
 * Tests that a changed occurrence ends up as one occurrence row, over a real in-memory database.
 *
 * A changed occurrence can be stored two ways; both at once show it twice:
 * - Model A: the exception has its own occurrence,
 *   `Occurrence(eventId = exception.id, exceptionEventId = null)`.
 * - Model B: the master's occurrence links to the exception,
 *   `Occurrence(eventId = master.id, exceptionEventId = exception.id)`.
 *
 * The pull, ICS sync and EventWriter link an exception that has an original instance time
 * through [OccurrenceGenerator.linkException], which leaves Model B only. The tests cover each
 * model built alone, that normalization, a truncated master with a past exception, a master
 * regeneration that restores the link, a RECURRENCE-ID normalized from DATE form, synthetic
 * masters for orphan exceptions, repairing an unlinked occurrence and re-linking an already
 * linked one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExceptionOccurrenceModelTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var testAccountId: Long = 0

    companion object {
        // Master: weekly from Jan 20 2025 10:00 UTC. Exception: the Jan 27 occurrence, moved
        // from 10:00 to 14:00 UTC.
        private const val MASTER_START = 1737363600000L  // Jan 20 2025 10:00 UTC
        private const val MASTER_END = 1737367200000L    // Jan 20 2025 11:00 UTC
        private const val ORIGINAL_INSTANCE_TIME = 1737968400000L  // Jan 27 2025 10:00 UTC
        private const val EXCEPTION_START = 1737982800000L  // Jan 27 2025 14:00 UTC
        private const val EXCEPTION_END = 1737986400000L    // Jan 27 2025 15:00 UTC

        // Query range: January 2025.
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

    // ==================== Model A and Model B ====================

    @Test
    fun `Model A - PullStrategy creates separate occurrence for exception`() = runTest {
        // Builds Model A by hand, the shape linkException replaces:
        // 1. Create the master and its occurrences.
        // 2. Create the exception.
        // 3. regenerateOccurrences(exception) gives the exception its own occurrence.
        // 4. cancelOccurrence(master, originalTime) cancels the master's original occurrence.

        val masterUid = UUID.randomUUID().toString()

        // Step 1: the master and its occurrences.
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

        val masterOccurrences = database.occurrencesDao().getForEvent(masterId)
        println("=== Master Occurrences (before exception) ===")
        masterOccurrences.forEach { occ ->
            println("  startTs=${occ.startTs}, eventId=${occ.eventId}, exceptionEventId=${occ.exceptionEventId}, isCancelled=${occ.isCancelled}")
        }
        assertTrue("Master should have occurrences", masterOccurrences.isNotEmpty())

        // Step 2: the exception.
        val exception = Event(
            calendarId = testCalendarId,
            uid = masterUid,  // same UID as the master
            title = "Moved to afternoon",
            startTs = EXCEPTION_START,  // moved to 14:00
            endTs = EXCEPTION_END,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = ORIGINAL_INSTANCE_TIME,  // was at 10:00
            syncStatus = SyncStatus.SYNCED
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        // Step 3: Model A, an occurrence with eventId = exception.id.
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Step 4: cancel the master's original occurrence.
        occurrenceGenerator.cancelOccurrence(masterId, ORIGINAL_INSTANCE_TIME)

        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model A) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        // Occurrences at the exception time, Jan 27 14:00.
        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // One occurrence at the exception time.
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model A: eventId=exception)",
            1,
            occurrencesAtExceptionTime.size
        )

        // It's the Model A occurrence (eventId = exception.id).
        val exceptionOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model A: eventId should be exception", exceptionId, exceptionOccurrence.eventId)
        assertFalse("Model A: should not have exceptionEventId", exceptionOccurrence.exceptionEventId != null)
    }

    @Test
    fun `Model B - linkException creates linked occurrence on master`() = runTest {
        // linkException(masterEventId, time, exceptionEvent) deletes any occurrence the
        // exception owns (Model A), then links the master's occurrence and moves it to the
        // exception's times.

        val masterUid = UUID.randomUUID().toString()

        // Step 1: the master.
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

        // Step 2: the exception.
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

        // Step 3: linkException gives Model B.
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (Model B) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.title=${data.event.title}")
        }

        // One occurrence at the exception time.
        assertEquals(
            "Should have exactly 1 occurrence at exception time (Model B: linked)",
            1,
            occurrencesAtExceptionTime.size
        )

        // It's the Model B occurrence (eventId = master.id, exceptionEventId = exception.id).
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Model B: eventId should be master", masterId, linkedOccurrence.eventId)
        assertEquals("Model B: exceptionEventId should be exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    @Test
    fun `linkException normalizes Model A to Model B preventing duplicates`() = runTest {
        // linkException over an existing Model A deletes it and leaves only the Model B row,
        // so the query returns no duplicate.

        val masterUid = UUID.randomUUID().toString()

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

        // Model A first, as a leftover to normalize.
        occurrenceGenerator.regenerateOccurrences(savedException)

        // Model A before normalization (printed only).
        val beforeNormalization = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END).first()
        val modelABefore = beforeNormalization.filter { it.eventId == exceptionId }
        println("=== Before linkException: Model A occurrences = ${modelABefore.size} ===")

        // Normalize with linkException, as the pull does.
        occurrenceGenerator.linkException(masterId, ORIGINAL_INSTANCE_TIME, savedException)

        val allOccurrences = database.occurrencesDao().getOccurrencesWithEventsInRange(RANGE_START, RANGE_END)
            .first()

        println("\n=== All Occurrences (after linkException normalization) ===")
        allOccurrences.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "startTs=${data.startTs}, isCancelled=${data.isCancelled}, " +
                    "event.title=${data.event.title}")
        }

        val occurrencesAtExceptionTime = allOccurrences.filter {
            it.startTs == EXCEPTION_START && !it.isCancelled
        }

        println("\n=== Occurrences at exception time (14:00) after normalization ===")
        occurrencesAtExceptionTime.forEach { data ->
            println("  eventId=${data.eventId}, exceptionEventId=${data.exceptionEventId}, " +
                    "event.id=${data.event.id}, event.title=${data.event.title}")
        }

        // One occurrence, Model B.
        assertEquals(
            "linkException should normalize to exactly 1 occurrence (Model B)",
            1,
            occurrencesAtExceptionTime.size
        )

        // Linked on the master.
        val linkedOccurrence = occurrencesAtExceptionTime.first()
        assertEquals("Should be Model B: eventId = master", masterId, linkedOccurrence.eventId)
        assertEquals("Should be Model B: exceptionEventId = exception", exceptionId, linkedOccurrence.exceptionEventId)
    }

    /**
     * Reproduces a user report: "On Jun 01, KashCal shows 2 events: one exception, one master
     * 19:00."
     *
     * Sequence on iCloud (verified server-side):
     *   1. Master DAILY;COUNT=10 starting May 29 19:00 CT.
     *   2. Edit the Jun 01 occurrence, giving a bundled exception VEVENT
     *      (RECURRENCE-ID Jun 01 19:00, moved to Jun 01 10:00).
     *   3. This-and-future split from Jun 02, truncating the master to COUNT=4.
     *
     * The stored ICS then has:
     *   - master VEVENT: COUNT=4, expanding to May 29, 30, 31 and Jun 01 19:00
     *   - exception VEVENT: RECURRENCE-ID Jun 01 19:00, DTSTART Jun 01 10:00
     *
     * The exception replaces the Jun 01 19:00 instance (RFC 5545 §3.8.4.4), so Room must hold
     * one occurrence row on Jun 01, at the exception's time, linked by exceptionEventId. The
     * test builds the same shape on its own dates.
     *
     * The duplicate appears if linkException's update matches no row (for example timezone
     * drift of more than 60 seconds between the master expansion and originalInstanceTime)
     * and its insert step adds a second row on the same day.
     */
    @Test
    fun `truncated master plus past exception leaves exactly one occurrence on exception day`() = runTest {
        val masterUid = UUID.randomUUID().toString()

        // The master after the split: COUNT=4.
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

        // The master's occurrences from its RRULE, as the pull's master pass stores them.
        occurrenceGenerator.regenerateOccurrences(savedMaster)
        val masterOccurrencesPass1 = database.occurrencesDao().getForEvent(masterId)
        assertEquals("Master COUNT=4 should produce 4 occurrences", 4, masterOccurrencesPass1.size)

        // The 4th occurrence (index 3) is the exception's day; RECURRENCE-ID is this
        // RRULE-generated time.
        val recurrenceIdTime = masterOccurrencesPass1.sortedBy { it.startTs }[3].startTs
        // The exception moves 9 hours earlier on the same day, as in the report (Jun 01 19:00
        // to 10:00).
        val exceptionStart = recurrenceIdTime - 9 * 3600_000L
        val exceptionEnd = exceptionStart + 30 * 60_000L

        // The bundled exception VEVENT as its own Event row with originalEventId and
        // originalInstanceTime, as the pull's exception pass stores it.
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

        // The pull calls linkException(masterId, originalInstanceTime, savedException), the
        // overload that normalizes to Model B.
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        // Assertions
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
     * Reproduces a user-reported duplicate after a this-and-future split of a series with an
     * existing exception.
     *
     * State in Room after the split, before any pull:
     *   - the master with a COUNT=4 RRULE
     *   - the exception row (originalInstanceTime Jun 01 19:00)
     *   - master occurrences May 29, 30, 31 and Jun 01 10:00, the last linked to the exception
     *
     * When a pull regenerates the master (for example an etag mismatch or forceFullSync),
     * regenerateOccurrences:
     *   - reads the existing links (the Jun 01 10:00 row, keyed by its start)
     *   - expands the RRULE, deletes the master's rows and inserts 4 new ones (Jun 01 at
     *     19:00, not 10:00)
     *   - calls restoreExceptionLink, which calls linkException(masterId, Jun 01 19:00,
     *     exception) to move the new Jun 01 19:00 row to 10:00 and link it
     *
     * If linkException's update matches no row (for example its conflict check on the
     * exception's start deleted another row, or the 60-second tolerance misses on a timezone
     * edge), its insert step adds a row at the exception's time, leaving both Jun 01 19:00
     * (from the RRULE) and Jun 01 10:00 in the table.
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

        // First regeneration: 4 rows at the master's time.
        occurrenceGenerator.regenerateOccurrences(savedMaster)
        val masterOccs = database.occurrencesDao().getForEvent(masterId).sortedBy { it.startTs }
        assertEquals(4, masterOccs.size)
        val recurrenceIdTime = masterOccs[3].startTs

        // The exception, as an earlier single-occurrence edit would have stored it.
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

        // First link: Model B, the master row moved to 10:00 and linked.
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        val editedDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(exceptionStart, false)
        val initialRowsOnDay = database.occurrencesDao().getForEvent(masterId)
            .filter { it.startDay == editedDayCode }
        assertEquals("after link, 1 row on edited day", 1, initialRowsOnDay.size)
        assertEquals(exceptionId, initialRowsOnDay.single().exceptionEventId)

        // Second regeneration, as a pull that regenerates the master runs it. The linked
        // row is replaced by 4 rows at the master's time and restoreExceptionLink re-links.
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
     * Tests the occurrence side of RECURRENCE-ID value-type normalization. When an exception
     * has `RECURRENCE-ID;VALUE=DATE` against a timed master, the stored originalInstanceTime
     * must equal the master's RRULE-expanded time on that day, so linkException's update
     * matches the master's occurrence. Unnormalized, the time would be midnight UTC and
     * linkException would insert a duplicate row.
     *
     * The normalization itself is tested in `RecurrenceIdNormalizationTest`. This test sets
     * the normalized originalInstanceTime and checks linkException leaves one row on the day.
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

        // The exception's RECURRENCE-ID is the DATE Jan 23 2025. Normalization makes it
        // Jan 23 10:00 UTC, the master's RRULE-expanded instance that day.
        val recurrenceIdNormalizedTs = MASTER_START + 3 * 86400_000L
        // The exception moves it to Jan 23 14:00 UTC.
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
     * Tests the synthetic master a CalDAV pull makes for an orphan exception.
     *
     * On a first sync, or when the master is outside the lookback window, the server can
     * return an exception VEVENT without its master. Dropping it would lose the edit: when the
     * master arrives, its RRULE shows the unedited occurrence at the original time.
     *
     * As in ICS sync (#227), the pull inserts a placeholder master tagged
     * `X-KASHCAL-SYNTHETIC-MASTER`, with `rrule = null` and `status = "CANCELLED"`, and the
     * exception links to it by `originalEventId`. When the real master arrives, the master pass
     * finds the placeholder by UID and upserts over its row id (real RRULE, sentinel cleared),
     * so exception FKs survive.
     *
     * This test calls the helper directly; the pull's use of it isn't asserted here.
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

        // The sentinel lets the FTS-search and title-suggest exclusions in `EventsDao`, shared
        // with ICS-sync synthetics, hide it.
        assertEquals(
            "true",
            syntheticMaster.extraProperties?.get("X-KASHCAL-SYNTHETIC-MASTER"),
        )
        // No RRULE: the synthetic is only an FK target. OccurrenceGenerator skips it by the
        // sentinel; without that skip the rrule-less row would get a single phantom occurrence.
        assertEquals(null, syntheticMaster.rrule)
        // CANCELLED is a valid RFC 5545 STATUS.
        assertEquals("CANCELLED", syntheticMaster.status)
        // UID and calendar let the master pass find it by UID.
        assertEquals(orphanUid, syntheticMaster.uid)
        assertEquals(testCalendarId, syntheticMaster.calendarId)
        // SYNCED, so it isn't queued for push.
        assertEquals(SyncStatus.SYNCED, syntheticMaster.syncStatus)
    }

    @Test
    fun `synthetic master allows exception to link via FK without duplicate occurrence`() = runTest {
        // Synthesize and insert the placeholder, insert an exception whose originalEventId
        // points at it, then linkException. The day has one occurrence row (the exception's)
        // and none from the synthetic.
        val orphanUid = UUID.randomUUID().toString()

        val syntheticMaster = org.onekash.kashcal.sync.strategy.synthesizeMasterForOrphanException(
            uid = orphanUid,
            calendarId = testCalendarId,
            recurrenceIdMs = ORIGINAL_INSTANCE_TIME,
            placeholderTitle = "Edited occurrence",
        )
        val syntheticId = database.eventsDao().upsert(syntheticMaster)
        assertTrue("Synthetic master must insert with a real id", syntheticId > 0)

        // Regenerating the synthetic gives no occurrence: generateOccurrences returns 0 for
        // the sentinel.
        occurrenceGenerator.regenerateOccurrences(syntheticMaster.copy(id = syntheticId))
        val syntheticOccs = database.occurrencesDao().getForEvent(syntheticId)
        assertEquals(
            "Synthetic master with rrule=null must NOT generate any occurrence " +
                "(regen path must guard the sentinel)",
            0,
            syntheticOccs.size,
        )

        // The orphan exception, linked to the synthetic.
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
        // State 1: the orphan arrives; the synthetic is created and the exception linked.
        // State 2: the real master with the same UID arrives and is upserted over the row.
        // State 3: the real master has the synthetic's id; exception FKs survive.
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

        // State 2: the real master arrives in a later sync, for example after the window
        // widened. The pull's master pass looks it up by (uid, calendarId,
        // original_event_id IS NULL), finds the synthetic and upserts with its id, as
        // IcsSubscriptionRepository.upsertEvent does. Same row id, real RRULE, sentinel
        // cleared; exception FKs are untouched.
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
            extraProperties = null, // the real master clears the sentinel
        )
        val upsertResult = database.eventsDao().upsert(realMaster)
        // @Upsert returns -1 for an update; the row keeps existingForRealMaster.id, passed in
        // as the id.
        val realMasterId = if (upsertResult == -1L) existingForRealMaster.id else upsertResult

        // State 3
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
        // The exception's FK still points at the same row.
        val storedException = database.eventsDao().getById(exceptionId)
        assertEquals(realMasterId, storedException?.originalEventId)
    }

    /**
     * Tests the repair for a pull killed between committing an exception row and linking it:
     * the master's RRULE-expanded occurrence at the instance time has no exception_event_id
     * while the exception row exists.
     *
     * On the next pull both etags match, so the master pass doesn't regenerate the master
     * (the UID isn't in `uidsWithRegeneratedMaster`) and the exception pass takes its
     * etag-unchanged branch. Without a repair the state would stay broken until the master's
     * etag changed; that branch re-runs linkException when the occurrence at the instance time
     * is unlinked.
     *
     * The test builds the broken state and makes that linkException call directly;
     * PullStrategy's guard isn't exercised here.
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

        // Insert the exception row without calling linkException: the post-crash state, with
        // the master's occurrence at recurrenceIdTime unlinked.
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

        // The master's occurrence at recurrenceIdTime is unlinked.
        val occBefore = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, recurrenceIdTime)
        assertNotNull(occBefore)
        assertEquals(null, occBefore!!.exceptionEventId)

        // The pull's repair:
        //   if (occ != null && occ.exceptionEventId == null) {
        //     linkException(masterId, recurrenceIdTime, savedException)
        //   }
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, savedException)

        // Repaired: a linked occurrence at the exception's time.
        val occAfter = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, exceptionStartTs)
        assertNotNull("Occurrence at exception's modified time must exist", occAfter)
        assertEquals(exceptionId, occAfter!!.exceptionEventId)
        // The unlinked row at recurrenceIdTime is gone.
        val phantom = database.occurrencesDao()
            .getByEventIdAndStartTs(masterId, recurrenceIdTime)
        assertEquals("Unlinked row must be replaced, not duplicated", null, phantom)
    }

    @Test
    fun `linkException is no-op when occurrence is already linked correctly`() = runTest {
        // Re-running linkException on a linked occurrence must not add a row.
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

        // Again: still one row.
        occurrenceGenerator.linkException(masterId, recurrenceIdTime, saved)

        val rowsAfter = database.occurrencesDao().getForEvent(masterId)
            .filter { it.exceptionEventId == exceptionId }
        assertEquals(
            "Re-running linkException must be idempotent — exactly ONE linked row",
            1,
            rowsAfter.size,
        )
        // The row is at the exception's time and points at the exception. Its id can change:
        // linkException's conflict check deletes the linked row itself (it sits at the new
        // start) and the insert step re-creates it.
        assertEquals(exceptionStartTs, rowsAfter.single().startTs)
        assertEquals(exceptionId, rowsAfter.single().exceptionEventId)
    }

    // ==================== Helpers ====================

    private fun Long.toDateString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(this))
    }
}
