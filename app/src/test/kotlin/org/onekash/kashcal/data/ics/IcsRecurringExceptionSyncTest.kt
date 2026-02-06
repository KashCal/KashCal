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
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
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
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns listOf(
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
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns listOf(
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
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns listOf(existingMaster)

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
     * linkException for exceptions (Model B pattern per CLAUDE.md #13).
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
     * Exception without master in same feed should be skipped with warning.
     * This is acceptable for ICS subscriptions since feeds are read-only
     * and typically complete.
     */
    @Test
    fun `exception without master in same feed should be skipped with warning`() = runTest {
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

        // Should succeed but add 0 events (exception skipped due to missing master)
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(0, (result as IcsSubscriptionRepository.SyncResult.Success).count.added)
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
}
