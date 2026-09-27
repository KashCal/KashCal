package org.onekash.kashcal.data.repository

import android.util.Log
import androidx.work.WorkManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.coVerifyOrder
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.adapter.ContactSystemAccountRegistrar
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.scheduler.SyncScheduler

/**
 * Tests [AccountRepositoryImpl]:
 * - deleteAccount cleanup: sync work (shared periodic work only after the last syncable
 *   account), reminders, pending operations, credentials, the cascade, and the contacts
 *   system account purge with its same-email sibling rule
 * - setContactSyncEnabled: registering or purging the contacts account, clearing address-book
 *   rows, and the [ContactPurgeOutcome] it reports
 * - CRUD, sync metadata and credential delegation to the DAO and [CredentialManager]
 */
class AccountRepositoryImplTest {

    private lateinit var accountRepository: AccountRepositoryImpl

    private lateinit var accountsDao: AccountsDao
    private lateinit var addressBookDao: AddressBookDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var credentialManager: CredentialManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var workManager: WorkManager
    private lateinit var contactSystemAccountRegistrar: ContactSystemAccountRegistrar
    private lateinit var contactsProviderRepository: FakeContactsProviderRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Data-bearing DAOs are explicit, not relaxed, so an unstubbed query throws instead of
        // silently returning null or empty. These are the defaults; per-test stubs override them.
        accountsDao = mockk()
        calendarsDao = mockk()
        eventsDao = mockk()
        coEvery { accountsDao.getById(any()) } returns null
        coEvery { accountsDao.getByProviderAndEmail(any(), any()) } returns null
        coEvery { accountsDao.getByProviderEmailAndHomeSetUrl(any(), any(), any()) } returns null
        coEvery { accountsDao.getAllOnce() } returns emptyList()
        coEvery { accountsDao.getEnabledAccounts() } returns emptyList()
        coEvery { accountsDao.getByProvider(any()) } returns emptyList()
        every { accountsDao.getAll() } returns flowOf(emptyList())
        every { accountsDao.getAccountCountByProvider(any()) } returns flowOf(0)
        coEvery { accountsDao.insert(any()) } returns 0L
        coEvery { accountsDao.deleteById(any()) } just Runs
        coEvery { accountsDao.recordSyncSuccess(any(), any()) } just Runs
        coEvery { accountsDao.recordSyncFailure(any(), any()) } just Runs
        coEvery { accountsDao.updateCalDavUrls(any(), any(), any()) } just Runs
        coEvery { accountsDao.setEnabled(any(), any()) } just Runs
        coEvery { accountsDao.setContactSyncEnabled(any(), any()) } just Runs
        coEvery { calendarsDao.getByAccountIdOnce(any()) } returns emptyList()
        coEvery { eventsDao.getAllMasterEventsForCalendar(any()) } returns emptyList()

        // A contacts account purge also deletes the address-book rows holding the sync-tokens;
        // stubbed so the delete is verifiable and any other call throws.
        addressBookDao = mockk()
        coEvery { addressBookDao.deleteByAccountId(any()) } just Runs

        pendingOperationsDao = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        // Side-effect collaborator (Unit-returning, no data): relaxed is fine.
        contactSystemAccountRegistrar = mockk(relaxed = true)
        // Data-bearing (its rows and counts decide the purge outcome): the shared fake, not a
        // relaxed mock, keeps a leftover-rows state real.
        contactsProviderRepository = FakeContactsProviderRepository()

        accountRepository = AccountRepositoryImpl(
            accountsDao = accountsDao,
            addressBookDao = addressBookDao,
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            credentialManager = credentialManager,
            reminderScheduler = reminderScheduler,
            workManager = workManager,
            contactSystemAccountRegistrar = contactSystemAccountRegistrar,
            contactsProviderRepository = contactsProviderRepository
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Delete Account Tests ==========

    @Test
    fun `deleteAccount cancels WorkManager jobs first`() = runBlocking {
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { workManager.cancelUniqueWork("sync_account_1") }
    }

    @Test
    fun `deleteAccount cancels all reminders before cascade delete`() = runBlocking {
        val accountId = 1L
        val calendar = Calendar(
            id = 10L,
            accountId = accountId,
            displayName = "Test Calendar",
            caldavUrl = "https://caldav.example.com/cal/",
            color = 0xFF0000FF.toInt()
        )
        val event1 = mockk<Event>(relaxed = true) { every { id } returns 100L }
        val event2 = mockk<Event>(relaxed = true) { every { id } returns 101L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(calendar)
        coEvery { eventsDao.getAllMasterEventsForCalendar(10L) } returns listOf(event1, event2)

        accountRepository.deleteAccount(accountId)

        // Reminders are cancelled for both events.
        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { reminderScheduler.cancelRemindersForEvent(101L) }
    }

    @Test
    fun `deleteAccount deletes pending operations for account events`() = runBlocking {
        val accountId = 1L
        val calendar = Calendar(
            id = 10L,
            accountId = accountId,
            displayName = "Test",
            caldavUrl = "https://caldav.example.com/cal/",
            color = 0xFF0000FF.toInt()
        )
        val event = mockk<Event>(relaxed = true) { every { id } returns 100L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(calendar)
        coEvery { eventsDao.getAllMasterEventsForCalendar(10L) } returns listOf(event)

        accountRepository.deleteAccount(accountId)

        coVerify { pendingOperationsDao.deleteForEvent(100L) }
    }

    @Test
    fun `deleteAccount handles credential deletion failure gracefully`() = runBlocking {
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { credentialManager.deleteCredentials(accountId) } throws RuntimeException("Encryption error")

        // The credential failure is logged and ignored; the cascade still runs.
        accountRepository.deleteAccount(accountId)

        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount performs cascade delete via Room`() = runBlocking {
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount cancels shared one-shot and periodic work when last syncable account removed`() = runBlocking {
        // The one-shot and expedited jobs have one work name shared by all accounts, so they
        // are always cancelled: an enqueued one-shot could bring back sync UI after the account
        // is gone. The shared periodic jobs are cancelled only once no syncable account
        // remains, as here.
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { workManager.cancelUniqueWork("sync_account_1") }
        verify { workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_SYNC_WORK) }
        verify { workManager.cancelUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK) }
        verify { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK) }
        verify { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK) }
    }

    @Test
    fun `deleteAccount cancels one-shot but keeps periodic when another syncable account remains`() = runBlocking {
        // A CalDAV login with stored credentials remains, so the shared periodic jobs must
        // survive. The shared one-shot and expedited jobs are still cancelled; a remaining
        // account re-enqueues on its next trigger.
        val accountId = 1L
        val remaining = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { email } returns "bob@example.test"
            every { provider } returns AccountProvider.CALDAV
        }
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns listOf(remaining)
        coEvery { credentialManager.hasCredentials(2L) } returns true

        accountRepository.deleteAccount(accountId)

        verify { workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_SYNC_WORK) }
        verify { workManager.cancelUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK) }
        verify(exactly = 0) { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK) }
        verify(exactly = 0) { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK) }
    }

    @Test
    fun `deleteAccount keeps periodic when remaining syncable account lacks credentials but another has them`() = runBlocking {
        // "Syncable" means CalDAV-capable with stored credentials. A remaining CalDAV login
        // without credentials doesn't keep periodic work alive on its own, but a second one
        // with credentials does.
        val accountId = 1L
        val noCreds = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { provider } returns AccountProvider.CALDAV
        }
        val withCreds = mockk<Account>(relaxed = true) {
            every { id } returns 3L
            every { provider } returns AccountProvider.ICLOUD
        }
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns listOf(noCreds, withCreds)
        coEvery { credentialManager.hasCredentials(2L) } returns false
        coEvery { credentialManager.hasCredentials(3L) } returns true

        accountRepository.deleteAccount(accountId)

        verify(exactly = 0) { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK) }
    }

    @Test
    fun `deleteAccount cancels periodic when only a credential-less CalDAV account remains`() = runBlocking {
        // A remaining CalDAV login without stored credentials can't sync, so it must not keep
        // the shared periodic jobs alive.
        val accountId = 1L
        val noCreds = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { provider } returns AccountProvider.CALDAV
        }
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { accountsDao.getAllOnce() } returns listOf(noCreds)
        coEvery { credentialManager.hasCredentials(2L) } returns false

        accountRepository.deleteAccount(accountId)

        verify { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK) }
        verify { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK) }
    }

    @Test
    fun `deleteAccount removes the per-login contacts system account`() = runBlocking {
        // A CardDAV-capable login, resolved to its login email.
        val accountId = 1L
        val account = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "alice@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        // The contacts system account is removed by login email, after the row's cascade
        // delete: the irreversible purge runs last, so an earlier failure leaves the contacts.
        coVerifyOrder {
            accountsDao.deleteById(accountId)
            contactSystemAccountRegistrar.removeAccount("alice@example.test")
        }
    }

    @Test
    fun `deleteAccount keeps contacts account when another CardDAV login shares the email`() = runBlocking {
        // Two CardDAV logins share one email (accounts are unique on provider, email and
        // home_set_url). Deleting one must not purge the shared email-named contacts account
        // while the sibling still syncs contacts into it.
        val accountId = 1L
        val deleting = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        val sibling = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.CALDAV
            every { contactSyncEnabled } returns true
        }
        coEvery { accountsDao.getById(accountId) } returns deleting
        coEvery { accountsDao.getAllOnce() } returns listOf(deleting, sibling)
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount removes contacts account when the CardDAV sibling has contact sync disabled`() = runBlocking {
        // A same-email CardDAV sibling has contact sync off, so it holds no contacts in the
        // shared account. It must not block the purge, or the deleted login's contacts are
        // stranded on the device with nothing syncing them.
        val accountId = 1L
        val deleting = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        val idleSibling = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.CALDAV
            every { contactSyncEnabled } returns false
        }
        coEvery { accountsDao.getById(accountId) } returns deleting
        coEvery { accountsDao.getAllOnce() } returns listOf(deleting, idleSibling)
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
    }

    @Test
    fun `deleteAccount removes contacts account when only a non-CardDAV sibling shares the email`() = runBlocking {
        // A LOCAL or ICS sibling sharing the email never registered a contacts account, so it
        // must not block the purge, or the email-named contacts account leaks with nothing left
        // to manage it.
        val accountId = 1L
        val deleting = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        val localSibling = mockk<Account>(relaxed = true) {
            every { id } returns 2L
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.LOCAL
        }
        coEvery { accountsDao.getById(accountId) } returns deleting
        coEvery { accountsDao.getAllOnce() } returns listOf(deleting, localSibling)
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
    }

    @Test
    fun `deleteAccount skips contacts removal for a non-CardDAV account`() = runBlocking {
        // A LOCAL or ICS login never registers a contacts account, so deleting it must not
        // attempt a removal. getAllOnce still runs, for the shared periodic work check only.
        val accountId = 1L
        val account = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "local@example.test"
            every { provider } returns AccountProvider.LOCAL
        }
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
    }

    @Test
    fun `deleteAccount skips contacts removal when account no longer resolvable`() = runBlocking {
        // No row for this id, so there is no email to resolve.
        val accountId = 1L
        coEvery { accountsDao.getById(accountId) } returns null
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Must not throw or call removeAccount.
        accountRepository.deleteAccount(accountId)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount clears the delta cursor of an idle same-email CardDAV sibling whose contacts the purge wipes`() = runBlocking {
        // Deleting account 7 cascade-drops its own address_books rows, but the purge of the
        // shared email-keyed system account also wipes idle sibling 8's RawContacts, and
        // sibling 8's rows and sync-tokens survive the cascade. They are deleted, or re-enabling
        // sibling 8 later takes the delta path and never restores the wiped contacts. Account
        // 7's rows need no explicit delete; the FK cascade drops them.
        val accountId = 7L
        val deleting = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        val idleSibling = mockk<Account>(relaxed = true) {
            every { id } returns 8L
            every { email } returns "shared@example.test"
            every { provider } returns AccountProvider.CALDAV
            every { contactSyncEnabled } returns false
        }
        coEvery { accountsDao.getById(accountId) } returns deleting
        // getAllOnce() is read by the purge decision before accountsDao.deleteById(7), then
        // after it by the periodic work check and the address-book delete, when only the
        // sibling remains. One fixed stub can't model both states, so the first call sees both
        // and every later call sees only the sibling.
        coEvery { accountsDao.getAllOnce() } returnsMany listOf(
            listOf(deleting, idleSibling),
            listOf(idleSibling),
        )
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
        // Only the sibling's address-book rows are deleted explicitly; the deleted account's
        // went with the FK cascade.
        coVerify(exactly = 1) { addressBookDao.deleteByAccountId(8L) }
        coVerify(exactly = 0) { addressBookDao.deleteByAccountId(7L) }
    }

    @Test
    fun `deleteAccount handles multiple calendars with multiple events`() = runBlocking {
        val accountId = 1L
        val cal1 = Calendar(
            id = 10L,
            accountId = accountId,
            displayName = "Cal1",
            caldavUrl = "https://caldav.example.com/cal1/",
            color = 0xFF0000FF.toInt()
        )
        val cal2 = Calendar(
            id = 20L,
            accountId = accountId,
            displayName = "Cal2",
            caldavUrl = "https://caldav.example.com/cal2/",
            color = 0xFF00FF00.toInt()
        )
        val event1 = mockk<Event>(relaxed = true) { every { id } returns 100L }
        val event2 = mockk<Event>(relaxed = true) { every { id } returns 101L }
        val event3 = mockk<Event>(relaxed = true) { every { id } returns 200L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(cal1, cal2)
        coEvery { eventsDao.getAllMasterEventsForCalendar(10L) } returns listOf(event1, event2)
        coEvery { eventsDao.getAllMasterEventsForCalendar(20L) } returns listOf(event3)

        accountRepository.deleteAccount(accountId)

        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { reminderScheduler.cancelRemindersForEvent(101L) }
        coVerify { reminderScheduler.cancelRemindersForEvent(200L) }
        coVerify { pendingOperationsDao.deleteForEvent(100L) }
        coVerify { pendingOperationsDao.deleteForEvent(101L) }
        coVerify { pendingOperationsDao.deleteForEvent(200L) }
    }

    // ========== CRUD Tests ==========

    @Test
    fun `createAccount returns generated ID`() = runBlocking {
        val account = Account(
            id = 0L,
            provider = AccountProvider.CALDAV,
            email = "user@example.com",
            displayName = "Test"
        )
        coEvery { accountsDao.insert(account) } returns 42L

        val result = accountRepository.createAccount(account)

        assertEquals(42L, result)
    }

    @Test
    fun `getAccountById returns account from DAO`() = runBlocking {
        val account = Account(
            id = 1L,
            provider = AccountProvider.CALDAV,
            email = "user@example.com",
            displayName = "Test"
        )
        coEvery { accountsDao.getById(1L) } returns account

        val result = accountRepository.getAccountById(1L)

        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderAndEmail returns matching account`() = runBlocking {
        val account = Account(
            id = 1L,
            provider = AccountProvider.ICLOUD,
            email = "user@icloud.com",
            displayName = "iCloud"
        )
        coEvery { accountsDao.getByProviderAndEmail(AccountProvider.ICLOUD, "user@icloud.com") } returns account

        val result = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "user@icloud.com")

        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderEmailAndHomeSetUrl returns matching account`() = runBlocking {
        val account = Account(
            id = 1L,
            provider = AccountProvider.CALDAV,
            email = "admin",
            displayName = "Nextcloud",
            homeSetUrl = "https://nextcloud.example.com/dav/calendars/admin/"
        )
        coEvery {
            accountsDao.getByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, "admin", "https://nextcloud.example.com/dav/calendars/admin/"
            )
        } returns account

        val result = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
            AccountProvider.CALDAV, "admin", "https://nextcloud.example.com/dav/calendars/admin/"
        )

        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderEmailAndHomeSetUrl returns null for different server`() = runBlocking {
        coEvery {
            accountsDao.getByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, "admin", "https://other-server.com/dav/calendars/admin/"
            )
        } returns null

        val result = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
            AccountProvider.CALDAV, "admin", "https://other-server.com/dav/calendars/admin/"
        )

        assertNull(result)
    }

    // ========== Flow Tests ==========

    @Test
    fun `getAllAccountsFlow returns flow from DAO`() = runBlocking {
        val accounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A")
        )
        every { accountsDao.getAll() } returns flowOf(accounts)

        val flow = accountRepository.getAllAccountsFlow()

        assertNotNull(flow)
    }

    @Test
    fun `getAccountCountByProviderFlow returns count flow`() = runBlocking {
        every { accountsDao.getAccountCountByProvider(AccountProvider.CALDAV) } returns flowOf(3)

        val flow = accountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV)

        assertNotNull(flow)
    }

    // ========== Credential Delegation Tests ==========

    @Test
    fun `saveCredentials delegates to credentialManager`() = runBlocking {
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        coEvery { credentialManager.saveCredentials(1L, credentials) } returns true

        val result = accountRepository.saveCredentials(1L, credentials)

        assertTrue(result)
        coVerify { credentialManager.saveCredentials(1L, credentials) }
    }

    @Test
    fun `getCredentials delegates to credentialManager`() = runBlocking {
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        coEvery { credentialManager.getCredentials(1L) } returns credentials

        val result = accountRepository.getCredentials(1L)

        assertEquals(credentials, result)
    }

    @Test
    fun `hasCredentials delegates to credentialManager`() = runBlocking {
        coEvery { credentialManager.hasCredentials(1L) } returns true

        val result = accountRepository.hasCredentials(1L)

        assertTrue(result)
    }

    // ========== Sync Metadata Tests ==========

    @Test
    fun `recordSyncSuccess updates DAO`() = runBlocking {
        accountRepository.recordSyncSuccess(1L, 12345L)

        coVerify { accountsDao.recordSyncSuccess(1L, 12345L) }
    }

    @Test
    fun `recordSyncFailure updates DAO`() = runBlocking {
        accountRepository.recordSyncFailure(1L, 12345L)

        coVerify { accountsDao.recordSyncFailure(1L, 12345L) }
    }

    @Test
    fun `updateCalDavUrls updates DAO`() = runBlocking {
        accountRepository.updateCalDavUrls(1L, "https://principal", "https://home")

        coVerify { accountsDao.updateCalDavUrls(1L, "https://principal", "https://home") }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `deleteAccount on non-existent ID is no-op - does not throw`() = runBlocking {
        val nonExistentId = 999L
        coEvery { calendarsDao.getByAccountIdOnce(nonExistentId) } returns emptyList()

        // Doesn't throw, and the cleanup still runs.
        accountRepository.deleteAccount(nonExistentId)

        verify { workManager.cancelUniqueWork("sync_account_999") }
        coVerify { accountsDao.deleteById(nonExistentId) }
    }

    @Test
    fun `deleteAccount for LOCAL provider account works without credentials`() = runBlocking {
        // A LOCAL account has no stored credentials.
        val accountId = 1L
        val calendar = Calendar(
            id = 10L,
            accountId = accountId,
            displayName = "Local Calendar",
            caldavUrl = "",  // local calendars have an empty caldavUrl
            color = 0xFF0000FF.toInt()
        )
        val event = mockk<Event>(relaxed = true) { every { id } returns 100L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(calendar)
        coEvery { eventsDao.getAllMasterEventsForCalendar(10L) } returns listOf(event)
        // No credentials exist, so the delete returns without effect.
        coEvery { credentialManager.deleteCredentials(accountId) } just Runs

        accountRepository.deleteAccount(accountId)

        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { pendingOperationsDao.deleteForEvent(100L) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount for ICS provider account works without credentials`() = runBlocking {
        // An ICS subscription account has no credentials.
        val accountId = 2L
        val calendar = Calendar(
            id = 20L,
            accountId = accountId,
            displayName = "Public Holiday Calendar",
            caldavUrl = "https://example.com/holidays.ics",
            color = 0xFF00FF00.toInt()
        )
        val event = mockk<Event>(relaxed = true) { every { id } returns 200L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(calendar)
        coEvery { eventsDao.getAllMasterEventsForCalendar(20L) } returns listOf(event)

        accountRepository.deleteAccount(accountId)

        coVerify { reminderScheduler.cancelRemindersForEvent(200L) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `getAccountById returns null for non-existent ID`() = runBlocking {
        coEvery { accountsDao.getById(999L) } returns null

        val result = accountRepository.getAccountById(999L)

        assertNull(result)
    }

    @Test
    fun `deleteAccount handles WorkManager exception gracefully`() = runBlocking {
        // cancelUniqueWork runs first and isn't guarded, so its exception propagates and the
        // rest of the cleanup doesn't run. The test accepts either outcome and asserts nothing.
        val accountId = 1L
        every { workManager.cancelUniqueWork(any()) } throws IllegalStateException("WorkManager not initialized")
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        try {
            accountRepository.deleteAccount(accountId)
        } catch (e: IllegalStateException) {
            // The exception propagates from the first cancelUniqueWork.
        }
    }

    @Test
    fun `getEnabledAccounts returns only enabled accounts`() = runBlocking {
        val enabledAccounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A", isEnabled = true)
        )
        coEvery { accountsDao.getEnabledAccounts() } returns enabledAccounts

        val result = accountRepository.getEnabledAccounts()

        assertEquals(1, result.size)
        assertTrue(result.all { it.isEnabled })
    }

    @Test
    fun `setEnabled updates account enabled state`() = runBlocking {
        accountRepository.setEnabled(1L, false)

        coVerify { accountsDao.setEnabled(1L, false) }
    }

    @Test
    fun `getAllAccounts returns all accounts one-shot`() = runBlocking {
        val accounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A"),
            Account(id = 2L, provider = AccountProvider.CALDAV, email = "b@example.com", displayName = "B")
        )
        coEvery { accountsDao.getAllOnce() } returns accounts

        val result = accountRepository.getAllAccounts()

        assertEquals(2, result.size)
    }

    @Test
    fun `getAccountsByProvider returns filtered accounts`() = runBlocking {
        val caldavAccounts = listOf(
            Account(id = 2L, provider = AccountProvider.CALDAV, email = "b@example.com", displayName = "B")
        )
        coEvery { accountsDao.getByProvider(AccountProvider.CALDAV) } returns caldavAccounts

        val result = accountRepository.getAccountsByProvider(AccountProvider.CALDAV)

        assertEquals(1, result.size)
        assertEquals(AccountProvider.CALDAV, result[0].provider)
    }

    // ========== Contact-sync opt-in ==========

    @Test
    fun `setContactSyncEnabled true registers the contacts account then persists the flag`() = runBlocking {
        val accountId = 7L
        coEvery { accountsDao.getById(accountId) } returns
            Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")

        accountRepository.setContactSyncEnabled(accountId, true)

        // The system account is enrolled before the flag reads true, so Android won't purge
        // RawContacts written under it.
        coVerifyOrder {
            contactSystemAccountRegistrar.ensureAccount("carol@example.test")
            accountsDao.setContactSyncEnabled(accountId, true)
        }
        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
    }

    @Test
    fun `setContactSyncEnabled false removes the contacts account and clears the flag`() = runBlocking {
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify { contactSystemAccountRegistrar.removeAccount("carol@example.test") }
        coVerify { accountsDao.setContactSyncEnabled(accountId, false) }
        verify(exactly = 0) { contactSystemAccountRegistrar.ensureAccount(any()) }
    }

    @Test
    fun `setContactSyncEnabled false keeps the contacts account when a CardDAV sibling shares the email`() = runBlocking {
        // Same-email CardDAV logins share one email-named contacts system account. Disabling
        // contact sync on one must not purge it, with the sibling's contacts, while the sibling
        // still syncs into it; deleteAccount applies the same rule. The flag is still cleared.
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val sibling = Account(
            id = 8L,
            provider = AccountProvider.CALDAV,
            email = "shared@example.test",
            contactSyncEnabled = true,
        )
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, sibling)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
        coVerify { accountsDao.setContactSyncEnabled(accountId, false) }
    }

    @Test
    fun `setContactSyncEnabled false removes the contacts account when the CardDAV sibling has contact sync disabled`() = runBlocking {
        // The only same-email CardDAV sibling has contact sync off, so it holds no contacts in
        // the shared account. Disabling sync here must purge the account, or the disabled
        // login's contacts linger on the device with nothing syncing them.
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val idleSibling = Account(
            id = 8L,
            provider = AccountProvider.CALDAV,
            email = "shared@example.test",
            contactSyncEnabled = false,
        )
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, idleSibling)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
        coVerify { accountsDao.setContactSyncEnabled(accountId, false) }
    }

    @Test
    fun `setContactSyncEnabled false removes the contacts account when only a non-CardDAV sibling shares the email`() = runBlocking {
        // A LOCAL or ICS sibling sharing the email never registered a contacts account, so it
        // must not block the purge on disable.
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val localSibling = Account(id = 8L, provider = AccountProvider.LOCAL, email = "shared@example.test")
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, localSibling)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
        coVerify { accountsDao.setContactSyncEnabled(accountId, false) }
    }

    @Test
    fun `setContactSyncEnabled false clears the delta sync cursor when it purges the contacts account`() = runBlocking {
        // Purging the contacts system account wipes every RawContact under it, so the
        // address-book rows and their sync-tokens must go with it. Otherwise the next re-enable
        // takes the delta (RFC 6578) path against a still-valid token, the server reports only
        // changes, and the wiped contacts are never fetched again: the account shows on the
        // device with zero contacts.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify { contactSystemAccountRegistrar.removeAccount("carol@example.test") }
        coVerify { addressBookDao.deleteByAccountId(accountId) }
    }

    @Test
    fun `setContactSyncEnabled false leaves the cursor intact when an active sibling keeps the account`() = runBlocking {
        // An active same-email CardDAV sibling blocks the purge, so no contacts are wiped and
        // the address-book rows must survive for the sibling's delta sync.
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val activeSibling = Account(
            id = 8L,
            provider = AccountProvider.CALDAV,
            email = "shared@example.test",
            contactSyncEnabled = true,
        )
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, activeSibling)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
        coVerify(exactly = 0) { addressBookDao.deleteByAccountId(any()) }
    }

    @Test
    fun `setContactSyncEnabled false clears the cursor for every same-email CardDAV login whose contacts were purged`() = runBlocking {
        // The purged account is email-keyed and shared: an idle (contact sync off) same-email
        // CardDAV sibling's contacts also lived under it and were wiped. Its address-book rows
        // are deleted too, or re-enabling that sibling later would take the same stale delta. A
        // non-CardDAV sibling never synced contacts into the account, so it gets no delete.
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val idleCardDavSibling = Account(
            id = 8L,
            provider = AccountProvider.CALDAV,
            email = "shared@example.test",
            contactSyncEnabled = false,
        )
        val localSibling = Account(id = 9L, provider = AccountProvider.LOCAL, email = "shared@example.test")
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, idleCardDavSibling, localSibling)

        accountRepository.setContactSyncEnabled(accountId, false)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
        coVerify { addressBookDao.deleteByAccountId(accountId) }
        coVerify { addressBookDao.deleteByAccountId(8L) }
        coVerify(exactly = 0) { addressBookDao.deleteByAccountId(9L) }
    }

    @Test
    fun `setContactSyncEnabled is a no-op when the account does not exist`() = runBlocking {
        coEvery { accountsDao.getById(99L) } returns null

        accountRepository.setContactSyncEnabled(99L, true)

        verify(exactly = 0) { contactSystemAccountRegistrar.ensureAccount(any()) }
        coVerify(exactly = 0) { accountsDao.setContactSyncEnabled(any(), any()) }
    }

    // ========== Contacts purge: scoped delete and post-purge count ==========

    @Test
    fun `disable explicitly purges our scoped rows before removing the account`() = runBlocking {
        // The account-removal cascade isn't trusted to delete the RawContacts: our
        // account-scoped rows are deleted first, then the account is removed. The registrar
        // records into the fake's ordering log so the test asserts the real sequence; a plain
        // verify {} would pass with the two calls swapped.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        every { contactSystemAccountRegistrar.removeAccount("carol@example.test") } answers {
            contactsProviderRepository.operationLog += "remove:carol@example.test"
            true
        }
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")

        accountRepository.setContactSyncEnabled(accountId, false)

        // The scoped purge (our ACCOUNT_NAME and type only) ran before the account removal,
        // and our rows are gone without the cascade.
        assertTrue(
            "expected an explicit scoped purge for the login email",
            contactsProviderRepository.purgeCalls.contains("carol@example.test"),
        )
        assertTrue(
            "the scoped purge must run before the account removal, got order " +
                "${contactsProviderRepository.operationLog}",
            contactsProviderRepository.operationLog.indexOf("purge:carol@example.test") <
                contactsProviderRepository.operationLog.indexOf("remove:carol@example.test"),
        )
        assertTrue(
            "our rows must be gone after purge",
            contactsProviderRepository.hrefsFor("carol@example.test").isEmpty(),
        )
    }

    @Test
    fun `disable runs the scoped purge exactly once, not a byte-identical retry`() = runBlocking {
        // The scoped delete runs before the synchronous account removal and is deterministic:
        // rows surviving it are ones the account-scoped predicate can't match, which an
        // identical retry couldn't clear either. So the purge is issued once and leftovers are
        // reported as INCOMPLETE (`disable reports INCOMPLETE when rows survive the scoped
        // purge`).
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")
        // The count reports leftovers whatever the successful delete did: the account-less
        // survivor case a retry can't fix.
        contactsProviderRepository.countOverride = 1

        accountRepository.setContactSyncEnabled(accountId, false)

        assertEquals(
            "the scoped purge must run exactly once — no dead byte-identical retry",
            1,
            contactsProviderRepository.purgeCalls.count { it == "carol@example.test" },
        )
    }

    @Test
    fun `disable reports PURGED when the device verifiably ends with zero rows`() = runBlocking {
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")

        val outcome = accountRepository.setContactSyncEnabled(accountId, false)

        assertEquals(ContactPurgeOutcome.PURGED, outcome)
    }

    @Test
    fun `disable reports INCOMPLETE when the scoped delete fails on revoked WRITE_CONTACTS`() = runBlocking {
        // WRITE_CONTACTS is revoked, so purgeAccount can't delete and returns failure, and
        // countRawContacts, with READ also revoked, returns 0 as "can't tell". Trusting that 0
        // would report a clean purge with rows still on the device. The outcome must be
        // INCOMPLETE: after a failed delete a 0 is never treated as verified-empty.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")
        contactsProviderRepository.purgeResult = Result.failure(SecurityException("WRITE_CONTACTS revoked"))
        contactsProviderRepository.countOverride = 0 // READ also revoked → "can't tell" 0

        val outcome = accountRepository.setContactSyncEnabled(accountId, false)

        assertEquals(ContactPurgeOutcome.INCOMPLETE, outcome)
    }

    @Test
    fun `disable reports INCOMPLETE when rows survive the scoped purge`() = runBlocking {
        // The delete succeeds but the scoped predicate never matches the survivors (for
        // example account-less rows), so the count stays above 0: INCOMPLETE, not a false clean.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")
        // Every count read reports leftovers whatever the successful delete did.
        contactsProviderRepository.countOverride = 1

        val outcome = accountRepository.setContactSyncEnabled(accountId, false)

        assertEquals(ContactPurgeOutcome.INCOMPLETE, outcome)
    }

    @Test
    fun `disable reports NOT_ATTEMPTED when an active same-email sibling keeps the account`() = runBlocking {
        val accountId = 7L
        val disabling = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "shared@example.test")
        val activeSibling = Account(
            id = 8L,
            provider = AccountProvider.CALDAV,
            email = "shared@example.test",
            contactSyncEnabled = true,
        )
        coEvery { accountsDao.getById(accountId) } returns disabling
        coEvery { accountsDao.getAllOnce() } returns listOf(disabling, activeSibling)

        val outcome = accountRepository.setContactSyncEnabled(accountId, false)

        assertEquals(ContactPurgeOutcome.NOT_ATTEMPTED, outcome)
        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
    }

    @Test
    fun `enable reports NOT_ATTEMPTED (no purge on the enable path)`() = runBlocking {
        val accountId = 7L
        coEvery { accountsDao.getById(accountId) } returns
            Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")

        val outcome = accountRepository.setContactSyncEnabled(accountId, true)

        assertEquals(ContactPurgeOutcome.NOT_ATTEMPTED, outcome)
    }

    @Test
    fun `deleteAccount purges our scoped rows and verifies they are gone`() = runBlocking {
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")

        accountRepository.deleteAccount(accountId)

        assertTrue(contactsProviderRepository.purgeCalls.contains("carol@example.test"))
        assertTrue(contactsProviderRepository.hrefsFor("carol@example.test").isEmpty())
    }
}
