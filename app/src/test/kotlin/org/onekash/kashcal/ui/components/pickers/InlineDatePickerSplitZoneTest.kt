package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.testutil.SplitZone
import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.testutil.withSplitDeviceTimeZone
import org.onekash.kashcal.ui.util.SelectedDay
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Calendar as JavaCalendar

/**
 * The shared inline date picker highlights the selected day and a tap yields the tapped day, in
 * zones where java.util and java.time disagree about daylight saving time ([SplitZone], as
 * Android reads dates after its cutoff year, #435). The selected date comes in as java.time
 * computes it (the start of the day, as HomeViewModel and the repeat rule's Until hand it over),
 * and every consumer reads the tapped value's day with java.time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InlineDatePickerSplitZoneTest {

    @get:Rule
    val rule = createComposeRule()

    private val june7 = LocalDate.of(2026, 6, 7)

    /** Shows the picker on [selected]'s month and returns the days its taps yield. */
    private fun showPicker(selected: LocalDate): MutableList<LocalDate> {
        val tapped = mutableListOf<LocalDate>()
        val selectedMs = phoneMidnight(selected)
        rule.setContent {
            MaterialTheme {
                // Opens on the selected date's month, as the picker's callers build it.
                var displayedMonth by remember { mutableStateOf(SelectedDay.yearMonth(selectedMs)) }
                InlineDatePickerContent(
                    selectedDateMillis = selectedMs,
                    displayedMonth = displayedMonth,
                    onDateSelect = { ms -> tapped += phoneLocalDate(ms) },
                    onMonthChange = { displayedMonth = it },
                    firstDayOfWeek = JavaCalendar.MONDAY,
                )
            }
        }
        rule.waitForIdle()
        return tapped
    }

    private fun highlightsTheSelectedDay(zone: SplitZone): Unit = withSplitDeviceTimeZone(zone) {
        showPicker(june7)
        rule.onNodeWithText("7").assertIsSelected()
        rule.onNodeWithText("6").assertIsNotSelected()
        Unit
    }

    private fun tapYieldsTheTappedDay(zone: SplitZone): Unit = withSplitDeviceTimeZone(zone) {
        val tapped = showPicker(june7)
        rule.onNodeWithText("15").performClick()
        rule.waitForIdle()
        assertEquals("tapped day in $zone", listOf(LocalDate.of(2026, 6, 15)), tapped)
    }

    @Test
    fun `the selected day is highlighted in split Berlin`() = highlightsTheSelectedDay(SplitZone.BERLIN)

    @Test
    fun `the selected day is highlighted in split New York`() = highlightsTheSelectedDay(SplitZone.NEW_YORK)

    @Test
    fun `the selected day is highlighted in Tokyo`() = highlightsTheSelectedDay(SplitZone.TOKYO)

    @Test
    fun `a tap yields the tapped day in split Berlin`() = tapYieldsTheTappedDay(SplitZone.BERLIN)

    @Test
    fun `a tap yields the tapped day in split New York`() = tapYieldsTheTappedDay(SplitZone.NEW_YORK)

    @Test
    fun `a tap yields the tapped day in UTC`() = tapYieldsTheTappedDay(SplitZone.UTC)
}
