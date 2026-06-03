package org.onekash.kashcal.data.ics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.onekash.kashcal.data.repository.AccountRepository
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
            eventReader = eventReader,
            context = mockk(relaxed = true)
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

    // ==================== Issue #219 reproduction: Apple iCloud Holidays ====================

    /**
     * Issue #219: subscribing to https://calendars.icloud.com/holidays/us_en.ics
     * resulted in zero events on the calendar. Fixed by the parser update;
     * this test pins the post-fix behavior so the regression can't return.
     *
     * Real Apple-served iCloud holiday feed (PRODID:icalendar-ruby), all-day
     * recurring events with yearly RRULEs.
     */
    @Test
    fun `regression - Issue 219 Apple iCloud US holidays subscription imports events`() = runTest {
        val content = loadResource("ics/apple_icloud_us_holidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content, etag = "\"icloud-etag\"", lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "Apple iCloud holidays must import successfully (issue #219)",
            result is IcsSubscriptionRepository.SyncResult.Success
        )
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue(
            "iCloud holidays must contain >50 events (regression guard against silent zero)",
            success.count.added > 50
        )

        val caldavUrls = insertedEvents.mapNotNull { it.caldavUrl }.toSet()
        assertEquals("Each event must have a unique caldavUrl", insertedEvents.size, caldavUrls.size)

        val allDayCount = insertedEvents.count { it.isAllDay }
        assertTrue("Most iCloud holidays are all-day events", allDayCount > 50)
    }

    // ==================== Issue #227 reproduction: Google ICS export quirks ====================

    /**
     * Issue #227: Google's private ICS export emits two adversarial patterns
     * in a single feed — an orphaned RECURRENCE-ID (master sliced out of the
     * export window) and two non-exception VEVENTs sharing a UID. Pre-fix,
     * KashCal imported only 1 of 3 events.
     *
     * Post-fix: 4 rows inserted (synthetic master + 1 linked exception for
     * abc@google.com, plus 2 disambiguated xxx@google.com#dup=* masters);
     * 3 rows visible to user (synthetic has no occurrences).
     */
    @Test
    fun `regression - Issue 227 Google ICS feed inserts 4 rows and renders 3`() = runTest {
        val content = loadResource("ics/issue_227_google_orphan_and_duplicate_uid.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(
            "Synthetic master + linked exception + 2 disambiguated masters",
            4,
            (result as IcsSubscriptionRepository.SyncResult.Success).count.added
        )

        // Bug A: orphaned RECURRENCE-ID linked to synthetic master.
        val abcRows = insertedEvents.filter { it.uid == "abc@google.com" }
        assertEquals("abc@google.com: 1 synthetic + 1 linked exception", 2, abcRows.size)
        val abcSynthetic = abcRows.single { it.originalInstanceTime == null }
        val abcException = abcRows.single { it.originalInstanceTime != null }
        assertEquals(
            "Synthetic carries the X-KASHCAL-SYNTHETIC-MASTER sentinel",
            "true",
            abcSynthetic.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY)
        )
        assertEquals("Synthetic status CANCELLED", "CANCELLED", abcSynthetic.status)
        assertEquals(
            "Exception linked to synthetic master",
            abcSynthetic.id,
            abcException.originalEventId
        )

        // Bug B: duplicate-UID masters disambiguated by startTs.
        val mutated = insertedEvents.filter { it.uid.startsWith("xxx@google.com#dup=") }
        assertEquals("Both xxx@google.com events imported with mutated UIDs", 2, mutated.size)
        assertEquals("Mutated UIDs are distinct", 2, mutated.map { it.uid }.toSet().size)
        mutated.forEach { event ->
            assertEquals(
                "Original UID preserved in extraProperties",
                "xxx@google.com",
                event.extraProperties?.get(ORIGINAL_UID_EXTRA_KEY)
            )
        }
    }

    // ==================== Locale/script coverage: Thunderbird non-ASCII calendars ====================

    /**
     * Thunderbird China holidays — Chinese-script SUMMARY/LOCATION/DESCRIPTION,
     * RRULE-based recurring all-day events. Regression guard for non-ASCII
     * content handling on the import path.
     */
    @Test
    fun `regression - Thunderbird China holidays import non-ASCII content`() = runTest {
        val content = loadResource("ics/thunderbird_chinaholidays.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue("China holidays import multiple events", success.count.added > 0)

        // At least one event has a non-ASCII title (Chinese characters).
        val hasNonAscii = insertedEvents.any { ev ->
            ev.title.any { it.code > 127 }
        }
        assertTrue("Chinese characters survive import", hasNonAscii)
    }

    /**
     * Thunderbird Canadian-French holidays — accented characters (é, à, ç),
     * higher event count. Regression guard for Latin-script extended characters.
     */
    @Test
    fun `regression - Thunderbird Canadian French holidays import accented content`() = runTest {
        val content = loadResource("ics/thunderbird_canadaholidaysfrench.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)
        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertTrue("Canadian French holidays import multiple events", success.count.added > 0)

        val hasAccented = insertedEvents.any { ev ->
            ev.title.any { c -> c in 'À'..'ÿ' }
        }
        assertTrue("Accented Latin characters survive import", hasAccented)
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

    /**
     * Issue #227 reporter's full sanitized feed: 120 VEVENTs spanning
     * 20 distinct UIDs. Three of the UIDs ship a master VEVENT
     * (uid-000012, uid-000016, uid-000018); the other 17 UIDs ship
     * exception VEVENTs only — Google's truncated-private-export pattern
     * where the master is sliced out of the export window.
     *
     * Pre-fix: ~10 of 120 events rendered (one per UID, dropped via
     * the master-uniqueness trigger silently catching second-and-
     * subsequent same-UID orphan-promotion INSERTs).
     *
     * Post-fix: 137 rows inserted — 17 synthetic masters + 117 linked
     * exceptions + 3 real masters. 120 events visible (synthetic
     * masters produce no occurrences). Each orphan exception keeps
     * its originalInstanceTime intact and links to the synthetic for
     * its UID.
     */
    @Test
    fun `regression - Issue 227 reporter's 120-event sanitized feed materializes all events`() = runTest {
        val content = loadResource("ics/issue_227_reporter_full.ics")

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = content, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)
        assertTrue(
            "Sync must succeed on the reporter's full feed",
            result is IcsSubscriptionRepository.SyncResult.Success
        )
        val count = (result as IcsSubscriptionRepository.SyncResult.Success).count
        assertEquals(
            "17 synthetic + 117 exceptions + 3 real masters = 137 rows",
            137,
            count.added
        )
        assertEquals(137, insertedEvents.size)

        val synthetics = insertedEvents.filter {
            it.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true"
        }
        assertEquals(
            "One synthetic per orphan UID (17 UIDs lack a master in the feed)",
            17,
            synthetics.size
        )
        synthetics.forEach { s ->
            assertEquals("Synthetic status CANCELLED", "CANCELLED", s.status)
            assertEquals(
                "Synthetic is zero-duration",
                s.startTs,
                s.endTs
            )
            assertNull("Synthetic has no rrule", s.rrule)
        }

        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals("All 117 RECURRENCE-ID events are linked exceptions", 117, exceptions.size)
        exceptions.forEach { exception ->
            assertNotNull(
                "Each exception has an originalEventId pointing to its master",
                exception.originalEventId
            )
            assertNotNull(
                "Each exception preserves its originalInstanceTime",
                exception.originalInstanceTime
            )
        }

        val realMasters = insertedEvents.filter {
            it.originalInstanceTime == null &&
                it.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) != "true"
        }
        assertEquals("3 real masters in the feed", 3, realMasters.size)

        // No occurrence regeneration for synthetics — only the 3 real
        // masters get regenerateOccurrences called.
        coVerify(exactly = 3) { occurrenceGenerator.regenerateOccurrences(any()) }
        // 117 linked exceptions all hit linkException.
        coVerify(exactly = 117) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }
}
