package org.onekash.kashcal.calendarbackup

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests the specs [WorkManagerCalendarBackupScheduler] leaves in a test WorkManager. One-time
 * runs start at once and block in [BlockingWorker] until [release] completes, so a test can tap
 * "run now" while one is going.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WorkManagerCalendarBackupSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerCalendarBackupScheduler

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(Executors.newSingleThreadExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = BlockingWorker(appContext, workerParameters, release)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerCalendarBackupScheduler(context)
    }

    private val release = CompletableDeferred<Unit>()

    private class BlockingWorker(
        context: Context,
        params: WorkerParameters,
        private val release: CompletableDeferred<Unit>,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            release.await()
            return Result.success()
        }
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        workManager.cancelAllWork()
    }

    private fun awaitState(name: String, state: WorkInfo.State) {
        val deadline = System.currentTimeMillis() + 5_000
        while (infos(name).none { it.state == state }) {
            check(System.currentTimeMillis() < deadline) { "no $state work under $name: ${infos(name).map { it.state }}" }
            Thread.sleep(10)
        }
    }

    private fun infos(name: String) = workManager.getWorkInfosForUniqueWork(name).get()
    private fun livePeriodic() = infos(CalendarBackupWorker.PERIODIC_WORK).single { !it.state.isFinished }

    @Test
    fun `arming enqueues the periodic run at the interval, delayed one interval, without constraints`() = runBlocking {
        scheduler.ensurePeriodic(12)

        val info = livePeriodic()
        assertEquals(TimeUnit.HOURS.toMillis(12), info.periodicityInfo?.repeatIntervalMillis)
        assertTrue(info.initialDelayMillis >= TimeUnit.HOURS.toMillis(12) - 1_000)
        assertFalse(info.constraints.requiresBatteryNotLow())
        assertFalse(info.constraints.requiresCharging())
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertTrue(info.tags.contains(CalendarBackupWorker.TAG_CALENDAR_BACKUP))
    }

    @Test
    fun `a repeat at the same period changes nothing`() = runBlocking {
        scheduler.ensurePeriodic(12)
        val first = livePeriodic().id
        scheduler.ensurePeriodic(12)

        assertEquals(first, livePeriodic().id)
        assertEquals(1, infos(CalendarBackupWorker.PERIODIC_WORK).size)
    }

    @Test
    fun `a new period updates the armed job in place`() = runBlocking {
        scheduler.ensurePeriodic(12)
        val first = livePeriodic().id
        scheduler.ensurePeriodic(54)

        assertEquals(first, livePeriodic().id)
        assertEquals(TimeUnit.HOURS.toMillis(54), livePeriodic().periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `a cancelled job is armed again`() = runBlocking {
        scheduler.ensurePeriodic(12)
        scheduler.cancelPeriodic()
        assertTrue(infos(CalendarBackupWorker.PERIODIC_WORK).all { it.state.isFinished })

        scheduler.ensurePeriodic(24)
        assertEquals(TimeUnit.HOURS.toMillis(24), livePeriodic().periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `run now while a run is going queues exactly one more`() = runBlocking {
        scheduler.runNow()
        awaitState(CalendarBackupWorker.ONE_TIME_WORK, WorkInfo.State.RUNNING)

        scheduler.runNow()
        scheduler.runNow()

        val states = infos(CalendarBackupWorker.ONE_TIME_WORK).map { it.state }.sorted()
        assertEquals(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED).sorted(), states)
        assertTrue(scheduler.running.first())
    }

    @Test
    fun `the queued run starts after the one that was going`() = runBlocking {
        scheduler.runNow()
        awaitState(CalendarBackupWorker.ONE_TIME_WORK, WorkInfo.State.RUNNING)
        scheduler.runNow()
        release.complete(Unit)

        awaitState(CalendarBackupWorker.ONE_TIME_WORK, WorkInfo.State.SUCCEEDED)
        val deadline = System.currentTimeMillis() + 5_000
        while (infos(CalendarBackupWorker.ONE_TIME_WORK).any { !it.state.isFinished }) {
            check(System.currentTimeMillis() < deadline) { "follow-up never finished" }
            Thread.sleep(10)
        }
        assertEquals(2, infos(CalendarBackupWorker.ONE_TIME_WORK).count { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun `run now never touches the periodic job`() = runBlocking {
        scheduler.ensurePeriodic(12)
        val periodic = livePeriodic().id
        scheduler.runNow()

        assertEquals(periodic, livePeriodic().id)
    }

    @Test
    fun `running is false while nothing runs`() = runBlocking {
        scheduler.ensurePeriodic(12)
        assertFalse(scheduler.running.first())
    }

    @Test
    fun `run now decides from the one-time run states`() {
        assertEquals(RunNowDecision.ENQUEUE, decideRunNow(emptyList()))
        assertEquals(RunNowDecision.ENQUEUE, decideRunNow(listOf(WorkInfo.State.SUCCEEDED)))
        assertEquals(RunNowDecision.NOTHING, decideRunNow(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(RunNowDecision.APPEND, decideRunNow(listOf(WorkInfo.State.RUNNING)))
        // A follow-up queued behind a running run waits BLOCKED; a third tap adds nothing.
        assertEquals(
            RunNowDecision.NOTHING,
            decideRunNow(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)),
        )
        assertEquals(RunNowDecision.ENQUEUE, decideRunNow(listOf(WorkInfo.State.CANCELLED, WorkInfo.State.FAILED)))
    }
}
