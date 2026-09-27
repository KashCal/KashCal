package org.onekash.kashcal.sync.scheduler

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-arms the periodic contact-sync job from the accounts in the database, so an install
 * whose job is missing or dead gets it back. The contact counterpart of
 * [IcsRefreshScheduleReconciler].
 *
 * Two cases leave the recurring contact pull unscheduled while the user's toggle already
 * reads "on", so a toggle-based heal never reaches them:
 *  - Never armed. [SyncScheduler.schedulePeriodicSync] arms the contact job only alongside
 *    the calendar job, so a login whose calendar job was armed before the contact job
 *    existed can have contact sync enabled and no contact job.
 *  - Terminally failed. A periodic spec that once returned `Result.failure` (a single 401,
 *    before the workers guarded against it) stays FAILED.
 *
 * App start calls [reconcile] once, not gated on any toggle, so both cases recover on the
 * next launch. It schedules through [SyncScheduler.ensureContactSyncScheduled], whose `KEEP`
 * arms a new job where none is pending, a FAILED spec included, and leaves a pending one
 * alone. `ContactSyncWorker` never returns failure on a periodic run, so no new FAILED spec
 * arises.
 *
 * Safe to call repeatedly. It only arms, never cancels: contact sync rides alongside
 * calendar sync, whose disable and purge path owns cancellation.
 */
@Singleton
class ContactSyncScheduleReconciler @Inject constructor(
    private val accountRepository: AccountRepository,
    private val userPreferences: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
) {

    /**
     * Serializes read-decide-apply as one step, so overlapping passes can't act on a stale
     * read. Only app start calls [reconcile] today; the lock matches
     * [IcsRefreshScheduleReconciler], whose feed mutations do overlap.
     */
    private val mutex = Mutex()

    /**
     * Arms the shared periodic contact-sync job at the global sync interval when at least one
     * enabled, CardDAV-capable, contact-sync-enabled account exists. A no-op when none does,
     * or when the interval is the "manual only" sentinel, as on the enable path.
     */
    suspend fun reconcile(): Unit = mutex.withLock {
        try {
            val hasContactSyncAccount = accountRepository.getEnabledAccounts()
                .any { it.contactSyncEnabled && it.provider.supportsCardDAV }
            if (!hasContactSyncAccount) {
                // Don't cancel: calendar sync's disable and purge path owns cancellation.
                return@withLock
            }

            val intervalMs = userPreferences.syncIntervalMs.first()
            // Long.MAX_VALUE is the "manual only" sentinel: as with calendar sync, no
            // periodic job. A user-initiated pull is a separate one-shot and still runs.
            if (intervalMs == Long.MAX_VALUE) {
                return@withLock
            }

            // The scheduler floors to the WorkManager minimum, so this only converts to minutes.
            syncScheduler.ensureContactSyncScheduled(intervalMs / (60 * 1000L))
        } catch (e: CancellationException) {
            // Cancellation isn't a failure; rethrow so the best-effort catch below doesn't
            // report a cancelled caller as finished.
            throw e
        } catch (e: Exception) {
            // Best-effort: the caller is a bare application-scope launch with no exception
            // handler, so a throw here would crash the process at startup. WorkManager or
            // the DB can fail (a full disk, for one); a schedule that heals one launch
            // late is recoverable, a startup crash isn't.
            Log.w(TAG, "Could not reconcile contact-sync schedule", e)
        }
    }

    private companion object {
        const val TAG = "ContactSyncScheduleReconciler"
    }
}
