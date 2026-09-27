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
 * Derives the periodic ICS refresh schedule from the feeds in the database and applies it.
 *
 * Every place that can change the answer (adding, removing, editing or toggling a feed,
 * restoring a backup, app start) calls [reconcile], so no mutation path schedules anything
 * itself, each feed's configured interval reaches WorkManager, and an install whose job was
 * lost gets it back.
 *
 * Safe to call repeatedly: [IcsScheduler] treats an already-correct schedule as a no-op.
 */
@Singleton
class IcsRefreshScheduleReconciler @Inject constructor(
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val icsScheduler: IcsScheduler,
) {

    /**
     * Serializes read-decide-apply as one step. Mutations and app start can overlap: one
     * pass could read "one feed enabled", the other "none left", and the later decision
     * wins, leaving the job cancelled with a live feed or armed with none. The scheduler's
     * own lock can't help, since the stale decision is made before it is taken.
     */
    private val mutex = Mutex()

    /**
     * Brings the periodic refresh job in line with the enabled feeds: cancels it when there
     * are none, otherwise arms it at the shortest interval any enabled feed asks for.
     *
     * The job is one shared check pass, not a per-feed sync: it calls
     * `IcsSubscriptionRepository.refreshAllDueSubscriptions`, which refreshes only feeds
     * where [org.onekash.kashcal.data.db.entity.IcsSubscription.isDueForSync] holds. So it
     * wakes at the shortest interval and due-ness filters the rest.
     */
    suspend fun reconcile(): Unit = mutex.withLock {
        try {
            val enabled = icsSubscriptionsDao.getEnabled()
            if (enabled.isEmpty()) {
                Log.i(TAG, "No enabled ICS feeds — cancelling periodic refresh")
                icsScheduler.cancelPeriodicRefresh()
                return@withLock
            }

            // Floor the stored value: 0 would ask WorkManager for a period it can't honour,
            // and a row restored from a hand-edited or corrupt backup by an older importer,
            // which didn't coerce, may still hold one. The scheduler floors again where it
            // builds the period; this is the boundary guard, not the only one.
            val intervalHours = maxOf(
                enabled.minOf { it.syncIntervalHours }.toLong(),
                IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS,
            )
            icsScheduler.ensurePeriodicRefresh(intervalHours)
        } catch (e: CancellationException) {
            // Cancellation isn't a failure, so the best-effort catch below must not absorb
            // it. A backup restore reconciles from a screen-scoped coroutine the user can
            // cancel by navigating away. Swallowed here, a restore of feeds without
            // preferences (nothing after this suspends) would be reported as succeeded.
            throw e
        } catch (e: Exception) {
            // Best-effort: most callers are bare `applicationScope` launches, which have no
            // exception handler, so a throw here would crash the process. WorkManager can
            // fail (a full disk while it writes its own database, for one). A feed that
            // refreshes late is recoverable, since the next mutation or app start
            // reconciles again; a crash on every toggle isn't.
            Log.w(TAG, "Could not reconcile ICS refresh schedule", e)
        }
    }

    private companion object {
        const val TAG = "IcsScheduleReconciler"
    }
}
