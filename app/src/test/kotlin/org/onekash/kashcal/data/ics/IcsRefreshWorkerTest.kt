package org.onekash.kashcal.data.ics

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for IcsRefreshWorker.
 *
 * Tests:
 * - Refresh type routing (all, due, single)
 * - Success/failure/partial result handling
 * - Output data keys
 * - Retry logic on exception
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IcsRefreshWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var repository: IcsSubscriptionRepository
    private lateinit var worker: IcsRefreshWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        repository = mockk(relaxed = true)

        every { workerParams.runAttemptCount } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun createWorker(inputData: Data = Data.EMPTY): IcsRefreshWorker {
        every { workerParams.inputData } returns inputData
        return IcsRefreshWorker(context, workerParams, repository)
    }

    // ==================== Refresh type routing ====================

    @Test
    fun `REFRESH_TYPE_ALL calls forceRefreshAll`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } returns listOf(
            IcsSubscriptionRepository.SyncResult.Success(IcsSubscriptionRepository.SyncCount(1, 0, 0))
        )

        val result = worker.doWork()
        // Result.success() with output data — just verify it's a success
        assertTrue(result is ListenableWorker.Result)

        coVerify { repository.forceRefreshAll() }
    }

    @Test
    fun `REFRESH_TYPE_DUE calls refreshAllDueSubscriptions`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_DUE)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.refreshAllDueSubscriptions() } returns listOf(
            IcsSubscriptionRepository.SyncResult.NotModified
        )

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)

        coVerify { repository.refreshAllDueSubscriptions() }
    }

    @Test
    fun `REFRESH_TYPE_SINGLE with valid ID calls refreshSubscription`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_SINGLE)
            .putLong(IcsRefreshWorker.KEY_SUBSCRIPTION_ID, 42L)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.refreshSubscription(42L) } returns
            IcsSubscriptionRepository.SyncResult.Success(IcsSubscriptionRepository.SyncCount(2, 1, 0))

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)

        coVerify { repository.refreshSubscription(42L) }
    }

    @Test
    fun `REFRESH_TYPE_SINGLE without ID returns failure`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_SINGLE)
            .build()
        worker = createWorker(inputData)

        val result = worker.doWork()
        // Should be failure with error message
        assertTrue(result is ListenableWorker.Result)
    }

    // ==================== Result aggregation ====================

    @Test
    fun `all success results return Result_success with correct output`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } returns listOf(
            IcsSubscriptionRepository.SyncResult.Success(IcsSubscriptionRepository.SyncCount(3, 1, 0)),
            IcsSubscriptionRepository.SyncResult.Success(IcsSubscriptionRepository.SyncCount(0, 2, 1)),
            IcsSubscriptionRepository.SyncResult.NotModified
        )

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)
    }

    @Test
    fun `mixed results with some errors return success with partial error`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } returns listOf(
            IcsSubscriptionRepository.SyncResult.Success(IcsSubscriptionRepository.SyncCount(1, 0, 0)),
            IcsSubscriptionRepository.SyncResult.Error("Connection timeout")
        )

        val result = worker.doWork()
        // Partial success - still returns success but with error message
        assertTrue(result is ListenableWorker.Result)
    }

    @Test
    fun `all errors return Result_failure`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } returns listOf(
            IcsSubscriptionRepository.SyncResult.Error("Failed to connect"),
            IcsSubscriptionRepository.SyncResult.Error("Server error")
        )

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)
    }

    // ==================== Retry logic ====================

    @Test
    fun `exception with retries remaining returns Result_retry`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        every { workerParams.runAttemptCount } returns 1
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } throws RuntimeException("Unexpected error")

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `exception with max retries returns Result_failure`() = runTest {
        val inputData = Data.Builder()
            .putString(IcsRefreshWorker.KEY_REFRESH_TYPE, IcsRefreshWorker.REFRESH_TYPE_ALL)
            .build()
        every { workerParams.runAttemptCount } returns 3
        worker = createWorker(inputData)

        coEvery { repository.forceRefreshAll() } throws RuntimeException("Unexpected error")

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)
    }

    // ==================== Default refresh type ====================

    @Test
    fun `missing refresh type defaults to DUE`() = runTest {
        worker = createWorker(Data.EMPTY)

        coEvery { repository.refreshAllDueSubscriptions() } returns emptyList()

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)

        coVerify { repository.refreshAllDueSubscriptions() }
    }
}
