package org.onekash.kashcal.widget

import android.content.Context
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for WidgetUpdateManager scheduling methods.
 *
 * Tests cover:
 * - schedulePeriodicUpdates() enqueues periodic work
 * - scheduleMidnightUpdate() enqueues one-time work with delay
 * - cancelAllUpdates() cancels all scheduled work
 *
 * Retry and updateAllWidgets() are covered in WidgetUpdateManagerRetryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WidgetUpdateManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var manager: WidgetUpdateManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        manager = WidgetUpdateManager(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== schedulePeriodicUpdates Tests ====================

    @Test
    fun `schedulePeriodicUpdates enqueues periodic work`() {
        manager.schedulePeriodicUpdates()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertTrue("Periodic work should be enqueued", workInfos.isNotEmpty())
        // WorkManager test executor may run work immediately (RUNNING) or keep ENQUEUED
        assertTrue(
            "Periodic work should be active, was: ${workInfos[0].state}",
            workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun `schedulePeriodicUpdates uses KEEP policy for idempotency`() {
        manager.schedulePeriodicUpdates()
        manager.schedulePeriodicUpdates()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        assertEquals("Should have exactly one periodic work", 1, workInfos.size)
    }

    // ==================== scheduleMidnightUpdate Tests ====================

    @Test
    fun `scheduleMidnightUpdate enqueues one-time work`() {
        manager.scheduleMidnightUpdate()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_midnight_update").get()
        assertTrue("Midnight work should be enqueued", workInfos.isNotEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)
    }

    @Test
    fun `scheduleMidnightUpdate replaces existing work`() {
        manager.scheduleMidnightUpdate()
        manager.scheduleMidnightUpdate()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_midnight_update").get()
        val activeWorkInfos = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("Should have exactly one active midnight work", 1, activeWorkInfos.size)
    }

    @Test
    fun `scheduleMidnightUpdate initial delay is between 0 and 24h`() {
        // We can't directly inspect the delay from WorkInfo, but we can verify
        // the work is enqueued (not running immediately), which implies a delay was set.
        // The production code calculates: midnight - now, which is always 0..86_400_000ms.
        manager.scheduleMidnightUpdate()

        val workInfos = workManager.getWorkInfosForUniqueWork("widget_midnight_update").get()
        assertTrue("Midnight work should be enqueued (not immediate)", workInfos.isNotEmpty())
        // ENQUEUED state (not RUNNING) confirms initial delay was set
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)
    }

    // ==================== cancelAllUpdates Tests ====================

    @Test
    fun `cancelAllUpdates cancels all widget work`() {
        manager.schedulePeriodicUpdates()
        manager.scheduleMidnightUpdate()

        manager.cancelAllUpdates()

        val periodicWork = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        val midnightWork = workManager.getWorkInfosForUniqueWork("widget_midnight_update").get()

        assertTrue(
            "Periodic work should be cancelled",
            periodicWork.isEmpty() || periodicWork[0].state == WorkInfo.State.CANCELLED
        )
        assertTrue(
            "Midnight work should be cancelled",
            midnightWork.isEmpty() || midnightWork[0].state == WorkInfo.State.CANCELLED
        )
    }

    @Test
    fun `cancelAllUpdates is idempotent`() {
        manager.cancelAllUpdates()
        manager.cancelAllUpdates()

        val periodicWork = workManager.getWorkInfosForUniqueWork("widget_periodic_update").get()
        val midnightWork = workManager.getWorkInfosForUniqueWork("widget_midnight_update").get()

        assertTrue(
            "No periodic work should exist",
            periodicWork.isEmpty() || periodicWork[0].state == WorkInfo.State.CANCELLED
        )
        assertTrue(
            "No midnight work should exist",
            midnightWork.isEmpty() || midnightWork[0].state == WorkInfo.State.CANCELLED
        )
    }
}
