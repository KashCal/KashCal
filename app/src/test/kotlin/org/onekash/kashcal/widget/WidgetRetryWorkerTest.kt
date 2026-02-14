package org.onekash.kashcal.widget

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.work.WorkerParameters
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Unit tests for WidgetRetryWorker.
 *
 * Tests the isTransientError() classification logic via reflection,
 * which is the core decision point for retry vs failure.
 *
 * Note: doWork() itself calls Glance updateAll() extension functions
 * which cannot be easily mocked in unit tests. The retry/failure
 * behavior is tested via the isTransientError classification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WidgetRetryWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var worker: WidgetRetryWorker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)

        every { workerParams.runAttemptCount } returns 0

        worker = WidgetRetryWorker(context, workerParams)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /**
     * Invoke the private isTransientError method via reflection.
     */
    private fun invokeIsTransientError(e: Exception): Boolean {
        val method = WidgetRetryWorker::class.java.getDeclaredMethod("isTransientError", Exception::class.java)
        method.isAccessible = true
        return method.invoke(worker, e) as Boolean
    }

    // ==================== isTransientError classification ====================

    @Test
    fun `IOException is transient`() {
        assertTrue(invokeIsTransientError(IOException("Network error")))
    }

    @Test
    fun `SocketTimeoutException is transient (subclass of IOException)`() {
        assertTrue(invokeIsTransientError(SocketTimeoutException("Timeout")))
    }

    @Test
    fun `RemoteException is transient`() {
        assertTrue(invokeIsTransientError(RemoteException("Binder failed")))
    }

    @Test
    fun `SecurityException is not transient`() {
        assertFalse(invokeIsTransientError(SecurityException("Permission denied")))
    }

    @Test
    fun `IllegalStateException is not transient`() {
        assertFalse(invokeIsTransientError(IllegalStateException("Bad state")))
    }

    @Test
    fun `RuntimeException is not transient`() {
        assertFalse(invokeIsTransientError(RuntimeException("Something broke")))
    }
}
