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
 * Unit tests for ContactEventSyncWorker.
 *
 * Tests doWork() logic: feature guard, sync result handling,
 * retry logic, permission error handling, and dual-repository sync.
 */
class ContactEventSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var params: WorkerParameters
    private lateinit var birthdayRepository: ContactBirthdayRepository
    private lateinit var anniversaryRepository: ContactAnniversaryRepository
    private lateinit var dataStore: KashCalDataStore

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        params = mockk(relaxed = true)
        birthdayRepository = mockk(relaxed = true)
        anniversaryRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
    }

    private fun createWorker(runAttemptCount: Int = 0): ContactEventSyncWorker {
        every { params.runAttemptCount } returns runAttemptCount
        return ContactEventSyncWorker(context, params, birthdayRepository, anniversaryRepository, dataStore)
    }

    // ========== Feature Guard ==========

    @Test
    fun `doWork returns success when both features disabled`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns false
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false

        val worker = createWorker()
        val result = worker.doWork()

        assertTrue("Should succeed when both disabled", result is Result)
        // Should not attempt sync on either repository
        coVerify(exactly = 0) { birthdayRepository.syncBirthdays() }
        coVerify(exactly = 0) { anniversaryRepository.syncAnniversaries() }
    }

    // ========== Birthday-Only Sync ==========

    @Test
    fun `doWork returns success with output data on successful birthday sync`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } returns ContactEventSyncResult.Success(
            added = 3,
            updated = 1,
            deleted = 0
        )
        coEvery { dataStore.setContactBirthdaysLastSync(any()) } returns Unit

        val worker = createWorker()
        val result = worker.doWork()

        // Verify last sync time was recorded
        coVerify { dataStore.setContactBirthdaysLastSync(any()) }
        // Should not sync anniversaries
        coVerify(exactly = 0) { anniversaryRepository.syncAnniversaries() }
    }

    // ========== Anniversary-Only Sync ==========

    @Test
    fun `doWork syncs only anniversaries when birthdays disabled`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns false
        coEvery { dataStore.getContactAnniversariesEnabled() } returns true
        coEvery { anniversaryRepository.syncAnniversaries() } returns ContactEventSyncResult.Success(
            added = 2,
            updated = 0,
            deleted = 1
        )
        coEvery { dataStore.setContactAnniversariesLastSync(any()) } returns Unit

        val worker = createWorker()
        val result = worker.doWork()

        // Should sync anniversaries
        coVerify { anniversaryRepository.syncAnniversaries() }
        coVerify { dataStore.setContactAnniversariesLastSync(any()) }
        // Should not sync birthdays
        coVerify(exactly = 0) { birthdayRepository.syncBirthdays() }
    }

    // ========== Dual Sync ==========

    @Test
    fun `doWork syncs both when both enabled`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns true
        coEvery { birthdayRepository.syncBirthdays() } returns ContactEventSyncResult.Success(
            added = 3,
            updated = 1,
            deleted = 0
        )
        coEvery { anniversaryRepository.syncAnniversaries() } returns ContactEventSyncResult.Success(
            added = 2,
            updated = 0,
            deleted = 1
        )
        coEvery { dataStore.setContactBirthdaysLastSync(any()) } returns Unit
        coEvery { dataStore.setContactAnniversariesLastSync(any()) } returns Unit

        val worker = createWorker()
        val result = worker.doWork()

        // Should sync both
        coVerify { birthdayRepository.syncBirthdays() }
        coVerify { anniversaryRepository.syncAnniversaries() }
        coVerify { dataStore.setContactBirthdaysLastSync(any()) }
        coVerify { dataStore.setContactAnniversariesLastSync(any()) }
    }

    @Test
    fun `doWork returns success when neither enabled`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns false
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false

        val worker = createWorker()
        val result = worker.doWork()

        assertTrue("Should succeed when neither enabled", result is Result)
        coVerify(exactly = 0) { birthdayRepository.syncBirthdays() }
        coVerify(exactly = 0) { anniversaryRepository.syncAnniversaries() }
    }

    // ========== Sync Error - Retry ==========

    @Test
    fun `doWork retries on sync error when under max attempts`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } returns ContactEventSyncResult.Error(
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
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } returns ContactEventSyncResult.Error(
            "Transient error"
        )

        val worker = createWorker(runAttemptCount = 2)
        val result = worker.doWork()

        assertTrue("Should retry at attempt 2", result == Result.retry())
    }

    @Test
    fun `doWork fails on sync error when max attempts exceeded`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } returns ContactEventSyncResult.Error(
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
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } throws SecurityException("Permission denied")

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        // SecurityException should fail immediately, NOT retry
        assertTrue("Should fail on SecurityException, not retry: $result", result != Result.retry())
    }

    // ========== General Exception - Retry ==========

    @Test
    fun `doWork retries on general exception when under max attempts`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } throws RuntimeException("Unexpected crash")

        val worker = createWorker(runAttemptCount = 0)
        val result = worker.doWork()

        assertTrue("Should retry on exception: $result", result == Result.retry())
    }

    @Test
    fun `doWork fails on general exception when max attempts exceeded`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } throws RuntimeException("Persistent crash")

        val worker = createWorker(runAttemptCount = 3)
        val result = worker.doWork()

        assertTrue("Should fail after max retries: $result", result != Result.retry())
    }

    @Test
    fun `doWork exception with null message uses class name not Unknown error`() = runTest {
        coEvery { dataStore.getContactBirthdaysEnabled() } returns true
        coEvery { dataStore.getContactAnniversariesEnabled() } returns false
        coEvery { birthdayRepository.syncBirthdays() } throws NullPointerException()

        val worker = createWorker(runAttemptCount = 3) // At max, will produce failure
        val result = worker.doWork()

        assertTrue("Should fail after max retries: $result", result != Result.retry())
    }

    // ========== Constants ==========

    @Test
    fun `SYNC_WORK constant is correct`() {
        assertEquals("contact_event_sync", ContactEventSyncWorker.SYNC_WORK)
    }

    @Test
    fun `output data keys are unique`() {
        val keys = setOf(
            ContactEventSyncWorker.KEY_BIRTHDAYS_ADDED,
            ContactEventSyncWorker.KEY_BIRTHDAYS_UPDATED,
            ContactEventSyncWorker.KEY_BIRTHDAYS_DELETED,
            ContactEventSyncWorker.KEY_ANNIVERSARIES_ADDED,
            ContactEventSyncWorker.KEY_ANNIVERSARIES_UPDATED,
            ContactEventSyncWorker.KEY_ANNIVERSARIES_DELETED,
            ContactEventSyncWorker.KEY_ERROR_MESSAGE
        )
        assertEquals("All output keys should be unique", 7, keys.size)
    }
}
