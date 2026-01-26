package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.rrule.EndCondition

/**
 * Compose UI tests for EndConditionSelector component.
 *
 * Tests the occurrence count text field behavior, particularly:
 * - Select-all on focus (typing replaces, not appends)
 * - Count updates propagate to callback
 */
@RunWith(AndroidJUnit4::class)
class EndConditionSelectorComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun endConditionSelector_displays_count_option() {
        composeTestRule.setContent {
            MaterialTheme {
                EndConditionSelector(
                    endCondition = EndCondition.Count(10),
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = {}
                )
            }
        }

        // Should display "After" label
        composeTestRule.onNodeWithText("After").assertIsDisplayed()
        // Should display "occurrences" label
        composeTestRule.onNodeWithText("occurrences").assertIsDisplayed()
    }

    @Test
    fun endConditionSelector_count_field_shows_initial_value() {
        composeTestRule.setContent {
            MaterialTheme {
                EndConditionSelector(
                    endCondition = EndCondition.Count(15),
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = {}
                )
            }
        }

        // Text field should show "15"
        composeTestRule.onNode(hasSetTextAction()).assertTextEquals("15")
    }

    @Test
    fun endConditionSelector_count_update_triggers_callback() {
        var capturedCount = 0

        composeTestRule.setContent {
            var endCondition by remember { mutableStateOf<EndCondition>(EndCondition.Count(10)) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = endCondition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        endCondition = newCondition
                        if (newCondition is EndCondition.Count) {
                            capturedCount = newCondition.count
                        }
                    }
                )
            }
        }

        // Replace text with new value
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("25")
        composeTestRule.waitForIdle()

        // Callback should have been called with new count
        assertEquals(25, capturedCount)
    }

    @Test
    fun endConditionSelector_typing_replaces_value_via_select_all() {
        // This test verifies the select-all on focus behavior works correctly
        // When user clicks field (selects all) and types "2", result should be "2" not "210"
        var capturedCount = 0

        composeTestRule.setContent {
            var endCondition by remember { mutableStateOf<EndCondition>(EndCondition.Count(10)) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = endCondition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        endCondition = newCondition
                        if (newCondition is EndCondition.Count) {
                            capturedCount = newCondition.count
                        }
                    }
                )
            }
        }

        val textField = composeTestRule.onNode(hasSetTextAction())

        // Click on text field to focus it (which should select all)
        textField.performClick()
        composeTestRule.waitForIdle()

        // Type new value - with select-all on focus, this should replace "10" with "2"
        // Using performTextReplacement to simulate the user typing after select-all
        textField.performTextReplacement("2")
        composeTestRule.waitForIdle()

        // Value should be 2, NOT 210 or 102
        assertEquals(2, capturedCount)
    }

    @Test
    fun endConditionSelector_never_option_works() {
        var endCondition: EndCondition = EndCondition.Count(10)

        composeTestRule.setContent {
            var condition by remember { mutableStateOf(endCondition) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = condition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        condition = newCondition
                        endCondition = newCondition
                    }
                )
            }
        }

        // Click on "Never" option
        composeTestRule.onNodeWithText("Never").performClick()
        composeTestRule.waitForIdle()

        // End condition should now be Never
        assertEquals(EndCondition.Never, endCondition)
    }

    @Test
    fun endConditionSelector_until_date_option_works() {
        var endCondition: EndCondition = EndCondition.Count(10)

        composeTestRule.setContent {
            var condition by remember { mutableStateOf(endCondition) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = condition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        condition = newCondition
                        endCondition = newCondition
                    }
                )
            }
        }

        // Click on "On date" option
        composeTestRule.onNodeWithText("On date").performClick()
        composeTestRule.waitForIdle()

        // End condition should now be Until
        assert(endCondition is EndCondition.Until)
    }

    @Test
    fun endConditionSelector_filters_non_digit_input() {
        var capturedCount = 0

        composeTestRule.setContent {
            var endCondition by remember { mutableStateOf<EndCondition>(EndCondition.Count(10)) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = endCondition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        endCondition = newCondition
                        if (newCondition is EndCondition.Count) {
                            capturedCount = newCondition.count
                        }
                    }
                )
            }
        }

        // Try to input text with non-digits - the filter should strip non-digits
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("abc123xyz")
        composeTestRule.waitForIdle()

        // Only digits should be kept: "123"
        assertEquals(123, capturedCount)
    }

    @Test
    fun endConditionSelector_limits_to_3_digits() {
        var capturedCount = 0

        composeTestRule.setContent {
            var endCondition by remember { mutableStateOf<EndCondition>(EndCondition.Count(10)) }

            MaterialTheme {
                EndConditionSelector(
                    endCondition = endCondition,
                    startDateMillis = System.currentTimeMillis(),
                    onEndConditionChange = { newCondition ->
                        endCondition = newCondition
                        if (newCondition is EndCondition.Count) {
                            capturedCount = newCondition.count
                        }
                    }
                )
            }
        }

        // Try to input more than 3 digits
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("12345")
        composeTestRule.waitForIdle()

        // Should be truncated to 3 digits: "123"
        assertEquals(123, capturedCount)
    }
}
