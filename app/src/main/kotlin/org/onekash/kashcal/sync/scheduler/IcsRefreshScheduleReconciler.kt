package org.onekash.kashcal.sync.scheduler

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives the periodic ICS refresh schedule from the feeds actually in the
 * database, and applies it.
 *
 * This exists so no mutation path has to remember to schedule anything. Every
 * place that can change the answer — adding, removing, editing or toggling a
 * feed, restoring a backup, and app start — just calls [reconcile], and the
 * schedule follows the data. Previously a single call site armed the job once
 * with a hard-coded interval, so a feed's configured interval never reached
 * WorkManager and an install whose job was lost had no way to get it back.
 *
 * Being safe to call repeatedly is part of the contract: [IcsScheduler] treats
 * an already-correct schedule as a no-op.
 */
@Singleton
class IcsRefreshScheduleReconciler @Inject constructor(
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val icsScheduler: IcsScheduler,
) {

    /**
     * Serializes read-decide-apply as one step. Mutations run on the application
     * scope and app start reconciles too, so two passes can overlap: without this,
     * one could read "one feed enabled" while the other reads "none left" and the
     * later-landing decision wins, leaving the job cancelled with a live feed (or
     * armed with none). The scheduler's own lock cannot help — by the time it is
     * taken, the stale decision has already been made.
     */
    private val mutex = Mutex()

    /**
     * Brings the periodic refresh job in line with the enabled feeds: cancels it
     * when there are none, otherwise arms it at the shortest interval any
     * enabled feed asks for.
     *
     * The shortest interval is right because the job is a single shared *check*
     * pass, not a per-feed sync: it calls
     * `IcsSubscriptionRepository.refreshAllDueSubscriptions`, which gates each
     * feed on [org.onekash.kashcal.data.db.entity.IcsSubscription.isDueForSync].
     * Waking at the shortest interval and letting due-ness filter the rest is
     * the existing design.
     */
    suspend fun reconcile(): Unit = mutex.withLock {
        try {
            val enabled = icsSubscriptionsDao.getEnabled()
            if (enabled.isEmpty()) {
                Log.i(TAG, "No enabled ICS feeds — cancelling periodic refresh")
                icsScheduler.cancelPeriodicRefresh()
                return@withLock
            }

            // Floor the stored value rather than trusting it: 0 would ask WorkManager
            // for a period it cannot honour, and a row written from a hand-edited or
            // corrupt backup before the importer started coercing is still on disk.
            // The scheduler floors again where it turns this into a period, so this
            // is defence at the boundary, not the only guard.
            val intervalHours = maxOf(
                enabled.minOf { it.syncIntervalHours }.toLong(),
                IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS,
            )
            icsScheduler.ensurePeriodicRefresh(intervalHours)
        } catch (e: CancellationException) {
            // Cancellation is not a failure, so the best-effort catch below must not
            // absorb it. Restoring a backup reconciles from a screen-scoped coroutine
            // the user can cancel by navigating away, and that caller rethrows
            // cancellation itself. Swallowing it here would run on past the
            // cancellation, and for a backup carrying feeds but no preferences —
            // where nothing after this point suspends — the restore would then be
            // reported as having succeeded.
            throw e
        } catch (e: Exception) {
            // Best-effort on purpose. Most callers are bare `applicationScope`
            // launches, and that scope has no exception handler, so a throw from
            // here would take the process down. Talking to WorkManager can fail
            // (a full disk while it writes its own database, for one), and a feed
            // that refreshes late is recoverable — the next mutation or app start
            // reconciles again — whereas a crash on every toggle is not.
            Log.w(TAG, "Could not reconcile ICS refresh schedule", e)
        }
    }

    private companion object {
        const val TAG = "IcsScheduleReconciler"
    }
}
