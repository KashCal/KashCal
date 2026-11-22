package org.onekash.kashcal.calendarbackup

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupWorkerTest {

    private val runner = mockk<CalendarBackupRunner>()
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun worker(kind: RunKind?): CalendarBackupWorker {
        val builder = TestListenableWorkerBuilder<CalendarBackupWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = CalendarBackupWorker(appContext, workerParameters, runner)
            })
        if (kind != null) builder.setInputData(CalendarBackupWorker.inputFor(kind))
        return builder.build()
    }

    @Test
    fun `the run kind reaches the runner`() = runBlocking {
        coEvery { runner.run(any()) } returns BackupRunOutcome.Skipped
        worker(RunKind.ONE_TIME).doWork()
        worker(RunKind.PERIODIC).doWork()
        coVerify(exactly = 1) { runner.run(RunKind.ONE_TIME) }
        coVerify(exactly = 1) { runner.run(RunKind.PERIODIC) }
    }

    @Test
    fun `a request without a kind runs as periodic`() = runBlocking {
        coEvery { runner.run(any()) } returns BackupRunOutcome.Skipped
        worker(null).doWork()
        coVerify(exactly = 1) { runner.run(RunKind.PERIODIC) }
    }

    @Test
    fun `it succeeds even when the run records failures or throws`() = runBlocking {
        coEvery { runner.run(any()) } returns
            BackupRunOutcome.Done(RunKind.PERIODIC, FolderFailure.NO_ACCESS, emptyList())
        assertEquals(ListenableWorker.Result.success(), worker(RunKind.PERIODIC).doWork())

        coEvery { runner.run(any()) } throws IllegalStateException("boom")
        assertEquals(ListenableWorker.Result.success(), worker(RunKind.PERIODIC).doWork())
    }
}
