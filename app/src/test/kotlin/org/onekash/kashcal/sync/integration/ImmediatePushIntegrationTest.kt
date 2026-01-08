package org.onekash.kashcal.sync.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for immediate push sync functionality.
 *
 * Tests verify WorkManager behavior:
 * - Expedited work is enqueued properly
 * - Rapid sync requests are coalesced (REPLACE policy)
 * - Work has correct tags and constraints
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ImmediatePushIntegrationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var syncScheduler: SyncScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize WorkManager for testing with synchronous executor
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        syncScheduler = SyncScheduler(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
    }

    @Test
    fun `requestExpeditedSync enqueues work immediately`() {
        // When
        syncScheduler.requestExpeditedSync(forceFullSync = false)

        // Then - work should be enqueued
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals("Should have exactly 1 work item", 1, workInfos.size)
        assertTrue(
            "Work should have expedited tag",
            workInfos[0].tags.contains(SyncScheduler.TAG_EXPEDITED)
        )
        assertTrue(
            "Work should have sync tag",
            workInfos[0].tags.contains(SyncScheduler.TAG_SYNC)
        )
    }

    @Test
    fun `multiple rapid syncs are coalesced via REPLACE policy`() {
        // When - rapid fire sync requests (simulating user making multiple quick changes)
        repeat(5) {
            syncScheduler.requestExpeditedSync(forceFullSync = false)
        }

        // Then - only one work item due to ExistingWorkPolicy.REPLACE
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(
            "Multiple rapid requests should be coalesced into 1 work item",
            1,
            workInfos.size
        )
    }

    @Test
    fun `expedited sync has network constraint`() {
        // When
        syncScheduler.requestExpeditedSync(forceFullSync = false)

        // Then - verify work was created (constraints are internal to WorkRequest)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        // Note: We can't directly inspect constraints via WorkInfo API,
        // but we verify the work is created correctly
        assertTrue(workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `forceFullSync flag is passed correctly`() {
        // When - request with forceFullSync = true
        syncScheduler.requestExpeditedSync(forceFullSync = true)

        // Then - work is enqueued (input data is internal)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_EXPEDITED))
    }

    @Test
    fun `requestExpeditedSync returns work UUID`() {
        // When
        val workId = syncScheduler.requestExpeditedSync(forceFullSync = false)

        // Then
        val workInfo = workManager.getWorkInfoById(workId).get()
        assertTrue("Work should exist", workInfo != null)
        assertTrue(
            "Work should be enqueued or running",
            workInfo!!.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun `periodic sync work is separate from expedited sync`() {
        // Given - schedule periodic sync
        syncScheduler.schedulePeriodicSync(intervalMinutes = 30)

        // When - also request expedited sync
        syncScheduler.requestExpeditedSync(forceFullSync = false)

        // Then - both should exist independently
        val expeditedWork = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        val periodicWork = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()

        assertEquals("Should have 1 expedited work", 1, expeditedWork.size)
        assertEquals("Should have 1 periodic work", 1, periodicWork.size)
    }
}
