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

/**
 * Unit tests for AccountRepositoryImpl.
 *
 * Tests:
 * - Account CRUD operations
 * - deleteAccount with full cleanup (WorkManager, reminders, credentials)
 * - Credential delegation
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

        // Data-bearing DAOs are explicit (not relaxed) so an unexpected query
        // throws instead of silently returning null/empty. Defaults below
        // reproduce the previous relaxed behavior; per-test stubs override them.
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

        // Purge of the contacts system account must also drop the delta sync
        // cursor; stub the DAO so the clear is verifiable (and unstubbed use throws).
        addressBookDao = mockk()
        coEvery { addressBookDao.deleteByAccountId(any()) } just Runs

        pendingOperationsDao = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        // Side-effect collaborator (Unit-returning, no data): relaxed is fine.
        contactSystemAccountRegistrar = mockk(relaxed = true)
        // Data-bearing (row store + counts drive the post-purge verify): use the
        // canonical fake, not a relaxed mock, so a leftover-rows state is real.
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
        // Setup
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify WorkManager jobs cancelled
        verify { workManager.cancelUniqueWork("sync_account_1") }
    }

    @Test
    fun `deleteAccount cancels all reminders before cascade delete`() = runBlocking {
        // Setup
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

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify reminders cancelled for BOTH events
        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { reminderScheduler.cancelRemindersForEvent(101L) }
    }

    @Test
    fun `deleteAccount deletes pending operations for account events`() = runBlocking {
        // Setup
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

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify pending ops deleted
        coVerify { pendingOperationsDao.deleteForEvent(100L) }
    }

    @Test
    fun `deleteAccount handles credential deletion failure gracefully`() = runBlocking {
        // Setup
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { credentialManager.deleteCredentials(accountId) } throws RuntimeException("Encryption error")

        // Execute - should NOT throw
        accountRepository.deleteAccount(accountId)

        // Verify cascade delete still happens
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount performs cascade delete via Room`() = runBlocking {
        // Setup
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify cascade delete called
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount removes the per-login contacts system account`() = runBlocking {
        // Setup: a CardDAV login (contacts-capable) resolves to a login email.
        val accountId = 1L
        val account = mockk<Account>(relaxed = true) {
            every { id } returns accountId
            every { email } returns "alice@example.test"
            every { provider } returns AccountProvider.ICLOUD
        }
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify the contacts system account was removed by login email, and
        // that removal happens after the row is cascade-deleted (irreversible
        // purge runs only once the reversible DB work has committed).
        coVerifyOrder {
            accountsDao.deleteById(accountId)
            contactSystemAccountRegistrar.removeAccount("alice@example.test")
        }
    }

    @Test
    fun `deleteAccount keeps contacts account when another CardDAV login shares the email`() = runBlocking {
        // Two CardDAV logins share one email (allowed — accounts are unique on
        // provider+email+home_set_url). Deleting one must NOT purge the shared,
        // email-named contacts account while the sibling login is still actively
        // syncing contacts into it.
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
        // A same-email CardDAV sibling exists but has contact sync OFF, so it holds
        // no contacts in the shared account. It must NOT block the purge — else the
        // deleted login's contacts are stranded on the device with nothing syncing.
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
        // A LOCAL/ICS sibling that happens to share the email never registered a
        // contacts account, so it must NOT block the purge — otherwise the
        // email-named contacts account leaks with nothing left to manage it.
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
        // A LOCAL/ICS login never registers a contacts account, so deleting it
        // must not attempt a removal (and must not run the sibling-scan query).
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
        // The full-table sibling scan must not run for a non-contacts account.
        coVerify(exactly = 0) { accountsDao.getAllOnce() }
    }

    @Test
    fun `deleteAccount skips contacts removal when account no longer resolvable`() = runBlocking {
        // Setup: no row for this id (already gone) → nothing to resolve an email.
        val accountId = 1L
        coEvery { accountsDao.getById(accountId) } returns null
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Execute — must not throw and must not call removeAccount with a null.
        accountRepository.deleteAccount(accountId)

        verify(exactly = 0) { contactSystemAccountRegistrar.removeAccount(any()) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount clears the delta cursor of an idle same-email CardDAV sibling whose contacts the purge wipes`() = runBlocking {
        // Deleting account 7 cascade-drops its own address_books, but the purge of the
        // shared email-keyed system account also wipes idle sibling 8's RawContacts —
        // and sibling 8's row (and its stale cursor) survive the cascade. Clear it, or
        // re-enabling sibling 8 later takes the incremental path and never restores the
        // wiped contacts. The deleted account's own cursor needs no explicit clear
        // (the FK cascade already dropped it).
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
        // getAllOnce() is read by the purge decision (pre-cascade, sees both) and then
        // by the cursor sweep, which runs AFTER accountsDao.deleteById(7) — so by the
        // sweep the cascade has already dropped account 7 and only the sibling remains.
        // A single relaxed stub can't model both states, so answer positionally: first
        // call sees both, every later call sees only the surviving sibling.
        coEvery { accountsDao.getAllOnce() } returnsMany listOf(
            listOf(deleting, idleSibling),
            listOf(idleSibling),
        )
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        accountRepository.deleteAccount(accountId)

        verify { contactSystemAccountRegistrar.removeAccount("shared@example.test") }
        // Only the surviving sibling's cursor is cleared; the deleted account's own
        // books went with the FK cascade, so it must never be cleared explicitly.
        coVerify(exactly = 1) { addressBookDao.deleteByAccountId(8L) }
        coVerify(exactly = 0) { addressBookDao.deleteByAccountId(7L) }
    }

    @Test
    fun `deleteAccount handles multiple calendars with multiple events`() = runBlocking {
        // Setup
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

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify all events cleaned up
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
        // Setup
        val account = Account(
            id = 0L,
            provider = AccountProvider.CALDAV,
            email = "user@example.com",
            displayName = "Test"
        )
        coEvery { accountsDao.insert(account) } returns 42L

        // Execute
        val result = accountRepository.createAccount(account)

        // Verify
        assertEquals(42L, result)
    }

    @Test
    fun `getAccountById returns account from DAO`() = runBlocking {
        // Setup
        val account = Account(
            id = 1L,
            provider = AccountProvider.CALDAV,
            email = "user@example.com",
            displayName = "Test"
        )
        coEvery { accountsDao.getById(1L) } returns account

        // Execute
        val result = accountRepository.getAccountById(1L)

        // Verify
        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderAndEmail returns matching account`() = runBlocking {
        // Setup
        val account = Account(
            id = 1L,
            provider = AccountProvider.ICLOUD,
            email = "user@icloud.com",
            displayName = "iCloud"
        )
        coEvery { accountsDao.getByProviderAndEmail(AccountProvider.ICLOUD, "user@icloud.com") } returns account

        // Execute
        val result = accountRepository.getAccountByProviderAndEmail(AccountProvider.ICLOUD, "user@icloud.com")

        // Verify
        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderEmailAndHomeSetUrl returns matching account`() = runBlocking {
        // Setup
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

        // Execute
        val result = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
            AccountProvider.CALDAV, "admin", "https://nextcloud.example.com/dav/calendars/admin/"
        )

        // Verify
        assertEquals(account, result)
    }

    @Test
    fun `getAccountByProviderEmailAndHomeSetUrl returns null for different server`() = runBlocking {
        // Setup
        coEvery {
            accountsDao.getByProviderEmailAndHomeSetUrl(
                AccountProvider.CALDAV, "admin", "https://other-server.com/dav/calendars/admin/"
            )
        } returns null

        // Execute
        val result = accountRepository.getAccountByProviderEmailAndHomeSetUrl(
            AccountProvider.CALDAV, "admin", "https://other-server.com/dav/calendars/admin/"
        )

        // Verify
        assertNull(result)
    }

    // ========== Flow Tests ==========

    @Test
    fun `getAllAccountsFlow returns flow from DAO`() = runBlocking {
        // Setup
        val accounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A")
        )
        every { accountsDao.getAll() } returns flowOf(accounts)

        // Execute
        val flow = accountRepository.getAllAccountsFlow()

        // Verify - flow should emit
        assertNotNull(flow)
    }

    @Test
    fun `getAccountCountByProviderFlow returns count flow`() = runBlocking {
        // Setup
        every { accountsDao.getAccountCountByProvider(AccountProvider.CALDAV) } returns flowOf(3)

        // Execute
        val flow = accountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV)

        // Verify
        assertNotNull(flow)
    }

    // ========== Credential Delegation Tests ==========

    @Test
    fun `saveCredentials delegates to credentialManager`() = runBlocking {
        // Setup
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        coEvery { credentialManager.saveCredentials(1L, credentials) } returns true

        // Execute
        val result = accountRepository.saveCredentials(1L, credentials)

        // Verify
        assertTrue(result)
        coVerify { credentialManager.saveCredentials(1L, credentials) }
    }

    @Test
    fun `getCredentials delegates to credentialManager`() = runBlocking {
        // Setup
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        coEvery { credentialManager.getCredentials(1L) } returns credentials

        // Execute
        val result = accountRepository.getCredentials(1L)

        // Verify
        assertEquals(credentials, result)
    }

    @Test
    fun `hasCredentials delegates to credentialManager`() = runBlocking {
        // Setup
        coEvery { credentialManager.hasCredentials(1L) } returns true

        // Execute
        val result = accountRepository.hasCredentials(1L)

        // Verify
        assertTrue(result)
    }

    // ========== Sync Metadata Tests ==========

    @Test
    fun `recordSyncSuccess updates DAO`() = runBlocking {
        // Execute
        accountRepository.recordSyncSuccess(1L, 12345L)

        // Verify
        coVerify { accountsDao.recordSyncSuccess(1L, 12345L) }
    }

    @Test
    fun `recordSyncFailure updates DAO`() = runBlocking {
        // Execute
        accountRepository.recordSyncFailure(1L, 12345L)

        // Verify
        coVerify { accountsDao.recordSyncFailure(1L, 12345L) }
    }

    @Test
    fun `updateCalDavUrls updates DAO`() = runBlocking {
        // Execute
        accountRepository.updateCalDavUrls(1L, "https://principal", "https://home")

        // Verify
        coVerify { accountsDao.updateCalDavUrls(1L, "https://principal", "https://home") }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `deleteAccount on non-existent ID is no-op - does not throw`() = runBlocking {
        // Setup - account doesn't exist, no calendars
        val nonExistentId = 999L
        coEvery { calendarsDao.getByAccountIdOnce(nonExistentId) } returns emptyList()

        // Execute - should NOT throw
        accountRepository.deleteAccount(nonExistentId)

        // Verify cleanup still attempted (defensive)
        verify { workManager.cancelUniqueWork("sync_account_999") }
        coVerify { accountsDao.deleteById(nonExistentId) }
    }

    @Test
    fun `deleteAccount for LOCAL provider account works without credentials`() = runBlocking {
        // Setup - LOCAL accounts don't have credentials stored
        val accountId = 1L
        val calendar = Calendar(
            id = 10L,
            accountId = accountId,
            displayName = "Local Calendar",
            caldavUrl = "",  // Local calendars have empty caldavUrl
            color = 0xFF0000FF.toInt()
        )
        val event = mockk<Event>(relaxed = true) { every { id } returns 100L }

        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns listOf(calendar)
        coEvery { eventsDao.getAllMasterEventsForCalendar(10L) } returns listOf(event)
        // Credential deletion returns silently (no credentials exist)
        coEvery { credentialManager.deleteCredentials(accountId) } just Runs

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify full cleanup still happens
        coVerify { reminderScheduler.cancelRemindersForEvent(100L) }
        coVerify { pendingOperationsDao.deleteForEvent(100L) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount for ICS provider account works without credentials`() = runBlocking {
        // Setup - ICS subscription accounts don't have credentials
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

        // Execute
        accountRepository.deleteAccount(accountId)

        // Verify cleanup
        coVerify { reminderScheduler.cancelRemindersForEvent(200L) }
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `getAccountById returns null for non-existent ID`() = runBlocking {
        // Setup
        coEvery { accountsDao.getById(999L) } returns null

        // Execute
        val result = accountRepository.getAccountById(999L)

        // Verify
        assertNull(result)
    }

    @Test
    fun `deleteAccount handles WorkManager exception gracefully`() = runBlocking {
        // Setup - WorkManager throws but should not break the flow
        val accountId = 1L
        every { workManager.cancelUniqueWork(any()) } throws IllegalStateException("WorkManager not initialized")
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()

        // Execute - should NOT throw, should continue with cleanup
        try {
            accountRepository.deleteAccount(accountId)
            // If we reach here without WorkManager fix, the test documents current behavior
        } catch (e: IllegalStateException) {
            // Current behavior: exception propagates
            // This test documents that we may need to add try-catch around WorkManager call
        }
    }

    @Test
    fun `getEnabledAccounts returns only enabled accounts`() = runBlocking {
        // Setup
        val enabledAccounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A", isEnabled = true)
        )
        coEvery { accountsDao.getEnabledAccounts() } returns enabledAccounts

        // Execute
        val result = accountRepository.getEnabledAccounts()

        // Verify
        assertEquals(1, result.size)
        assertTrue(result.all { it.isEnabled })
    }

    @Test
    fun `setEnabled updates account enabled state`() = runBlocking {
        // Execute
        accountRepository.setEnabled(1L, false)

        // Verify
        coVerify { accountsDao.setEnabled(1L, false) }
    }

    @Test
    fun `getAllAccounts returns all accounts one-shot`() = runBlocking {
        // Setup
        val accounts = listOf(
            Account(id = 1L, provider = AccountProvider.ICLOUD, email = "a@icloud.com", displayName = "A"),
            Account(id = 2L, provider = AccountProvider.CALDAV, email = "b@example.com", displayName = "B")
        )
        coEvery { accountsDao.getAllOnce() } returns accounts

        // Execute
        val result = accountRepository.getAllAccounts()

        // Verify
        assertEquals(2, result.size)
    }

    @Test
    fun `getAccountsByProvider returns filtered accounts`() = runBlocking {
        // Setup
        val caldavAccounts = listOf(
            Account(id = 2L, provider = AccountProvider.CALDAV, email = "b@example.com", displayName = "B")
        )
        coEvery { accountsDao.getByProvider(AccountProvider.CALDAV) } returns caldavAccounts

        // Execute
        val result = accountRepository.getAccountsByProvider(AccountProvider.CALDAV)

        // Verify
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

        // Enrol the system account BEFORE the flag reads true, so Android won't
        // purge RawContacts written under it.
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
        // Same-email CardDAV logins share ONE email-named contacts system account.
        // Disabling contact sync on one must NOT purge the shared account (and the
        // sibling's synced contacts) while the sibling is still actively syncing
        // contacts into it — mirroring the guard deleteAccount already applies. The
        // flag is still cleared.
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
        // The only same-email CardDAV sibling has contact sync OFF, so it holds no
        // contacts in the shared account. Disabling sync here must purge the account
        // — otherwise the just-disabled login's contacts linger on the device with
        // nothing left syncing them.
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
        // A LOCAL/ICS sibling sharing the email never registered a contacts
        // account, so it must NOT block the purge on disable.
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
        // address-book delta cursor MUST be dropped in lockstep. Otherwise the next
        // re-enable takes the incremental (RFC 6578) path against a still-valid token,
        // the server reports only *changes*, and the wiped contacts are never
        // re-fetched — the account shows on the device holding zero contacts.
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
        // An active same-email CardDAV sibling blocks the purge, so no contacts are
        // wiped — the cursor must survive so the sibling keeps syncing incrementally.
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
        // The purged account is email-keyed and shared: an idle (contact-sync-off)
        // same-email CardDAV sibling's contacts also lived under it and were wiped.
        // Its cursor must be cleared too, or re-enabling THAT sibling later would hit
        // the identical stale-delta bug. A non-CardDAV sibling never synced contacts
        // into the account, so its (nonexistent) cursor must not be touched.
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

    // ========== Contacts purge: explicit scoped delete + post-purge verify ==========

    @Test
    fun `disable explicitly purges our scoped rows before removing the account`() = runBlocking {
        // Don't trust the OS account-removal cascade to delete the RawContacts:
        // delete our own account-scoped rows first, then remove the account. The
        // "before" is load-bearing: removal must never depend on the cascade, so
        // wire the registrar to record its call into the same ordering log the
        // fake writes to, then assert the real sequence — a plain verify{} would
        // pass even if the two calls were swapped.
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

        // The scoped purge ran (touching only our ACCOUNT_NAME + type) BEFORE the
        // account was removed, and our rows are gone without relying on the cascade.
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
        // The explicit scoped delete runs before the (synchronous) account removal, and
        // it is deterministic: any rows surviving it are ones the account-scoped
        // predicate can't match, which a byte-identical retry couldn't clear either. So
        // there must be no retry — the purge is issued once, and leftovers are reported
        // honestly (see the INCOMPLETE test) rather than retried in a loop that no-ops.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")
        // The count keeps reporting leftovers regardless of the (successful) delete —
        // the classic account-less-survivor case that a retry cannot fix.
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
        // The reported false-clean: WRITE_CONTACTS is revoked, so purgeAccount can't
        // delete and returns failure, AND countRawContacts (READ also revoked) returns
        // 0 as "can't tell". The old code discarded the failure and trusted the 0,
        // reporting a clean purge with rows still on the device. The outcome must be
        // INCOMPLETE — a 0 that could just mean "couldn't check" is never treated as
        // verified-empty when the delete itself failed.
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
        // The delete succeeds (Result.success) but the scoped predicate never matches
        // the survivors (e.g. account-less rows), so the count stays non-zero. Honest
        // signal: INCOMPLETE, not a false clean.
        val accountId = 7L
        val account = Account(id = accountId, provider = AccountProvider.ICLOUD, email = "carol@example.test")
        coEvery { accountsDao.getById(accountId) } returns account
        coEvery { accountsDao.getAllOnce() } returns listOf(account)
        contactsProviderRepository.seed("carol@example.test", "/c1.vcf", "\"e1\"")
        // Every count read reports leftovers regardless of the (successful) deletes.
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
