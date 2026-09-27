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
 * Implements [IcsScheduler] on [androidx.work.WorkManager], through [IcsRefreshWorker]'s
 * companion methods so the worker's constraints are defined in one place.
 */
@Singleton
class WorkManagerIcsScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : IcsScheduler {

    /**
     * Serializes the read-then-decide below against itself and against cancellation. App
     * start and feed mutations can overlap; without it both could read "nothing armed" and
     * both enqueue, or one could read a spec the other is cancelling.
     */
    private val mutex = Mutex()

    override suspend fun ensurePeriodicRefresh(intervalHours: Long): Unit = mutex.withLock {
        val desiredHours = maxOf(intervalHours, IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS)
        val desiredIntervalMs = TimeUnit.HOURS.toMillis(desiredHours)

        val live = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .first()
            .firstOrNull { !it.state.isFinished }

        // A job armed while the worker still required battery-not-low keeps that
        // constraint as long as the period matches, and "every 6 hours" is both a
        // selectable feed interval and the period such jobs were armed with. Comparing
        // the period alone would leave those installs skipping refresh windows forever.
        val carriesStaleConstraint = live?.constraints?.requiresBatteryNotLow() == true

        when {
            // Nothing armed, or only a finished (cancelled or failed) spec. KEEP, not
            // UPDATE: UPDATE doesn't apply to a finished job, so a job cancelled when the
            // last feed was removed would never come back when a feed is added. KEEP
            // prunes the finished spec and arms a fresh one.
            live == null -> {
                Log.i(TAG, "Arming periodic ICS refresh every $desiredHours hours")
                IcsRefreshWorker.schedulePeriodicRefresh(
                    context,
                    desiredHours,
                    ExistingPeriodicWorkPolicy.KEEP,
                )
            }

            // Armed at the wrong period, or with a constraint the worker no longer sets.
            // UPDATE keeps the run history (the last-run anchor and period count; only
            // the spec generation changes), so changing a feed's interval doesn't
            // starve the job or restart its window.
            live.periodicityInfo?.repeatIntervalMillis != desiredIntervalMs ||
                carriesStaleConstraint -> {
                Log.i(TAG, "Moving periodic ICS refresh to every $desiredHours hours")
                IcsRefreshWorker.schedulePeriodicRefresh(
                    context,
                    desiredHours,
                    ExistingPeriodicWorkPolicy.UPDATE,
                )
            }

            // Already correct. Don't re-enqueue: this runs on every app start, and the
            // platform throttles frequent job-scheduling calls.
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
