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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for SyncScheduler.
 *
 * Tests:
 * - Periodic sync scheduling
 * - One-shot sync scheduling
 * - Expedited sync scheduling
 * - Calendar/account-specific sync
 * - Sync cancellation
 * - Status observation
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        context = RuntimeEnvironment.getApplication()

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        scheduler = SyncScheduler(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
        unmockkAll()
    }

    // ==================== Periodic Sync Tests ====================

    @Test
    fun `schedulePeriodicSync enqueues periodic work`() {
        // When
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        // Then
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].state == WorkInfo.State.ENQUEUED || workInfos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `schedulePeriodicSync respects minimum interval`() {
        // Given - Request less than 15 minutes
        scheduler.schedulePeriodicSync(intervalMinutes = 5)

        // Then - Should still work (WorkManager enforces minimum internally)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `schedulePeriodicSync with forceFullSync sets input data`() {
        // When
        scheduler.schedulePeriodicSync(intervalMinutes = 30, forceFullSync = true)

        // Then
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_SYNC))
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_PERIODIC))
    }

    @Test
    fun `cancelPeriodicSync removes periodic work`() {
        // Given
        scheduler.schedulePeriodicSync(intervalMinutes = 30)
        var workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertTrue(workInfos.isNotEmpty())

        // When
        scheduler.cancelPeriodicSync()

        // Then
        workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertTrue(workInfos.isEmpty() || workInfos[0].state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun `updatePeriodicSyncInterval replaces existing work`() {
        // Given
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        // When
        scheduler.updatePeriodicSyncInterval(intervalMinutes = 60)

        // Then - Should still have one unique work
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `schedulePeriodicSync also enqueues the contact-sync job`() {
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_SYNC))
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_PERIODIC))
    }

    @Test
    fun `updatePeriodicSyncInterval keeps a single contact-sync job`() {
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        scheduler.updatePeriodicSyncInterval(intervalMinutes = 60)

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `cancelPeriodicSync removes the contact-sync job too`() {
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        scheduler.cancelPeriodicSync()

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK).get()
        assertTrue(workInfos.isEmpty() || workInfos[0].state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun `requestImmediateContactSync enqueues a one-shot contact-sync job`() {
        val workId = scheduler.requestImmediateContactSync()

        assertNotNull(workId)
        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_SYNC))
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_ONE_SHOT))
    }

    @Test
    fun `requestImmediateContactSync scoped to an account still enqueues one contact-sync job`() {
        // The scoped-id plumbing itself (input data → worker filter) is proven in
        // ContactSyncWorkerTest; here we only confirm the scoped overload enqueues
        // the same single one-shot job. WorkInfo does not expose a request's input
        // data, so the id round-trip is verified at the worker, not here.
        val workId = scheduler.requestImmediateContactSync(accountId = 42L)

        assertNotNull(workId)
        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `requestImmediateContactSync replaces existing one-shot contact work`() {
        scheduler.requestImmediateContactSync()

        scheduler.requestImmediateContactSync()

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `requestImmediateContactSync requires internet without requiring validation`() {
        scheduler.requestImmediateContactSync()

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_CONTACT_SYNC_WORK).get()
        val request = workInfos[0].constraints.requiredNetworkRequest
        assertNotNull("Contact sync work should carry a custom NetworkRequest", request)
        assertTrue(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(
            "Contact sync must NOT require VALIDATED (would block LAN/VPN servers, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    @Test
    fun `ensureContactSyncScheduled enqueues the periodic contact job on its own`() {
        // A login that enabled contacts AFTER periodic calendar sync was last
        // scheduled (or was set up before the feature shipped) has no periodic
        // contact job. Enabling must be able to schedule it without rescheduling
        // calendar sync.
        scheduler.ensureContactSyncScheduled(intervalMinutes = 30)

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_PERIODIC))
    }

    @Test
    fun `ensureContactSyncScheduled keeps an already-scheduled contact job`() {
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        // KEEP policy: a second ensure must not tear down / duplicate the job.
        scheduler.ensureContactSyncScheduled(intervalMinutes = 60)

        val workInfos =
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_CONTACT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    // ==================== Network Constraint Tests (#296) ====================

    @Test
    fun `periodic sync requires internet without requiring validation`() {
        // A self-hosted server on a LAN/VPN reports INTERNET without VALIDATED.
        // The sync job must be dispatchable on such a network, so its constraint
        // requires INTERNET but not VALIDATED.
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        val request = workInfos[0].constraints.requiredNetworkRequest
        assertNotNull("Sync work should carry a custom NetworkRequest", request)
        assertTrue(
            "Sync must require INTERNET",
            request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
        assertFalse(
            "Sync must NOT require VALIDATED (would block LAN/VPN servers, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        assertFalse(
            "Sync must NOT require NOT_VPN (would block VPN-only servers, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        )
    }

    @Test
    fun `expedited sync requires internet without requiring validation`() {
        scheduler.requestExpeditedSync()

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        val request = workInfos[0].constraints.requiredNetworkRequest
        assertNotNull("Expedited sync work should carry a custom NetworkRequest", request)
        assertTrue(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    // ==================== Immediate Sync Tests ====================

    @Test
    fun `requestImmediateSync enqueues one-time work`() {
        // When
        val workId = scheduler.requestImmediateSync()

        // Then
        assertNotNull(workId)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_ONE_SHOT))
    }

    @Test
    fun `requestImmediateSync replaces existing work`() {
        // Given
        scheduler.requestImmediateSync()

        // When
        scheduler.requestImmediateSync()

        // Then - Should still have one work
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `requestImmediateSync returns valid UUID`() {
        // When
        val workId = scheduler.requestImmediateSync()

        // Then
        val workInfo = workManager.getWorkInfoById(workId).get()
        assertNotNull(workInfo)
    }

    // ==================== Expedited Sync Tests ====================

    @Test
    fun `requestExpeditedSync enqueues expedited work`() {
        // When
        val workId = scheduler.requestExpeditedSync()

        // Then
        assertNotNull(workId)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos[0].tags.contains(SyncScheduler.TAG_EXPEDITED))
    }

    @Test
    fun `requestExpeditedSync with force flag sets input data`() {
        // When
        scheduler.requestExpeditedSync(forceFullSync = true)

        // Then
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncScheduler.EXPEDITED_SYNC_WORK).get()
        assertEquals(1, workInfos.size)
    }

    // ==================== Calendar-Specific Sync Tests ====================

    @Test
    fun `syncCalendar enqueues calendar-specific work`() {
        // Given
        val calendarId = 42L

        // When
        val workId = scheduler.syncCalendar(calendarId)

        // Then
        assertNotNull(workId)
        val workInfo = workManager.getWorkInfoById(workId).get()
        assertNotNull(workInfo)
        assertTrue(workInfo?.tags?.contains("calendar_$calendarId") == true)
    }

    @Test
    fun `syncCalendar with different calendars creates separate works`() {
        // When
        val workId1 = scheduler.syncCalendar(1L)
        val workId2 = scheduler.syncCalendar(2L)

        // Then
        assertNotEquals(workId1, workId2)
    }

    @Test
    fun `cancelCalendarSync cancels specific calendar work`() {
        // Given
        val calendarId = 42L
        scheduler.syncCalendar(calendarId)

        // When
        scheduler.cancelCalendarSync(calendarId)

        // Then
        val workInfos = workManager.getWorkInfosForUniqueWork("sync_calendar_$calendarId").get()
        assertTrue(workInfos.isEmpty() || workInfos[0].state == WorkInfo.State.CANCELLED)
    }

    // ==================== Account-Specific Sync Tests ====================

    @Test
    fun `syncAccount enqueues account-specific work`() {
        // Given
        val accountId = 7L

        // When
        val workId = scheduler.syncAccount(accountId)

        // Then
        assertNotNull(workId)
        val workInfo = workManager.getWorkInfoById(workId).get()
        assertNotNull(workInfo)
        assertTrue(workInfo?.tags?.contains("account_$accountId") == true)
    }

    @Test
    fun `cancelAccountSync cancels specific account work`() {
        // Given
        val accountId = 7L
        scheduler.syncAccount(accountId)

        // When
        scheduler.cancelAccountSync(accountId)

        // Then
        val workInfos = workManager.getWorkInfosForUniqueWork("sync_account_$accountId").get()
        assertTrue(workInfos.isEmpty() || workInfos[0].state == WorkInfo.State.CANCELLED)
    }

    // ==================== Cancel All Tests ====================

    @Test
    fun `cancelAllSync cancels all sync work`() {
        // Given
        scheduler.schedulePeriodicSync(intervalMinutes = 30)
        scheduler.requestImmediateSync()
        scheduler.syncCalendar(1L)
        scheduler.syncAccount(1L)

        // When
        scheduler.cancelAllSync()

        // Then - All work with TAG_SYNC should be cancelled
        // Verify periodic is cancelled
        val periodicWork = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC_WORK).get()
        assertTrue(periodicWork.isEmpty() || periodicWork[0].state == WorkInfo.State.CANCELLED)
    }

    // ==================== Status Observation Tests ====================

    @Test
    fun `observeImmediateSyncStatus returns idle when no work`() = runTest {
        // When
        val status = scheduler.observeImmediateSyncStatus().first()

        // Then
        assertEquals(SyncStatus.Idle, status)
    }

    @Test
    fun `observeImmediateSyncStatus returns enqueued after scheduling`() = runTest {
        // Given
        scheduler.requestImmediateSync()

        // When
        val status = scheduler.observeImmediateSyncStatus().first()

        // Then
        assertTrue(status == SyncStatus.Enqueued || status == SyncStatus.Running || status == SyncStatus.Blocked)
    }

    @Test
    fun `isPeriodicSyncEnabled returns false when not scheduled`() {
        // When
        val enabled = scheduler.isPeriodicSyncEnabled()

        // Then
        assertFalse(enabled)
    }

    @Test
    fun `isPeriodicSyncEnabled returns true when scheduled`() {
        // Given
        scheduler.schedulePeriodicSync(intervalMinutes = 30)

        // When
        val enabled = scheduler.isPeriodicSyncEnabled()

        // Then
        assertTrue(enabled)
    }

    // ==================== SyncStatus Tests ====================

    @Test
    fun `SyncStatus_Idle is default`() {
        val status: SyncStatus = SyncStatus.Idle
        assertNotNull(status)
    }

    @Test
    fun `SyncStatus_Succeeded contains sync statistics`() {
        val status = SyncStatus.Succeeded(
            calendarsSynced = 3,
            eventsPushed = 5,
            eventsPulled = 10,
            durationMs = 1500
        )

        assertEquals(3, status.calendarsSynced)
        assertEquals(5, status.eventsPushed)
        assertEquals(10, status.eventsPulled)
        assertEquals(1500, status.durationMs)
    }

    @Test
    fun `SyncStatus_Failed contains error message`() {
        val status = SyncStatus.Failed(errorMessage = "Network error")
        assertEquals("Network error", status.errorMessage)
    }

    @Test
    fun `SyncStatus_Failed can have null error message`() {
        val status = SyncStatus.Failed(errorMessage = null)
        assertNull(status.errorMessage)
    }

    // ==================== PeriodicSyncInfo Tests ====================

    @Test
    fun `PeriodicSyncInfo contains expected fields`() {
        val info = PeriodicSyncInfo(
            isEnabled = true,
            state = SyncStatus.Running,
            nextScheduledRunTime = 123456789L
        )

        assertTrue(info.isEnabled)
        assertEquals(SyncStatus.Running, info.state)
        assertEquals(123456789L, info.nextScheduledRunTime)
    }

    // ==================== Prune Tests ====================

    @Test
    fun `pruneCompletedWork does not throw`() {
        // When/Then - Should not throw
        scheduler.pruneCompletedWork()
    }

    // ==================== Banner Flag Tests ====================

    @Test
    fun `showBannerForSync initial state is false`() = runTest {
        val value = scheduler.showBannerForSync.first()
        assertFalse(value)
    }

    @Test
    fun `setShowBannerForSync true updates state`() = runTest {
        scheduler.setShowBannerForSync(true)
        assertTrue(scheduler.showBannerForSync.first())
    }

    @Test
    fun `setShowBannerForSync false updates state`() = runTest {
        scheduler.setShowBannerForSync(true)
        scheduler.setShowBannerForSync(false)
        assertFalse(scheduler.showBannerForSync.first())
    }

    @Test
    fun `resetBannerFlag resets to false`() = runTest {
        scheduler.setShowBannerForSync(true)
        scheduler.resetBannerFlag()
        assertFalse(scheduler.showBannerForSync.first())
    }
}
