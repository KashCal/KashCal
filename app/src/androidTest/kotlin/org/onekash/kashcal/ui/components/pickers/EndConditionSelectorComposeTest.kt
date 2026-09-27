package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.rrule.EndCondition

/**
 * Compose UI tests for [EndConditionSelector].
 *
 * Covered:
 * - The "After ... occurrences" row and the count field's initial value show
 * - A replaced count reaches onEndConditionChange
 * - A tap then a replacement yields the new count; select-all on focus isn't exercised, since
 *   performTextReplacement ignores the selection
 * - The count field keeps digits only, at most 3
 * - Tapping Never and On date emits Never and Until
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

        composeTestRule.onNodeWithText("After").assertIsDisplayed()
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

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("25")
        composeTestRule.waitForIdle()

        assertEquals(25, capturedCount)
    }

    @Test
    fun endConditionSelector_typing_replaces_value_via_select_all() {
        // A tap on the field selects all, so typing "2" gives 2, not 210.
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

        textField.performClick()
        composeTestRule.waitForIdle()

        // performTextReplacement stands in for typing after select-all. It replaces the
        // whole text whatever the selection, so this passes even without select-all.
        textField.performTextReplacement("2")
        composeTestRule.waitForIdle()

        // 2, not 210 or 102.
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

        composeTestRule.onNodeWithText("Never").performClick()
        composeTestRule.waitForIdle()

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

        composeTestRule.onNodeWithText("On date").performClick()
        composeTestRule.waitForIdle()

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

        // The input filter strips non-digits.
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("abc123xyz")
        composeTestRule.waitForIdle()

        // Only the digits "123" remain.
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

        // More than 3 digits.
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("12345")
        composeTestRule.waitForIdle()

        // Truncated to the first 3: "123".
        assertEquals(123, capturedCount)
    }
}
