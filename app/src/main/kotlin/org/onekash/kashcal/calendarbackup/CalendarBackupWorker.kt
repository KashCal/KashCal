package org.onekash.kashcal.calendarbackup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Runs one automatic backup ([CalendarBackupRunner]) for the periodic job or a run started now;
 * [KEY_RUN_KIND] says which.
 *
 * Always succeeds: a periodic worker that returns failure is never scheduled again, and the
 * runner keeps every failure in the store for the sheet and the notification.
 */
@HiltWorker
class CalendarBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: CalendarBackupRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_RUN_KIND)
            ?.let { name -> RunKind.entries.firstOrNull { it.name == name } }
            ?: RunKind.PERIODIC
        try {
            runner.run(kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backup run failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "CalendarBackupWorker"

        const val PERIODIC_WORK = "calendar_backup_periodic"
        const val ONE_TIME_WORK = "calendar_backup_now"
        const val TAG_CALENDAR_BACKUP = "calendar_backup"
        const val KEY_RUN_KIND = "run_kind"

        fun inputFor(kind: RunKind): Data = Data.Builder().putString(KEY_RUN_KIND, kind.name).build()
    }
}
