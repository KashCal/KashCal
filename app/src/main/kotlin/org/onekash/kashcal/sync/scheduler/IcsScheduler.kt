package org.onekash.kashcal.sync.scheduler

/**
 * Schedules the periodic ICS subscription refresh; the ICS counterpart of [SyncScheduler].
 *
 * An injectable seam, so callers don't hold a `Context` to call the worker companion. It has
 * two methods on purpose: [IcsRefreshScheduleReconciler] is the one component that decides
 * the period and drives this seam, so no second arming path with its own interval appears.
 */
interface IcsScheduler {

    /**
     * Brings the periodic refresh job in line with [intervalHours]: arms it if missing, moves
     * its period if it differs, otherwise leaves it alone.
     *
     * Must be idempotent and cheap: it runs on every app start and every feed mutation. There
     * is no default interval, so a forgotten argument is a compile error, not a silent fixed
     * period; the caller always derives it from the feeds in the database.
     */
    suspend fun ensurePeriodicRefresh(intervalHours: Long)

    /**
     * Stops the periodic refresh job, suspending until the cancellation is committed.
     *
     * Cancelling is asynchronous underneath. Returning early would let a caller that arms
     * the job straight afterwards (feed toggled off, then on) read the dying spec as live and
     * leave the job cancelled with a feed enabled.
     */
    suspend fun cancelPeriodicRefresh()
}
