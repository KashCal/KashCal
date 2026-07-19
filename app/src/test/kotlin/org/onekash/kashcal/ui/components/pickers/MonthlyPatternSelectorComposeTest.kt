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
 * Compose tests for the monthly nth-weekday selector.
 *
 * Drives [MonthlyPatternSelector] and [RecurrencePickerRow] through their real
 * composition so that rendering from the parsed pattern (not the start date) is
 * exercised at the surface where the user sits. Runs under Robolectric so it
 * lands in the normal unit-test sweep; run the class in isolation given the
 * repo's known multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class MonthlyPatternSelectorComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Assertions match English strings and NARROW weekday labels, so pin the
    // locale rather than rely on the JVM/Robolectric default.
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

    // Sat 2026-04-18 00:00 UTC — a Saturday, the 3rd Saturday of April. Used as
    // the start-date fallback to prove the parsed rule wins over it.
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
        // A parsed "last Friday" rule opened on a Saturday-the-18th event must show
        // Last + Friday selected, NOT 3rd + Saturday from the start date.
        renderSelector(initial = MonthlyPattern.NthWeekday(-1, DayOfWeek.FRIDAY))

        composeTestRule.onNodeWithText("last").assertIsSelected()
        composeTestRule.onNodeWithText("F").assertIsSelected()
        // The start-date-derived ordinal (3rd) must NOT be highlighted.
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
        // NARROW English weekday labels: S M T W T F S -> 2x"S", 2x"T", singles M W F.
        composeTestRule.onAllNodesWithText("S").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("T").assertCountEquals(2)
        composeTestRule.onNodeWithText("F").assertIsSelected()
    }

    @Test
    fun switchingIntoNthWeekday_fromDay29Start_seedsLastNotInvalidFifth() {
        // The fresh-switch fallback clamps positional ordinal 5 (days 29-31) to
        // Last, so it seeds NthWeekday(-1, weekday) — never an invalid 5th.
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
        // The "On day N" radio reflects the parsed rule's day-of-month, NOT the
        // start date's. A BYMONTHDAY=9 rule opened on a day-18 start must read
        // "On day 9" (start date is only a fallback when the rule has none). The
        // nth-weekday rows are absent when pattern is SameDay.
        renderSelector(initial = MonthlyPattern.SameDay(9), startDayOfMonth = 18)

        composeTestRule.onNodeWithText("On day 9").assertIsSelected()
        composeTestRule.onNodeWithText("On day 18").assertDoesNotExist()
        composeTestRule.onNodeWithText("Which").assertDoesNotExist()
        composeTestRule.onNodeWithText("last").assertDoesNotExist()
    }

    @Test
    fun sameDayRule_openedOnDivergingStart_roundTripsDayNineThroughPickerRow() {
        // End-to-end guard: a BYMONTHDAY=9 rule opened on a day-18 start must show
        // "On day 9" and, when that radio is tapped, re-emit BYMONTHDAY=9 — not
        // silently rewrite to the start date's day 18. Start emitted at null (not the
        // expected value) so the assertion only passes if the tap actually emits.
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
        // The untouched "On last day" radio stays selected, rows absent.
        renderSelector(initial = MonthlyPattern.LastDay)

        composeTestRule.onNodeWithText("On last day of month").assertIsSelected()
        composeTestRule.onNodeWithText("Which").assertDoesNotExist()
    }

    @Test
    fun customMonthInterval2_lastFridayRule_showsLastFridaySelectedThroughPickerRow() {
        // The custom every-N-months host renders the same selector. Opening a
        // FREQ=MONTHLY;INTERVAL=2;BYDAY=-1FR rule on a Saturday start must show
        // Last + Friday selected — end-to-end through the custom-month path.
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
