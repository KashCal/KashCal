package org.onekash.kashcal.sync.scheduler

/**
 * Narrow scheduler for ICS subscription periodic refresh. Sibling to
 * [SyncScheduler] (main CalDAV sync).
 *
 * Exists so ViewModels can depend on an injectable seam instead of holding a
 * `Context` to call a worker companion directly.
 */
interface IcsScheduler {
    fun schedulePeriodicRefresh(intervalHours: Long = DEFAULT_INTERVAL_HOURS)
    fun cancelPeriodicRefresh()

    companion object {
        /**
         * Default periodic-refresh interval. Kept independent of the worker's
         * own `DEFAULT_REFRESH_INTERVAL_HOURS` to avoid a layer inversion
         * (data/ics cannot reference sync/scheduler). If either changes, update both.
         */
        const val DEFAULT_INTERVAL_HOURS: Long = 6L
    }
}