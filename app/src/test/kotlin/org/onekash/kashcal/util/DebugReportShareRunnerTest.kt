package org.onekash.kashcal.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugReportShareRunnerTest {

    private val events = mutableListOf<String>()

    private fun runner(export: suspend () -> Result<String>) = DebugReportShareRunner(
        export = export,
        onSuccess = { events += "share $it" },
        onFailure = { events += "failed ${it.javaClass.simpleName}" },
    )

    @Test
    fun `success opens the share menu`() = runTest {
        runner { Result.success("report.txt") }.run(this)
        advanceUntilIdle()

        assertEquals(listOf("share report.txt"), events)
    }

    @Test
    fun `failure reports the failure and opens no share menu`() = runTest {
        runner { Result.failure(IllegalStateException()) }.run(this)
        advanceUntilIdle()

        assertEquals(listOf("failed IllegalStateException"), events)
    }

    @Test
    fun `a second run while one is in flight starts no second export`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var exports = 0
        val runner = runner {
            exports++
            gate.await()
            Result.success("report.txt")
        }

        runner.run(this)
        advanceUntilIdle()
        runner.run(this)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, exports)
        assertEquals(listOf("share report.txt"), events)
    }

    @Test
    fun `a run after the first finished starts a new export`() = runTest {
        var exports = 0
        val runner = runner {
            exports++
            Result.success("report.txt")
        }

        runner.run(this)
        advanceUntilIdle()
        runner.run(this)
        advanceUntilIdle()

        assertEquals(2, exports)
    }
}
