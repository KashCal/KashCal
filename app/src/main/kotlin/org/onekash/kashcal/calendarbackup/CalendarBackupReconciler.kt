package org.onekash.kashcal.calendarbackup

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the periodic backup job in line with [CalendarBackupStore.settings]: armed at the
 * chosen interval while backup is on with a folder, cancelled otherwise. Every place that changes
 * the answer (the sheet's actions, app start) calls [reconcile]; a job lost to a force-stop or a
 * task killer comes back at the next app start.
 */
@Singleton
class CalendarBackupReconciler @Inject constructor(
    private val store: CalendarBackupStore,
    private val scheduler: CalendarBackupScheduler,
) {

    // Serializes read-decide-apply, so an older decision can't land after a newer one.
    private val mutex = Mutex()

    suspend fun reconcile(): Unit = mutex.withLock {
        try {
            val settings = store.settings.value
            if (settings.isActive) {
                scheduler.ensurePeriodic(settings.intervalHours)
            } else {
                scheduler.cancelPeriodic()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: callers are bare launches with no handler, and the next change or app
            // start reconciles again.
            Log.w(TAG, "Could not reconcile automatic backup: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "CalendarBackupReconciler"
    }
}
