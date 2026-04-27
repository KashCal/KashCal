package org.onekash.kashcal.sync.scheduler

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [androidx.work.WorkManager]-backed implementation of [IcsScheduler].
 *
 * Forwards to [IcsRefreshWorker]'s companion methods so the worker's
 * constraint definitions (network, battery) stay in one place.
 */
@Singleton
class WorkManagerIcsScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : IcsScheduler {

    override fun schedulePeriodicRefresh(intervalHours: Long) {
        IcsRefreshWorker.schedulePeriodicRefresh(context, intervalHours)
    }

    override fun cancelPeriodicRefresh() {
        IcsRefreshWorker.cancelPeriodicRefresh(context)
    }
}
