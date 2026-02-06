package org.onekash.kashcal.data.ics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Real-world ICS feed regression tests.
 *
 * These tests verify that the ICS subscription sync handles real-world ICS feeds
 * correctly, including:
 * - Large feeds (350+ events)
 * - All-day events
 * - Recurring events with exceptions (RECURRENCE-ID)
 * - Various RECURRENCE-ID formats (UTC, TZID)
 * - Different calendar producers (Thunderbird, Outlook, Google)
 *
 * Test fixtures are stored in: app/src/test/resources/ics/
 */
class IcsRealWorldFeedsTest {

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

    // Test subscription
    private val testSubscription = IcsSubscription(
        id = 1L,
        url = "https://example.com/calendar.ics",
        name = "Test Calendar",
        color = 0xFF0000FF.toInt(),
        calendarId = 100L,
        enabled = true,
        syncIntervalHours = 24,
        lastSync = 0L,
        etag = null,
        lastModified = null,
        lastError = null
    )

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
            assignedId
        }
    }

    /**
     * Load ICS content from test resources.
     */
    private fun loadResource(path: String): String {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }

    // ==================== Thunderbird US Holidays Tests ====================

    /**
     * Regression test: Thunderbird US Holidays (large feed, 350+ events, all-day).
     *
     * This verifies:
     * - Large feeds sync without error
     * - All-day events are parsed correctly
     * - No UNIQUE constraint violations (importId == uid for non-exceptions)
     */
    @Test
    fun `regression - Thunderbird US Holidays syncs without error`() = runTest {
        val content = loadResource("ics/thunderbird_us_holidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = "\"thunderbird-etag\"",
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        // Should succeed
        assertTrue(
            "Large feed should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // Thunderbird US Holidays has 350+ events
        assertTrue(
            "Should sync many events (>100)",
            success.count.added > 100
        )

        // All events should have unique caldavUrls (importId-based)
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )

        // All events should be all-day (holidays are DATE, not DATE-TIME)
        val allDayCount = insertedEvents.count { it.isAllDay }
        assertTrue(
            "Most events should be all-day",
            allDayCount > 100
        )
    }

    /**
     * Verify that Thunderbird holidays have no recurring event exceptions.
     * This ensures the simple importId == uid path works.
     */
    @Test
    fun `Thunderbird US Holidays - no recurring exceptions`() = runTest {
        val content = loadResource("ics/thunderbird_us_holidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // No events should have originalInstanceTime (no RECURRENCE-ID)
        val exceptionsCount = insertedEvents.count { it.originalInstanceTime != null }
        assertEquals(
            "Thunderbird holidays should have no exceptions",
            0,
            exceptionsCount
        )

        // No events should have originalEventId
        val linkedCount = insertedEvents.count { it.originalEventId != null }
        assertEquals(
            "No events should be linked to master",
            0,
            linkedCount
        )

        // regenerateOccurrences should be called for each event (no linkException)
        coVerify(exactly = insertedEvents.size) {
            occurrenceGenerator.regenerateOccurrences(any())
        }
    }

    // ==================== Outlook Recurring with Exceptions Tests ====================

    /**
     * Regression test: Issue #36 exact reproduction.
     *
     * Outlook ICS with recurring event + exceptions should sync correctly:
     * - Master event with RRULE
     * - Exception events with RECURRENCE-ID (same UID)
     * - CANCELLED exception filtered out
     */
    @Test
    fun `regression - Issue 36 Outlook recurring with exceptions`() = runTest {
        val content = loadResource("ics/outlook_recurring_with_exceptions.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = "\"outlook-etag\"",
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        // Should succeed (not fail with UNIQUE constraint)
        assertTrue(
            "Outlook ICS should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // 1 master + 1 non-cancelled exception = 2 events
        // (CANCELLED exception is filtered by IcsParserService)
        assertEquals(
            "Should add master + 1 exception (cancelled filtered)",
            2,
            success.count.added
        )

        // Verify structure
        val master = insertedEvents.find { it.rrule != null }
        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Master event should exist", master)
        assertNotNull("Exception event should exist", exception)

        // Exception should be linked to master
        assertNotNull(
            "Exception should have originalEventId",
            exception!!.originalEventId
        )

        // Both share same UID
        assertEquals(
            "Master and exception should share UID",
            master!!.uid,
            exception.uid
        )

        // Different importIds (caldavUrls)
        assertTrue(
            "caldavUrls should be different",
            master.caldavUrl != exception.caldavUrl
        )
    }

    /**
     * Verify Outlook exception uses TZID format for RECURRENCE-ID.
     */
    @Test
    fun `Outlook exceptions use TZID format correctly`() = runTest {
        val content = loadResource("ics/outlook_recurring_with_exceptions.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Exception should have originalInstanceTime", exception!!.originalInstanceTime)

        // linkException should be called for the exception
        coVerify(exactly = 1) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }

        // regenerateOccurrences should be called for the master only
        coVerify(exactly = 1) {
            occurrenceGenerator.regenerateOccurrences(any())
        }
    }

    // ==================== Google Calendar Export Tests ====================

    /**
     * Regression test: Google Calendar recurring with exceptions.
     *
     * Google uses different RECURRENCE-ID format and has multiple exceptions.
     */
    @Test
    fun `regression - Google Calendar recurring with exceptions`() = runTest {
        val content = loadResource("ics/google_recurring_with_exceptions.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = "\"google-etag\"",
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "Google ICS should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // 1 master + 2 non-cancelled exceptions = 3 events
        // (1 CANCELLED exception filtered)
        assertEquals(
            "Should add master + 2 exceptions (cancelled filtered)",
            3,
            success.count.added
        )

        // Verify master
        val master = insertedEvents.find { it.rrule != null }
        assertNotNull("Master should exist", master)
        assertEquals("Team Standup", master!!.title)

        // Verify exceptions
        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals("Should have 2 exceptions", 2, exceptions.size)

        // All exceptions should be linked to master
        exceptions.forEach { exception ->
            assertNotNull(
                "Exception should have originalEventId",
                exception.originalEventId
            )
            assertEquals(
                "Exception should share master's UID",
                master.uid,
                exception.uid
            )
        }

        // Each event should have unique caldavUrl
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            3,
            caldavUrls.size
        )
    }

    /**
     * Verify Google exceptions preserve their modified properties.
     */
    @Test
    fun `Google exceptions preserve modified properties`() = runTest {
        val content = loadResource("ics/google_recurring_with_exceptions.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }

        // Find the rescheduled one
        val rescheduled = exceptions.find { it.title.contains("Rescheduled") }
        assertNotNull("Should have rescheduled exception", rescheduled)
        assertEquals("Conference Room B", rescheduled!!.location)
        assertEquals("Rescheduled due to client meeting", rescheduled.description)

        // Find the extended one
        val extended = exceptions.find { it.title.contains("Extended") }
        assertNotNull("Should have extended exception", extended)
        assertEquals("Large Conference Room", extended!!.location)
    }

    // ==================== Existing Holiday Files Tests ====================

    /**
     * Regression test: Brazil Holidays (existing fixture).
     */
    @Test
    fun `regression - Brazil Holidays syncs without error`() = runTest {
        val content = loadResource("ics/BrazilHolidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "Brazil Holidays should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue("Should sync events", success.count.added > 0)

        // All caldavUrls should be unique
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )
    }

    /**
     * Regression test: German Holidays (existing fixture).
     */
    @Test
    fun `regression - German Holidays syncs without error`() = runTest {
        val content = loadResource("ics/GermanHolidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "German Holidays should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue("Should sync events", success.count.added > 0)

        // All caldavUrls should be unique
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )
    }

    /**
     * Regression test: Japan Holidays (existing fixture).
     */
    @Test
    fun `regression - Japan Holidays syncs without error`() = runTest {
        val content = loadResource("ics/JapanHolidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "Japan Holidays should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue("Should sync events", success.count.added > 0)

        // All caldavUrls should be unique
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )
    }

    // ==================== Occurrence Generation Tests ====================

    /**
     * Verify correct occurrence method is called based on event type.
     *
     * - Master events: regenerateOccurrences()
     * - Exception events: linkException()
     */
    @Test
    fun `occurrence methods called correctly for mixed feed`() = runTest {
        val content = loadResource("ics/google_recurring_with_exceptions.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        repository.refreshSubscription(1L)

        // 1 master -> regenerateOccurrences
        coVerify(exactly = 1) {
            occurrenceGenerator.regenerateOccurrences(any())
        }

        // 2 exceptions -> linkException
        coVerify(exactly = 2) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }
}
