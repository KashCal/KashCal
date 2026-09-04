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
 * Re-arms the periodic contact-sync job from the accounts actually in the
 * database, so no mutation path has to remember to schedule it and an install
 * whose job was lost gets it back.
 *
 * The sibling of [IcsRefreshScheduleReconciler], for contacts. It exists because
 * two one-way doors leave the recurring contact pull unscheduled with no way to
 * recover from a user toggle:
 *  - **Job never armed.** A login whose periodic *calendar* sync was last
 *    scheduled before contact sync shipped never had the contact job enqueued
 *    (the calendar scheduler only arms the contact job alongside a fresh calendar
 *    schedule). Enabling contact sync on such a login did a one-time import but no
 *    ongoing sync until the next full re-schedule.
 *  - **Job died terminally.** A periodic spec that once returned `Result.failure`
 *    (a single pre-guard 401) is permanently FAILED. The affected cohort's toggle
 *    already reads "on", so a toggle-based heal can never reach them.
 *
 * App start is where this heals: it reconciles once, unconditionally (not gated on
 * any user toggle), so both cohorts recover on the next launch.
 *
 * **KEEP semantics + the one limitation.** Scheduling goes through
 * [SyncScheduler.ensureContactSyncScheduled], which uses `KEEP`: it installs the
 * job where none exists and re-arms one that is no longer active. A spec left in a
 * terminal FAILED state is NOT resurrected by a KEEP re-arm — that terminal-failure
 * path was separately removed at the worker level (the full-sync worker folds auth
 * and transport errors into a partial success instead of returning failure), so new
 * terminal failures don't recur; only specs already dead before that fix stay dead.
 *
 * Safe to call repeatedly: `ensureContactSyncScheduled` treats an already-correct
 * schedule as a no-op, and this only ever *arms* — it never cancels (contact sync
 * rides alongside calendar sync, whose own disable/purge path owns cancellation).
 */
@Singleton
class ContactSyncScheduleReconciler @Inject constructor(
    private val accountRepository: AccountRepository,
    private val userPreferences: UserPreferencesRepository,
    private val syncScheduler: SyncScheduler,
) {

    /**
     * Serializes read-decide-apply as one step. Mutations run on the application
     * scope and app start reconciles too, so two passes can overlap; without this a
     * stale read could win. Mirrors [IcsRefreshScheduleReconciler.reconcile].
     */
    private val mutex = Mutex()

    /**
     * Arm the single shared periodic contact-sync job when at least one CardDAV,
     * contact-sync-enabled account exists, at the global sync interval. A no-op
     * when no such account exists, or when the sync interval is the "manual only"
     * sentinel (no periodic job in manual mode, exactly as the enable path decides).
     */
    suspend fun reconcile(): Unit = mutex.withLock {
        try {
            val hasContactSyncAccount = accountRepository.getEnabledAccounts()
                .any { it.contactSyncEnabled && it.provider.supportsCardDAV }
            if (!hasContactSyncAccount) {
                // Nothing to sync. Don't cancel: the job is shared with calendar
                // sync's lifecycle, whose disable/purge path owns cancellation.
                return@withLock
            }

            val intervalMs = userPreferences.syncIntervalMs.first()
            // Long.MAX_VALUE is the "manual only" sentinel: as with calendar sync,
            // don't arm a periodic job in that mode. A user-initiated pull is a
            // separate one-shot and is unaffected.
            if (intervalMs == Long.MAX_VALUE) {
                return@withLock
            }

            // The scheduler floors to the WorkManager minimum, so this is a plain
            // ms -> minutes conversion.
            syncScheduler.ensureContactSyncScheduled(intervalMs / (60 * 1000L))
        } catch (e: CancellationException) {
            // Cancellation is not a failure — let it through so a caller that can be
            // cancelled (a screen-scoped restore) isn't reported as having finished.
            throw e
        } catch (e: Exception) {
            // Best-effort: the callers are bare application-scope launches with no
            // exception handler, so a throw here would take the process down. A
            // schedule that heals one launch late is recoverable; a startup crash is
            // not. Talking to WorkManager or the DB can fail (a full disk, for one).
            Log.w(TAG, "Could not reconcile contact-sync schedule", e)
        }
    }

    private companion object {
        const val TAG = "ContactSyncScheduleReconciler"
    }
}
