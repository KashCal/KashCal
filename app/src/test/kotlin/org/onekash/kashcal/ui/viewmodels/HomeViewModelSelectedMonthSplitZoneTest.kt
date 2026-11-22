package org.onekash.kashcal.ui.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.testutil.SplitZone
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withSplitDeviceTimeZone
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The real [HomeViewModel] keeps the viewed month and the day label on the selected day when the
 * 1st of a summer month is selected, in zones where java.util and java.time disagree about
 * daylight saving time ([SplitZone], as Android reads dates after its cutoff year, #435).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelSelectedMonthSplitZoneTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(): HomeViewModel {
        val factory = DeviceHomeViewModelTestFactory()
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    private val julyFirst = LocalDate.of(2026, 7, 1)

    /** Selects [julyFirst] in [zone], switches to [mode] and checks the viewed month and label. */
    private fun switchLandsOnJuly(zone: SplitZone, mode: ViewMode): Unit = withSplitDeviceTimeZone(zone) {
        val viewModel = newViewModel()
        viewModel.selectDate(phoneMidnight(julyFirst))
        viewModel.setViewMode(ViewMode.INSIGHTS, persist = false)
        viewModel.setViewMode(mode, persist = false)

        val state = viewModel.uiState.value
        assertEquals("viewed year in $zone", 2026, state.viewingYear)
        assertEquals("viewed month (0-based) in $zone", 6, state.viewingMonth)
        val expectedLabel = julyFirst.format(DateTimeUtils.localizedFormatter("yMMMMd"))
        assertEquals("label in $zone", expectedLabel, state.selectedDayLabel)
    }

    @Test
    fun `the day label names July 1 in split Berlin`(): Unit = withSplitDeviceTimeZone(SplitZone.BERLIN) {
        val viewModel = newViewModel()
        viewModel.selectDate(phoneMidnight(julyFirst))
        val expected = julyFirst.format(DateTimeUtils.localizedFormatter("yMMMMd"))
        assertEquals(expected, viewModel.uiState.value.selectedDayLabel)
    }

    @Test
    fun `switching to Month with July 1 selected shows July in split Berlin`() =
        switchLandsOnJuly(SplitZone.BERLIN, ViewMode.MONTH)

    @Test
    fun `switching to Month with July 1 selected shows July in split New York`() =
        switchLandsOnJuly(SplitZone.NEW_YORK, ViewMode.MONTH)

    @Test
    fun `switching to Month full with July 1 selected shows July in split Berlin`() =
        switchLandsOnJuly(SplitZone.BERLIN, ViewMode.MONTH_FULL)

    @Test
    fun `switching to Month with July 1 selected shows July in Tokyo`() =
        switchLandsOnJuly(SplitZone.TOKYO, ViewMode.MONTH)

    @Test
    fun `switching to Month with July 1 selected shows July in UTC`() =
        switchLandsOnJuly(SplitZone.UTC, ViewMode.MONTH)
}
