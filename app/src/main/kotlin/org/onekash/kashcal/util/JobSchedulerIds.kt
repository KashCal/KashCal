package org.onekash.kashcal.util

/**
 * Splits the app's JobScheduler job ids between WorkManager and the jobs the app schedules itself.
 *
 * Job ids are shared by everything in the app's uid, and WorkManager assigns its own ids from
 * its configured range, by default every non-negative int. KashCalApplication caps that range at
 * [WORK_MANAGER_MAX] so an app-owned id above it can never be reused, which would silently
 * replace one job with the other. WorkManager assigns small sequential ids, so the cap leaves the
 * ids it already uses untouched. JobScheduler namespaces would avoid this, but they need API 34.
 * `JobSchedulerIdsTest` holds both rules.
 */
object JobSchedulerIds {
    /** Lowest id WorkManager may assign. */
    const val WORK_MANAGER_MIN = 0

    /** Highest id WorkManager may assign; app-owned job ids lie above it. */
    const val WORK_MANAGER_MAX = Int.MAX_VALUE - 1000

    /** The job of [org.onekash.kashcal.reminder.device.DeviceCalendarChangeJobService]. */
    const val DEVICE_CALENDAR_CHANGE = Int.MAX_VALUE - 1
}
