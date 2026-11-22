package org.onekash.kashcal.util

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.KashCalApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that the job ids the app gives JobScheduler itself can never be handed out by WorkManager,
 * which schedules its own jobs through the same per-app id space.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class JobSchedulerIdsTest {

    private val noWorkers = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = null
    }

    @Test
    fun `device calendar change job id lies outside the WorkManager range`() {
        val id = JobSchedulerIds.DEVICE_CALENDAR_CHANGE
        assertTrue(
            "id $id must not be one WorkManager can assign",
            id < JobSchedulerIds.WORK_MANAGER_MIN || id > JobSchedulerIds.WORK_MANAGER_MAX,
        )
    }

    @Test
    fun `the app's WorkManager configuration uses the WorkManager range`() {
        // Building it also enforces WorkManager's own minimum span of 1000 ids.
        val configuration = KashCalApplication.workManagerConfiguration(noWorkers)

        assertEquals(JobSchedulerIds.WORK_MANAGER_MIN, configuration.minJobSchedulerId)
        assertEquals(JobSchedulerIds.WORK_MANAGER_MAX, configuration.maxJobSchedulerId)
    }
}
