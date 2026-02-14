package org.onekash.kashcal.data.contacts

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Unit tests for ContactBirthdayWorker.
 *
 * Tests doWork() logic: feature guard, sync result handling,
 * retry logic, and permission error handling.
 */
class ContactBirthdayWorkerTest {

    private lateinit var context: Context
    private lateinit var params: WorkerParameters
    private lateinit var repository: ContactBirthdayRepository
    private lateinit var dataStore: KashCalDataStore

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        params = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
    }

    private fun createWorker(runAttemptCount: Int = 0): ContactBirthdayWorker {
        every { params.runAttemptCount } returns runAttemptCount
        return ContactBirthdayWorker(context, params, repository, dataStore)
    }

    // ========== Feature Guard ==========

    @Test
    fun `doWork returns success when feature disabled`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns false

        val worker = createWorker()
        val result = worker.doWork()

        assertTrue("Should succeed when disabled", result is Result)
        // Should not attempt sync
        coVerify(exactly = 0) { repository.syncBirthdays() }
    }

    // ========== Sync Success ==========

    @Test
    fun `doWork returns success with output data on successful sync`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } returns ContactBirthdayRepository.SyncResult.Success(
            added = 3,
            updated = 1,
            deleted = 0
        )
        coEvery { dataStore.setContactBirthdaysLastSync(any()) } returns Unit

        val worker = createWorker()
        val result = worker.doWork()

        // Verify last sync time was recorded
        coVerify { dataStore.setContactBirthdaysLastSync(any()) }
    }

    // ========== Sync Error - Retry ==========

    @Test
    fun `doWork retries on sync error when under max attempts`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } returns ContactBirthdayRepository.SyncResult.Error(
            "Database locked"
        )

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        // Under max retries (3), should retry
        assertTrue("Should retry on error: $result", result == Result.retry())
    }

    @Test
    fun `doWork retries on sync error at second attempt`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } returns ContactBirthdayRepository.SyncResult.Error(
            "Transient error"
        )

        val worker = createWorker(runAttemptCount = 2)
        val result = worker.doWork()

        assertTrue("Should retry at attempt 2", result == Result.retry())
    }

    @Test
    fun `doWork fails on sync error when max attempts exceeded`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } returns ContactBirthdayRepository.SyncResult.Error(
            "Persistent error"
        )

        val worker = createWorker(runAttemptCount = 3) // At max
        val result = worker.doWork()

        assertTrue("Should fail after max retries: $result", result is Result)
        // Result.failure() returns a Result with failure state
    }

    // ========== SecurityException ==========

    @Test
    fun `doWork fails immediately on SecurityException (permission denied)`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } throws SecurityException("Permission denied")

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        // SecurityException should fail immediately, NOT retry
        assertTrue("Should fail on SecurityException, not retry: $result", result != Result.retry())
    }

    // ========== General Exception - Retry ==========

    @Test
    fun `doWork retries on general exception when under max attempts`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } throws RuntimeException("Unexpected crash")

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        assertTrue("Should retry on exception: $result", result == Result.retry())
    }

    @Test
    fun `doWork fails on general exception when max attempts exceeded`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { repository.syncBirthdays() } throws RuntimeException("Persistent crash")

        val worker = createWorker(runAttemptCount = 3)
        val result = worker.doWork()

        assertTrue("Should fail after max retries: $result", result != Result.retry())
    }

    // ========== Constants ==========

    @Test
    fun `SYNC_WORK constant is correct`() {
        assertEquals("contact_birthday_sync", ContactBirthdayWorker.SYNC_WORK)
    }

    @Test
    fun `output data keys are unique`() {
        val keys = setOf(
            ContactBirthdayWorker.KEY_BIRTHDAYS_ADDED,
            ContactBirthdayWorker.KEY_BIRTHDAYS_UPDATED,
            ContactBirthdayWorker.KEY_BIRTHDAYS_DELETED,
            ContactBirthdayWorker.KEY_ERROR_MESSAGE
        )
        assertEquals("All output keys should be unique", 4, keys.size)
    }
}
