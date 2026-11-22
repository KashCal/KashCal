package org.onekash.kashcal.calendarbackup

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [CalendarBackupScheduler] on WorkManager with two unique works running
 * [CalendarBackupWorker]: the periodic job and the one-time run.
 *
 * Neither has constraints: a backup needs no network, and a periodic run whose constraint is
 * unmet at its window is skipped, not deferred.
 */
@Singleton
class WorkManagerCalendarBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarBackupScheduler {

    // Serializes each read-then-enqueue against itself, so two callers can't both read "nothing
    // armed" and both enqueue.
    private val mutex = Mutex()

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun ensurePeriodic(intervalHours: Int): Unit = mutex.withLock {
        val hours = CalendarBackupInterval.storedOrDefault(intervalHours).toLong()
        val live = workManager.getWorkInfosForUniqueWorkFlow(CalendarBackupWorker.PERIODIC_WORK)
            .first()
            .firstOrNull { !it.state.isFinished }
        val request = PeriodicWorkRequestBuilder<CalendarBackupWorker>(hours, TimeUnit.HOURS)
            // The first periodic run waits one interval, so it can't land on the run that turning
            // backup on starts at once.
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setInputData(CalendarBackupWorker.inputFor(RunKind.PERIODIC))
            .addTag(CalendarBackupWorker.TAG_CALENDAR_BACKUP)
            .build()
        when {
            // KEEP, not UPDATE: UPDATE doesn't apply to a finished (cancelled) job, so turning
            // backup off and on again would leave it unarmed.
            live == null -> {
                Log.i(TAG, "Arming automatic backup every $hours hours")
                workManager.enqueueUniquePeriodicWork(
                    CalendarBackupWorker.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
                ).await()
            }
            live.periodicityInfo?.repeatIntervalMillis != TimeUnit.HOURS.toMillis(hours) -> {
                Log.i(TAG, "Moving automatic backup to every $hours hours")
                workManager.enqueueUniquePeriodicWork(
                    CalendarBackupWorker.PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
                ).await()
            }
            // Already right. Not re-enqueued: this runs on every app start, and the platform
            // throttles frequent job scheduling.
            else -> Unit
        }
    }

    override suspend fun cancelPeriodic(): Unit = mutex.withLock {
        workManager.cancelUniqueWork(CalendarBackupWorker.PERIODIC_WORK).await()
    }

    override suspend fun runNow(): Unit = mutex.withLock {
        val states = workManager.getWorkInfosForUniqueWorkFlow(CalendarBackupWorker.ONE_TIME_WORK)
            .first()
            .map { it.state }
        val policy = when (decideRunNow(states)) {
            RunNowDecision.NOTHING -> return@withLock
            RunNowDecision.ENQUEUE -> ExistingWorkPolicy.KEEP
            RunNowDecision.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        val request = OneTimeWorkRequestBuilder<CalendarBackupWorker>()
            .setInputData(CalendarBackupWorker.inputFor(RunKind.ONE_TIME))
            .addTag(CalendarBackupWorker.TAG_CALENDAR_BACKUP)
            .build()
        workManager.enqueueUniqueWork(CalendarBackupWorker.ONE_TIME_WORK, policy, request).await()
    }

    override val running: Flow<Boolean>
        get() = combine(
            workManager.getWorkInfosForUniqueWorkFlow(CalendarBackupWorker.PERIODIC_WORK),
            workManager.getWorkInfosForUniqueWorkFlow(CalendarBackupWorker.ONE_TIME_WORK),
        ) { periodic, oneTime -> (periodic + oneTime).any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()

    private companion object {
        const val TAG = "CalendarBackupScheduler"
    }
}
