package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler

/**
 * Unit tests for ContactAnniversaryRepository.
 *
 * Tests calendar management and sync operations with mocked dependencies.
 *
 * Tests:
 * - anniversaryCalendarExists: true/false cases
 * - ensureCalendarExists: creates new, returns existing
 * - removeCalendar: delegates to AccountRepository
 * - syncAnniversaries: error when no calendar, SecurityException handling
 * - getCaldavUrl: format validation
 * - Calendar color operations
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAnniversaryRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var eventReader: EventReader
    private lateinit var contentResolver: ContentResolver
    private lateinit var dataStore: KashCalDataStore
    private lateinit var repository: ContactAnniversaryRepository

    private val testAccount = Account(
        id = 10L,
        provider = AccountProvider.CONTACTS,
        email = ContactAnniversaryRepository.ACCOUNT_EMAIL,
        displayName = "Contact Anniversaries"
    )

    private val testCalendar = Calendar(
        id = 20L,
        accountId = 10L,
        caldavUrl = "local://contact_anniversaries",
        displayName = "Contact Anniversaries",
        color = 0xFFE91E63.toInt(),
        isReadOnly = true,
        isVisible = true,
        isDefault = false
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        accountRepository = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        occurrenceGenerator = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)

        repository = ContactAnniversaryRepository(
            accountRepository = accountRepository,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            reminderScheduler = reminderScheduler,
            eventReader = eventReader,
            contentResolver = contentResolver,
            dataStore = dataStore
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ==================== anniversaryCalendarExists ====================

    @Test
    fun `anniversaryCalendarExists returns false when no account`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns null

        assertFalse(repository.anniversaryCalendarExists())
    }

    @Test
    fun `anniversaryCalendarExists returns false when account exists but no calendar`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns emptyList()

        assertFalse(repository.anniversaryCalendarExists())
    }

    @Test
    fun `anniversaryCalendarExists returns true when account and calendar exist`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)

        assertTrue(repository.anniversaryCalendarExists())
    }

    // ==================== ensureCalendarExists ====================

    @Test
    fun `ensureCalendarExists returns existing calendar ID when present`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)

        val calendarId = repository.ensureCalendarExists()
        assertEquals(20L, calendarId)
    }

    @Test
    fun `ensureCalendarExists creates account and calendar when missing`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns null
        coEvery { accountRepository.createAccount(any()) } returns 10L
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns emptyList()
        coEvery { calendarsDao.insert(any()) } returns 20L

        val calendarId = repository.ensureCalendarExists()
        assertEquals(20L, calendarId)

        coVerify { accountRepository.createAccount(any()) }
        coVerify { calendarsDao.insert(any()) }
    }

    // ==================== removeCalendar ====================

    @Test
    fun `removeCalendar delegates to accountRepository deleteAccount`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount

        repository.removeCalendar()

        coVerify { accountRepository.deleteAccount(10L) }
    }

    @Test
    fun `removeCalendar does nothing when no account exists`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns null

        repository.removeCalendar()

        coVerify(exactly = 0) { accountRepository.deleteAccount(any()) }
    }

    // ==================== syncAnniversaries ====================

    @Test
    fun `syncAnniversaries returns error when calendar not created`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns null

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Error)
        assertTrue((result as ContactEventSyncResult.Error).message.contains("not created"))
    }

    @Test
    fun `syncAnniversaries handles SecurityException`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)
        coEvery { calendarsDao.getById(20L) } returns testCalendar
        coEvery { dataStore.getAnniversaryReminder() } returns 0

        // ContentResolver throws SecurityException (no permission)
        every { contentResolver.query(any(), any(), any(), any(), any()) } throws SecurityException("No permission")

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Error)
        assertTrue((result as ContactEventSyncResult.Error).message.contains("permission"))
    }

    @Test
    fun `syncAnniversaries exception with null message uses class name`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)
        coEvery { calendarsDao.getById(20L) } returns testCalendar
        coEvery { dataStore.getAnniversaryReminder() } returns 0

        // Throw exception with null message
        every { contentResolver.query(any(), any(), any(), any(), any()) } throws NullPointerException()

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Error)
        assertEquals("NullPointerException", (result as ContactEventSyncResult.Error).message)
    }

    @Test
    fun `syncAnniversaries returns Success with zero counts when no contacts`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)
        coEvery { calendarsDao.getById(20L) } returns testCalendar
        coEvery { dataStore.getAnniversaryReminder() } returns 0
        coEvery { eventsDao.getAllMasterEventsForCalendar(20L) } returns emptyList()

        // Empty cursor (no contacts with anniversaries)
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Success)
        val success = result as ContactEventSyncResult.Success
        assertEquals(0, success.added)
        assertEquals(0, success.updated)
        assertEquals(0, success.deleted)
    }

    @Test
    fun `syncAnniversaries deletes orphaned events for removed contacts`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)
        coEvery { calendarsDao.getById(20L) } returns testCalendar
        coEvery { dataStore.getAnniversaryReminder() } returns 0

        // Existing event for a contact that no longer has anniversary
        val orphanEvent = Event(
            id = 100L,
            uid = "test-uid",
            calendarId = 20L,
            title = "Old Contact",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 86400000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "contact_anniversary:old_lookup_key"
        )
        coEvery { eventsDao.getAllMasterEventsForCalendar(20L) } returns listOf(orphanEvent)

        // No contacts returned
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Success)
        val success = result as ContactEventSyncResult.Success
        assertEquals(1, success.deleted)

        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { eventsDao.deleteById(100L) }
    }

    // ==================== getCaldavUrl ====================

    @Test
    fun `getCaldavUrl includes date for unique key per anniversary`() {
        assertEquals(
            "contact_anniversary:abc123:6-15",
            ContactAnniversaryRepository.getCaldavUrl("abc123", 6, 15)
        )
    }

    @Test
    fun `getCaldavUrl with special characters in lookupKey`() {
        assertEquals(
            "contact_anniversary:key/with/slashes:12-25",
            ContactAnniversaryRepository.getCaldavUrl("key/with/slashes", 12, 25)
        )
    }

    // ==================== Multiple anniversaries per contact ====================

    @Test
    fun `syncAnniversaries deletes old-format caldavUrl events as orphans`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)
        coEvery { calendarsDao.getById(20L) } returns testCalendar
        coEvery { dataStore.getAnniversaryReminder() } returns 0

        // Existing event with OLD format caldavUrl (no date suffix)
        val oldFormatEvent = Event(
            id = 100L,
            uid = "test-uid",
            calendarId = 20L,
            title = "Alice",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 86400000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = "contact_anniversary:alice_key"  // Old format: no date
        )
        coEvery { eventsDao.getAllMasterEventsForCalendar(20L) } returns listOf(oldFormatEvent)

        // No contacts returned — old event should be deleted as orphan
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val result = repository.syncAnniversaries()
        assertTrue(result is ContactEventSyncResult.Success)
        assertEquals(1, (result as ContactEventSyncResult.Success).deleted)
        coVerify { eventsDao.deleteById(100L) }
    }

    // ==================== Calendar color operations ====================

    @Test
    fun `updateCalendarColor delegates to dao`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns testAccount
        coEvery { calendarsDao.getByAccountIdOnce(10L) } returns listOf(testCalendar)

        repository.updateCalendarColor(0xFFFF0000.toInt())

        coVerify { calendarsDao.updateColor(20L, 0xFFFF0000.toInt()) }
    }

    @Test
    fun `getCalendarColor returns null when no calendar`() = runTest {
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ContactAnniversaryRepository.ACCOUNT_EMAIL)
        } returns null

        assertNull(repository.getCalendarColor())
    }
}
