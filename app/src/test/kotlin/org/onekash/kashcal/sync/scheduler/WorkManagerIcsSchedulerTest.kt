package org.onekash.kashcal.sync.scheduler

import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.ics.IcsRefreshWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests that [WorkManagerIcsScheduler] leaves the expected periodic ICS refresh spec in a test
 * WorkManager: its name, tag, period, network constraint, and how a repeat, a new period, a
 * stale battery constraint or a cancelled job is handled.
 *
 * Broken scheduling shows users no error; subscriptions stop updating. The tag assertion uses
 * the [IcsRefreshWorker.TAG_ICS] constant, not the raw string, so a rename can't quietly defeat
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WorkManagerIcsSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerIcsScheduler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        context = RuntimeEnvironment.getApplication()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            // Worker bodies stay on a background executor: this test has no WorkerFactory, so
            // a worker that ran would fail to build.
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            // WorkManager's own bookkeeping runs inline, so a work-info read right after an
            // enqueue sees the committed spec instead of racing it.
            .setTaskExecutor(SynchronousExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        scheduler = WorkManagerIcsScheduler(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
        unmockkAll()
    }

    /** The single live (non-finished) periodic spec, or null if none is armed. */
    private fun livePeriodicWork(): WorkInfo? =
        workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()
            .singleOrNull { !it.state.isFinished }

    @Test
    fun `ensurePeriodicRefresh enqueues periodic work under the ICS unique work name`() = runTest {
        scheduler.ensurePeriodicRefresh(6)

        val workInfos = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()

        assertEquals(1, workInfos.size)
        val info = workInfos[0]
        assertTrue(
            "Worker should be ENQUEUED or RUNNING; was ${info.state}",
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
        )
    }

    @Test
    fun `ensurePeriodicRefresh tags work with the ICS constant`() = runTest {
        scheduler.ensurePeriodicRefresh(6)

        val workInfos = workManager
            .getWorkInfosByTag(IcsRefreshWorker.TAG_ICS)
            .get()

        assertEquals(1, workInfos.size)
        assertTrue(
            "Tag should be IcsRefreshWorker.TAG_ICS; found tags ${workInfos[0].tags}",
            workInfos[0].tags.contains(IcsRefreshWorker.TAG_ICS),
        )
    }

    @Test
    fun `ensurePeriodicRefresh enqueues work at the requested period`() = runTest {
        scheduler.ensurePeriodicRefresh(6)

        val info = livePeriodicWork()
        assertNotNull("Periodic work should be armed", info)
        assertEquals(
            TimeUnit.HOURS.toMillis(6),
            info!!.periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun `ensurePeriodicRefresh applies the network constraint and does not require battery not low`() = runTest {
        scheduler.ensurePeriodicRefresh(6)

        val workInfos = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()

        assertEquals(1, workInfos.size)
        val constraints = workInfos[0].constraints
        // A self-hosted ICS URL on a LAN or VPN reports INTERNET without VALIDATED. Refresh
        // must run there, so the constraint requires INTERNET but not VALIDATED (#296).
        // Don't assert requiredNetworkType: a custom NetworkRequest sets it to NOT_REQUIRED
        // on SDK 34.
        val request = constraints.requiredNetworkRequest
        assertNotNull("ICS refresh should carry a custom NetworkRequest", request)
        assertTrue(
            "ICS refresh must require INTERNET",
            request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
        assertFalse(
            "ICS refresh must NOT require VALIDATED (would block LAN/VPN URLs, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        assertFalse(
            "ICS refresh must NOT require NOT_VPN (would block VPN-only URLs, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        )
        assertFalse(
            "ICS refresh must NOT require battery-not-low: an unmet constraint at a " +
                "periodic window boundary makes that run be SKIPPED, not deferred, so a " +
                "phone habitually under the threshold silently loses refresh windows",
            constraints.requiresBatteryNotLow(),
        )
    }

    /**
     * Arms the job as an older install has it: the right period, with a battery-not-low
     * constraint that [IcsRefreshWorker.schedulePeriodicRefresh] doesn't set.
     */
    private suspend fun armLegacyJobWithBatteryConstraint(intervalHours: Long) {
        val legacy = PeriodicWorkRequestBuilder<IcsRefreshWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(IcsRefreshWorker.TAG_ICS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            IcsRefreshWorker.PERIODIC_REFRESH_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            legacy,
        ).await()
    }

    @Test
    fun `ensurePeriodicRefresh at an unchanged interval does not re-enqueue`() = runTest {
        scheduler.ensurePeriodicRefresh(6)
        val first = livePeriodicWork()!!

        scheduler.ensurePeriodicRefresh(6)
        val second = livePeriodicWork()!!

        // Assert on generation, not id: UPDATE keeps the spec's UUID, so an id check passes
        // whether or not the no-op branch was taken. UPDATE bumps the generation, so it is
        // the only observable that tells "left alone" from "re-enqueued".
        assertEquals(
            "Unchanged interval should not churn the work spec",
            first.generation,
            second.generation,
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(6),
            second.periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun `ensurePeriodicRefresh drops a stale battery constraint at an unchanged interval`() = runTest {
        // 6 hours is both a selectable feed interval and the period older installs were
        // armed with, so a period-only comparison would leave those installs skipping
        // refresh windows forever.
        armLegacyJobWithBatteryConstraint(6)
        val before = livePeriodicWork()!!
        assertTrue(
            "Precondition: the legacy job must carry the battery constraint",
            before.constraints.requiresBatteryNotLow(),
        )

        scheduler.ensurePeriodicRefresh(6)

        val after = livePeriodicWork()
        assertNotNull("Job should still be armed", after)
        assertFalse(
            "A same-period job carrying the old battery-not-low constraint must be " +
                "re-enqueued without it, or it keeps skipping refresh windows",
            after!!.constraints.requiresBatteryNotLow(),
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(6),
            after.periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun `ensurePeriodicRefresh at a new interval moves the period`() = runTest {
        // A feed's configured interval must reach the scheduler, or the job stays at the
        // period it was first armed with and "Every hour" is unreachable.
        scheduler.ensurePeriodicRefresh(6)
        scheduler.ensurePeriodicRefresh(1)

        val info = livePeriodicWork()
        assertNotNull("Periodic work should still be armed", info)
        assertEquals(
            "Period must follow the requested interval",
            TimeUnit.HOURS.toMillis(1),
            info!!.periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun `ensurePeriodicRefresh at a new interval does not leave a second job`() = runTest {
        scheduler.ensurePeriodicRefresh(6)
        scheduler.ensurePeriodicRefresh(1)

        val live = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()
            .filter { !it.state.isFinished }

        assertEquals("Exactly one live periodic job should exist; got $live", 1, live.size)
    }

    @Test
    fun `ensurePeriodicRefresh re-arms a cancelled job`() = runTest {
        // Deleting the last feed cancels the job; adding one back must revive it. So the
        // enqueue policy branches: UPDATE alone reports NOT_APPLIED against a finished spec
        // and would leave the job dead.
        scheduler.ensurePeriodicRefresh(6)
        scheduler.cancelPeriodicRefresh()
        assertEquals(null, livePeriodicWork())

        scheduler.ensurePeriodicRefresh(6)

        val info = livePeriodicWork()
        assertNotNull("A cancelled job must be re-armed, not left finished", info)
        assertEquals(
            TimeUnit.HOURS.toMillis(6),
            info!!.periodicityInfo?.repeatIntervalMillis,
        )
    }

    @Test
    fun `cancelPeriodicRefresh removes enqueued work`() = runTest {
        scheduler.ensurePeriodicRefresh(6)
        assertEquals(
            1,
            workManager
                .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
                .get()
                .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        )

        scheduler.cancelPeriodicRefresh()

        val after = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()
        // After cancel, no remaining info is enqueued or running.
        assertTrue(
            "No enqueued/running work should remain after cancel; got ${after.map { it.state }}",
            after.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        )
    }
}
