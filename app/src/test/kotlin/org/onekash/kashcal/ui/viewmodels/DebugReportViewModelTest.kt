package org.onekash.kashcal.ui.viewmodels

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.domain.diagnostics.DebugReportExporter

@OptIn(ExperimentalCoroutinesApi::class)
class DebugReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val exporter = mockk<DebugReportExporter>()
    private val uri = mockk<Uri>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `events wait for a screen that starts collecting after the export ends`() = runTest(dispatcher) {
        coEvery { exporter.export() } returns Result.success(uri)
        val viewModel = DebugReportViewModel(exporter)

        viewModel.export()
        advanceUntilIdle()

        assertEquals(
            listOf(DebugReportEvent.Ready(uri)),
            viewModel.events.take(1).toList()
        )
    }

    @Test
    fun `a failed export reports Failed`() = runTest(dispatcher) {
        coEvery { exporter.export() } returns Result.failure(IllegalStateException())
        val viewModel = DebugReportViewModel(exporter)

        viewModel.export()
        advanceUntilIdle()

        assertEquals(
            listOf(DebugReportEvent.Failed),
            viewModel.events.take(1).toList()
        )
    }

    @Test
    fun `a second tap during an export starts no second export`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { exporter.export() } coAnswers {
            gate.await()
            Result.success(uri)
        }
        val viewModel = DebugReportViewModel(exporter)

        viewModel.export()
        advanceUntilIdle()
        viewModel.export()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { exporter.export() }
    }
}
