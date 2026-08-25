package org.onekash.kashcal.sync.scheduler

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [androidx.work.WorkManager]-backed implementation of [IcsScheduler].
 *
 * Forwards to [IcsRefreshWorker]'s companion methods so the worker's constraint
 * definitions stay in one place.
 */
@Singleton
class WorkManagerIcsScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : IcsScheduler {

    /**
     * Serializes the read-then-decide below against itself and against
     * cancellation. Callers include app startup and every feed mutation, so two of
     * them can overlap; without this, both could read "nothing armed" and both
     * enqueue, or one could read a spec the other is in the middle of cancelling.
     */
    private val mutex = Mutex()

    override suspend fun ensurePeriodicRefresh(intervalHours: Long): Unit = mutex.withLock {
        val desiredHours = maxOf(intervalHours, IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS)
        val desiredIntervalMs = TimeUnit.HOURS.toMillis(desiredHours)

        val live = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .first()
            .firstOrNull { !it.state.isFinished }

        // An install armed before the battery-not-low constraint was dropped keeps
        // that constraint for as long as the period matches, and "every 6 hours" is
        // a selectable feed interval that matches the period the job used to be
        // armed with — so comparing the period alone would leave those installs
        // skipping refresh windows forever.
        val carriesStaleConstraint = live?.constraints?.requiresBatteryNotLow() == true

        when {
            // Nothing armed, or only a finished (cancelled/failed) spec. KEEP is
            // required here rather than UPDATE: updating a job that has already
            // finished does not apply, so a job cancelled when the last feed was
            // removed would never come back when a feed is added again. KEEP
            // prunes the finished spec and arms a fresh one.
            live == null -> {
                Log.i(TAG, "Arming periodic ICS refresh every $desiredHours hours")
                IcsRefreshWorker.schedulePeriodicRefresh(
                    context,
                    desiredHours,
                    ExistingPeriodicWorkPolicy.KEEP,
                )
            }

            // Armed but at the wrong period, or carrying constraints this app no
            // longer asks for. UPDATE keeps the existing run history (it preserves
            // the last-run anchor and period count and only bumps the spec
            // generation), so changing a feed's interval does not starve the job or
            // restart its window.
            live.periodicityInfo?.repeatIntervalMillis != desiredIntervalMs ||
                carriesStaleConstraint -> {
                Log.i(TAG, "Moving periodic ICS refresh to every $desiredHours hours")
                IcsRefreshWorker.schedulePeriodicRefresh(
                    context,
                    desiredHours,
                    ExistingPeriodicWorkPolicy.UPDATE,
                )
            }

            // Already correct. Not re-enqueueing matters: this runs on every app
            // start, and the platform throttles frequent job-scheduling calls.
            else -> Log.d(TAG, "Periodic ICS refresh already every $desiredHours hours")
        }
    }

    override suspend fun cancelPeriodicRefresh(): Unit = mutex.withLock {
        IcsRefreshWorker.cancelPeriodicRefresh(context)
    }

    private companion object {
        const val TAG = "IcsScheduler"
    }
}
