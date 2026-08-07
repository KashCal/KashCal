package org.onekash.kashcal.sync.contacts

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavClientFactory
import org.onekash.kashcal.sync.carddav.CardDavHostResolver
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.ui.permission.PermissionChecker

/**
 * Background worker that mirrors CardDAV contact changes/deletions from the
 * server onto the device for every contact-sync-enabled account.
 *
 * It assembles each account's CardDAV client from the [ProviderRegistry] routing
 * (quirks + credentials — iCloud entry point vs the account's own home host for
 * generic CardDAV), then hands off to [ContactPullStrategy].
 *
 * Permission handling is the load-bearing part. A revoked WRITE_CONTACTS never
 * surfaces as an exception the worker can catch: [AndroidContactsProviderRepository]
 * swallows the provider [SecurityException] into a failed result, and
 * [ContactPullStrategy] folds that into its counts and still returns Success. So
 * the deterministic signal is a **pre-flight** WRITE_CONTACTS check. When the
 * permission is absent the worker syncs nothing and raises an app-global re-grant
 * flag so settings can surface an inline affordance; when present it clears any
 * stale flag. The flag is app-global because WRITE_CONTACTS is a single app-wide
 * runtime permission, not per-account.
 *
 * Scheduled through the shared [org.onekash.kashcal.sync.scheduler.SyncScheduler]
 * at the same interval as calendar sync — not a second scheduling mechanism.
 */
@HiltWorker
class ContactSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val providerRegistry: ProviderRegistry,
    private val cardDavClientFactory: CardDavClientFactory,
    private val cardDavHostResolver: CardDavHostResolver,
    private val contactPullStrategy: ContactPullStrategy,
    private val permissionChecker: PermissionChecker,
    private val dataStore: KashCalDataStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(context, params) {

    // Serialize every contact sweep across the whole process. The periodic job and
    // a user-initiated one-shot run under different WorkManager unique-work names,
    // so WorkManager can execute both at once; both sweep the same accounts with a
    // non-transactional delete-then-insert replace and no SOURCE_ID uniqueness
    // constraint, so an overlap can double-insert a contact. WorkManager creates a
    // fresh worker instance per run, so the lock must be process-static (companion),
    // not per-instance. withLock (not tryLock) means a second run waits and then
    // executes: it re-reads the account list, so a just-enabled account is never
    // dropped, and the read-only pull is idempotent, so re-running is safe.
    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        syncLock.withLock { runSweep() }
    }

    private suspend fun runSweep(): Result {
        // A user-initiated "Sync now" from one account's sheet scopes the sweep to
        // that account via input data; the periodic/global one-shot leaves it unset
        // (-1) and sweeps every contact-sync login. Scoping avoids re-pulling every
        // other account's address books just because the user refreshed one.
        val scopedAccountId = inputData.getLong(KEY_ACCOUNT_ID, UNSCOPED_ACCOUNT_ID)
        val accounts = accountRepository.getEnabledAccounts()
            .filter { it.contactSyncEnabled && it.provider.supportsCardDAV }
            .filter { scopedAccountId == UNSCOPED_ACCOUNT_ID || it.id == scopedAccountId }

        // Nothing to do — don't touch the re-grant flag, which belongs to the
        // contact-sync feature and shouldn't flip when the feature is unused.
        if (accounts.isEmpty()) {
            return Result.success()
        }

        // Pre-flight permission gate: a revoked WRITE_CONTACTS makes every
        // provider write silently fail downstream, so refuse to run and flag it
        // for a settings re-grant affordance instead of looping fruitless syncs.
        if (!permissionChecker.hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS revoked; skipping contact sync and flagging re-grant")
            dataStore.setContactSyncPermissionNeeded(true)
            return Result.success()
        }
        // Permission is present — clear any stale banner from a prior denial.
        dataStore.setContactSyncPermissionNeeded(false)

        // Track the worst outcome across the sweep: a retryable failure asks
        // WorkManager for a bounded backoff retry; a non-retryable one is terminal
        // for this run (retrying 401s just hammers the server). The read-only pull
        // is idempotent, so retrying accounts that already succeeded re-skips them.
        var sawRetryable = false
        var sawTerminalError = false

        for (account in accounts) {
            try {
                when (val outcome = syncAccount(account)) {
                    is ContactPullResult.Error ->
                        if (outcome.isRetryable) sawRetryable = true else sawTerminalError = true
                    else -> Unit
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation (worker stopped) must propagate, not be
                // logged as an account failure and have the loop keep issuing work.
                throw e
            } catch (e: Exception) {
                // One account's failure must not abort the sweep or crash the
                // worker; log, mark for retry, and move on to the next.
                Log.w(TAG, "Contact sync failed for account ${account.id}: ${e.message}")
                sawRetryable = true
            }
        }

        return when {
            sawRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
            sawRetryable || sawTerminalError -> Result.failure()
            else -> Result.success()
        }
    }

    /**
     * Sync one account, returning the strategy's [ContactPullResult] so [doWork]
     * can honor the retryable signal. Returns a benign [ContactPullResult.Success]
     * for accounts skipped before the strategy runs (no credential provider,
     * credentials, or resolvable home host) — a skip is not a failure to retry.
     */
    private suspend fun syncAccount(account: Account): ContactPullResult {
        val credentialProvider = providerRegistry.getCredentialProvider(account.provider)
        if (credentialProvider == null) {
            Log.w(TAG, "No credential provider for ${account.provider}; skipping account ${account.id}")
            return SKIPPED
        }
        val credentials = credentialProvider.getCredentials(account.id)
        if (credentials == null) {
            Log.w(TAG, "No credentials for account ${account.id}; skipping")
            return SKIPPED
        }

        val quirks = providerRegistry.getCardDavQuirksForAccount(account)
        if (quirks == null) {
            // A CardDAV-capable account with no resolvable home host (e.g. a generic
            // CardDAV account whose homeSetUrl was never discovered) would otherwise
            // start discovery from an empty URL and fail opaquely — skip and log.
            Log.w(TAG, "No CardDAV quirks/base URL for account ${account.id}; skipping")
            return SKIPPED
        }
        val client: CardDavClient = cardDavClientFactory.createClient(credentials, quirks)
        // RFC 6764 §6: for generic CardDAV accounts, discover the contacts host from
        // the account's email domain via DNS SRV/TXT, falling back to the configured
        // host (quirks.baseUrl) when no in-domain SRV record exists (self-hosted
        // without SRV) or the resolver is unreachable. Pinned-host providers (iCloud,
        // Zoho) skip discovery: their bootstrap host is known and unrelated to the
        // account email domain, so an email-domain SRV lookup could only misdirect
        // them. Whether the host is discoverable is the quirks' own decision, not the
        // account provider's — so a generic provider can still carry a pinned host.
        // The downstream well-known + principal walk in the pull strategy is unchanged
        // — it just starts from a better-discovered seed.
        val baseUrl = if (quirks.discoverHostViaDns) {
            cardDavHostResolver.resolveBaseUrl(domainOf(account.email), quirks.baseUrl)
        } else {
            quirks.baseUrl
        }
        return contactPullStrategy.sync(account, baseUrl, client)
    }

    /** The domain part of an email address (after the last '@'), or "" if none. */
    private fun domainOf(email: String): String = email.substringAfterLast('@', "")

    companion object {
        private const val TAG = "ContactSyncWorker"

        /** Unique work name for the periodic contact-sync job. */
        const val SYNC_WORK = "contact_dav_sync"

        /**
         * Input-data key carrying the single account a user-initiated "Sync now"
         * scopes the sweep to. Absent (or [UNSCOPED_ACCOUNT_ID]) means sweep every
         * contact-sync login, as the periodic and global one-shot runs do.
         */
        const val KEY_ACCOUNT_ID = "account_id"

        /** Sentinel for "no account scope" — sweep all contact-sync logins. */
        const val UNSCOPED_ACCOUNT_ID = -1L

        /** Build scoped input data for a per-account one-shot contact sync. */
        fun createScopedInput(accountId: Long): Data =
            Data.Builder().putLong(KEY_ACCOUNT_ID, accountId).build()

        /**
         * Process-wide guard so no two contact sweeps run at once. Periodic and
         * one-shot contact sync are separate WorkManager unique-work names, so they
         * can be dispatched concurrently; static (not per-instance) because
         * WorkManager builds a fresh worker per run. See [doWork].
         */
        private val syncLock = Mutex()

        /**
         * Bounded retry budget, matching the calendar sync worker. Past this
         * WorkManager attempt count a retryable failure becomes terminal rather
         * than backing off forever.
         */
        private const val MAX_RETRY_ATTEMPTS = 3

        /** A pre-strategy skip is not a failure — treat it as a benign no-op. */
        private val SKIPPED = ContactPullResult.Success(
            inserted = 0, replaced = 0, skipped = 0, deleted = 0, booksFailed = 0,
        )
    }
}
