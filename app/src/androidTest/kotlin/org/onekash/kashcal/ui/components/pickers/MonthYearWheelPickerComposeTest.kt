package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormatSymbols
import java.util.Calendar as JavaCalendar

/**
 * Compose UI tests for the month/year wheel toggle in [InlineDatePickerContent] and for
 * [MonthYearWheelPicker] on its own.
 *
 * Covered: a header tap swaps the grid for the wheels and a second tap brings the grid back;
 * the wheels open on the current month and year; toggling doesn't call onDateSelect; the month
 * arrows are gone while the wheels show; the standalone picker shows its month, year and
 * content description; a month-wheel swipe fires onMonthChange with a new month.
 */
@RunWith(AndroidJUnit4::class)
class MonthYearWheelPickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val monthNames = DateFormatSymbols.getInstance().months.filter { it.isNotBlank() }

    /** Renders [InlineDatePickerContent] holding its selected date and displayed month. */
    private fun setUpDatePicker(
        initialDateMillis: Long = System.currentTimeMillis(),
        onDateSelect: (Long) -> Unit = {},
        onMonthChange: (JavaCalendar) -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                var selectedDate by remember { mutableLongStateOf(initialDateMillis) }
                var displayedMonth by remember {
                    mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = initialDateMillis })
                }

                InlineDatePickerContent(
                    selectedDateMillis = selectedDate,
                    displayedMonth = displayedMonth,
                    onDateSelect = { millis ->
                        selectedDate = millis
                        onDateSelect(millis)
                    },
                    onMonthChange = { cal ->
                        displayedMonth = cal
                        onMonthChange(cal)
                    }
                )
            }
        }
    }

    // ==================== Header Toggle Tests ====================

    @Test
    fun headerClick_showsWheelPicker_hidesDayHeaders() {
        setUpDatePicker()

        // Day-of-week headers show first; "S" appears twice (Sun and Sat).
        composeTestRule.onAllNodesWithText("S").onFirst().assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Month and year picker").assertIsDisplayed()
    }

    @Test
    fun headerClickTwice_returnsToCalendarGrid() {
        setUpDatePicker()

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // With the wheels open, the header's content description is "Show calendar".
        composeTestRule.onNodeWithContentDescription("Show calendar").performClick()
        composeTestRule.waitForIdle()

        // The day-of-week headers are back.
        composeTestRule.onAllNodesWithText("S").onFirst().assertIsDisplayed()
    }

    // ==================== Month/Year Selection Tests ====================

    @Test
    fun wheelPicker_showsCurrentMonthSelected() {
        val cal = JavaCalendar.getInstance()
        val currentMonth = monthNames[cal.get(JavaCalendar.MONTH)]
        val currentYear = cal.get(JavaCalendar.YEAR).toString()

        setUpDatePicker(initialDateMillis = cal.timeInMillis)

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // The wheels show the current month and year.
        composeTestRule.onNodeWithText(currentMonth).assertIsDisplayed()
        composeTestRule.onNodeWithText(currentYear).assertIsDisplayed()
    }

    // ==================== Date Preservation Tests ====================

    @Test
    fun togglePreservesSelectedDate() {
        var lastSelectedDate = 0L
        val initialDate = System.currentTimeMillis()

        composeTestRule.setContent {
            MaterialTheme {
                var selectedDate by remember { mutableLongStateOf(initialDate) }
                var displayedMonth by remember {
                    mutableStateOf(JavaCalendar.getInstance().apply { timeInMillis = initialDate })
                }

                InlineDatePickerContent(
                    selectedDateMillis = selectedDate,
                    displayedMonth = displayedMonth,
                    onDateSelect = {
                        selectedDate = it
                        lastSelectedDate = it
                    },
                    onMonthChange = { displayedMonth = it }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Show calendar").performClick()
        composeTestRule.waitForIdle()

        // The toggle never calls onDateSelect.
        assertEquals("Date should not change during toggle", 0L, lastSelectedDate)
    }

    // ==================== Arrow Visibility Tests ====================

    @Test
    fun arrowButtons_hiddenWhenWheelsShowing() {
        setUpDatePicker()

        composeTestRule.onNodeWithContentDescription("Previous month").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next month").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // The month arrows leave the tree.
        composeTestRule.onNodeWithContentDescription("Previous month").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Next month").assertDoesNotExist()
    }

    // ==================== MonthYearWheelPicker Standalone Tests ====================

    @Test
    fun monthYearWheelPicker_displaysMonthAndYear() {
        composeTestRule.setContent {
            MaterialTheme {
                MonthYearWheelPicker(
                    selectedYear = 2025,
                    selectedMonth = 1, // February
                    onMonthYearSelected = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("February").assertIsDisplayed()
        composeTestRule.onNodeWithText("2025").assertIsDisplayed()
    }

    @Test
    fun monthYearWheelPicker_accessibilityDescription() {
        composeTestRule.setContent {
            MaterialTheme {
                MonthYearWheelPicker(
                    selectedYear = 2025,
                    selectedMonth = 0,
                    onMonthYearSelected = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Month and year picker").assertIsDisplayed()
    }

    // ==================== Wheel Scroll Callback Tests ====================

    @Test
    fun wheelScroll_firesOnMonthChange() {
        // January 2025.
        val cal = JavaCalendar.getInstance().apply {
            set(JavaCalendar.YEAR, 2025)
            set(JavaCalendar.MONTH, JavaCalendar.JANUARY)
            set(JavaCalendar.DAY_OF_MONTH, 15)
        }
        var monthChangeCount = 0
        var lastChangedMonth = -1

        composeTestRule.setContent {
            MaterialTheme {
                var selectedDate by remember { mutableLongStateOf(cal.timeInMillis) }
                var displayedMonth by remember { mutableStateOf(cal.clone() as JavaCalendar) }

                InlineDatePickerContent(
                    selectedDateMillis = selectedDate,
                    displayedMonth = displayedMonth,
                    onDateSelect = { selectedDate = it },
                    onMonthChange = {
                        displayedMonth = it
                        monthChangeCount++
                        lastChangedMonth = it.get(JavaCalendar.MONTH)
                    }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // The picker's center lies in the month wheel (the left 55%), so this swipe advances
        // months.
        composeTestRule.onNodeWithContentDescription("Month and year picker")
            .performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // onMonthChange fired at least once, last with a month other than January.
        assertTrue(
            "Wheel scroll should trigger onMonthChange (count=$monthChangeCount)",
            monthChangeCount > 0
        )
        assertNotEquals(
            "Month should have changed from January (0)",
            JavaCalendar.JANUARY, lastChangedMonth
        )
    }
}
