package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the full-height month view's saved pinch zoom in [HomeViewModel]: restored and clamped
 * at startup, applied at once, saved once after the pinch settles, and kept apart from the
 * Planner's zoom. Collaborators are the strict mocks of [DeviceHomeViewModelTestFactory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelMonthFullZoomTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        // Startup's account check reads this; startup has to finish for the zoom seeding.
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    @Test
    fun `startup seeds the saved month zoom, clamped`() = runTest {
        for ((saved, expected) in listOf(2.0f to 1.6f, Float.NaN to 1.0f, 0f to 1.0f, 0.9f to 0.9f)) {
            coEvery { factory.dataStore.getMonthFullZoom() } returns saved
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals("saved $saved", expected, vm.uiState.value.monthFullZoom)
        }
    }

    @Test
    fun `a month pinch updates the zoom at once and saves it after a pause`() = runTest {
        coJustRun { factory.dataStore.setMonthFullZoom(any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setMonthFullZoom(0.95f)
        vm.setMonthFullZoom(0.9f)
        assertEquals(0.9f, vm.uiState.value.monthFullZoom)
        coVerify(exactly = 0) { factory.dataStore.setMonthFullZoom(any()) }

        advanceTimeBy(1_100)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.dataStore.setMonthFullZoom(0.9f) }
    }

    @Test
    fun `the month zoom and the Planner zoom don't move each other`() = runTest {
        coEvery { factory.dataStore.getMonthFullZoom() } returns 0.9f
        coEvery { factory.dataStore.getPlannerZoom() } returns 1.3f
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setMonthFullZoom(1.5f)
        assertEquals(1.3f, vm.uiState.value.plannerZoom)
        vm.setPlannerZoom(1.1f)
        assertEquals(1.5f, vm.uiState.value.monthFullZoom)

        advanceTimeBy(1_100)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.dataStore.setMonthFullZoom(1.5f) }
        coVerify(exactly = 1) { factory.dataStore.setPlannerZoom(1.1f) }
    }
}
