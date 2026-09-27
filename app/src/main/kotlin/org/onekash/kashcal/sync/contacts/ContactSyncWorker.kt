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
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.ui.permission.PermissionChecker

/**
 * Runs contact sync ([ContactPullStrategy]: push device edits, then pull server changes) for
 * every contact-sync-enabled CardDAV account.
 *
 * It builds each account's CardDAV client from the [ProviderRegistry] quirks and credentials
 * (the iCloud entry point, or the account's own host for generic CardDAV).
 *
 * A revoked WRITE_CONTACTS never reaches the worker as an exception:
 * [AndroidContactsProviderRepository] turns the provider [SecurityException] into a failed
 * result, and [ContactPullStrategy] folds that into its counts and still returns Success. So the
 * worker checks WRITE_CONTACTS before running. Without it the worker syncs nothing and raises an
 * app-global re-grant flag for an inline affordance in settings; with it the flag is cleared.
 * The flag is app-global because WRITE_CONTACTS is one app-wide permission, not per account.
 *
 * Scheduled by [SyncScheduler] at the calendar sync interval.
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

    // Serialize every contact sweep in the process. The periodic job and a user-initiated
    // one-shot have different WorkManager unique-work names, so both can run at once; they
    // sync the same accounts, and with no SOURCE_ID uniqueness constraint an overlap can
    // insert a contact twice. WorkManager creates a worker per run, so the lock lives in the
    // companion. withLock, not tryLock: a second run waits, then re-reads the account list,
    // so a just-enabled account is never dropped.
    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        try {
            syncLock.withLock { runSweep() }
        } catch (e: CancellationException) {
            // Cancellation isn't a failure; let it propagate.
            throw e
        } catch (e: Exception) {
            // The account query and the permission-flag writes run outside the per-account
            // guard, so a locked database or a full disk throws out of the sweep. Uncaught,
            // that reaches WorkManager as failure, which is terminal for the periodic spec:
            // the permanent stop the result branches in runSweep avoid.
            Log.e(TAG, "Contact sync sweep failed", e)
            when {
                runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                isPeriodicRun() -> Result.success()
                else -> Result.failure()
            }
        }
    }

    private suspend fun runSweep(): Result {
        // "Sync now" from one account's sheet scopes the sweep to that account through
        // input data, so the other accounts' books aren't re-pulled. The periodic and
        // global one-shot runs leave it unset (-1) and sweep every contact-sync login.
        val scopedAccountId = inputData.getLong(KEY_ACCOUNT_ID, UNSCOPED_ACCOUNT_ID)
        val accounts = accountRepository.getEnabledAccounts()
            .filter { it.contactSyncEnabled && it.provider.supportsCardDAV }
            .filter { scopedAccountId == UNSCOPED_ACCOUNT_ID || it.id == scopedAccountId }

        // Nothing to do. Leave the re-grant flag alone: it shouldn't flip while the
        // feature is unused.
        if (accounts.isEmpty()) {
            return Result.success()
        }

        // Without WRITE_CONTACTS every provider write silently fails downstream, so don't
        // run; flag it for the re-grant affordance in settings.
        if (!permissionChecker.hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS revoked; skipping contact sync and flagging re-grant")
            dataStore.setContactSyncPermissionNeeded(true)
            return Result.success()
        }
        // Permission present: clear a banner left from an earlier denial.
        dataStore.setContactSyncPermissionNeeded(false)

        // The worst outcome across the sweep: a retryable failure asks WorkManager for a
        // bounded backoff retry; a non-retryable one isn't retried in this run (retrying a
        // 401 only hammers the server). A retry re-runs accounts that already succeeded;
        // their contacts' etags match, so the pull skips them.
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
                // Worker stopped: propagate, or the loop logs it as an account failure and
                // keeps issuing work.
                throw e
            } catch (e: Exception) {
                // One account's failure must not abort the sweep or crash the worker.
                Log.w(TAG, "Contact sync failed for account ${account.id}: ${e.javaClass.simpleName}")
                sawRetryable = true
            }
        }

        return when {
            sawRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
            // Retries are spent, or the error isn't worth retrying. Failure is terminal
            // for a periodic work spec: WorkManager stops scheduling it, and nothing
            // re-arms contact sync except account creation or toggling the feature off
            // and on, so one expired password would end contact sync for good. The
            // periodic job reports success and the next period is its retry; a one-shot
            // has no future run to lose, so it reports the failure.
            !isPeriodicRun() && (sawRetryable || sawTerminalError) -> Result.failure()
            else -> Result.success()
        }
    }

    /**
     * Returns whether this run belongs to the periodic job. Only the periodic request carries
     * [SyncScheduler.TAG_PERIODIC].
     */
    private fun isPeriodicRun(): Boolean = SyncScheduler.TAG_PERIODIC in tags

    /**
     * Syncs one account and returns the strategy's [ContactPullResult], whose retryable flag
     * decides the run's result. An account skipped before the strategy (no credential provider,
     * no credentials, or no resolvable host) returns an empty [ContactPullResult.Success]: a
     * skip isn't a failure to retry.
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
            // A CardDAV account with no resolvable host (e.g. a generic account whose
            // homeSetUrl was never discovered) would start discovery from an empty URL and
            // fail opaquely.
            Log.w(TAG, "No CardDAV quirks/base URL for account ${account.id}; skipping")
            return SKIPPED
        }
        val client: CardDavClient = cardDavClientFactory.createClient(credentials, quirks)
        // RFC 6764 §6: when the quirks allow it, discover the contacts host from the
        // account's email domain via DNS SRV/TXT, falling back to quirks.baseUrl when no
        // in-domain SRV record exists (self-hosted without SRV) or the resolver is
        // unreachable. Pinned-host providers (iCloud, Zoho) skip it: their host is known
        // and unrelated to the email domain, so the lookup could only misdirect them. The
        // quirks decide, not the account provider, so a generic provider can still carry a
        // pinned host. The pull strategy's well-known and principal walk starts from the
        // result.
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
         * Input-data key for the one account a "Sync now" scopes the sweep to. Absent or
         * [UNSCOPED_ACCOUNT_ID] sweeps every contact-sync login.
         */
        const val KEY_ACCOUNT_ID = "account_id"

        /** Sentinel for no account scope: sweep all contact-sync logins. */
        const val UNSCOPED_ACCOUNT_ID = -1L

        /** Builds input data scoping a one-shot contact sync to [accountId]. */
        fun createScopedInput(accountId: Long): Data =
            Data.Builder().putLong(KEY_ACCOUNT_ID, accountId).build()

        /** Keeps two contact sweeps from running at once; why is in [doWork]. */
        private val syncLock = Mutex()

        /**
         * Retry budget, matching the calendar sync worker. Past this attempt count a
         * retryable failure stops backing off and ends the run.
         */
        private const val MAX_RETRY_ATTEMPTS = 3

        /** Result for an account skipped before the strategy runs; not a failure. */
        private val SKIPPED = ContactPullResult.Success(
            inserted = 0, replaced = 0, skipped = 0, deleted = 0, booksFailed = 0,
        )
    }
}
