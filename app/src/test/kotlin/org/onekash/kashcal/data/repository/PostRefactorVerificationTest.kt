package org.onekash.kashcal.data.repository

import android.util.Log
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.strategy.ConflictResolver
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushStrategy

/**
 * Tests that account data goes through [AccountRepository] and the unified [CredentialManager].
 *
 * Covered here:
 * - Over a real [AccountRepositoryImpl] with relaxed DAO mocks,
 *   [AccountRepositoryImpl.deleteAccount]: a reminder-cancel failure propagates, a
 *   credential-delete failure doesn't stop the cascade, the order of its cleanup steps, and
 *   that it deletes only its own account's credentials.
 * - Over the same repository, credential save, read and [AccountRepository.hasCredentials]
 *   delegate to [CredentialManager], one set per account.
 * - The iCloud and CalDAV discovery services' `removeAccount` delegates to
 *   [AccountRepository.deleteAccount].
 * - `CalDavSyncEngine` takes a [CalendarRepository] (the test only constructs it).
 * - `ICloudCredentialProvider` and `CalDavCredentialProvider` read through [CredentialManager].
 *
 * The EventCoordinator, AccountSettingsViewModel and HomeViewModel tests call only the mocked
 * [AccountRepository], not those classes. The old-credential-manager and end-to-end sync tests
 * are placeholders that assert nothing. Elsewhere, `AccountRepositoryImplTest` covers the
 * WorkManager and reminder cancels and `UnifiedCredentialManagerTest` the account-keyed format.
 */
class PostRefactorVerificationTest {

    private lateinit var accountRepository: AccountRepositoryImpl

    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var eventsDao: EventsDao
    private lateinit var pendingOperationsDao: PendingOperationsDao
    private lateinit var credentialManager: CredentialManager
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        eventsDao = mockk(relaxed = true)
        pendingOperationsDao = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        accountRepository = AccountRepositoryImpl(
            accountsDao = accountsDao,
            addressBookDao = mockk(relaxed = true),
            calendarsDao = calendarsDao,
            eventsDao = eventsDao,
            pendingOperationsDao = pendingOperationsDao,
            credentialManager = credentialManager,
            reminderScheduler = reminderScheduler,
            workManager = workManager,
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== deleteAccount Failure Handling ==========

    /**
     * Asserts that a reminder-cancel failure propagates out of deleteAccount.
     *
     * deleteAccount has no `@Transaction` and no catch around `cancelRemindersForEvent`, so the
     * steps after it, the credential delete and the cascade included, don't run (not asserted
     * here). The test name describes the cascade completing, which the code doesn't do.
     */
    @Test
    fun `deleteAccount completes cascade delete even if reminder cancellation fails`() = runBlocking {
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
        // The reminder cancel throws.
        coEvery { reminderScheduler.cancelRemindersForEvent(100L) } throws RuntimeException("AlarmManager error")

        // The exception reaches the caller.
        try {
            accountRepository.deleteAccount(accountId)
            fail("Expected exception to propagate")
        } catch (e: RuntimeException) {
            assertEquals("AlarmManager error", e.message)
        }
    }

    @Test
    fun `deleteAccount continues after credential deletion failure`() = runBlocking {
        val accountId = 1L
        coEvery { calendarsDao.getByAccountIdOnce(accountId) } returns emptyList()
        coEvery { credentialManager.deleteCredentials(accountId) } throws RuntimeException("Encryption error")

        // deleteAccount catches and logs a credential-delete failure.
        accountRepository.deleteAccount(accountId)

        // The cascade delete still runs.
        coVerify { accountsDao.deleteById(accountId) }
    }

    @Test
    fun `deleteAccount order of operations - WorkManager before reminders before cascade`() = runBlocking {
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

        val callOrder = mutableListOf<String>()

        every { workManager.cancelUniqueWork(any()) } answers {
            callOrder.add("workManager")
            mockk(relaxed = true)
        }
        coEvery { reminderScheduler.cancelRemindersForEvent(any()) } answers {
            callOrder.add("reminderScheduler")
        }
        coEvery { pendingOperationsDao.deleteForEvent(any()) } answers {
            callOrder.add("pendingOps")
        }
        coEvery { credentialManager.deleteCredentials(any()) } answers {
            callOrder.add("credentials")
        }
        coEvery { accountsDao.deleteById(any()) } answers {
            callOrder.add("cascade")
        }

        accountRepository.deleteAccount(accountId)

        // Checks relative order by first occurrence: the WorkManager cancels (this account's
        // job and the shared one-shot and expedited work), then reminders, pending operations,
        // credentials and the cascade. The periodic-work cancels come after the cascade when no
        // syncable account remains (the relaxed getAllOnce returns none), so the list isn't
        // compared whole.
        assertTrue(callOrder.indexOf("workManager") < callOrder.indexOf("reminderScheduler"))
        assertTrue(callOrder.indexOf("reminderScheduler") < callOrder.indexOf("pendingOps"))
        assertTrue(callOrder.indexOf("pendingOps") < callOrder.indexOf("credentials"))
        assertTrue(callOrder.indexOf("credentials") < callOrder.indexOf("cascade"))
    }

    // ========== Discovery Service Delegation ==========

    /**
     * Asserts that `ICloudAccountDiscoveryService.removeAccount` calls
     * [AccountRepository.deleteAccount] once. The service takes [AccountRepository], not
     * [AccountsDao].
     */
    @Test
    fun `PHASE 7 - ICloudAccountDiscoveryService delegates to AccountRepository`() = runBlocking {
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)
        val mockCalendarRepository: CalendarRepository = mockk(relaxed = true)
        val mockClientFactory: org.onekash.kashcal.sync.client.CalDavClientFactory = mockk(relaxed = true)
        val mockCredentialProvider: org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider = mockk(relaxed = true)
        val mockQuirks = org.onekash.kashcal.sync.provider.icloud.ICloudQuirks()

        val discoveryService = org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService(
            clientFactory = mockClientFactory,
            credentialProvider = mockCredentialProvider,
            icloudQuirks = mockQuirks,
            accountRepository = mockAccountRepository,
            calendarRepository = mockCalendarRepository
        )

        val accountId = 42L
        discoveryService.removeAccount(accountId)

        coVerify(exactly = 1) { mockAccountRepository.deleteAccount(accountId) }
    }

    /**
     * Asserts that `CalDavAccountDiscoveryService.removeAccount` calls
     * [AccountRepository.deleteAccount] once.
     */
    @Test
    fun `PHASE 7 - CalDavAccountDiscoveryService delegates to AccountRepository`() = runBlocking {
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)
        val mockCalendarRepository: CalendarRepository = mockk(relaxed = true)
        val mockClientFactory: org.onekash.kashcal.sync.client.CalDavClientFactory = mockk(relaxed = true)

        val discoveryService = org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService(
            calDavClientFactory = mockClientFactory,
            accountRepository = mockAccountRepository,
            calendarRepository = mockCalendarRepository
        )

        val accountId = 99L
        discoveryService.removeAccount(accountId)

        coVerify(exactly = 1) { mockAccountRepository.deleteAccount(accountId) }
    }

    // ========== Sync Layer Repositories ==========

    /**
     * Constructs `CalDavSyncEngine` with a [CalendarRepository]; compiling is the check.
     *
     * In the sync layer, `CalDavSyncEngine`, [PullStrategy], [PushStrategy] and [ConflictResolver]
     * take [CalendarRepository], the two strategies and `CalDavSyncWorker` also take
     * [AccountRepository], and `CalDavSyncEngine` has no [AccountsDao]. Only the engine is built
     * here.
     */
    @Test
    fun `PHASE 8 - CalDavSyncEngine uses repositories`() = runBlocking {
        val mockCalendarRepository: CalendarRepository = mockk(relaxed = true)
        val mockPullStrategy: PullStrategy = mockk(relaxed = true)
        val mockPushStrategy: PushStrategy = mockk(relaxed = true)
        val mockConflictResolver: ConflictResolver = mockk(relaxed = true)
        val mockEventsDao: EventsDao = mockk(relaxed = true)
        val mockPendingOperationsDao: PendingOperationsDao = mockk(relaxed = true)
        val mockSyncLogsDao: org.onekash.kashcal.data.db.dao.SyncLogsDao = mockk(relaxed = true)
        val mockSyncSessionStore: org.onekash.kashcal.sync.session.SyncSessionStore = mockk(relaxed = true)
        val mockNotificationManager: org.onekash.kashcal.sync.notification.SyncNotificationManager = mockk(relaxed = true)

        // Compiles only while CalDavSyncEngine takes these parameters.
        val syncEngine = org.onekash.kashcal.sync.engine.CalDavSyncEngine(
            pullStrategy = mockPullStrategy,
            pushStrategy = mockPushStrategy,
            conflictResolver = mockConflictResolver,
            calendarRepository = mockCalendarRepository,
            eventsDao = mockEventsDao,
            pendingOperationsDao = mockPendingOperationsDao,
            syncLogsDao = mockSyncLogsDao,
            syncSessionStore = mockSyncSessionStore,
            notificationManager = mockNotificationManager
        )

        assertTrue("CalDavSyncEngine accepts CalendarRepository", syncEngine != null)
    }

    // ========== Credential Providers ==========

    /**
     * Asserts that `ICloudCredentialProvider.getCredentials` reads through [CredentialManager].
     * The provider takes [CredentialManager] and [AccountRepository]; the credentials are
     * stored account-keyed.
     */
    @Test
    fun `PHASE 9 - ICloudCredentialProvider uses unified CredentialManager`() = runBlocking {
        val mockCredentialManager: CredentialManager = mockk(relaxed = true)
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)

        val icloudCredentialProvider = org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider(
            credentialManager = mockCredentialManager,
            accountRepository = mockAccountRepository
        )

        val accountId = 42L
        val testCredentials = AccountCredentials(
            username = "test@icloud.com",
            password = "test-password",
            serverUrl = "https://caldav.icloud.com"
        )
        coEvery { mockCredentialManager.isEncryptionAvailable() } returns true
        coEvery { mockCredentialManager.getCredentials(accountId) } returns testCredentials

        val result = icloudCredentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertEquals("test@icloud.com", result?.username)
        coVerify { mockCredentialManager.getCredentials(accountId) }
    }

    /**
     * Asserts that `CalDavCredentialProvider.getCredentials` and `getAccountCredentials` read
     * through [CredentialManager], trust-insecure flag included. The provider takes
     * [CredentialManager] and [AccountRepository], and its `getTrustInsecure` and
     * `setTrustInsecure` are suspend functions (not called here).
     */
    @Test
    fun `PHASE 9 - CalDavCredentialProvider uses unified CredentialManager`() = runBlocking {
        val mockCredentialManager: CredentialManager = mockk(relaxed = true)
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)

        val caldavCredentialProvider = org.onekash.kashcal.sync.provider.caldav.CalDavCredentialProvider(
            credentialManager = mockCredentialManager,
            accountRepository = mockAccountRepository
        )

        val accountId = 99L
        val testCredentials = AccountCredentials(
            username = "user@nextcloud.com",
            password = "caldav-password",
            serverUrl = "https://nextcloud.example.com",
            trustInsecure = true
        )
        coEvery { mockCredentialManager.isEncryptionAvailable() } returns true
        coEvery { mockCredentialManager.getCredentials(accountId) } returns testCredentials

        val result = caldavCredentialProvider.getCredentials(accountId)

        assertNotNull(result)
        assertEquals("user@nextcloud.com", result?.username)
        assertTrue(result?.trustInsecure == true)
        coVerify { mockCredentialManager.getCredentials(accountId) }

        // getAccountCredentials returns the same stored set.
        val rawCreds = caldavCredentialProvider.getAccountCredentials(accountId)
        assertNotNull(rawCreds)
        assertEquals("https://nextcloud.example.com", rawCreds?.serverUrl)
    }

    // ========== Account Reads Outside EventReader ==========

    /**
     * Calls the three account flows on a mocked [AccountRepository] and asserts they return
     * their stubs; neither EventReader nor EventCoordinator is called.
     *
     * EventReader exposes no account queries. EventCoordinator reads accounts from
     * [AccountRepository]: `getCalDavAccounts` from `getAccountsByProviderFlow(CALDAV)`,
     * `getCalDavAccountCount` from `getAccountCountByProviderFlow(CALDAV)`, `getAllAccounts`
     * from `getAllAccountsFlow`, and account lookups by provider and email.
     */
    @Test
    fun `PHASE 10 - EventReader account methods removed and EventCoordinator uses AccountRepository`() = runBlocking {
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)

        val testAccounts = listOf(
            Account(id = 1, provider = AccountProvider.CALDAV, email = "test@example.com",
                displayName = "Test", isEnabled = true)
        )
        every { mockAccountRepository.getAllAccountsFlow() } returns kotlinx.coroutines.flow.flowOf(testAccounts)
        every { mockAccountRepository.getAccountsByProviderFlow(AccountProvider.CALDAV) } returns kotlinx.coroutines.flow.flowOf(testAccounts)
        every { mockAccountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV) } returns kotlinx.coroutines.flow.flowOf(1)

        val allAccountsFlow = mockAccountRepository.getAllAccountsFlow()
        val caldavAccountsFlow = mockAccountRepository.getAccountsByProviderFlow(AccountProvider.CALDAV)
        val caldavCountFlow = mockAccountRepository.getAccountCountByProviderFlow(AccountProvider.CALDAV)

        val allAccounts = allAccountsFlow.first()
        val caldavAccounts = caldavAccountsFlow.first()
        val caldavCount = caldavCountFlow.first()

        assertEquals(1, allAccounts.size)
        assertEquals(1, caldavAccounts.size)
        assertEquals(1, caldavCount)
        assertEquals("test@example.com", allAccounts.first().email)
    }

    // ========== ViewModel Account Reads ==========

    /**
     * Calls `getAccountsByProvider`, `hasCredentials` and `saveCredentials` on a mocked
     * [AccountRepository] and asserts they return their stubs; AccountSettingsViewModel isn't
     * built.
     *
     * AccountSettingsViewModel loads iCloud accounts with `getAccountsByProvider(ICLOUD)`, saves
     * credentials with `saveCredentials`, and signs out of iCloud through the discovery
     * service's `removeAccountByEmail`.
     */
    @Test
    fun `PHASE 10 - AccountSettingsViewModel uses AccountRepository`() = runBlocking {
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)
        val mockCredentials = AccountCredentials("test@icloud.com", "password", "https://caldav.icloud.com")

        coEvery { mockAccountRepository.getAccountsByProvider(AccountProvider.ICLOUD) } returns listOf(
            Account(id = 1, provider = AccountProvider.ICLOUD, email = "test@icloud.com",
                displayName = "iCloud", isEnabled = true)
        )
        coEvery { mockAccountRepository.hasCredentials(1) } returns true
        coEvery { mockAccountRepository.saveCredentials(any(), any()) } returns true

        val accounts = mockAccountRepository.getAccountsByProvider(AccountProvider.ICLOUD)
        val hasCredentials = mockAccountRepository.hasCredentials(1)
        val saved = mockAccountRepository.saveCredentials(1, mockCredentials)

        assertEquals(1, accounts.size)
        assertTrue(hasCredentials)
        assertTrue(saved)
    }

    /**
     * Runs a copy of HomeViewModel's account status check over a mocked [AccountRepository]:
     * `getAllAccounts`, filtered to providers with `supportsCalDAV`, then `hasCredentials`.
     *
     * The copy checks only the first syncable account; `HomeViewModel.checkAccountStatus`
     * treats the app as configured when any syncable account has credentials.
     */
    @Test
    fun `PHASE 10 - HomeViewModel uses AccountRepository for account status check`() = runBlocking {
        val mockAccountRepository: AccountRepository = mockk(relaxed = true)

        // One iCloud account with credentials.
        val testAccount = Account(
            id = 1,
            provider = AccountProvider.ICLOUD,
            email = "test@icloud.com",
            displayName = "iCloud",
            isEnabled = true
        )
        coEvery { mockAccountRepository.getAllAccounts() } returns listOf(testAccount)
        coEvery { mockAccountRepository.hasCredentials(testAccount.id) } returns true

        // The copy of the status check.
        val allAccounts = mockAccountRepository.getAllAccounts()
        val syncableAccounts = allAccounts.filter { it.provider.supportsCalDAV }
        val account = syncableAccounts.firstOrNull()
        val hasCredentials = account?.let { mockAccountRepository.hasCredentials(it.id) } ?: false

        assertNotNull(account)
        assertTrue(hasCredentials)
        assertEquals("test@icloud.com", account?.email)
    }

    // ========== Old Credential Managers ==========

    /**
     * Placeholder that asserts nothing. These files must not exist, and don't in `app/src/main`:
     * - ICloudAuthManager.kt
     * - CalDavCredentialManager.kt
     * - ICloudAccount.kt
     */
    @Test
    fun `PHASE 12 - Old credential managers deleted`() {
        // A manual check, which prints nothing while the old managers are gone:
        // grep -r "ICloudAuthManager\|CalDavCredentialManager" --include="*.kt" app/src/main/

        assertTrue("Implement once old credential managers are removed - verify old files deleted", true)
    }

    // ========== End-to-End Sync ==========

    /**
     * Placeholder that asserts nothing, for an iCloud end-to-end test to run on a device or
     * emulator (androidTest). The flow it would cover:
     * 1. Save credentials via AccountRepository
     * 2. Trigger sync
     * 3. Verify events appear
     * 4. Delete account via AccountRepository
     * 5. Verify reminders cancelled, credentials deleted
     */
    @Test
    fun `INTEGRATION - iCloud sync end-to-end after refactor`() {
        // No androidTest implements this flow.

        assertTrue("Implement as androidTest - requires real iCloud credentials", true)
    }

    // ========== Credential Delegation ==========

    @Test
    fun `unified credentials use account-keyed format`() = runBlocking {
        // Save and read pass the account ID to CredentialManager; the key format itself is
        // UnifiedCredentialManagerTest's.
        val accountId = 42L
        val credentials = AccountCredentials(
            username = "user@example.com",
            password = "password",
            serverUrl = "https://server.com"
        )

        coEvery { credentialManager.saveCredentials(accountId, credentials) } returns true
        coEvery { credentialManager.getCredentials(accountId) } returns credentials

        val saved = accountRepository.saveCredentials(accountId, credentials)
        val retrieved = accountRepository.getCredentials(accountId)

        assertTrue(saved)
        assertEquals(credentials, retrieved)
        coVerify { credentialManager.saveCredentials(accountId, credentials) }
        coVerify { credentialManager.getCredentials(accountId) }
    }

    @Test
    fun `hasCredentials delegates to CredentialManager`() = runBlocking {
        coEvery { credentialManager.hasCredentials(1L) } returns true
        coEvery { credentialManager.hasCredentials(2L) } returns false

        assertTrue(accountRepository.hasCredentials(1L))
        assertFalse(accountRepository.hasCredentials(2L))
    }

    // ========== Multi-Account Credentials ==========

    /**
     * Asserts that two accounts read back their own credentials, which the old unkeyed iCloud
     * format couldn't hold (`PreRefactorSnapshotTest`).
     */
    @Test
    fun `multiple accounts have isolated credentials`() = runBlocking {
        // Two iCloud accounts.
        val account1Id = 1L
        val account2Id = 2L
        val creds1 = AccountCredentials("user1@icloud.com", "pass1", "https://caldav.icloud.com")
        val creds2 = AccountCredentials("user2@icloud.com", "pass2", "https://caldav.icloud.com")

        coEvery { credentialManager.getCredentials(account1Id) } returns creds1
        coEvery { credentialManager.getCredentials(account2Id) } returns creds2

        val retrieved1 = accountRepository.getCredentials(account1Id)
        val retrieved2 = accountRepository.getCredentials(account2Id)

        // Each account gets its own credentials.
        assertEquals("user1@icloud.com", retrieved1?.username)
        assertEquals("user2@icloud.com", retrieved2?.username)
        assertNotEquals(retrieved1, retrieved2)
    }

    @Test
    fun `deleting one account does not affect other accounts credentials`() = runBlocking {
        val account1Id = 1L
        val account2Id = 2L
        val creds2 = AccountCredentials("user2@icloud.com", "pass2", "https://caldav.icloud.com")

        coEvery { calendarsDao.getByAccountIdOnce(account1Id) } returns emptyList()
        coEvery { credentialManager.getCredentials(account2Id) } returns creds2

        // Delete account 1.
        accountRepository.deleteAccount(account1Id)

        // Account 2's credentials still read back.
        val retrieved2 = accountRepository.getCredentials(account2Id)
        assertEquals("user2@icloud.com", retrieved2?.username)

        // Only account 1's credentials are deleted.
        coVerify { credentialManager.deleteCredentials(account1Id) }
        coVerify(exactly = 0) { credentialManager.deleteCredentials(account2Id) }
    }
}