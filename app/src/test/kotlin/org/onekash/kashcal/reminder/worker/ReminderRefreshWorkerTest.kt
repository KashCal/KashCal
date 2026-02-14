package org.onekash.kashcal.reminder.worker

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ReminderRefreshWorker.
 *
 * Tests:
 * - Normal doWork success path
 * - Migration logic (version check)
 * - Cleanup failure is best-effort
 * - Retry/failure logic
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderRefreshWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var dataStore: KashCalDataStore
    private lateinit var worker: ReminderRefreshWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)

        every { workerParams.runAttemptCount } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun createWorker(): ReminderRefreshWorker {
        return ReminderRefreshWorker(context, workerParams, reminderScheduler, dataStore)
    }

    @Test
    fun `doWork success calls scheduleUpcomingReminders and cleanupOldReminders`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } returns 1
        coEvery { reminderScheduler.scheduleUpcomingReminders(30) } returns 5

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { reminderScheduler.scheduleUpcomingReminders(30) }
        coVerify { reminderScheduler.cleanupOldReminders() }
    }

    @Test
    fun `doWork runs migration when migrationVersion is less than 1`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } returns 0
        coEvery { reminderScheduler.scheduleUpcomingReminders(any()) } returns 0

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { reminderScheduler.rescheduleAllPending() }
        coVerify { dataStore.setReminderMigrationVersion(1) }
    }

    @Test
    fun `doWork skips migration when migrationVersion is 1 or above`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } returns 1
        coEvery { reminderScheduler.scheduleUpcomingReminders(any()) } returns 0

        worker = createWorker()
        worker.doWork()

        coVerify(exactly = 0) { reminderScheduler.rescheduleAllPending() }
    }

    @Test
    fun `cleanup failure still returns success`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } returns 1
        coEvery { reminderScheduler.scheduleUpcomingReminders(any()) } returns 3
        coEvery { reminderScheduler.cleanupOldReminders() } throws RuntimeException("Cleanup failed")

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `exception with retries less than 3 returns retry`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } throws RuntimeException("DB error")
        every { workerParams.runAttemptCount } returns 1

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `exception with retries at 3 returns failure`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } throws RuntimeException("DB error")
        every { workerParams.runAttemptCount } returns 3

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `exception with retries above 3 returns failure`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } throws RuntimeException("DB error")
        every { workerParams.runAttemptCount } returns 5

        worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork uses SCAN_WINDOW_DAYS constant for scheduling`() = runTest {
        coEvery { dataStore.getReminderMigrationVersion() } returns 1
        coEvery { reminderScheduler.scheduleUpcomingReminders(any()) } returns 0

        worker = createWorker()
        worker.doWork()

        coVerify { reminderScheduler.scheduleUpcomingReminders(ReminderRefreshWorker.SCAN_WINDOW_DAYS) }
    }
}
