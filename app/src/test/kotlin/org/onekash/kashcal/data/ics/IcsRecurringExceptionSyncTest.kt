package org.onekash.kashcal.data.ics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Tests for ICS subscription sync with recurring events that have exceptions.
 *
 * GitHub Issue #36: Outlook ICS sync fails with UNIQUE constraint failed
 * https://github.com/KashCal/KashCal/issues/36
 *
 * RFC 5545 specifies that exception events (modified occurrences) share the same UID
 * as their master event and are distinguished by RECURRENCE-ID. The ICS subscription
 * sync must properly handle this by:
 * 1. Using importId (which includes RECURRENCE-ID) for deduplication, not UID alone
 * 2. Linking exception events to their master via originalEventId
 *
 * Test cases:
 * - Outlook ICS with master + exceptions (exact reproduction of issue #36)
 * - Multiple exceptions for the same master
 * - Re-sync with modified exceptions
 * - Master event only (baseline)
 * - Exceptions with different summary/time than master
 */
class IcsRecurringExceptionSyncTest {

    // Mocks
    private lateinit var database: KashCalDatabase
    private lateinit var icsSubscriptionsDao: IcsSubscriptionsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var icsFetcher: IcsFetcher
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var eventReader: EventReader

    // System under test
    private lateinit var repository: IcsSubscriptionRepository

    // Captured events for verification
    private val insertedEvents = mutableListOf<Event>()
    private val updatedEvents = mutableListOf<Event>()

    // Test subscription
    private val testSubscription = IcsSubscription(
        id = 1L,
        url = "https://outlook.office365.com/calendar.ics",
        name = "Outlook Calendar",
        color = 0xFF0000FF.toInt(),
        calendarId = 100L,
        enabled = true,
        syncIntervalHours = 24,
        lastSync = 0L,
        etag = null,
        lastModified = null,
        lastError = null
    )

    /**
     * Exact ICS content from GitHub issue #36.
     * Outlook uses the same UID for master and exceptions (correct per RFC 5545).
     */
    private val outlookIcsFromIssue = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:Microsoft Exchange Server 2010
        BEGIN:VEVENT
        UID:040000008200E00074C5B7101A82E008000000000AF7F171249FDB010000000000000000100000007F622C628A6CAF41803A50FD1817AB5A
        SUMMARY:Daily To-Do
        DTSTART;TZID=India Standard Time:20250402T101000
        DTEND;TZID=India Standard Time:20250402T103000
        RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR
        END:VEVENT
        BEGIN:VEVENT
        UID:040000008200E00074C5B7101A82E008000000000AF7F171249FDB010000000000000000100000007F622C628A6CAF41803A50FD1817AB5A
        RECURRENCE-ID;TZID=India Standard Time:20250404T101000
        SUMMARY:Daily To-Do
        DTSTART;TZID=India Standard Time:20250404T101000
        DTEND;TZID=India Standard Time:20250404T103000
        END:VEVENT
        BEGIN:VEVENT
        UID:040000008200E00074C5B7101A82E008000000000AF7F171249FDB010000000000000000100000007F622C628A6CAF41803A50FD1817AB5A
        RECURRENCE-ID;TZID=India Standard Time:20250408T101000
        SUMMARY:Canceled: Daily To-Do
        DTSTART;TZID=India Standard Time:20250408T101000
        DTEND;TZID=India Standard Time:20250408T103000
        STATUS:CANCELLED
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /**
     * ICS with master + multiple exceptions (different scenarios).
     */
    private val masterWithMultipleExceptions = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//KashCal//EN
        BEGIN:VEVENT
        UID:recurring-master@kashcal.test
        DTSTAMP:20250101T000000Z
        SUMMARY:Weekly Team Meeting
        DTSTART:20250106T100000Z
        DTEND:20250106T110000Z
        RRULE:FREQ=WEEKLY;COUNT=10
        END:VEVENT
        BEGIN:VEVENT
        UID:recurring-master@kashcal.test
        DTSTAMP:20250101T000000Z
        RECURRENCE-ID:20250113T100000Z
        SUMMARY:Weekly Team Meeting (Moved)
        DTSTART:20250113T140000Z
        DTEND:20250113T150000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:recurring-master@kashcal.test
        DTSTAMP:20250101T000000Z
        RECURRENCE-ID:20250120T100000Z
        SUMMARY:Weekly Team Meeting (Room Changed)
        LOCATION:Conference Room B
        DTSTART:20250120T100000Z
        DTEND:20250120T110000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:recurring-master@kashcal.test
        DTSTAMP:20250101T000000Z
        RECURRENCE-ID:20250127T100000Z
        SUMMARY:Canceled
        DTSTART:20250127T100000Z
        DTEND:20250127T110000Z
        STATUS:CANCELLED
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /**
     * Simple ICS with only master event (no exceptions) - baseline test.
     */
    private val masterOnlyIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//KashCal//EN
        BEGIN:VEVENT
        UID:master-only@kashcal.test
        DTSTAMP:20250101T000000Z
        SUMMARY:Daily Standup
        DTSTART:20250106T090000Z
        DTEND:20250106T091500Z
        RRULE:FREQ=DAILY;COUNT=5
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /**
     * ICS with exception having significantly different properties than master.
     */
    private val exceptionWithDifferentProperties = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//KashCal//EN
        BEGIN:VEVENT
        UID:different-props@kashcal.test
        DTSTAMP:20250101T000000Z
        SUMMARY:Morning Coffee Chat
        DTSTART:20250106T080000Z
        DTEND:20250106T083000Z
        RRULE:FREQ=DAILY;COUNT=5
        LOCATION:Kitchen
        DESCRIPTION:Casual morning chat
        END:VEVENT
        BEGIN:VEVENT
        UID:different-props@kashcal.test
        DTSTAMP:20250101T000000Z
        RECURRENCE-ID:20250107T080000Z
        SUMMARY:Special Breakfast Meeting
        DTSTART:20250107T073000Z
        DTEND:20250107T090000Z
        LOCATION:Main Conference Room
        DESCRIPTION:Important client breakfast
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        icsSubscriptionsDao = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        occurrenceGenerator = mockk(relaxed = true)
        icsFetcher = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)

        insertedEvents.clear()
        updatedEvents.clear()

        // Mock database.runInTransaction to just execute the block
        coEvery { database.runInTransaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        repository = IcsSubscriptionRepository(
            database = database,
            icsSubscriptionsDao = icsSubscriptionsDao,
            accountRepository = accountRepository,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            icsFetcher = icsFetcher,
            reminderScheduler = reminderScheduler,
            eventReader = eventReader
        )

        // Default: ICS account exists
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns Account(
            id = 1L,
            provider = AccountProvider.ICS,
            email = "subscriptions@local",
            isEnabled = true
        )

        // Capture inserted events with proper ID assignment
        var nextInsertId = 1000L
        coEvery { eventsDao.insert(any()) } answers {
            val event = firstArg<Event>()
            val assignedId = nextInsertId++
            insertedEvents.add(event.copy(id = assignedId))
            assignedId  // Return the ID
        }

        // Capture updated events
        coEvery { eventsDao.update(any()) } answers {
            val event = firstArg<Event>()
            updatedEvents.add(event)
        }
    }

    // ==================== Issue #36 Reproduction ====================

    /**
     * FAILING TEST: Exact reproduction of GitHub issue #36.
     *
     * When syncing an Outlook ICS feed with a recurring event that has exceptions,
     * the sync should:
     * 1. Create master event with RRULE
     * 2. Create exception events linked to master via originalEventId
     * 3. NOT fail with UNIQUE constraint error
     *
     * Current behavior: Fails because sync uses UID-only matching, causing
     * exceptions to overwrite the master or fail with duplicate UID error.
     */
    @Test
    fun `issue 36 - Outlook ICS with recurring event exceptions should sync successfully`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = outlookIcsFromIssue,
            etag = "\"outlook-etag\"",
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        // Should succeed, not fail with UNIQUE constraint
        assertTrue(
            "Sync should succeed for Outlook ICS with exceptions",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        // Should have inserted 2 events (master + 1 non-cancelled exception)
        // Note: CANCELLED exception is filtered out by IcsParserService
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(
            "Should add master + 1 exception (cancelled is filtered)",
            2,
            success.count.added
        )

        // Verify events were inserted with correct structure
        val master = insertedEvents.find { it.rrule != null }
        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Master event should be created", master)
        assertNotNull("Exception event should be created", exception)

        // Master should have RRULE
        assertTrue(
            "Master should have RRULE",
            master!!.rrule?.contains("FREQ=WEEKLY") == true
        )

        // Exception should be linked to master
        assertNotNull(
            "Exception should have originalEventId linking to master",
            exception!!.originalEventId
        )

        // Both should have the same UID (RFC 5545 requirement)
        assertEquals(
            "Master and exception should share same UID",
            master.uid,
            exception.uid
        )

        // But different importIds (for database uniqueness)
        assertNotEquals(
            "Master and exception should have different importIds",
            master.importId,
            exception.importId
        )
    }

    // ==================== Multiple Exceptions Tests ====================

    /**
     * FAILING TEST: Multiple exceptions for the same recurring master.
     */
    @Test
    fun `multiple exceptions should each be linked to same master`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterWithMultipleExceptions,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)

        // Should have master + 2 non-cancelled exceptions (1 cancelled is filtered)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(
            "Should add master + 2 exceptions (cancelled filtered)",
            3,
            success.count.added
        )

        // Verify structure
        val master = insertedEvents.find { it.rrule != null }
        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }

        assertNotNull("Master event should exist", master)
        assertEquals("Should have 2 exception events", 2, exceptions.size)

        // All exceptions should link to the same master
        exceptions.forEach { exception ->
            assertEquals(
                "Exception should link to master",
                master!!.id,
                exception.originalEventId
            )
            assertEquals(
                "Exception should share master's UID",
                master.uid,
                exception.uid
            )
        }

        // Each exception should have unique importId
        val importIds = insertedEvents.map { it.importId }.toSet()
        assertEquals(
            "Each event should have unique importId",
            3,
            importIds.size
        )
    }

    /**
     * FAILING TEST: Exception events should preserve their specific properties.
     */
    @Test
    fun `exception events should preserve their modified properties`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = exceptionWithDifferentProperties,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)

        val master = insertedEvents.find { it.rrule != null }
        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Master should exist", master)
        assertNotNull("Exception should exist", exception)

        // Exception should have its own properties, not master's
        assertEquals("Special Breakfast Meeting", exception!!.title)
        assertEquals("Main Conference Room", exception.location)
        assertEquals("Important client breakfast", exception.description)

        // Master should have original properties
        assertEquals("Morning Coffee Chat", master!!.title)
        assertEquals("Kitchen", master.location)
    }

    // ==================== Baseline Tests ====================

    /**
     * PASSING TEST: Master-only recurring event (no exceptions) should work.
     */
    @Test
    fun `master-only recurring event should sync normally`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterOnlyIcs,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals("Should add 1 master event", 1, success.count.added)

        val master = insertedEvents.first()
        assertNotNull("Should have RRULE", master.rrule)
        assertNull("Should not have originalEventId", master.originalEventId)
        assertNull("Should not have originalInstanceTime", master.originalInstanceTime)
    }

    // ==================== Re-sync Tests ====================

    /**
     * FAILING TEST: Re-syncing should update existing events correctly.
     *
     * When re-syncing, the repository should:
     * 1. Match existing events by importId (not UID)
     * 2. Update master and exceptions independently
     * 3. Not create duplicates
     */
    @Test
    fun `re-sync should update existing master and exceptions independently`() = runTest {
        // First sync creates initial events
        val existingMaster = Event(
            id = 100L,
            uid = "recurring-master@kashcal.test",
            importId = "recurring-master@kashcal.test",
            calendarId = testSubscription.calendarId,
            title = "Weekly Team Meeting",
            startTs = 1736157600000L, // 2025-01-06 10:00 UTC
            endTs = 1736161200000L,   // 2025-01-06 11:00 UTC
            dtstamp = 0L,
            rrule = "FREQ=WEEKLY;COUNT=10",
            caldavUrl = "ics_subscription:1:recurring-master@kashcal.test",
            syncStatus = SyncStatus.SYNCED
        )

        val existingException = Event(
            id = 101L,
            uid = "recurring-master@kashcal.test",
            importId = "recurring-master@kashcal.test:RECID:20250113T100000Z",  // iCal datetime format
            calendarId = testSubscription.calendarId,
            title = "Weekly Team Meeting (Moved)",
            startTs = 1736780400000L, // 2025-01-13 14:00 UTC
            endTs = 1736784000000L,   // 2025-01-13 15:00 UTC
            dtstamp = 0L,
            originalEventId = 100L,
            originalInstanceTime = 1736762400000L, // 2025-01-13 10:00 UTC
            caldavUrl = "ics_subscription:1:recurring-master@kashcal.test:RECID:20250113T100000Z",  // importId-based format
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterWithMultipleExceptions,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(
            existingMaster,
            existingException
        )

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // Should update existing master and exception, add new exception
        // Master + Exception1 = updated (2)
        // Exception2 = added (1)
        // Exception3 (cancelled) = filtered out
        assertEquals("Should update 2 existing events", 2, success.count.updated)
        assertEquals("Should add 1 new exception", 1, success.count.added)
    }

    /**
     * FAILING TEST: Orphaned exception should be deleted when master is removed.
     */
    @Test
    fun `orphaned exceptions should be deleted when master is removed from feed`() = runTest {
        // Existing events from previous sync
        val existingMaster = Event(
            id = 100L,
            uid = "old-master@kashcal.test",
            importId = "old-master@kashcal.test",
            calendarId = testSubscription.calendarId,
            title = "Old Meeting",
            startTs = 1736157600000L,
            endTs = 1736161200000L,
            dtstamp = 0L,
            rrule = "FREQ=WEEKLY;COUNT=10",
            caldavUrl = "ics_subscription:1:old-master@kashcal.test",
            syncStatus = SyncStatus.SYNCED
        )

        val existingException = Event(
            id = 101L,
            uid = "old-master@kashcal.test",
            importId = "old-master@kashcal.test:RECID:20250113T100000Z",  // iCal datetime format
            calendarId = testSubscription.calendarId,
            title = "Old Meeting (Moved)",
            startTs = 1736780400000L,
            endTs = 1736784000000L,
            dtstamp = 0L,
            originalEventId = 100L,
            originalInstanceTime = 1736762400000L, // 2025-01-13 10:00 UTC
            caldavUrl = "ics_subscription:1:old-master@kashcal.test:RECID:20250113T100000Z",  // importId-based format
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterOnlyIcs, // Different event, old one removed
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(
            existingMaster,
            existingException
        )

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // Both master and exception should be deleted (orphaned)
        assertEquals("Should delete orphaned master and exception", 2, success.count.deleted)
        coVerify { eventsDao.deleteById(100L) }
        coVerify { eventsDao.deleteById(101L) }
    }

    // ==================== Edge Cases ====================

    /**
     * FAILING TEST: Exception-only feed (master in different sync) should work.
     * Some calendar systems may send exceptions separately.
     */
    @Test
    fun `exception referencing existing master should link correctly`() = runTest {
        // Master already exists from previous sync
        val existingMaster = Event(
            id = 100L,
            uid = "different-props@kashcal.test",
            importId = "different-props@kashcal.test",
            calendarId = testSubscription.calendarId,
            title = "Morning Coffee Chat",
            startTs = 1736150400000L,
            endTs = 1736152200000L,
            dtstamp = 0L,
            rrule = "FREQ=DAILY;COUNT=5",
            location = "Kitchen",
            caldavUrl = "ics_subscription:1:different-props@kashcal.test",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = exceptionWithDifferentProperties,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(existingMaster)

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)

        // Exception should link to existing master
        val insertedExceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals("Should insert 1 exception", 1, insertedExceptions.size)
        assertEquals(
            "Exception should link to existing master",
            100L,
            insertedExceptions.first().originalEventId
        )
    }

    /**
     * FAILING TEST: caldavUrl format should include RECURRENCE-ID for exceptions.
     */
    @Test
    fun `caldavUrl should be unique for master and exceptions`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterWithMultipleExceptions,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // Each event should have a unique caldavUrl
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event (master + exceptions) should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )
    }

    // ==================== Occurrence Generation Tests ====================

    /**
     * Verify correct occurrence handling: regenerateOccurrences for master,
     * linkException for exceptions (Model B occurrence-linking pattern).
     */
    @Test
    fun `occurrences should be generated correctly for masters and exceptions`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterWithMultipleExceptions,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // Master uses regenerateOccurrences (1 master)
        coVerify(exactly = 1) { occurrenceGenerator.regenerateOccurrences(any()) }

        // Exceptions use linkException (Model B pattern) - 2 non-cancelled exceptions
        coVerify(exactly = 2) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    // ==================== Edge Case Tests ====================

    /**
     * Issue #227: orphaned RECURRENCE-ID is imported as a standalone event.
     *
     * Google Calendar legitimately emits exception events with no master in
     * the same feed (master sliced out of export window, or series deleted).
     * The orphan must appear on the user's calendar at its DTSTART, not be
     * silently dropped. The :RECID: marker stays in importId so a future
     * sync can detect this row was a promoted orphan.
     */
    @Test
    fun `orphaned RECURRENCE-ID should be imported as standalone event`() = runTest {
        val exceptionOnlyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:orphan@test
            DTSTAMP:20250101T000000Z
            RECURRENCE-ID:20250106T100000Z
            SUMMARY:Orphaned Exception
            DTSTART:20250106T140000Z
            DTEND:20250106T150000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = exceptionOnlyIcs,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        // Promoted to standalone — visible to the user.
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Orphan should be imported as 1 standalone event",
            1,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )

        assertEquals("Should insert exactly 1 event", 1, insertedEvents.size)
        val standalone = insertedEvents.first()
        assertNull(
            "Standalone must not carry originalEventId — it's not an exception of anything stored",
            standalone.originalEventId
        )
        assertNull(
            "Standalone must not carry originalInstanceTime — it would mark the row as a phantom exception",
            standalone.originalInstanceTime
        )
        assertEquals("UID is preserved", "orphan@test", standalone.uid)

        // The :RECID: marker stays in importId — that's how a future sync
        // detects this row was a promoted orphan and sweeps it when the
        // master arrives.
        assertNotNull("ImportId should be set", standalone.importId)
        assertTrue(
            "ImportId should retain :RECID: marker for re-sync detection (was: ${standalone.importId})",
            standalone.importId!!.contains(":RECID:")
        )

        // Single-occurrence path, not exception linking.
        coVerify(exactly = 1) { occurrenceGenerator.regenerateOccurrences(any()) }
        coVerify(exactly = 0) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    /**
     * Issue #227 re-sync transition: in sync N, an orphan is promoted to
     * standalone. In sync N+1, the master arrives. The previously-promoted
     * standalone row must be swept (deleted, reminders cancelled) and
     * existingByImportId must be invalidated, so the master inserts cleanly
     * without tripping the master-uniqueness trigger and the inbound
     * exception inserts as a fresh exception row linked to the new master.
     *
     * The fresh exception's row id will differ from the prior standalone's
     * id — that's the regression discriminator that prevents a future
     * "optimization" back to in-place mutation, which is unsolvable against
     * the trigger ordering (see docs/ISSUE_227_ICS_IMPORT_ANALYSIS.md).
     */
    @Test
    fun `re-sync where master arrives after orphan promotion sweeps standalone and links fresh exception`() = runTest {
        val orphanOnlyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:later-master@test
            DTSTAMP:20250101T000000Z
            RECURRENCE-ID:20250113T100000Z
            SUMMARY:Standalone Today, Exception Later
            DTSTART:20250113T140000Z
            DTEND:20250113T150000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val orphanPlusMasterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:later-master@test
            DTSTAMP:20250101T000000Z
            SUMMARY:Weekly Series
            DTSTART:20250106T100000Z
            DTEND:20250106T110000Z
            RRULE:FREQ=WEEKLY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:later-master@test
            DTSTAMP:20250101T000000Z
            RECURRENCE-ID:20250113T100000Z
            SUMMARY:Standalone Today, Exception Later
            DTSTART:20250113T140000Z
            DTEND:20250113T150000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription

        // Sync N: feed has only the orphan exception.
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanOnlyIcs,
            etag = null,
            lastModified = null
        )
        // First refresh sees no existing rows for the subscription prefix.
        // Second refresh (after the orphan was promoted) sees the standalone
        // row in storage. We answer dynamically from `insertedEvents` so the
        // second call returns the row we captured during the first refresh.
        coEvery {
            eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any())
        } answers {
            insertedEvents.toList()
        }

        val firstResult = repository.refreshSubscription(1L)
        assertTrue(firstResult is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Sync N: orphan promoted to standalone",
            1,
            (firstResult as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        val priorStandaloneId = insertedEvents.single().id
        // Sanity: the standalone row carries the :RECID: marker, which is
        // what the sweep predicate keys on.
        assertTrue(
            "Sync N's standalone must carry :RECID: in importId so sync N+1 can sweep it",
            insertedEvents.single().importId?.contains(":RECID:") == true
        )

        // Sync N+1: feed now contains the master + the same orphan.
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanPlusMasterIcs,
            etag = null,
            lastModified = null
        )

        val secondResult = repository.refreshSubscription(1L)

        assertTrue(secondResult is IcsSubscriptionRepository.SyncResult.Success)
        val syncCount = (secondResult as IcsSubscriptionRepository.SyncResult.Success).count
        assertEquals("Standalone row swept", 1, syncCount.deleted)
        assertEquals("Master + fresh exception inserted", 2, syncCount.added)

        // Reminders cancelled for the swept row before deletion.
        coVerify { reminderScheduler.cancelRemindersForEvent(priorStandaloneId) }
        coVerify { eventsDao.deleteById(priorStandaloneId) }

        // End-state verification: 1 master + 1 properly-linked exception
        // among the rows inserted in sync N+1 (i.e., excluding the swept).
        val syncTwoInserts = insertedEvents.filter { it.id != priorStandaloneId }
        assertEquals(2, syncTwoInserts.size)
        val master = syncTwoInserts.single { it.rrule != null }
        val freshException = syncTwoInserts.single { it.originalInstanceTime != null }
        assertNull("Master has no originalEventId", master.originalEventId)
        assertEquals(
            "Fresh exception links to the just-inserted master",
            master.id,
            freshException.originalEventId
        )
        assertNotEquals(
            "Fresh exception's id must differ from the swept standalone's id — guards against in-place mutation regression",
            priorStandaloneId,
            freshException.id
        )

        // linkException ran for the fresh exception against the new master.
        coVerify {
            occurrenceGenerator.linkException(
                masterEventId = master.id,
                occurrenceTimeMs = freshException.originalInstanceTime!!,
                exceptionEvent = any()
            )
        }
    }

    /**
     * Verify linkException is called with correct parameters:
     * - masterId from the linked master event
     * - originalInstanceTime from the exception
     * - the exception Event itself
     */
    @Test
    fun `exception events should call linkException with correct parameters`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterWithMultipleExceptions,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // Verify exceptions were inserted with correct structure
        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals(2, exceptions.size)

        // Each exception should have originalEventId set (linked to master)
        exceptions.forEach { exception ->
            assertNotNull("Exception should have originalEventId", exception.originalEventId)
            assertNotNull("Exception should have originalInstanceTime", exception.originalInstanceTime)
        }

        // Verify linkException was called for each exception (2 times)
        coVerify(exactly = 2) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    // ==================== Issue #227: Duplicate UID disambiguation ====================

    /**
     * Alias to the production constant — tests assert against the same key
     * so a rename in production breaks them rather than silently diverging.
     */
    private val originalUidExtraKey = ORIGINAL_UID_EXTRA_KEY

    /**
     * Issue #227: Google's private ICS export sometimes emits two non-exception
     * VEVENTs sharing the same UID (RFC 5545 §3.8.4.7 says UID should be
     * unique, but Google does it). The fix mutates the uid column for both
     * events in the duplicate group so trigger_master_event_unique_insert
     * doesn't fire.
     */
    @Test
    fun `duplicate-UID masters in same feed are persisted with distinct disambiguated UIDs`() = runTest {
        val duplicateUidIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            BEGIN:VEVENT
            UID:xxx@google.com
            DTSTAMP:20260517T155628Z
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            SUMMARY:First Busy
            END:VEVENT
            BEGIN:VEVENT
            UID:xxx@google.com
            DTSTAMP:20260517T155628Z
            DTSTART:20270226T114500Z
            DTEND:20270226T120000Z
            SUMMARY:Second Busy
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = duplicateUidIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Both duplicate-UID events must be persisted",
            2,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals(2, insertedEvents.size)

        val uids = insertedEvents.map { it.uid }.toSet()
        assertEquals(
            "Stored UIDs must be distinct after disambiguation (got: $uids)",
            2,
            uids.size
        )
        insertedEvents.forEach { event ->
            assertTrue(
                "Stored UID must use #dup= disambiguator (was: ${event.uid})",
                event.uid.startsWith("xxx@google.com#dup=")
            )
            assertEquals(
                "Original UID must be preserved in extraProperties",
                "xxx@google.com",
                event.extraProperties?.get(originalUidExtraKey)
            )
        }

        val importIds = insertedEvents.mapNotNull { it.importId }.toSet()
        assertEquals("ImportIds must be distinct", 2, importIds.size)
        val caldavUrls = insertedEvents.mapNotNull { it.caldavUrl }.toSet()
        assertEquals("CaldavUrls must be distinct", 2, caldavUrls.size)
    }

    /**
     * Issue #227: re-syncing the same duplicate-UID feed must produce
     * `updated=2, added=0` — proving the disambiguator (event.startTs) is
     * stable across syncs, so existingByImportId matches and the upsert
     * takes the update path, not the insert path.
     */
    @Test
    fun `duplicate-UID disambiguation is idempotent across re-sync`() = runTest {
        val duplicateUidIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            BEGIN:VEVENT
            UID:xxx@google.com
            DTSTAMP:20260517T155628Z
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            SUMMARY:First Busy
            END:VEVENT
            BEGIN:VEVENT
            UID:xxx@google.com
            DTSTAMP:20260517T155628Z
            DTSTART:20270226T114500Z
            DTEND:20270226T120000Z
            SUMMARY:Second Busy
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = duplicateUidIcs, etag = null, lastModified = null
        )
        // Returns dynamic state: empty on first call, the just-inserted rows
        // on the second. The mock at insertedEvents captures rows with
        // assigned ids and sets caldavUrl=null on the captured copy, so we
        // re-derive caldavUrl from the inbound event's original caldavUrl
        // which the production code mutates before insert. We approximate
        // by returning insertedEvents directly.
        coEvery {
            eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any())
        } answers {
            insertedEvents.toList()
        }

        val first = repository.refreshSubscription(1L)
        assertTrue(first is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(2, (first as IcsSubscriptionRepository.SyncResult.Success).count.added)

        val firstUids = insertedEvents.map { it.uid }.sorted()
        val firstImportIds = insertedEvents.mapNotNull { it.importId }.sorted()

        val second = repository.refreshSubscription(1L)
        assertTrue(second is IcsSubscriptionRepository.SyncResult.Success)
        val secondCount = (second as IcsSubscriptionRepository.SyncResult.Success).count
        assertEquals("Re-sync must update, not add", 0, secondCount.added)
        assertEquals("Both rows updated on re-sync", 2, secondCount.updated)
        assertEquals("No deletion on re-sync", 0, secondCount.deleted)

        // Mutated UIDs must be deterministic across syncs (function of startTs).
        val secondUids = updatedEvents.map { it.uid }.sorted()
        val secondImportIds = updatedEvents.mapNotNull { it.importId }.sorted()
        assertEquals("UIDs stable across re-sync", firstUids, secondUids)
        assertEquals("ImportIds stable across re-sync", firstImportIds, secondImportIds)

        // X-KASHCAL-ORIGINAL-UID survives the upsert overwrite.
        updatedEvents.forEach { event ->
            assertEquals(
                "Original UID marker must survive re-sync",
                "xxx@google.com",
                event.extraProperties?.get(originalUidExtraKey)
            )
        }
    }

    /**
     * Issue #227: a feed with a single VEVENT for a UID must NOT trigger
     * disambiguation. The healthy single-event-per-UID path is the common
     * case and must not regress.
     */
    @Test
    fun `single-occurrence UID is not mutated`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterOnlyIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        assertEquals(1, insertedEvents.size)
        val event = insertedEvents.single()
        assertEquals(
            "Single-occurrence UID must remain unmodified",
            "master-only@kashcal.test",
            event.uid
        )
        assertNull(
            "X-KASHCAL-ORIGINAL-UID must not be set when no disambiguation occurred",
            event.extraProperties?.get(originalUidExtraKey)
        )
    }

    /**
     * Issue #227 degenerate case: two events sharing UID *and* DTSTART. After
     * the disambiguator (startTs) is appended, the mutated UIDs still
     * collide. The first persists, the second's INSERT trips the trigger
     * and is caught at the existing catch site. Sync still returns Success
     * with count.added==1. No crash.
     */
    @Test
    fun `same-UID same-DTSTART degenerate case persists first event without crash`() = runTest {
        val degenerateIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:dupe@test
            DTSTAMP:20260517T155628Z
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            SUMMARY:First Copy
            END:VEVENT
            BEGIN:VEVENT
            UID:dupe@test
            DTSTAMP:20260517T155628Z
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            SUMMARY:Second Copy (collides post-mutation)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = degenerateIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        // Simulate the master-uniqueness trigger firing on the second insert
        // — production catches SQLiteConstraintException at the master-loop
        // catch site (line 441-443). Real DB behavior is verified separately
        // in IcsSubscriptionRepositoryDuplicateUidIntegrationTest.
        var insertCallCount = 0
        coEvery { eventsDao.insert(any()) } answers {
            insertCallCount++
            if (insertCallCount == 1) {
                val event = firstArg<Event>()
                insertedEvents.add(event.copy(id = 1000L))
                1000L
            } else {
                throw android.database.sqlite.SQLiteConstraintException(
                    "UNIQUE constraint failed: duplicate master event uid in calendar"
                )
            }
        }

        val result = repository.refreshSubscription(1L)

        assertTrue("Sync must not crash on degenerate input", result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Only the first event persists; second is swallowed at the catch site",
            1,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals(1, insertedEvents.size)
    }

    /**
     * Issue #227 regression anchor: the literal feed pasted in the bug
     * report. Combines Bug A (orphaned RECURRENCE-ID, `abc@google.com`)
     * with Bug B (duplicate UID, `xxx@google.com`) in a single feed. The
     * user reports "Found 3 events" but only 1 visible today. After the
     * fix, all 3 must appear.
     *
     * The malformed Event 3 (duplicate DTSTART/DTEND/DTSTAMP lines per
     * RFC 5545 §3.6.1) is tolerated by ical4j's existing parsing.
     */
    @Test
    fun `issue 227 - Google ICS feed with orphaned exception and duplicate UID yields 3 visible events`() = runTest {
        val issue227Ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:PUBLISH
            X-WR-CALNAME:test@example.com
            X-WR-TIMEZONE:UTC
            BEGIN:VEVENT
            DTSTART:20260409T130000Z
            DTEND:20260409T133000Z
            DTSTAMP:20260517T161041Z
            UID:abc@google.com
            ATTENDEE;X-NUM-GUESTS=0:mailto:test@example.com
            RECURRENCE-ID:20260409T130000Z
            SUMMARY:Busy
            END:VEVENT
            BEGIN:VEVENT
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            DTSTAMP:20260517T155628Z
            UID:xxx@google.com
            ATTENDEE;X-NUM-GUESTS=0:mailto:test@example.com
            SUMMARY:Busy
            END:VEVENT
            BEGIN:VEVENT
            DTSTART:20270226T114500Z
            DTEND:20270226T120000Z
            DTSTAMP:20260517T155628Z
            DTSTART:20260409T140000Z
            DTEND:20260409T150000Z
            DTSTAMP:20260517T155628Z
            UID:xxx@google.com
            ATTENDEE;X-NUM-GUESTS=0:mailto:test@example.com
            SUMMARY:Busy
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = issue227Ics, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "Sync must succeed without crashing on the malformed feed",
            result is IcsSubscriptionRepository.SyncResult.Success
        )
        assertEquals(
            "All 3 events from issue #227's feed must be imported",
            3,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals(3, insertedEvents.size)

        // Event 1: orphaned exception (abc@google.com) → standalone.
        val orphanStandalone = insertedEvents.singleOrNull { it.uid == "abc@google.com" }
        assertNotNull("Orphan exception promoted to standalone", orphanStandalone)
        assertNull(
            "Standalone must not carry originalEventId",
            orphanStandalone!!.originalEventId
        )
        assertNull(
            "Standalone must not carry originalInstanceTime",
            orphanStandalone.originalInstanceTime
        )

        // Events 2 + 3: duplicate-UID masters (xxx@google.com) → mutated UIDs,
        // original UID preserved in extraProperties.
        val mutated = insertedEvents.filter { it.uid.startsWith("xxx@google.com#dup=") }
        assertEquals("Both xxx@google.com events imported with mutated UIDs", 2, mutated.size)
        assertEquals(
            "Mutated UIDs are distinct",
            2,
            mutated.map { it.uid }.toSet().size
        )
        mutated.forEach { event ->
            assertEquals(
                "Original UID preserved in extraProperties",
                "xxx@google.com",
                event.extraProperties?.get(originalUidExtraKey)
            )
        }
    }

    /**
     * Issue #227 invariant: a UID that already contains the literal
     * `#dup=` substring must not double-mutate to collide with another
     * such UID. If two such UIDs collide on the original UID, they get
     * a second `#dup=` segment appended (e.g., `X#dup=T1#dup=T2`) — still
     * distinct, still valid.
     */
    @Test
    fun `UIDs containing literal hash-dup are not double-mutated to collide`() = runTest {
        val literalHashDupIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:weird#dup=preexisting@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260101T100000Z
            DTEND:20260101T110000Z
            SUMMARY:First
            END:VEVENT
            BEGIN:VEVENT
            UID:weird#dup=preexisting@test
            DTSTAMP:20260101T000000Z
            DTSTART:20260102T100000Z
            DTEND:20260102T110000Z
            SUMMARY:Second
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = literalHashDupIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Both events must persist despite original UID containing #dup=",
            2,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        val storedUids = insertedEvents.map { it.uid }.toSet()
        assertEquals("Mutated UIDs must remain distinct", 2, storedUids.size)
    }
}
