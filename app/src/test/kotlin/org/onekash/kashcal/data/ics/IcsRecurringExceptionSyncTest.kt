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
 * Tests ICS subscription sync of recurring events with exceptions, over mocked DAOs.
 *
 * #36: a feed with a master and its exceptions failed with a UNIQUE constraint error
 * (https://github.com/KashCal/KashCal/issues/36).
 *
 * RFC 5545 exceptions share their master's UID and differ by RECURRENCE-ID, so the sync must
 * match rows by importId (which includes the RECURRENCE-ID), not by UID alone, and link each
 * exception to its master through originalEventId.
 *
 * Covers:
 * - the #36 feed, several exceptions per master, and exceptions with their own properties
 * - a master-only feed, and an exception linked to a master stored by an earlier sync
 * - re-sync updates, deletes of rows gone from the feed, and caldavUrl uniqueness
 * - occurrence generation and linkException calls
 * - synthetic masters for exceptions whose master isn't in the feed (#227), their self-heal,
 *   re-sync and reminders, and the sweep of legacy standalone rows
 * - renaming duplicate-UID masters (#227)
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

    /** The feed from #36: master and exceptions share one UID, as RFC 5545 requires. */
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

    /** A master with three exceptions: moved, room changed, and cancelled. */
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

    /** A recurring master with no exceptions. */
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

    /** An exception whose title, time, location and description all differ from its master. */
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

        // Runs the transaction block inline.
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
            eventReader = eventReader,
            context = mockk(relaxed = true)
        )

        // The ICS account exists.
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns Account(
            id = 1L,
            provider = AccountProvider.ICS,
            email = "subscriptions@local",
            isEnabled = true
        )

        // Captures inserted events with an assigned row id.
        var nextInsertId = 1000L
        coEvery { eventsDao.insert(any()) } answers {
            val event = firstArg<Event>()
            val assignedId = nextInsertId++
            insertedEvents.add(event.copy(id = assignedId))
            assignedId
        }

        // Captures updated events.
        coEvery { eventsDao.update(any()) } answers {
            val event = firstArg<Event>()
            updatedEvents.add(event)
        }
    }

    // ==================== Issue #36 Reproduction ====================

    /**
     * Reproduces #36: the feed syncs without a UNIQUE constraint error, creating the master with
     * its RRULE and the exception linked through originalEventId, with one UID and distinct
     * importIds.
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

        assertTrue(
            "Sync should succeed for Outlook ICS with exceptions",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        // Master and one exception: IcsParserService drops the CANCELLED exception.
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(
            "Should add master + 1 exception (cancelled is filtered)",
            2,
            success.count.added
        )

        val master = insertedEvents.find { it.rrule != null }
        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Master event should be created", master)
        assertNotNull("Exception event should be created", exception)

        assertTrue(
            "Master should have RRULE",
            master!!.rrule?.contains("FREQ=WEEKLY") == true
        )

        assertNotNull(
            "Exception should have originalEventId linking to master",
            exception!!.originalEventId
        )

        // RFC 5545: an exception has its master's UID.
        assertEquals(
            "Master and exception should share same UID",
            master.uid,
            exception.uid
        )

        // Distinct importIds keep the rows apart.
        assertNotEquals(
            "Master and exception should have different importIds",
            master.importId,
            exception.importId
        )
    }

    // ==================== Multiple Exceptions Tests ====================

    /** Links every exception of one master to that master's row. */
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

        // Master and two exceptions; the cancelled one is dropped.
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(
            "Should add master + 2 exceptions (cancelled filtered)",
            3,
            success.count.added
        )

        val master = insertedEvents.find { it.rrule != null }
        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }

        assertNotNull("Master event should exist", master)
        assertEquals("Should have 2 exception events", 2, exceptions.size)

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

        // Every row, master included, has its own importId.
        val importIds = insertedEvents.map { it.importId }.toSet()
        assertEquals(
            "Each event should have unique importId",
            3,
            importIds.size
        )
    }

    /** Stores an exception's own title, location and description, not its master's. */
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

        assertEquals("Special Breakfast Meeting", exception!!.title)
        assertEquals("Main Conference Room", exception.location)
        assertEquals("Important client breakfast", exception.description)

        assertEquals("Morning Coffee Chat", master!!.title)
        assertEquals("Kitchen", master.location)
    }

    // ==================== Baseline Tests ====================

    /** Stores a master-only feed as one master with no exception fields. */
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
     * Matches stored rows by importId on re-sync, so the stored master and exception are updated
     * and only the new exception is added.
     */
    @Test
    fun `re-sync should update existing master and exceptions independently`() = runTest {
        // Rows from an earlier sync.
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
            importId = "recurring-master@kashcal.test:RECID:20250113T100000Z",
            calendarId = testSubscription.calendarId,
            title = "Weekly Team Meeting (Moved)",
            startTs = 1736780400000L, // 2025-01-13 14:00 UTC
            endTs = 1736784000000L,   // 2025-01-13 15:00 UTC
            dtstamp = 0L,
            originalEventId = 100L,
            originalInstanceTime = 1736762400000L, // 2025-01-13 10:00 UTC
            caldavUrl = "ics_subscription:1:recurring-master@kashcal.test:RECID:20250113T100000Z",
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

        // Master and the moved exception are updated, the room-changed exception is added,
        // and the cancelled one is dropped.
        assertEquals("Should update 2 existing events", 2, success.count.updated)
        assertEquals("Should add 1 new exception", 1, success.count.added)
    }

    /** Deletes a stored master and its exception when the feed no longer has them. */
    @Test
    fun `orphaned exceptions should be deleted when master is removed from feed`() = runTest {
        // Rows from an earlier sync.
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
            importId = "old-master@kashcal.test:RECID:20250113T100000Z",
            calendarId = testSubscription.calendarId,
            title = "Old Meeting (Moved)",
            startTs = 1736780400000L,
            endTs = 1736784000000L,
            dtstamp = 0L,
            originalEventId = 100L,
            originalInstanceTime = 1736762400000L, // 2025-01-13 10:00 UTC
            caldavUrl = "ics_subscription:1:old-master@kashcal.test:RECID:20250113T100000Z",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = masterOnlyIcs, // A different event; the old one is gone
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

        assertEquals("Should delete orphaned master and exception", 2, success.count.deleted)
        coVerify { eventsDao.deleteById(100L) }
        coVerify { eventsDao.deleteById(101L) }
    }

    // ==================== Edge Cases ====================

    /**
     * Links a new exception to the master row stored by an earlier sync. The feed carries the
     * master too, which updates that row in place.
     */
    @Test
    fun `exception referencing existing master should link correctly`() = runTest {
        // Stored by an earlier sync.
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

        val insertedExceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals("Should insert 1 exception", 1, insertedExceptions.size)
        assertEquals(
            "Exception should link to existing master",
            100L,
            insertedExceptions.first().originalEventId
        )
    }

    /** Gives the master and each exception its own caldavUrl. */
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

        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event (master + exceptions) should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )
    }

    // ==================== Occurrence Generation Tests ====================

    /** Regenerates occurrences for the master and calls linkException once per exception. */
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

        coVerify(exactly = 1) { occurrenceGenerator.regenerateOccurrences(any()) }

        // The two exceptions that aren't cancelled.
        coVerify(exactly = 2) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    // ==================== Edge Case Tests ====================

    /**
     * Links an exception whose master isn't in the feed to a synthetic master (#227).
     *
     * Google Calendar emits exception events with no master in the same feed (master outside
     * the export window, or series deleted). The exception must show as a linked exception, not
     * a standalone event. The synthetic master (CANCELLED, zero duration, no rrule) gets no
     * regenerateOccurrences call, and the exception shows through its linkException occurrence.
     */
    @Test
    fun `orphaned RECURRENCE-ID is linked to synthetic master`() = runTest {
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

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Synthesis adds synthetic master + linked exception",
            2,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals("Should insert exactly 2 events (synthetic + exception)", 2, insertedEvents.size)

        val synthetic = insertedEvents.single { it.originalInstanceTime == null }
        val exception = insertedEvents.single { it.originalInstanceTime != null }

        assertEquals("UID preserved on synthetic", "orphan@test", synthetic.uid)
        assertEquals(
            "Synthetic importId is the bare UID (no :RECID: marker)",
            "orphan@test",
            synthetic.importId
        )
        assertNull("Synthetic has no rrule", synthetic.rrule)
        assertEquals("Synthetic status is CANCELLED", "CANCELLED", synthetic.status)
        assertEquals(
            "Synthetic is zero-duration (startTs == endTs)",
            synthetic.startTs,
            synthetic.endTs
        )
        assertEquals(
            "Synthetic dtstart is the earliest RECURRENCE-ID",
            1736157600000L, // 2025-01-06T10:00Z
            synthetic.startTs
        )
        assertEquals(
            "Synthetic carries the X-KASHCAL-SYNTHETIC-MASTER sentinel",
            "true",
            synthetic.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY)
        )

        assertEquals("Exception shares UID", "orphan@test", exception.uid)
        assertEquals(
            "Exception's originalEventId points to the synthetic's row id",
            synthetic.id,
            exception.originalEventId
        )
        assertNotNull(
            "Exception's originalInstanceTime preserved (RECURRENCE-ID epoch)",
            exception.originalInstanceTime
        )

        // No regenerateOccurrences for the synthetic; linkException for the exception.
        coVerify(exactly = 0) { occurrenceGenerator.regenerateOccurrences(any()) }
        coVerify(exactly = 1) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    /**
     * Updates the synthetic master in place when the real master arrives (#227).
     *
     * Sync N inserts a synthetic master for an exception-only UID. In sync N+1 the real master is
     * in the feed with the same importId (the UID), so the upsert updates the synthetic's row:
     * same row id, rrule set, status CONFIRMED, sentinel gone, and the exception's
     * `originalEventId` still points at it.
     *
     * The stable row id is what this pins: a synthetic master whose importId isn't its UID would
     * give the real master a new row and leave the exception's FK dangling.
     */
    @Test
    fun `self-heal - real master arriving after synthesis upserts the synthetic in place`() = runTest {
        val orphanOnlyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:later-master@test
            DTSTAMP:20250101T000000Z
            RECURRENCE-ID:20250113T100000Z
            SUMMARY:Exception Visible Today
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
            SUMMARY:Exception Visible Today
            DTSTART:20250113T140000Z
            DTEND:20250113T150000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription

        // Sync N: the feed has only the exception.
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanOnlyIcs,
            etag = null,
            lastModified = null
        )
        // Returns the rows inserted so far, so sync N+1 sees what sync N inserted.
        coEvery {
            eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any())
        } answers {
            insertedEvents.toList()
        }

        val firstResult = repository.refreshSubscription(1L)
        assertTrue(firstResult is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Sync N: 1 synthetic master + 1 linked exception",
            2,
            (firstResult as IcsSubscriptionRepository.SyncResult.Success).count.added
        )

        val priorSynthetic = insertedEvents.single { it.originalInstanceTime == null }
        val priorException = insertedEvents.single { it.originalInstanceTime != null }
        val priorSyntheticId = priorSynthetic.id
        assertEquals(
            "Synthetic importId == uid",
            "later-master@test",
            priorSynthetic.importId
        )
        assertEquals(
            "Pre-self-heal exception linked to synthetic",
            priorSyntheticId,
            priorException.originalEventId
        )

        // Sync N+1: the feed has the real master and the same exception.
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanPlusMasterIcs,
            etag = null,
            lastModified = null
        )

        val secondResult = repository.refreshSubscription(1L)

        assertTrue(secondResult is IcsSubscriptionRepository.SyncResult.Success)
        val syncCount = (secondResult as IcsSubscriptionRepository.SyncResult.Success).count
        // Nothing deleted: both rows are updated in place.
        assertEquals("No deletes — self-heal upserts in place", 0, syncCount.deleted)
        assertEquals("No new rows — both pre-existing rows updated", 0, syncCount.added)
        assertEquals("Both rows updated", 2, syncCount.updated)

        // The master passed to update() (rrule != null) has the synthetic's row id.
        val updatedMaster = updatedEvents.single { it.rrule != null }
        assertEquals(
            "Self-heal must upsert the synthetic in place — row id stable",
            priorSyntheticId,
            updatedMaster.id
        )
        assertEquals(
            "Self-heal flips status to CONFIRMED",
            "CONFIRMED",
            updatedMaster.status
        )
        assertNull(
            "Self-heal clears the SYNTHETIC sentinel from extraProperties",
            updatedMaster.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY)
        )

        val updatedException = updatedEvents.single { it.originalInstanceTime != null }
        assertEquals(
            "Exception's originalEventId still references the (now-real) master",
            priorSyntheticId,
            updatedException.originalEventId
        )

        // linkException runs again for the exception with the same master id.
        coVerify {
            occurrenceGenerator.linkException(
                masterEventId = priorSyntheticId,
                occurrenceTimeMs = priorException.originalInstanceTime!!,
                exceptionEvent = any()
            )
        }
    }

    /**
     * Keeps the synthetic master and its exceptions across re-syncs of an exception-only feed
     * (#227).
     *
     * The sweep deletes every stored row whose importId isn't in `newImportIds`. A synthetic
     * master's importId is its UID, which an exception-only feed doesn't contain, so unless
     * synthesis adds it to `newImportIds` the synthetic is deleted on every refresh and the FK
     * cascade deletes its exceptions. PASS 2 would then update the deleted rows by their old ids,
     * a no-op. Sync N+1 must report deleted=0 and added=0, with every row id unchanged.
     */
    @Test
    fun `re-sync of orphan-only feed preserves synthetic and exceptions across syncs`() = runTest {
        val orphanOnlyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:idempotent-orphan@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260106T100000Z
            SUMMARY:First Orphan
            DTSTART:20260106T100000Z
            DTEND:20260106T110000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:idempotent-orphan@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260113T100000Z
            SUMMARY:Second Orphan
            DTSTART:20260113T100000Z
            DTEND:20260113T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanOnlyIcs, etag = null, lastModified = null
        )
        // Returns the rows inserted so far, so sync N+1 sees what sync N inserted.
        coEvery {
            eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any())
        } answers {
            insertedEvents.toList()
        }

        // Sync N: synthesize + link.
        val firstResult = repository.refreshSubscription(1L)
        assertTrue(firstResult is IcsSubscriptionRepository.SyncResult.Success)
        val firstCount = (firstResult as IcsSubscriptionRepository.SyncResult.Success).count
        assertEquals("Sync N: 1 synthetic + 2 linked exceptions", 3, firstCount.added)

        val syntheticIdN = insertedEvents.single { it.originalInstanceTime == null }.id
        val exceptionIdsN = insertedEvents
            .filter { it.originalInstanceTime != null }
            .map { it.id }
            .toSet()

        // Sync N+1: the same feed.
        val secondResult = repository.refreshSubscription(1L)
        assertTrue(secondResult is IcsSubscriptionRepository.SyncResult.Success)
        val secondCount = (secondResult as IcsSubscriptionRepository.SyncResult.Success).count

        // The sweep must not delete the synthetic or its exceptions.
        assertEquals("No deletes on idempotent re-sync", 0, secondCount.deleted)
        assertEquals("No new rows on idempotent re-sync", 0, secondCount.added)

        // No cascade delete and re-insert: the exception row ids are unchanged.
        val exceptionIdsAfter = insertedEvents
            .filter { it.originalInstanceTime != null }
            .map { it.id }
            .toSet()
        assertEquals(
            "Exception row ids stable across syncs",
            exceptionIdsN,
            exceptionIdsAfter
        )

        val syntheticIdAfter = insertedEvents.single { it.originalInstanceTime == null }.id
        assertEquals("Synthetic row id stable across syncs", syntheticIdN, syntheticIdAfter)
    }

    /**
     * Gives each exception an originalEventId and originalInstanceTime and calls linkException
     * once per exception. Argument values aren't matched here.
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

        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals(2, exceptions.size)

        exceptions.forEach { exception ->
            assertNotNull("Exception should have originalEventId", exception.originalEventId)
            assertNotNull("Exception should have originalInstanceTime", exception.originalInstanceTime)
        }

        coVerify(exactly = 2) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    // ==================== Issue #227: Duplicate UID disambiguation ====================

    /** Aliases the production key, so a rename breaks compilation here instead of drifting. */
    private val originalUidExtraKey = ORIGINAL_UID_EXTRA_KEY

    /**
     * Stores two masters sharing a UID under distinct renamed UIDs (#227).
     *
     * Google's private ICS export sometimes emits two non-exception VEVENTs with one UID, though
     * RFC 5545 §3.8.4.7 says a UID MUST be globally unique. The sync renames the uid of every
     * event in the group so trigger_master_event_unique_insert doesn't fire.
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
     * Re-syncing a duplicate-UID feed gives `updated=2, added=0` (#227): the disambiguator is
     * the event's startTs, the same every sync, so the stored rows match by importId.
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
        // Returns the rows inserted so far: none on the first sync, the first sync's rows (with
        // their renamed caldavUrls) on the second.
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

        // Renamed UIDs derive from startTs, so they match across syncs.
        val secondUids = updatedEvents.map { it.uid }.sorted()
        val secondImportIds = updatedEvents.mapNotNull { it.importId }.sorted()
        assertEquals("UIDs stable across re-sync", firstUids, secondUids)
        assertEquals("ImportIds stable across re-sync", firstImportIds, secondImportIds)

        // X-KASHCAL-ORIGINAL-UID is still set after the update.
        updatedEvents.forEach { event ->
            assertEquals(
                "Original UID marker must survive re-sync",
                "xxx@google.com",
                event.extraProperties?.get(originalUidExtraKey)
            )
        }
    }

    /** Leaves a UID with one VEVENT unrenamed and without the original-UID key (#227). */
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
     * Stores only the first of two events sharing UID and DTSTART (#227). Appending startTs gives
     * both the same renamed UID, so the second insert trips the trigger and the master loop's
     * catch skips it. The sync still returns Success with count.added == 1.
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
        // Simulates the master-uniqueness trigger on the second insert; the master loop catches
        // any Exception. `IcsSubscriptionRepositoryDuplicateUidIntegrationTest` runs the real
        // trigger.
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
     * Syncs the feed from the #227 report: an exception with no master (`abc@google.com`) and a
     * duplicate UID (`xxx@google.com`).
     *
     * - 4 rows are inserted: a synthetic master for `abc@google.com`, its linked exception (the
     *   only `abc` event in the feed), and 2 renamed `xxx@google.com#dup=` masters.
     * - 3 events show, matching the report's "Found 3 events": the synthetic gets no
     *   regenerateOccurrences call, which is what the last assertion checks.
     *
     * The third VEVENT repeats DTSTART, DTEND and DTSTAMP, which RFC 5545 §3.6.1 forbids; ical4j
     * parses it anyway.
     */
    @Test
    fun `issue 227 - Google ICS feed with orphaned exception and duplicate UID inserts 4 rows and renders 3`() = runTest {
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
            "All 4 rows inserted: synthetic + 1 linked exception + 2 disambiguated masters",
            4,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals(4, insertedEvents.size)

        val abcRows = insertedEvents.filter { it.uid == "abc@google.com" }
        assertEquals(
            "abc@google.com produces 1 synthetic + 1 linked exception",
            2,
            abcRows.size
        )
        val abcSynthetic = abcRows.single { it.originalInstanceTime == null }
        val abcException = abcRows.single { it.originalInstanceTime != null }
        assertEquals(
            "Synthetic carries the X-KASHCAL-SYNTHETIC-MASTER sentinel",
            "true",
            abcSynthetic.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY)
        )
        assertEquals("Synthetic status CANCELLED", "CANCELLED", abcSynthetic.status)
        assertEquals(
            "Exception linked to synthetic",
            abcSynthetic.id,
            abcException.originalEventId
        )

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

        // The synthetic produces no occurrences, so 3 events show.
        coVerify(exactly = 0) {
            occurrenceGenerator.regenerateOccurrences(
                match { it.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true" }
            )
        }
    }

    /**
     * Keeps two masters distinct when their shared UID already contains `#dup=` (#227). Each gets
     * `#dup={startTs}` appended to the whole UID (`weird#dup=preexisting@test#dup={startTs}`).
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

    // ==================== Issue #227: Exception-only feeds ====================

    /**
     * Inserts one synthetic master for a UID with several exceptions and no master, and links
     * every exception to it (#227). Google's private export leaves the master out.
     */
    @Test
    fun `multiple orphan exceptions for same UID get one synthetic master with all linked`() = runTest {
        val multiOrphanIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:orphan-uid@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260106T100000Z
            SUMMARY:First Orphan
            DTSTART:20260106T100000Z
            DTEND:20260106T110000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:orphan-uid@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260113T100000Z
            SUMMARY:Second Orphan
            DTSTART:20260113T100000Z
            DTEND:20260113T110000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:orphan-uid@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260120T100000Z
            SUMMARY:Third Orphan
            DTSTART:20260120T100000Z
            DTEND:20260120T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = multiOrphanIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "1 synthetic + 3 linked exceptions",
            4,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )
        assertEquals(4, insertedEvents.size)

        val synthetics = insertedEvents.filter {
            it.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true"
        }
        assertEquals("Exactly one synthetic master per orphan UID", 1, synthetics.size)
        val synthetic = synthetics.single()
        assertEquals("orphan-uid@test", synthetic.uid)

        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals(3, exceptions.size)
        exceptions.forEach { exception ->
            assertEquals(
                "Each exception links to the synthetic",
                synthetic.id,
                exception.originalEventId
            )
            assertNotNull(
                "originalInstanceTime preserved for each exception",
                exception.originalInstanceTime
            )
        }

        assertEquals(
            "Each exception keeps its own RECURRENCE-ID",
            3,
            exceptions.mapNotNull { it.originalInstanceTime }.toSet().size
        )

        // No regenerateOccurrences for the synthetic; linkException once per exception.
        coVerify(exactly = 0) { occurrenceGenerator.regenerateOccurrences(any()) }
        coVerify(exactly = 3) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }

    /**
     * Schedules no reminders for a synthetic master (#227): it is a placeholder with no
     * occurrences, and the sync's synthetic loop never schedules reminders.
     */
    @Test
    fun `synthetic master is not passed to reminder scheduling`() = runTest {
        val orphanIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:no-reminder-orphan@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260106T100000Z
            SUMMARY:Lone Orphan
            DTSTART:20260106T100000Z
            DTEND:20260106T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // Only the synthetic is checked; the exception's reminders aren't asserted.
        coVerify(exactly = 0) {
            reminderScheduler.scheduleRemindersForEvent(
                event = match { it.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true" },
                occurrences = any(),
                calendarColor = any()
            )
        }
    }

    /**
     * Deletes a legacy standalone row before inserting the synthetic master (#227).
     *
     * Builds up to v23.7.45 stored an exception with no master as a standalone event with
     * importId `{uid}:RECID:{datetime}`. `sweepLegacyOrphanStandalones` deletes it so the
     * synthetic master's insert doesn't trip the master-uniqueness trigger on the (uid,
     * calendar_id, original_event_id IS NULL) collision.
     */
    @Test
    fun `legacy promoted-standalone row is swept when synthetic master is synthesized`() = runTest {
        val orphanIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//KashCal//EN
            BEGIN:VEVENT
            UID:legacy-orphan@test
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260106T100000Z
            SUMMARY:Lone Orphan
            DTSTART:20260106T100000Z
            DTEND:20260106T110000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // A standalone row as v23.7.45 stored it.
        val legacyStandalone = Event(
            id = 99L,
            uid = "legacy-orphan@test",
            importId = "legacy-orphan@test:RECID:20260106T100000Z",
            calendarId = testSubscription.calendarId,
            title = "Lone Orphan",
            startTs = 1767693600000L, // 2026-01-06T10:00Z
            endTs = 1767697200000L,
            dtstamp = 0L,
            // Legacy standalone rows have no originalEventId or originalInstanceTime.
            originalEventId = null,
            originalInstanceTime = null,
            caldavUrl = "ics_subscription:1:legacy-orphan@test:RECID:20260106T100000Z",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = orphanIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(
            legacyStandalone
        )

        val result = repository.refreshSubscription(1L)
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val syncCount = (result as IcsSubscriptionRepository.SyncResult.Success).count

        assertEquals("Legacy standalone deleted", 1, syncCount.deleted)
        assertEquals("Synthetic + linked exception inserted", 2, syncCount.added)

        // Its reminders are cancelled and the row deleted.
        coVerify { reminderScheduler.cancelRemindersForEvent(99L) }
        coVerify { eventsDao.deleteById(99L) }

        val synthetic = insertedEvents.single { it.originalInstanceTime == null }
        val exception = insertedEvents.single { it.originalInstanceTime != null }
        assertEquals(
            "Synthetic master replaces legacy standalone",
            "true",
            synthetic.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY)
        )
        assertEquals(
            "Exception linked to fresh synthetic",
            synthetic.id,
            exception.originalEventId
        )
    }
}
