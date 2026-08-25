package org.onekash.kashcal.sync.scheduler

/**
 * Narrow scheduler for ICS subscription periodic refresh. Sibling to
 * [SyncScheduler] (main CalDAV sync).
 *
 * Exists so callers can depend on an injectable seam instead of holding a
 * `Context` to call a worker companion directly.
 *
 * Deliberately only two methods. The refresh job used to have a single arming
 * call site that ran once per install, which left feeds that were never armed
 * with no way back. There is now exactly one component that decides the period
 * ([IcsRefreshScheduleReconciler]) and this seam is what it drives, so a second
 * arming path with its own idea of the interval cannot reappear.
 */
interface IcsScheduler {

    /**
     * Bring the periodic refresh job in line with [intervalHours]: arm it if it
     * is missing, move its period if it differs, otherwise leave it alone.
     *
     * Idempotent by design — this runs on every app start as well as on every
     * feed mutation, so it must be cheap and safe to call repeatedly.
     *
     * Takes no default interval on purpose: the caller always derives one from
     * the feeds in the database, so a forgotten argument should be a compile
     * error rather than a silent fallback to some fixed period.
     */
    suspend fun ensurePeriodicRefresh(intervalHours: Long)

    /**
     * Stop the periodic refresh job.
     *
     * Suspends until the cancellation has been committed. Cancelling is
     * asynchronous underneath, and returning early would let a caller that arms
     * the job straight afterwards (feed toggled off, then back on) read the
     * about-to-die spec as live and leave the job cancelled with a feed enabled.
     */
    suspend fun cancelPeriodicRefresh()
}
