package org.onekash.kashcal.data.ics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Tests [IcsSubscriptionRepository] over mocked DAOs and fetcher.
 *
 * Covers:
 * - reading subscriptions through the DAO
 * - adding (duplicate URL, ICS account and read-only calendar creation)
 * - removing (reminders cancelled, calendar deleted; the cascade itself isn't exercised)
 * - updating settings, and enabling or disabling
 * - refreshing: ETag and Last-Modified handling, the zero-event checks, fetch errors,
 *   cancellation, upsert and delete of events gone from the feed, occurrence generation
 * - refreshing all enabled or only due subscriptions
 * - webcal:// and webcals:// rewritten to https://
 * - duplicate-UID masters renamed on insert (#227)
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
        // Data-bearing collaborators aren't relaxed, so an unstubbed query throws instead of
        // silently returning null or empty. The defaults below return null, empty, false, 0 or
        // Unit, and a fetch returns an error; per-test stubs override them.
        icsSubscriptionsDao = mockk()
        coEvery { icsSubscriptionsDao.getById(any()) } returns null
        coEvery { icsSubscriptionsDao.getByUrl(any()) } returns null
        coEvery { icsSubscriptionsDao.getByCalendarId(any()) } returns null
        coEvery { icsSubscriptionsDao.getEnabled() } returns emptyList()
        coEvery { icsSubscriptionsDao.urlExists(any()) } returns false
        coEvery { icsSubscriptionsDao.insert(any()) } returns 0L
        coEvery { icsSubscriptionsDao.updateSyncSuccess(any(), any(), any(), any()) } returns Unit
        coEvery { icsSubscriptionsDao.updateSyncError(any(), any()) } returns Unit
        coEvery { icsSubscriptionsDao.setEnabled(any(), any()) } returns Unit
        coEvery { icsSubscriptionsDao.updateSettings(any(), any(), any(), any()) } returns Unit
        accountRepository = mockk()
        coEvery { accountRepository.getAccountByProviderAndEmail(any(), any()) } returns null
        coEvery { accountRepository.createAccount(any()) } returns 0L
        calendarsDao = mockk()
        coEvery { calendarsDao.getById(any()) } returns null
        coEvery { calendarsDao.insert(any()) } returns 0L
        coEvery { calendarsDao.deleteById(any()) } returns Unit
        coEvery { calendarsDao.updateDisplayName(any(), any()) } returns Unit
        coEvery { calendarsDao.updateColor(any(), any()) } returns Unit
        eventsDao = mockk()
        coEvery { eventsDao.insert(any()) } returns 0L
        coEvery { eventsDao.update(any()) } returns Unit
        coEvery { eventsDao.deleteById(any()) } returns Unit
        coEvery { eventsDao.getByCalendarIdInRange(any(), any(), any()) } returns emptyList()
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns emptyList()
        coEvery { eventsDao.getAllMasterEventsForCalendar(any()) } returns emptyList()
        coEvery { eventsDao.anyByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns false
        occurrenceGenerator = mockk(relaxed = true)
        icsFetcher = mockk()
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Error("Unmocked fetch")
        reminderScheduler = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)

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
    }

    // ==================== getAllSubscriptions Tests ====================

    @Test
    fun `getAllSubscriptions returns flow from DAO`() = runTest {
        val subscriptions = listOf(testSubscription)
        every { icsSubscriptionsDao.getAll() } returns flowOf(subscriptions)

        val result = repository.getAllSubscriptions().first()

        assertEquals(subscriptions, result)
        verify { icsSubscriptionsDao.getAll() }
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

        // Both reminders are cancelled and the calendar deleted; the order isn't asserted.
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

        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { icsSubscriptionsDao.setEnabled(1L, false) }
    }

    @Test
    fun `setSubscriptionEnabled does not cancel reminders when enabling`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = false)
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        repository.setSubscriptionEnabled(1L, true)

        // Enabling cancels nothing; the refresh reschedules reminders when the feed returns a
        // body.
        coVerify(exactly = 0) { reminderScheduler.cancelRemindersForEvent(any()) }
        coVerify { icsSubscriptionsDao.setEnabled(1L, true) }
    }

    @Test
    fun `setSubscriptionEnabled refreshes subscription when enabling`() = runTest {
        // Disabled for setSubscriptionEnabled's read, enabled for refreshSubscription's.
        val disabledSub = testSubscription.copy(enabled = false)
        val enabledSub = testSubscription.copy(enabled = true)
        coEvery { icsSubscriptionsDao.getById(1L) } returns disabledSub andThen enabledSub
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        repository.setSubscriptionEnabled(1L, true)

        coVerify { icsSubscriptionsDao.setEnabled(1L, true) }
        coVerify { icsFetcher.fetch(any()) }
    }

    @Test
    fun `setSubscriptionEnabled does not refresh when disabling`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(enabled = true)
        coEvery { eventsDao.getAllMasterEventsForCalendar(testSubscription.calendarId) } returns emptyList()

        repository.setSubscriptionEnabled(1L, false)

        coVerify(exactly = 0) { icsFetcher.fetch(any()) }
    }

    // ==================== refreshSubscription Tests ====================

    /** Stubs whether any event from this subscription is stored. */
    private fun stubLocalEventsExist(present: Boolean) {
        coEvery {
            eventsDao.anyByCalendarIdAndCaldavUrlPrefix(any(), any())
        } returns present
    }

    @Test
    fun `refreshSubscription returns NotModified when ETag matches`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(etag = "\"etag-123\"")
        stubLocalEventsExist(true)
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.NotModified)
        coVerify { icsSubscriptionsDao.updateSyncSuccess(any(), any(), any(), any()) }
    }

    @Test
    fun `refreshSubscription drops conditional headers when local has 0 events but cached ETag`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(
            etag = "\"stale-etag\"",
            lastModified = "Mon, 01 Jan 2024 00:00:00 GMT"
        )
        stubLocalEventsExist(false)
        val captured = slot<IcsSubscription>()
        coEvery { icsFetcher.fetch(capture(captured)) } returns IcsFetcher.FetchResult.Success(
            content = icsContent,
            etag = "\"new-etag\"",
            lastModified = "Tue, 13 May 2026 00:00:00 GMT"
        )
        coEvery { eventsDao.insert(any()) } returns 1L

        repository.refreshSubscription(1L)

        assertNull("etag must be dropped to force full fetch", captured.captured.etag)
        assertNull("lastModified must be dropped", captured.captured.lastModified)
    }

    @Test
    fun `refreshSubscription preserves conditional headers when events are stored`() = runTest {
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription.copy(
            etag = "\"healthy-etag\"",
            lastModified = "Mon, 01 Jan 2024 00:00:00 GMT"
        )
        stubLocalEventsExist(true)
        val captured = slot<IcsSubscription>()
        coEvery { icsFetcher.fetch(capture(captured)) } returns IcsFetcher.FetchResult.NotModified

        repository.refreshSubscription(1L)

        assertEquals("\"healthy-etag\"", captured.captured.etag)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", captured.captured.lastModified)
    }

    @Test
    fun `refreshSubscription refuses to cache ETag when parser returns 0 from a feed containing VEVENT`() = runTest {
        // A feed with BEGIN:VEVENT lines that parses to no events is treated as a parse failure
        // (#219): the ETag isn't cached and an error is reported, so a later refresh fetches the
        // body again.
        val brokenContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:event-1@test.com
            DTSTAMP;VALUE=NONSENSE:malformed
            DTSTART:20260115T100000Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        // The fetched feed is a VEVENT without DTSTART, which the parser drops. IcsParserService
        // is an `object` and can't be mocked. `brokenContent` above isn't used.
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//Test//EN\nBEGIN:VEVENT\nUID:bad\nEND:VEVENT\nEND:VCALENDAR",
            etag = "\"new-etag\"",
            lastModified = null
        )

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "expected SyncResult.Error, got $result",
            result is IcsSubscriptionRepository.SyncResult.Error
        )
        val errorMessage = (result as IcsSubscriptionRepository.SyncResult.Error).message
        assertTrue(
            "error message must mention 'Parsed 0 events': was '$errorMessage'",
            errorMessage.contains("Parsed 0 events")
        )
        // The ETag isn't cached.
        coVerify(exactly = 0) {
            icsSubscriptionsDao.updateSyncSuccess(any(), any(), any(), any())
        }
        coVerify { icsSubscriptionsDao.updateSyncError(eq(1L), any()) }
    }

    @Test
    fun `refreshSubscription accepts 0 events when feed contains no VEVENT (legitimately empty)`() = runTest {
        // A feed with no VEVENT (here only a VTODO) parses to 0 events. That isn't a parse
        // failure: the ETag is cached and Success reported, so later refreshes can get a 304.
        val emptyContent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VTODO
            UID:todo-1@test.com
            SUMMARY:Buy milk
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = emptyContent,
            etag = "\"empty-feed-etag\"",
            lastModified = null
        )

        val result = repository.refreshSubscription(1L)

        assertTrue(
            "expected Success(0 added) for legitimately empty feed, got $result",
            result is IcsSubscriptionRepository.SyncResult.Success
        )
        coVerify { icsSubscriptionsDao.updateSyncSuccess(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            icsSubscriptionsDao.updateSyncError(any(), any())
        }
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
        assertEquals(2, success.count.added) // the 2 events in icsContent
    }

    @Test
    fun `refreshSubscription deletes orphaned events`() = runTest {
        // A stored event the feed no longer has.
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
            content = icsContent, // event-1 and event-2 only
            etag = null,
            lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(existingEvent)
        coEvery { eventsDao.insert(any()) } returns 1L

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        val success = result as IcsSubscriptionRepository.SyncResult.Success
        assertEquals(1, success.count.deleted)
        coVerify { eventsDao.deleteById(100L) }
    }

    @Test
    fun `refreshSubscription updates existing events`() = runTest {
        // A stored event the feed still has.
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
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns listOf(existingEvent)
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
    fun `refreshSubscription does not record a sync error for a cancelled refresh`() = runTest {
        // Cancellation isn't a feed failure. Turning it into an Error records a last_error
        // against a feed whose fetch was cut short, and lets a caller looping over feeds carry
        // on with a cancelled coroutine.
        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } throws CancellationException("refresh cancelled")

        val thrown = runCatching { repository.refreshSubscription(1L) }.exceptionOrNull()

        assertTrue(
            "refreshSubscription should let cancellation through; got $thrown",
            thrown is CancellationException,
        )
        coVerify(exactly = 0) { icsSubscriptionsDao.updateSyncError(any(), any()) }
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

        // Once per inserted event.
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

        // Only the due subscription is fetched.
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

    // ==================== Issue #227: Duplicate-UID disambiguation (mocked) ====================

    @Test
    fun `duplicate-UID feed produces two insert calls with distinct mutated UIDs`() = runTest {
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

        // setup()'s insert stub doesn't capture arguments; this one collects every inserted
        // event.
        val capturedInserts = mutableListOf<Event>()
        var nextId = 100L
        coEvery { eventsDao.insert(any()) } answers {
            val event = firstArg<Event>()
            capturedInserts.add(event)
            nextId++
        }

        coEvery { icsSubscriptionsDao.getById(1L) } returns testSubscription
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(
            content = duplicateUidIcs, etag = null, lastModified = null
        )
        coEvery { eventsDao.getByCalendarIdAndCaldavUrlPrefix(any(), any()) } returns emptyList()

        val result = repository.refreshSubscription(1L)

        assertTrue(result is IcsSubscriptionRepository.SyncResult.Success)
        assertEquals(2, capturedInserts.size)
        assertEquals(
            "Both inserts must have distinct mutated UIDs",
            2,
            capturedInserts.map { it.uid }.toSet().size
        )
        capturedInserts.forEach { event ->
            assertTrue(
                "Inserted UID must use #dup= disambiguator: ${event.uid}",
                event.uid.startsWith("xxx@google.com#dup=")
            )
            assertEquals(
                "Original UID preserved in extraProperties",
                "xxx@google.com",
                event.extraProperties?.get(ORIGINAL_UID_EXTRA_KEY)
            )
        }
    }
}