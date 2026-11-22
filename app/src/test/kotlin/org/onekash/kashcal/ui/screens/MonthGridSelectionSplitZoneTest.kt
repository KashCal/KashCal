package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.testutil.SplitZone
import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.withSplitDeviceTimeZone
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.viewmodels.DeviceHomeViewModelTestFactory
import org.onekash.kashcal.ui.viewmodels.HomeViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar as JavaCalendar

/**
 * A tap on a day in the Month and Month (full) grids selects and highlights that day, with the
 * selection held by the real [HomeViewModel.selectDate], in zones where java.util and java.time
 * disagree about daylight saving time ([SplitZone], as Android reads dates after its cutoff
 * year, #435). Both the stored day and the highlighted cell are checked, so a wrong tap value
 * fails at every time of day: the highlight is off by a day for 23 hours, the selection in the
 * other hour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class MonthGridSelectionSplitZoneTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private enum class Grid { MONTH, MONTH_FULL }

    private fun newViewModel(): HomeViewModel {
        val factory = DeviceHomeViewModelTestFactory()
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    private fun tag(date: LocalDate) = "month_cell_${DayPagerUtils.localDateToDayCode(date)}"

    private val notSelected = SemanticsMatcher("is not selected") {
        it.config.getOrElse(SemanticsProperties.Selected) { false } == false
    }

    /** Shows [shown]'s grid in [zone], taps [tapped] and checks the selection and highlight. */
    private fun tapSelects(grid: Grid, zone: SplitZone, shown: YearMonth, tapped: LocalDate): Unit =
        withSplitDeviceTimeZone(zone) {
            val viewModel = newViewModel()
            rule.setContent {
                var selectedDate by remember { mutableLongStateOf(0L) }
                val onDateSelected: (Long) -> Unit = { ms ->
                    viewModel.selectDate(ms)
                    selectedDate = viewModel.uiState.value.selectedDate
                }
                MaterialTheme {
                    Column(modifier = Modifier.fillMaxSize()) {
                        when (grid) {
                            Grid.MONTH -> CalendarGrid(
                                year = shown.year,
                                month = shown.monthValue - 1,
                                selectedDate = selectedDate,
                                eventDots = persistentMapOf(),
                                onDateSelected = onDateSelected,
                                firstDayOfWeekPref = JavaCalendar.MONDAY,
                            )
                            Grid.MONTH_FULL -> FullHeightMonthGrid(
                                year = shown.year,
                                month = shown.monthValue - 1,
                                selectedDate = selectedDate,
                                monthEventsMap = persistentMapOf(),
                                // As HomeScreen wires it: the day code is what gets selected.
                                onDayTap = { dayCode, _ -> onDateSelected(DayPagerUtils.dayCodeToMs(dayCode)) },
                                firstDayOfWeekPref = JavaCalendar.MONDAY,
                                showWeekNumbers = false,
                                showEventEmojis = false,
                                nowMs = System.currentTimeMillis(),
                                todayDayCode = DayPagerUtils.localDateToDayCode(LocalDate.now()),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            rule.waitForIdle()
            rule.onNodeWithTag(tag(tapped)).performClick()
            rule.waitForIdle()

            assertEquals("selected day in $zone", tapped, phoneLocalDate(viewModel.uiState.value.selectedDate))
            if (YearMonth.from(tapped) == shown) {
                rule.onNodeWithTag(tag(tapped)).assertIsSelected()
                if (tapped.dayOfMonth > 1) rule.onNodeWithTag(tag(tapped.minusDays(1))).assertIsNotSelected()
                if (tapped.plusDays(1).month == tapped.month) rule.onNodeWithTag(tag(tapped.plusDays(1))).assertIsNotSelected()
            } else {
                // A padding day selects its own month's day; no cell of this grid is highlighted.
                // The Month grid's padding cells carry no selected state at all.
                rule.onNodeWithTag(tag(tapped)).assert(notSelected)
            }
            Unit
        }

    private val june2026 = YearMonth.of(2026, 6)
    private val june7 = LocalDate.of(2026, 6, 7)

    @Test
    fun `Month tap selects the tapped day in split Berlin`() =
        tapSelects(Grid.MONTH, SplitZone.BERLIN, june2026, june7)

    @Test
    fun `Month tap selects the tapped day in split New York`() =
        tapSelects(Grid.MONTH, SplitZone.NEW_YORK, june2026, june7)

    @Test
    fun `Month tap selects the tapped day in split Sydney`() =
        tapSelects(Grid.MONTH, SplitZone.SYDNEY, june2026, june7)

    @Test
    fun `Month tap selects a far future summer day in split Berlin`() =
        tapSelects(Grid.MONTH, SplitZone.BERLIN, YearMonth.of(2101, 7), LocalDate.of(2101, 7, 11))

    @Test
    fun `Month tap on a next month padding day selects that day in split Berlin`() =
        tapSelects(Grid.MONTH, SplitZone.BERLIN, june2026, LocalDate.of(2026, 7, 2))

    @Test
    fun `Month tap selects the tapped day in Tokyo`() =
        tapSelects(Grid.MONTH, SplitZone.TOKYO, june2026, june7)

    @Test
    fun `Month tap selects the tapped day in UTC`() =
        tapSelects(Grid.MONTH, SplitZone.UTC, june2026, june7)

    @Test
    fun `Month full tap selects the tapped day in split Berlin`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.BERLIN, june2026, june7)

    @Test
    fun `Month full tap selects the tapped day in split New York`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.NEW_YORK, june2026, june7)

    @Test
    fun `Month full tap selects the tapped day in split Sydney`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.SYDNEY, june2026, june7)

    @Test
    fun `Month full tap selects a far future summer day in split Berlin`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.BERLIN, YearMonth.of(2101, 7), LocalDate.of(2101, 7, 11))

    @Test
    fun `Month full tap on a next month padding day selects that day in split Berlin`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.BERLIN, june2026, LocalDate.of(2026, 7, 2))

    @Test
    fun `Month full tap selects the tapped day in Tokyo`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.TOKYO, june2026, june7)

    @Test
    fun `Month full tap selects the tapped day in UTC`() =
        tapSelects(Grid.MONTH_FULL, SplitZone.UTC, june2026, june7)
}
