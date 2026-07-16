package org.onekash.kashcal.sync.scheduler

import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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

/**
 * Regression guard for the silent-scheduling failure mode.
 *
 * If a future edit accidentally breaks ICS periodic refresh scheduling, users
 * see no error — subscriptions just stop updating. This test catches that by
 * asserting `WorkManager` actually enqueues the expected work after
 * [WorkManagerIcsScheduler.schedulePeriodicRefresh].
 *
 * Tag assertion uses [IcsRefreshWorker.TAG_ICS] constant (not the raw string)
 * so a rename can't quietly defeat the assertion.
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
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
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

    @Test
    fun `schedulePeriodicRefresh enqueues periodic work under the ICS unique work name`() {
        scheduler.schedulePeriodicRefresh()

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
    fun `schedulePeriodicRefresh tags work with the ICS constant`() {
        scheduler.schedulePeriodicRefresh()

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
    fun `schedulePeriodicRefresh applies network and battery constraints`() {
        scheduler.schedulePeriodicRefresh()

        val workInfos = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get()

        assertEquals(1, workInfos.size)
        val constraints = workInfos[0].constraints
        // A self-hosted ICS URL on a LAN/VPN reports INTERNET without VALIDATED.
        // Refresh must run there, so the constraint requires INTERNET but not
        // VALIDATED (#296). Do NOT assert requiredNetworkType — a custom
        // NetworkRequest sets it to NOT_REQUIRED on SDK 34.
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
        assertTrue("Should require battery not low", constraints.requiresBatteryNotLow())
    }

    @Test
    fun `schedulePeriodicRefresh uses KEEP policy — second call does not replace existing work`() {
        scheduler.schedulePeriodicRefresh(intervalHours = 6)
        val first = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get().single().id

        // Second call with a different interval should be a no-op (existing periodic work kept).
        scheduler.schedulePeriodicRefresh(intervalHours = 12)

        val second = workManager
            .getWorkInfosForUniqueWork(IcsRefreshWorker.PERIODIC_REFRESH_WORK)
            .get().single().id

        assertEquals("Existing periodic work should be kept (KEEP policy)", first, second)
    }

    @Test
    fun `cancelPeriodicRefresh removes enqueued work`() {
        scheduler.schedulePeriodicRefresh()
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
        // After cancel, remaining infos are either absent or in a terminal state.
        assertTrue(
            "No enqueued/running work should remain after cancel; got ${after.map { it.state }}",
            after.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        )
    }
}
