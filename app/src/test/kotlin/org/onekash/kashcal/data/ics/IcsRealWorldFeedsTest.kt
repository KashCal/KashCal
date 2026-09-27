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
 * Runs [IcsSubscriptionRepository.refreshSubscription] over real ICS feeds, with mocked DAOs that
 * record each inserted event.
 *
 * Covers a 700-event all-day feed, recurring series with exceptions (RECURRENCE-ID with a
 * Windows zone name, an IANA TZID or UTC), CANCELLED exceptions, orphaned exceptions and
 * duplicate UIDs (#227), the iCloud holiday feed (#219), other holiday fixtures, non-ASCII titles
 * and the choice between `regenerateOccurrences` and `linkException`.
 *
 * Fixtures live in `app/src/test/resources/ics/`.
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

        // runInTransaction runs the block directly
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

        // Records each inserted event with the sequential id insert returns
        var nextInsertId = 1000L
        coEvery { eventsDao.insert(any()) } answers {
            val event = firstArg<Event>()
            val assignedId = nextInsertId++
            insertedEvents.add(event.copy(id = assignedId))
            assignedId
        }
    }

    /** Reads a test resource; throws if it is missing. */
    private fun loadResource(path: String): String {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }

    // ==================== US Holidays Feed ====================

    /**
     * Syncs the 700-event all-day US holidays feed: the result is Success with more than 100
     * added, every caldavUrl (built from the importId) is unique, and more than 100 events are
     * all-day.
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

        assertTrue(
            "Large feed should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // The fixture has 700 VEVENTs
        assertTrue(
            "Should sync many events (>100)",
            success.count.added > 100
        )

        // Each caldavUrl, built from the importId, is unique
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            insertedEvents.size,
            caldavUrls.size
        )

        // Every VEVENT in the fixture has a DATE DTSTART; the assert only needs more than 100
        val allDayCount = insertedEvents.count { it.isAllDay }
        assertTrue(
            "Most events should be all-day",
            allDayCount > 100
        )
    }

    /**
     * Checks a feed without RECURRENCE-ID: no event is an exception or linked to a master, and
     * each gets `regenerateOccurrences`.
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

        // No event has an originalInstanceTime
        val exceptionsCount = insertedEvents.count { it.originalInstanceTime != null }
        assertEquals(
            "Thunderbird holidays should have no exceptions",
            0,
            exceptionsCount
        )

        // No event has an originalEventId
        val linkedCount = insertedEvents.count { it.originalEventId != null }
        assertEquals(
            "No events should be linked to master",
            0,
            linkedCount
        )

        // regenerateOccurrences runs once per event
        coVerify(exactly = insertedEvents.size) {
            occurrenceGenerator.regenerateOccurrences(any())
        }
    }

    // ==================== Recurring Series with Windows-Zone Exceptions ====================

    /**
     * Reproduces #36: a master with RRULE and two exceptions sharing its UID, RECURRENCE-ID with a
     * Windows zone name (`TZID=India Standard Time`). The CANCELLED exception is dropped; the
     * other is linked to the master and gets its own caldavUrl.
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

        // Succeeds instead of failing on a UNIQUE constraint
        assertTrue(
            "Outlook ICS should sync successfully",
            result is IcsSubscriptionRepository.SyncResult.Success
        )

        val success = result as IcsSubscriptionRepository.SyncResult.Success

        // 1 master + 1 exception; IcsParserService drops the CANCELLED one
        assertEquals(
            "Should add master + 1 exception (cancelled filtered)",
            2,
            success.count.added
        )

        val master = insertedEvents.find { it.rrule != null }
        val exception = insertedEvents.find { it.originalInstanceTime != null }

        assertNotNull("Master event should exist", master)
        assertNotNull("Exception event should exist", exception)

        // The exception is linked to the master
        assertNotNull(
            "Exception should have originalEventId",
            exception!!.originalEventId
        )

        // Both share the UID
        assertEquals(
            "Master and exception should share UID",
            master!!.uid,
            exception.uid
        )

        // Different importIds, so different caldavUrls
        assertTrue(
            "caldavUrls should be different",
            master.caldavUrl != exception.caldavUrl
        )
    }

    /**
     * Checks the exception with a Windows-zone RECURRENCE-ID gets an originalInstanceTime, one
     * `linkException` call, and `regenerateOccurrences` runs for the master only.
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

        coVerify(exactly = 1) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }

        coVerify(exactly = 1) {
            occurrenceGenerator.regenerateOccurrences(any())
        }
    }

    // ==================== Recurring Series with IANA-Zone Exceptions ====================

    /**
     * Syncs a master with three exceptions whose RECURRENCE-ID has an IANA TZID
     * (`America/New_York`), one of them CANCELLED.
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

        // 1 master + 2 exceptions; the CANCELLED one is dropped
        assertEquals(
            "Should add master + 2 exceptions (cancelled filtered)",
            3,
            success.count.added
        )

        val master = insertedEvents.find { it.rrule != null }
        assertNotNull("Master should exist", master)
        assertEquals("Team Standup", master!!.title)

        val exceptions = insertedEvents.filter { it.originalInstanceTime != null }
        assertEquals("Should have 2 exceptions", 2, exceptions.size)

        // Every exception is linked to the master and shares its UID
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

        // Each event has its own caldavUrl
        val caldavUrls = insertedEvents.map { it.caldavUrl }.toSet()
        assertEquals(
            "Each event should have unique caldavUrl",
            3,
            caldavUrls.size
        )
    }

    /** Checks exceptions keep their own title, location and description. */
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

        val rescheduled = exceptions.find { it.title.contains("Rescheduled") }
        assertNotNull("Should have rescheduled exception", rescheduled)
        assertEquals("Conference Room B", rescheduled!!.location)
        assertEquals("Rescheduled due to client meeting", rescheduled.description)

        val extended = exceptions.find { it.title.contains("Extended") }
        assertNotNull("Should have extended exception", extended)
        assertEquals("Large Conference Room", extended!!.location)
    }

    // ==================== Holiday Fixtures ====================

    /** Syncs the Brazil holidays fixture: at least one event added, every caldavUrl unique. */
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

    /** Syncs the German holidays fixture: at least one event added, every caldavUrl unique. */
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

    /** Syncs the Japan holidays fixture: at least one event added, every caldavUrl unique. */
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

    // ==================== Issue #219: iCloud Holiday Feed ====================

    /**
     * Pins #219: subscribing to https://calendars.icloud.com/holidays/us_en.ics must import events,
     * not zero.
     *
     * The fixture is the iCloud-served feed (PRODID:icalendar-ruby): 120 all-day events, 28 of
     * them with yearly RRULEs.
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

    // ==================== Issue #227: Orphaned Exception and Duplicate UID ====================

    /**
     * Reproduces #227: a private calendar export with an orphaned RECURRENCE-ID (its master is
     * outside the export window) and two non-exception VEVENTs sharing a UID.
     *
     * The sync inserts 4 rows: a synthetic master and its linked exception for abc@google.com, and
     * the two xxx@google.com masters renamed `xxx@google.com#dup=*`. The synthetic master gets no
     * occurrences, so 3 events show; the test asserts the rows, not the rendering.
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

        // The orphaned RECURRENCE-ID is linked to a synthetic master
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

        // Masters sharing a UID are renamed apart by startTs
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

    // ==================== Locale and Script Coverage: Non-ASCII Holiday Feeds ====================

    /**
     * Syncs a feed of 66 all-day events with Chinese SUMMARY and DESCRIPTION: at least one event is
     * added and at least one title keeps non-ASCII characters.
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

        val hasNonAscii = insertedEvents.any { ev ->
            ev.title.any { it.code > 127 }
        }
        assertTrue("Chinese characters survive import", hasNonAscii)
    }

    /**
     * Syncs a feed of 139 all-day events with accented French titles (é, ê, â, ë, É): at least one
     * event is added and at least one title keeps a character in À..ÿ.
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
     * Checks the occurrence call per row type: `regenerateOccurrences` once for the master,
     * `linkException` once per exception.
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
     * Syncs the #227 reporter's full sanitized feed: 120 VEVENTs over 20 UIDs. Three UIDs have a
     * master VEVENT (uid-000012, uid-000016, uid-000018); the other 17 have only exceptions, their
     * master outside the private export's window.
     *
     * The sync inserts 137 rows: 17 synthetic masters, 117 linked exceptions and 3 real masters.
     * Each synthetic master is CANCELLED, zero-duration and has no RRULE, and gets no occurrences,
     * so the 120 feed events show (not asserted here). Every exception has an originalEventId
     * (which master isn't asserted).
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

        // Only the 3 real masters get regenerateOccurrences; synthetics get none
        coVerify(exactly = 3) { occurrenceGenerator.regenerateOccurrences(any()) }
        // Each of the 117 exceptions gets linkException
        coVerify(exactly = 117) {
            occurrenceGenerator.linkException(any(), any(), any<Event>())
        }
    }
}
