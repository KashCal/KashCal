package org.onekash.kashcal.widget

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Tests [WidgetRetryWorker]'s private `isTransientError` classification through reflection: the
 * choice between retry and failure.
 *
 * `doWork` itself isn't driven: it reaches Glance's `updateAll` extension functions through
 * [refreshAllWidgets], which are hard to mock in unit tests. The attempt cap isn't tested here.
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

    /** Invokes the private `isTransientError` through reflection. */
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
