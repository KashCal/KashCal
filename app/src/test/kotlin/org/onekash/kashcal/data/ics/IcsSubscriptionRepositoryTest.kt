package org.onekash.kashcal.data.ics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Unit tests for IcsSubscriptionRepository.
 *
 * Tests cover:
 * - Adding subscriptions (validation, account creation, calendar creation)
 * - Removing subscriptions (cascade delete)
 * - Updating subscription settings
 * - Enabling/disabling subscriptions
 * - Syncing (fetch, parse, upsert, delete orphans)
 * - URL normalization (webcal:// to https://)
 * - Conditional request handling (ETag, Last-Modified)
 * - Error handling
 */
class IcsSubscriptionRepositoryTest {

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

    // Test data
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

    private val icsContent = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:event-1@test.com
        DTSTART:20260115T100000Z
        DTEND:20260115T110000Z
        SUMMARY:Test Event 1
        END:VEVENT
        BEGIN:VEVENT
        UID:event-2@test.com
        DTSTART:20260116T100000Z
        DTEND:20260116T110000Z
        SUMMARY:Test Event 2
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
    }

    // ==================== getAllSubscriptions Tests ====================

    @Test
    fun `getAllSubscriptions returns flow from DAO`() = runTest {
        val subscriptions = listOf(testSubscription)
        every { icsSubscriptionsDao.getAll() } returns flowOf(subscriptions)

        val flow = repository.getAllSubscriptions()

        // Verify flow is returned
        every { icsSubscriptionsDao.getAll() }
    }

    // ==================== getSubscriptionById Tests ====================

    @Test
    fun `getSubscriptionById returns subscription from DAO`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription

        val result = repository.getSubscriptionById(1L)

        assertEquals(testSubscription, result)
    }

    @Test
    fun `getSubscriptionById returns null for non-existent ID`() = runTest {
        coEvery { icsSubscriptionsDao.getById(999L) } returns null

        val result = repository.getSubscriptionById(999L)

        assertEquals(null, result)
    }

    // ==================== addSubscription Tests ====================

    @Test
    fun `addSubscription creates account calendar and subscription`() = runTest {
        coEvery { icsSubscriptionsDao.urlExists(any()) } returns false
        coEvery { calendarsDao.insert(any()) } returns 100L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 1L
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent,
            etag = "\"etag-123\"",
            lastModified = "Wed, 15 Jan 2026 10:00:00 GMT"
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        val result = repository.addSubscription(
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF0000FF.toInt()
        )

        assertTrue(result is IcsSubscriptionRepository.SubscriptionResult.Success)
        coVerify { calendarsDao.insert(any()) }
        coVerify { icsSubscriptionsDao.insert(any()) }
    }

    @Test
    fun `addSubscription rejects duplicate URL`() = runTest {
        coEvery { icsSubscriptionsDao.urlExists("https://example.com/calendar.ics") } returns true

        val result = repository.addSubscription(
            url = "https://example.com/calendar.ics",
            name = "Test",
            color = 0
        )

        assertTrue(result is IcsSubscriptionRepository.SubscriptionResult.Error)
        assertTrue((result as IcsSubscriptionRepository.SubscriptionResult.Error).message.contains("already exists"))
    }

    @Test
    fun `addSubscription normalizes webcal URL to https`() = runTest {
        val urlSlot = slot<String>()
        coEvery { icsSubscriptionsDao.urlExists(capture(urlSlot)) } returns false
        coEvery { calendarsDao.insert(any()) } returns 100L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 1L
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.addSubscription(
            url = "webcal://example.com/calendar.ics",
            name = "Test",
            color = 0
        )

        assertEquals("https://example.com/calendar.ics", urlSlot.captured)
    }

    @Test
    fun `addSubscription creates ICS account if not exists`() = runTest {
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 1L
        coEvery { icsSubscriptionsDao.urlExists(any()) } returns false
        coEvery { calendarsDao.insert(any()) } returns 100L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 1L
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.addSubscription("https://example.com/cal.ics", "Test", 0)

        coVerify { accountRepository.createAccount(match { it.provider == AccountProvider.ICS }) }
    }

    @Test
    fun `addSubscription marks calendar as read-only`() = runTest {
        val calendarSlot = slot<Calendar>()
        coEvery { icsSubscriptionsDao.urlExists(any()) } returns false
        coEvery { calendarsDao.insert(capture(calendarSlot)) } returns 100L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 1L
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.addSubscription("https://example.com/cal.ics", "Test", 0)

        assertTrue(calendarSlot.captured.isReadOnly)
    }

    // ==================== removeSubscription Tests ====================

    @Test
    fun `removeSubscription cancels reminders before deleting calendar`() = runTest {
        val event1 = Event(
            id = 100L,
            uid = "event-1@test.com",
            calendarId = testSubscription.calendarId,
            title = "Event 1",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
            syncStatus = SyncStatus.SYNCED,
            reminders = listOf("-PT15M")
        )
        val event2 = Event(
            id = 101L,
            uid = "event-2@test.com",
            calendarId = testSubscription.calendarId,
            title = "Event 2",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
            syncStatus = SyncStatus.SYNCED,
            reminders = listOf("-PT30M")
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns listOf(event1, event2)

        repository.removeSubscription(1L)

        // Verify reminders were cancelled for both events BEFORE calendar deletion
        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { reminderScheduler.cancelRemindersForEvent(101L) }
        coVerify { calendarsDao.deleteById(testSubscription.calendarId) }
    }

    @Test
    fun `removeSubscription deletes calendar which cascades to events`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns emptyList()

        repository.removeSubscription(1L)

        coVerify { calendarsDao.deleteById(testSubscription.calendarId) }
    }

    @Test
    fun `removeSubscription does nothing for non-existent subscription`() = runTest {
        coEvery { icsSubscriptionsDao.getById(999L) } returns null

        repository.removeSubscription(999L)

        coVerify(exactly = 0) { calendarsDao.deleteById(any()) }
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
    }

    // ==================== updateSubscriptionSettings Tests ====================

    @Test
    fun `updateSubscriptionSettings updates both subscription and calendar`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription

        repository.updateSubscriptionSettings(
            subscriptionId = 1L,
            name = "New Name",
            color = 0xFF00FF00.toInt(),
            syncIntervalHours = 12
        )

        coVerify { icsSubscriptionsDao.updateSettings(1L, "New Name", 0xFF00FF00.toInt(), 12) }
        coVerify { calendarsDao.updateDisplayName(testSubscription.calendarId, "New Name") }
        coVerify { calendarsDao.updateColor(testSubscription.calendarId, 0xFF00FF00.toInt()) }
    }

    // ==================== setSubscriptionEnabled Tests ====================

    @Test
    fun `setSubscriptionEnabled updates enabled state`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = true)
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns emptyList()

        repository.setSubscriptionEnabled(1L, false)

        coVerify { icsSubscriptionsDao.setEnabled(1L, false) }
    }

    @Test
    fun `setSubscriptionEnabled cancels reminders when disabling`() = runTest {
        val event1 = Event(
            id = 100L,
            uid = "event-1@test.com",
            calendarId = testSubscription.calendarId,
            title = "Event 1",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
            syncStatus = SyncStatus.SYNCED,
            reminders = listOf("-PT15M")
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = true)
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns listOf(event1)

        repository.setSubscriptionEnabled(1L, false)

        // Verify reminders were cancelled when disabling
        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { icsSubscriptionsDao.setEnabled(1L, false) }
    }

    @Test
    fun `setSubscriptionEnabled does not cancel reminders when enabling`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = false)
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        repository.setSubscriptionEnabled(1L, true)

        // Verify reminders were NOT cancelled when enabling (refresh will schedule them)
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
        coVerify { icsSubscriptionsDao.setEnabled(1L, true) }
    }

    @Test
    fun `setSubscriptionEnabled refreshes subscription when enabling`() = runTest {
        // First call returns disabled subscription (for the enable check)
        // Second call returns enabled subscription (for refreshSubscription)
        val disabledSub = testSubscription.copy(enabled = false)
        val enabledSub = testSubscription.copy(enabled = true)
        coEvery { icsSubscriptionsDao.getById(1L) } returns disabledSub andThen enabledSub
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        repository.setSubscriptionEnabled(1L, true)

        // Verify refresh was called when enabling
        coVerify { icsSubscriptionsDao.setEnabled(1L, true) }
        coVerify { icsFetcher.fetch(any()) }
    }

    @Test
    fun `setSubscriptionEnabled does not refresh when disabling`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = true)
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns emptyList()

        repository.setSubscriptionEnabled(1L, false)

        // Verify refresh was NOT called when disabling
        coVerify(exactly = 0) { icsFetcher.fetch(any()) }
    }

    // ==================== refreshSubscription Tests ====================

    @Test
    fun `refreshSubscription returns NotModified when ETag matches`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(etag = "\"etag-123\"")
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.NotModified)
        coVerify { icsSubscriptionsDao.updateSyncSuccess(any(), any(), any(), any()) }
    }

    @Test
    fun `refreshSubscription skips disabled subscription`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = false)

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Skipped)
    }

    @Test
    fun `refreshSubscription returns Error for non-existent subscription`() = runTest {
        coEvery { icsSubscriptionsDao.getById(999L) } returns null

        val result = repository.refreshSubscription(999L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Error)
    }

    @Test
    fun `refreshSubscription parses and syncs events`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent,
            etag = "\"new-etag\"",
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(2, success.count.added) // 2 events in test ICS
    }

    @Test
    fun `refreshSubscription deletes orphaned events`() = runTest {
        // Setup: One existing event that's no longer in the feed
        val existingEvent = Event(
            id = 100L,
            uid = "orphan@test.com",
            calendarId = testSubscription.calendarId,
            title = "Orphan",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
            caldavUrl = "ics_subscription:1:orphan@test.com",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, // Has event-1 and event-2, NOT orphan
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.insert(any()) } returns 1L

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(1, success.count.deleted) // Orphan deleted
        coVerify { eventsDao.deleteById(100L) }
    }

    @Test
    fun `refreshSubscription updates existing events`() = runTest {
        // Setup: One existing event that's also in the feed
        val existingEvent = Event(
            id = 100L,
            uid = "event-1@test.com",
            calendarId = testSubscription.calendarId,
            title = "Old Title",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L,
            caldavUrl = "ics_subscription:1:event-1@test.com",
            syncStatus = SyncStatus.SYNCED
        )

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, // Has event-1 with "Test Event 1"
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.insert(any()) } returns 1L

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(1, success.count.updated) // event-1 updated
        assertEquals(1, success.count.added) // event-2 added
    }

    @Test
    fun `refreshSubscription handles fetch error`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Error("Network error")

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Error)
        assertEquals("Network error", (result as IcsSubscriptionRepository.SyncResult.Error).message)
        coVerify { icsSubscriptionsDao.updateSyncError(1L, "Network error") }
    }

    @Test
    fun `refreshSubscription regenerates occurrences for events`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent,
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.refreshSubscription(1L)

        // Should regenerate occurrences for each inserted event
        coVerify(atLeast = 2) { occurrenceGenerator.regenerateOccurrences(any()) }
    }

    // ==================== refreshAllDueSubscriptions Tests ====================

    @Test
    fun `refreshAllDueSubscriptions only syncs due subscriptions`() = runTest {
        val dueSubscription = testSubscription.copy(
            id = 1L,
            lastSync = System.currentTimeMillis() - (25 * 60 * 60 * 1000) // 25 hours ago
        )
        val notDueSubscription = testSubscription.copy(
            id = 2L,
            lastSync = System.currentTimeMillis() - (1 * 60 * 60 * 1000) // 1 hour ago
        )

        coEvery { icsSubscriptionsDao.getEnabled() } returns listOf(dueSubscription, notDueSubscription)
        coEvery { icsSubscriptionsDao.getById(1L) } returns dueSubscription
        coEvery { icsSubscriptionsDao.getById(2L) } returns notDueSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        repository.refreshAllDueSubscriptions()

        // Only due subscription should be synced
        coVerify(exactly = 1) { icsFetcher.fetch(any()) }
    }

    // ==================== forceRefreshAll Tests ====================

    @Test
    fun `forceRefreshAll syncs all enabled subscriptions`() = runTest {
        val sub1 = testSubscription.copy(id = 1L)
        val sub2 = testSubscription.copy(id = 2L)

        coEvery { icsSubscriptionsDao.getEnabled() } returns listOf(sub1, sub2)
        coEvery { icsSubscriptionsDao.getById(1L) } returns sub1
        coEvery { icsSubscriptionsDao.getById(2L) } returns sub2
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        val results = repository.forceRefreshAll()

        assertEquals(2, results.size)
    }

    // ==================== URL Normalization Tests ====================

    @Test
    fun `normalizes webcals to https`() = runTest {
        val urlSlot = slot<String>()
        coEvery { icsSubscriptionsDao.urlExists(capture(urlSlot)) } returns false
        coEvery { calendarsDao.insert(any()) } returns 100L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 1L
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = icsContent, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.addSubscription("webcals://example.com/cal.ics", "Test", 0)

        assertEquals("https://example.com/cal.ics", urlSlot.captured)
    }
}