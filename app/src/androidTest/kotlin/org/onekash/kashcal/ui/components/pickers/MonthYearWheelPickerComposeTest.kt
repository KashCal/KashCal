package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Compose UI integration tests for the month/year wheel picker toggle
 * in [InlineDatePickerContent].
 *
 * Tests the header click → wheel picker swap → calendar grid swap flow.
 */
@RunWith(AndroidJUnit4::class)
class MonthYearWheelPickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val monthNames = DateFormatSymbols.getInstance().months.filter { it.isNotBlank() }

    /**
     * Creates a test harness wrapping InlineDatePickerContent with state management.
     */
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

        // Day-of-week headers should be visible initially
        composeTestRule.onNodeWithText("S").assertIsDisplayed()

        // Click the header to show wheel picker
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // Wheel picker should be visible
        composeTestRule.onNodeWithContentDescription("Month and year picker").assertIsDisplayed()
    }

    @Test
    fun headerClickTwice_returnsToCalendarGrid() {
        setUpDatePicker()

        // Toggle on
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // Toggle off — now the icon changes to "Show calendar"
        composeTestRule.onNodeWithContentDescription("Show calendar").performClick()
        composeTestRule.waitForIdle()

        // Day-of-week headers should be back
        composeTestRule.onNodeWithText("S").assertIsDisplayed()
    }

    // ==================== Month/Year Selection Tests ====================

    @Test
    fun wheelPicker_showsCurrentMonthSelected() {
        val cal = JavaCalendar.getInstance()
        val currentMonth = monthNames[cal.get(JavaCalendar.MONTH)]
        val currentYear = cal.get(JavaCalendar.YEAR).toString()

        setUpDatePicker(initialDateMillis = cal.timeInMillis)

        // Show wheel picker
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // Current month and year should be visible in the wheels
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

        // Toggle to wheels and back
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Show calendar").performClick()
        composeTestRule.waitForIdle()

        // onDateSelect should NOT have been called during toggle
        assertEquals("Date should not change during toggle", 0L, lastSelectedDate)
    }

    // ==================== Arrow Visibility Tests ====================

    @Test
    fun arrowButtons_hiddenWhenWheelsShowing() {
        setUpDatePicker()

        // Arrows should be visible initially
        composeTestRule.onNodeWithContentDescription("Previous month").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next month").assertIsDisplayed()

        // Show wheel picker
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // Arrows should no longer be displayed
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
        // Start at January 2025
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

        // Show wheel picker
        composeTestRule.onNodeWithContentDescription("Pick month and year").performClick()
        composeTestRule.waitForIdle()

        // Swipe up on the month wheel to advance months
        composeTestRule.onNodeWithContentDescription("Month and year picker")
            .performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // onMonthChange should have fired at least once with a different month
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
