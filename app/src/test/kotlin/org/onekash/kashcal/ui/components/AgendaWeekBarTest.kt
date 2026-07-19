package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class AgendaWeekBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Week containing Saturday 2026-07-18, Sunday-first: Jul 12..18.
    private val weekDates = AgendaWeekBarLogic.weekDates(LocalDate.of(2026, 7, 18), Calendar.SUNDAY)

    @Test
    fun tapping_a_date_invokes_callback_with_its_daycode() {
        var clicked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                AgendaWeekBar(
                    weekDates = weekDates,
                    selectedDayCode = null,
                    todayDayCode = 20260718,
                    onDayClick = { clicked = it }
                )
            }
        }
        // Each cell exposes its full date as the a11y label (the bare number is
        // cleared), so tap the "Wednesday, July 15" cell.
        composeTestRule.onNodeWithContentDescription("Wednesday, July 15").performClick()
        assertEquals(20260715, clicked)
    }

    @Test
    fun all_seven_days_are_rendered_with_descriptive_labels() {
        composeTestRule.setContent {
            MaterialTheme {
                AgendaWeekBar(
                    weekDates = weekDates,
                    selectedDayCode = 20260716,
                    todayDayCode = 20260718,
                    onDayClick = {}
                )
            }
        }
        // Full localized date labels replace the bare day numbers for screen readers.
        listOf(
            "Sunday, July 12", "Monday, July 13", "Tuesday, July 14", "Wednesday, July 15",
            "Thursday, July 16, Selected", "Friday, July 17", "Saturday, July 18, Today"
        ).forEach { label ->
            composeTestRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun today_and_selected_can_be_distinct_cells() {
        // Today = 18, selected = 16; both cells present and tappable.
        var clicked: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                AgendaWeekBar(
                    weekDates = weekDates,
                    selectedDayCode = 20260716,
                    todayDayCode = 20260718,
                    onDayClick = { clicked = it }
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Saturday, July 18, Today").performClick()
        assertEquals(20260718, clicked)
    }
}
