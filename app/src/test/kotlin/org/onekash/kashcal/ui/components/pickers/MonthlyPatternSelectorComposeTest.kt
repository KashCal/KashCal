package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.util.Calendar
import java.util.Locale

/**
 * Compose tests for the monthly pattern selector: the day, last-day and nth-weekday options show
 * the parsed rule, not the start date, and taps emit the chosen pattern.
 *
 * Renders [MonthlyPatternSelector] directly and through [RecurrencePickerRow], the surface the
 * user sees. Runs under Robolectric in the normal unit-test sweep; run the class in isolation,
 * since Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class MonthlyPatternSelectorComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Assertions match English strings and narrow weekday labels, so the locale is pinned.
    private var originalLocale: Locale? = null

    @Before
    fun pinLocaleToUS() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    // Sat 2026-04-18 00:00 UTC, the 3rd Saturday of April: the parsed rule must win over this
    // start date.
    private val saturday18Millis = 1776470400000L

    private fun renderSelector(
        initial: MonthlyPattern,
        startWeekday: DayOfWeek = DayOfWeek.SATURDAY,
        startOrdinal: Int = 3,
        startDayOfMonth: Int = 18,
        firstDayOfWeek: Int = Calendar.SUNDAY,
        onChange: (MonthlyPattern) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                var pattern by remember { mutableStateOf(initial) }
                MonthlyPatternSelector(
                    pattern = pattern,
                    dayOfMonth = startDayOfMonth,
                    ordinalInMonth = startOrdinal,
                    weekday = startWeekday,
                    onPatternChange = {
                        pattern = it
                        onChange(it)
                    },
                    firstDayOfWeek = firstDayOfWeek,
                )
            }
        }
    }

    @Test
    fun nthWeekdayPattern_showsLastAndFriday_regardlessOfSaturdayStart() {
        // A parsed "last Friday" rule opened on a Saturday-the-18th event must show Last and
        // Friday selected, not 3rd and Saturday from the start date.
        renderSelector(initial = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY))

        composeTestRule.onNodeWithText("last").assertIsSelected()
        composeTestRule.onNodeWithText("F").assertIsSelected()
        // The start date's ordinal (3rd) must not be highlighted.
        composeTestRule.onNodeWithText("3rd").assertIsNotSelected()
    }

    @Test
    fun clickingLastThenFriday_emitsLastFriday() {
        var last: MonthlyPattern? = null
        renderSelector(
            initial = MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY),
            onChange = { last = it },
        )

        composeTestRule.onNodeWithText("last").performClick()
        composeTestRule.onNodeWithText("F").performClick()

        assertEquals(MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY), last)
    }

    @Test
    fun clickingSecondThenMonday_emitsSecondMonday() {
        var last: MonthlyPattern? = null
        renderSelector(
            initial = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY),
            onChange = { last = it },
        )

        composeTestRule.onNodeWithText("2nd").performClick()
        composeTestRule.onNodeWithText("M").performClick()

        assertEquals(MonthlyPattern.NthWeekday(2, DayOfWeek.MONDAY), last)
    }

    @Test
    fun sevenWeekdayCircles_allRenderAtNarrowWidth() {
        renderSelector(initial = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY))
        // Narrow English weekday labels S M T W T F S: two "S", two "T", one each of M, W, F.
        composeTestRule.onAllNodesWithText("S").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("T").assertCountEquals(2)
        composeTestRule.onNodeWithText("F").assertIsSelected()
    }

    @Test
    fun switchingIntoNthWeekday_fromDay29Start_seedsLastNotInvalidFifth() {
        // Switching in from another pattern clamps start-date ordinal 5 (days 29-31) to Last, so
        // it seeds NthWeekday(-1, weekday), never an invalid 5th.
        var last: MonthlyPattern? = null
        renderSelector(
            initial = MonthlyPattern.SameDay(29),
            startWeekday = DayOfWeek.FRIDAY,
            startOrdinal = 5,
            startDayOfMonth = 29,
            onChange = { last = it },
        )

        // The nth-weekday radio previews the clamped fallback: "On the last Friday".
        composeTestRule.onNodeWithText("On the last Friday").performClick()

        assertEquals(MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY), last)
    }

    @Test
    fun sameDayPattern_showsRuleDayNotStartDay_andHidesOrdinalRows() {
        // The "On day N" radio shows the parsed rule's day of month, not the start date's: a
        // BYMONTHDAY=9 rule opened on a day-18 start reads "On day 9". The nth-weekday rows are
        // absent for SameDay.
        renderSelector(initial = MonthlyPattern.SameDay(9), startDayOfMonth = 18)

        composeTestRule.onNodeWithText("On day 9").assertIsSelected()
        composeTestRule.onNodeWithText("On day 18").assertDoesNotExist()
        composeTestRule.onNodeWithText("Which").assertDoesNotExist()
        composeTestRule.onNodeWithText("last").assertDoesNotExist()
    }

    @Test
    fun sameDayRule_openedOnDivergingStart_roundTripsDayNineThroughPickerRow() {
        // A BYMONTHDAY=9 rule opened on a day-18 start must show "On day 9" and, when that radio
        // is tapped, emit BYMONTHDAY=9, not silently rewrite to the start date's day 18.
        // `emitted` starts at null so the assertion passes only if the tap emits.
        var emitted: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember { mutableStateOf<String?>("FREQ=MONTHLY;BYMONTHDAY=9") }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = saturday18Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it; emitted = it },
                )
            }
        }

        composeTestRule.onNodeWithText("On day 9").assertIsSelected()
        composeTestRule.onNodeWithText("On day 9").performClick()
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=9", emitted)
    }

    @Test
    fun lastDayPattern_selectsLastDayRadio_andHidesOrdinalRows() {
        // The untouched "On last day" radio stays selected and the nth-weekday rows are absent.
        renderSelector(initial = MonthlyPattern.LastDay)

        composeTestRule.onNodeWithText("On last day of month").assertIsSelected()
        composeTestRule.onNodeWithText("Which").assertDoesNotExist()
    }

    @Test
    fun customMonthInterval2_lastFridayRule_showsLastFridaySelectedThroughPickerRow() {
        // The custom every-N-months path renders the same selector. Opening a
        // FREQ=MONTHLY;INTERVAL=2;BYDAY=-1FR rule on a Saturday start must select Last and Friday.
        composeTestRule.setContent {
            MaterialTheme {
                var rrule by remember {
                    mutableStateOf<String?>("FREQ=MONTHLY;INTERVAL=2;BYDAY=-1FR")
                }
                RecurrencePickerRow(
                    selectedRrule = rrule,
                    startDateMillis = saturday18Millis,
                    isExpanded = true,
                    onToggle = {},
                    onSelect = { rrule = it },
                )
            }
        }

        composeTestRule.onNodeWithText("last").assertIsSelected()
        composeTestRule.onNodeWithText("F").assertIsSelected()
    }
}
