package org.onekash.kashcal.sync.contacts

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavClientFactory
import org.onekash.kashcal.sync.carddav.CardDavHostResolver
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ICloudCardDavQuirks
import org.onekash.kashcal.sync.carddav.ZohoCardDavQuirks
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.ui.permission.FakePermissionChecker

/**
 * Unit tests for [ContactSyncWorker].
 *
 * doWork() runs [ContactPullStrategy] for every contact-sync-enabled,
 * CardDAV-capable account, assembling that account's CardDAV client from the
 * [ProviderRegistry] routing (quirks + credentials).
 *
 * The permission story is the load-bearing part. WRITE_CONTACTS revocation
 * cannot surface as a [SecurityException] escaping the strategy —
 * [AndroidContactsProviderRepository] catches it and returns `Result.failure`,
 * and [ContactPullStrategy] swallows that into its counts and still returns
 * `Success`. So a worker-level try/catch on SecurityException would be dead
 * code. The deterministic, production-real signal is a **pre-flight**
 * WRITE_CONTACTS check: when the permission is absent the worker syncs nothing
 * and raises an app-global re-grant flag for a settings affordance.
 */
class ContactSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var params: WorkerParameters
    private lateinit var accountRepository: AccountRepository
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var cardDavClientFactory: CardDavClientFactory
    private lateinit var cardDavHostResolver: CardDavHostResolver
    private lateinit var contactPullStrategy: ContactPullStrategy
    private lateinit var permissionChecker: FakePermissionChecker
    private lateinit var dataStore: KashCalDataStore
    private lateinit var client: CardDavClient
    private lateinit var credentialProvider: CredentialProvider

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        params = mockk(relaxed = true)
        // Default to unscoped input (sweep all logins). A relaxed inputData would
        // hand back a child mock whose getLong returns 0L — read as "scope to
        // account 0" and silently drop every real account. The scoped tests
        // override this per-test.
        every { params.inputData } returns Data.EMPTY
        // Data-bearing collaborators: stub explicitly, never relaxed.
        accountRepository = mockk()
        providerRegistry = mockk()
        cardDavClientFactory = mockk()
        cardDavHostResolver = mockk()
        contactPullStrategy = mockk()
        credentialProvider = mockk()
        client = mockk(relaxed = true)
        // Side-effect / Unit-returning collaborator: relaxed is safe.
        dataStore = mockk(relaxed = true)
        // Real fake, WRITE_CONTACTS granted by default.
        permissionChecker = FakePermissionChecker()

        every { cardDavClientFactory.createClient(any(), any()) } returns client
        every { providerRegistry.getCredentialProvider(any()) } returns credentialProvider
        every { providerRegistry.getCardDavQuirksForAccount(any()) } returns
            DefaultCardDavQuirks(serverBaseUrl = "https://contacts.example.test")
        coEvery { credentialProvider.getCredentials(any()) } returns
            Credentials(username = "user", password = "pass")
        // Default: no SRV hit, so the resolver returns the fallback base URL it was
        // handed (quirks.baseUrl) — the pre-DNS-discovery behavior. A dedicated test
        // overrides this to prove a discovered host flows through to the strategy.
        coEvery { cardDavHostResolver.resolveBaseUrl(any(), any()) } answers { secondArg() }
        coEvery { contactPullStrategy.sync(any(), any(), any()) } returns
            ContactPullResult.Success(inserted = 0, replaced = 0, skipped = 0, deleted = 0, booksFailed = 0)
    }

    private fun createWorker(
        runAttemptCount: Int = 0,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): ContactSyncWorker {
        every { params.runAttemptCount } returns runAttemptCount
        return ContactSyncWorker(
            context,
            params,
            accountRepository,
            providerRegistry,
            cardDavClientFactory,
            cardDavHostResolver,
            contactPullStrategy,
            permissionChecker,
            dataStore,
            dispatcher,
        )
    }

    private fun account(
        id: Long,
        provider: AccountProvider,
        contactSyncEnabled: Boolean,
        homeSetUrl: String? = "https://caldav.example.test/123/calendars/",
    ) = Account(
        id = id,
        provider = provider,
        email = "user$id@example.test",
        homeSetUrl = homeSetUrl,
        contactSyncEnabled = contactSyncEnabled,
    )

    // ========== Runs the strategy per enabled account ==========

    @Test
    fun `doWork runs ContactPullStrategy for each contact-sync-enabled account`() = runTest {
        val a = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val b = account(2, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a, b)

        val result = createWorker().doWork()

        assertTrue("Should succeed", result is Result.Success)
        coVerify(exactly = 1) { contactPullStrategy.sync(a, any(), any()) }
        coVerify(exactly = 1) { contactPullStrategy.sync(b, any(), any()) }
    }

    @Test
    fun `doWork skips accounts with contact sync disabled`() = runTest {
        val enabled = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val disabled = account(2, AccountProvider.ICLOUD, contactSyncEnabled = false)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(enabled, disabled)

        createWorker().doWork()

        coVerify(exactly = 1) { contactPullStrategy.sync(enabled, any(), any()) }
        coVerify(exactly = 0) { contactPullStrategy.sync(disabled, any(), any()) }
    }

    @Test
    fun `doWork skips non-CardDAV-capable accounts even when flag set`() = runTest {
        // LOCAL cannot support CardDAV; the flag should never have been set, but
        // the worker must defend against it rather than assemble a bogus client.
        val local = account(1, AccountProvider.LOCAL, contactSyncEnabled = true, homeSetUrl = null)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(local)

        val result = createWorker().doWork()

        assertTrue(result is Result.Success)
        coVerify(exactly = 0) { contactPullStrategy.sync(any(), any(), any()) }
    }

    @Test
    fun `doWork scoped to one account syncs only that account`() = runTest {
        // A per-account "Sync now" carries the account id in input data; the sweep
        // must pull only that login, not every other contact-sync account.
        val a = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val b = account(2, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a, b)
        every { params.inputData } returns
            ContactSyncWorker.createScopedInput(accountId = 2L)

        val result = createWorker().doWork()

        assertTrue(result is Result.Success)
        coVerify(exactly = 0) { contactPullStrategy.sync(a, any(), any()) }
        coVerify(exactly = 1) { contactPullStrategy.sync(b, any(), any()) }
    }

    @Test
    fun `doWork with unscoped input sweeps every contact-sync account`() = runTest {
        // Absent input data (periodic / enable-path one-shot) sweeps all logins.
        val a = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val b = account(2, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a, b)
        every { params.inputData } returns Data.EMPTY

        createWorker().doWork()

        coVerify(exactly = 1) { contactPullStrategy.sync(a, any(), any()) }
        coVerify(exactly = 1) { contactPullStrategy.sync(b, any(), any()) }
    }

    @Test
    fun `doWork succeeds when no accounts have contact sync enabled`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = false))

        val result = createWorker().doWork()

        assertTrue(result is Result.Success)
        coVerify(exactly = 0) { contactPullStrategy.sync(any(), any(), any()) }
    }

    // ========== RFC 6764 host discovery feeds the strategy's seed URL ==========

    @Test
    fun `doWork feeds the resolver-discovered base URL into the strategy`() = runTest {
        val a = account(1, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a)
        // The resolver discovered a real host via SRV; the strategy must sync against
        // THAT, not the raw quirks.baseUrl fallback.
        coEvery { cardDavHostResolver.resolveBaseUrl(any(), any()) } returns
            "https://dav.discovered.test/carddav/"

        createWorker().doWork()

        coVerify(exactly = 1) {
            contactPullStrategy.sync(a, "https://dav.discovered.test/carddav/", any())
        }
    }

    @Test
    fun `doWork skips SRV discovery for iCloud and uses its fixed bootstrap host`() = runTest {
        // iCloud's contacts host is a fixed bootstrap (contacts.icloud.com) unrelated
        // to the Apple ID's email domain. Running SRV on that domain could let a
        // custom-domain Apple ID publish a same-registrable-domain _carddavs record
        // and silently redirect iCloud contact sync — so discovery must be skipped and
        // the quirks bootstrap host used verbatim. The skip is driven by the quirks'
        // own discoverHostViaDns flag, not the account provider, so a pinned-host
        // provider is authoritative about whether its host is discoverable.
        val icloud = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(icloud)
        every { providerRegistry.getCardDavQuirksForAccount(any()) } returns ICloudCardDavQuirks()

        createWorker().doWork()

        coVerify(exactly = 0) { cardDavHostResolver.resolveBaseUrl(any(), any()) }
        coVerify(exactly = 1) {
            contactPullStrategy.sync(icloud, "https://contacts.icloud.com", any())
        }
    }

    @Test
    fun `doWork skips SRV discovery for Zoho and uses its pinned contacts host`() = runTest {
        // Zoho is a generic CALDAV account, but its contacts host is pinned
        // (contacts.zoho.com) and unrelated to the login email domain — which can be
        // a Gmail-backed or custom address. Discovering from that domain would send
        // SRV to the wrong domain entirely, so the pinned-host quirks must suppress
        // discovery exactly as iCloud does.
        val zoho = account(1, AccountProvider.CALDAV, contactSyncEnabled = true,
            homeSetUrl = "https://calendar.zoho.com/caldav/123/calendars/")
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(zoho)
        every { providerRegistry.getCardDavQuirksForAccount(any()) } returns ZohoCardDavQuirks()

        createWorker().doWork()

        coVerify(exactly = 0) { cardDavHostResolver.resolveBaseUrl(any(), any()) }
        coVerify(exactly = 1) {
            contactPullStrategy.sync(zoho, "https://contacts.zoho.com", any())
        }
    }

    @Test
    fun `doWork resolves using the account email domain and the quirks base as fallback`() = runTest {
        val a = account(1, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a)
        every { providerRegistry.getCardDavQuirksForAccount(any()) } returns
            DefaultCardDavQuirks(serverBaseUrl = "https://home.example.test")

        createWorker().doWork()

        // account(1).email is "user1@example.test" -> domain "example.test"; the
        // fallback handed to the resolver is the quirks base URL.
        coVerify(exactly = 1) {
            cardDavHostResolver.resolveBaseUrl("example.test", "https://home.example.test")
        }
    }

    // ========== WRITE_CONTACTS revoked: pre-flight skip + re-grant flag ==========

    @Test
    fun `doWork does not sync and flags re-grant when WRITE_CONTACTS is revoked`() = runTest {
        permissionChecker.writeContacts = false
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))

        val result = createWorker().doWork()

        // No crash, no retry-loop: the run no-ops gracefully.
        assertTrue("Must not retry on missing permission: $result", result != Result.retry())
        coVerify(exactly = 0) { contactPullStrategy.sync(any(), any(), any()) }
        // Account is flagged for a re-grant affordance in settings.
        coVerify { dataStore.setContactSyncPermissionNeeded(true) }
    }

    @Test
    fun `doWork clears the re-grant flag when WRITE_CONTACTS is granted`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))

        createWorker().doWork()

        // A prior denial that has since been re-granted must not leave a stale banner.
        coVerify { dataStore.setContactSyncPermissionNeeded(false) }
    }

    @Test
    fun `doWork does not touch the re-grant flag when no account wants contact sync`() = runTest {
        permissionChecker.writeContacts = false
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = false))

        val result = createWorker().doWork()

        assertTrue(result is Result.Success)
        coVerify(exactly = 0) { dataStore.setContactSyncPermissionNeeded(any()) }
    }

    // ========== Per-account resilience ==========

    @Test
    fun `doWork continues to next account when one account has no credentials`() = runTest {
        val a = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val b = account(2, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a, b)
        coEvery { credentialProvider.getCredentials(1) } returns null

        val result = createWorker().doWork()

        assertTrue(result is Result.Success)
        coVerify(exactly = 0) { contactPullStrategy.sync(a, any(), any()) }
        coVerify(exactly = 1) { contactPullStrategy.sync(b, any(), any()) }
    }

    @Test
    fun `doWork continues to next account when one account strategy throws`() = runTest {
        val a = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        val b = account(2, AccountProvider.CALDAV, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(a, b)
        coEvery { contactPullStrategy.sync(a, any(), any()) } throws RuntimeException("boom")

        val result = createWorker(runAttemptCount = 0).doWork()

        // One account failing must not abort the sweep or crash the worker; the
        // remaining accounts still sync. An unexpected failure is transient-ish, so
        // it asks for a bounded retry rather than reporting a clean success.
        coVerify(exactly = 1) { contactPullStrategy.sync(b, any(), any()) }
        assertEquals("An unexpected account failure should retry while attempts remain", Result.retry(), result)
    }

    // ========== Retry semantics: consume the strategy's retryable signal ==========

    @Test
    fun `doWork retries when the strategy reports a retryable error and attempts remain`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))
        coEvery { contactPullStrategy.sync(any(), any(), any()) } returns
            ContactPullResult.Error(code = 503, message = "transient", isRetryable = true)

        val result = createWorker(runAttemptCount = 0).doWork()

        assertEquals("A retryable strategy error must drive WorkManager backoff", Result.retry(), result)
    }

    @Test
    fun `doWork fails without retry once a retryable error exhausts the attempt budget`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))
        coEvery { contactPullStrategy.sync(any(), any(), any()) } returns
            ContactPullResult.Error(code = 503, message = "transient", isRetryable = true)

        val result = createWorker(runAttemptCount = 3).doWork()

        // At the retry cap, stop looping and report terminal failure for this run.
        assertTrue("Exhausted retries must terminate, not loop forever", result is Result.Failure)
    }

    @Test
    fun `doWork fails without retry on a non-retryable error`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))
        // e.g. bad credentials / 401: retrying would just hammer the server, so
        // report terminal failure (never retry) — but not a clean success either.
        // On a periodic worker, failure() does not cancel future scheduled runs.
        coEvery { contactPullStrategy.sync(any(), any(), any()) } returns
            ContactPullResult.Error(code = 401, message = "auth", isRetryable = false)

        val result = createWorker(runAttemptCount = 0).doWork()

        assertTrue("A non-retryable error must be terminal, not spin backoff", result is Result.Failure)
        assertTrue("A non-retryable error must not retry", result != Result.retry())
    }

    @Test
    fun `doWork rethrows CancellationException rather than swallowing it as an account failure`() = runTest {
        coEvery { accountRepository.getEnabledAccounts() } returns
            listOf(account(1, AccountProvider.ICLOUD, contactSyncEnabled = true))
        coEvery { contactPullStrategy.sync(any(), any(), any()) } throws
            CancellationException("worker stopped")

        var propagated = false
        try {
            createWorker().doWork()
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("Cooperative cancellation must propagate, not be caught as a per-account error", propagated)
    }

    // ========== Concurrency: periodic + one-shot must never sweep together ==========

    @Test
    fun `concurrent runs are serialized so contacts are never swept in parallel`() = runTest {
        // The periodic job and a user-initiated one-shot are separate WorkManager
        // unique-work names, so WorkManager can run them at once. Both sweep the
        // same accounts with a non-transactional delete-then-insert replace, so an
        // overlap can double-insert a contact. A process-wide lock must serialize
        // them: while one run is inside the sweep, a second must wait, not enter.
        val acct = account(1, AccountProvider.ICLOUD, contactSyncEnabled = true)
        coEvery { accountRepository.getEnabledAccounts() } returns listOf(acct)

        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        coEvery { contactPullStrategy.sync(any(), any(), any()) } coAnswers {
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
            if (!firstEntered.isCompleted) firstEntered.complete(Unit)
            release.await()
            inFlight--
            ContactPullResult.Success(inserted = 0, replaced = 0, skipped = 0, deleted = 0, booksFailed = 0)
        }

        // Run both workers on the test scheduler so ordering is deterministic:
        // launch run1, drain until it's parked inside the strategy holding the lock,
        // then launch run2 and drain again to prove it cannot enter the sweep.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val run1 = launch { createWorker(dispatcher = dispatcher).doWork() }
        testScheduler.runCurrent()      // run1 enters the strategy, holds the lock
        firstEntered.await()
        val run2 = launch { createWorker(dispatcher = dispatcher).doWork() }
        testScheduler.runCurrent()      // let run2 attempt to acquire the lock

        assertEquals("second run must block on the in-flight sweep, not enter it", 1, inFlight)

        release.complete(Unit)
        run1.join()
        run2.join()

        assertEquals("the two runs must never have overlapped", 1, maxInFlight)
    }

    // ========== Constants ==========

    @Test
    fun `SYNC_WORK constant is stable`() {
        assertEquals("contact_dav_sync", ContactSyncWorker.SYNC_WORK)
    }
}
