package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.onekash.kashcal.util.TimezoneUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [HomeViewModel.recentTimeZones] and [HomeViewModel.recordRecentTimeZone]: the time zone
 * sheet's recent picks come from the data store, and a pick is recorded once, with the device
 * zone so the store can leave it out. Collaborators are the strict mocks of
 * [DeviceHomeViewModelTestFactory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelRecentTimeZonesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    @Test
    fun `recent zones come from the data store`() = runTest(dispatcher) {
        every { factory.dataStore.recentTimeZones } returns MutableStateFlow(listOf("Asia/Tokyo", "Europe/Berlin"))
        val vm = createViewModel()
        val job = launch { vm.recentTimeZones.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("Asia/Tokyo", "Europe/Berlin"), vm.recentTimeZones.value)
        job.cancel()
    }

    @Test
    fun `a pick is recorded once with the device zone`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.recordRecentTimeZone("Asia/Tokyo")
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.dataStore.addRecentTimeZone("Asia/Tokyo", TimezoneUtils.getDeviceTimezone()) }
    }
}
