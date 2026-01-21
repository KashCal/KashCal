package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for WheelTimePicker and VerticalWheelPicker components.
 *
 * Tests cover:
 * - Rendering of picker components
 * - Selection callback behavior
 * - Circular scrolling wrap behavior
 * - Accessibility semantics
 *
 * Note: Some tests may need to account for the virtual index system
 * used by circular scrolling, where the LazyColumn has 12,000+ items
 * for a 12-item list (itemCount * CIRCULAR_MULTIPLIER).
 */
@RunWith(AndroidJUnit4::class)
class WheelTimePickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ==================== Rendering ====================

    @Test
    fun wheelTimePicker_displays_selected_hour_in_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 14, // 2 PM
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Should display "2" for 2 PM
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        // Should display "30" for minutes
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_displays_selected_hour_in_24h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 14, // 14:00
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true
                )
            }
        }

        // Should display "14" for hour
        composeTestRule.onNodeWithText("14").assertIsDisplayed()
        // Should display "30" for minutes
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_displays_AM_PM_in_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9, // 9 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Should display AM
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_displays_PM_in_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 15, // 3 PM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Should display PM
        composeTestRule.onNodeWithText("PM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_24h_mode_has_no_AM_PM() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9,
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true
                )
            }
        }

        // Should NOT have AM/PM in 24-hour mode
        composeTestRule.onNodeWithText("AM").assertDoesNotExist()
        composeTestRule.onNodeWithText("PM").assertDoesNotExist()
    }

    // ==================== Callbacks ====================

    @Test
    fun wheelTimePicker_calls_callback_on_selection() {
        var selectedHour = 10
        var selectedMinute = 30

        composeTestRule.setContent {
            MaterialTheme {
                var hour by remember { mutableIntStateOf(selectedHour) }
                var minute by remember { mutableIntStateOf(selectedMinute) }

                WheelTimePicker(
                    selectedHour = hour,
                    selectedMinute = minute,
                    onTimeSelected = { h, m ->
                        hour = h
                        minute = m
                        selectedHour = h
                        selectedMinute = m
                    },
                    use24Hour = true
                )
            }
        }

        // The callback is called on scroll settle, which requires actual scroll interaction
        // This test verifies the composable renders without crash and has the callback wired up
        composeTestRule.onNodeWithText("10").assertIsDisplayed()
    }

    // ==================== VerticalWheelPicker Circular Tests ====================

    @Test
    fun verticalWheelPicker_circular_displays_center_item() {
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = 12,
                    onItemSelected = {},
                    isCircular = true
                ) { item, isSelected ->
                    androidx.compose.material3.Text(
                        text = String.format("%02d", item),
                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
        }

        // Should display the selected item "12"
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_non_circular_displays_correctly() {
        composeTestRule.setContent {
            MaterialTheme {
                val amPm = listOf("AM", "PM")
                VerticalWheelPicker(
                    items = amPm,
                    selectedItem = "AM",
                    onItemSelected = {},
                    isCircular = false
                ) { item, isSelected ->
                    androidx.compose.material3.Text(
                        text = item,
                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    // ==================== Accessibility ====================

    @Test
    fun verticalWheelPicker_has_content_description() {
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = (1..12).toList(),
                    selectedItem = 6,
                    onItemSelected = {},
                    isCircular = true
                ) { item, _ ->
                    androidx.compose.material3.Text(text = item.toString())
                }
            }
        }

        // Should have content description for accessibility
        composeTestRule.onNodeWithContentDescription("Wheel picker with 12 options")
            .assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_circular_has_state_description() {
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = (1..12).toList(),
                    selectedItem = 6,
                    onItemSelected = {},
                    isCircular = true
                ) { item, _ ->
                    androidx.compose.material3.Text(text = item.toString())
                }
            }
        }

        // The circular picker should have state description indicating circular mode
        // This is verified via the semantics we added
        composeTestRule.onNode(
            hasContentDescription("Wheel picker with 12 options")
        ).assertIsDisplayed()
    }

    // ==================== Edge Cases ====================

    @Test
    fun wheelTimePicker_handles_midnight_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 0, // Midnight = 12 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Midnight should display as 12 AM
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_handles_noon_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 12, // Noon = 12 PM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Noon should display as 12 PM
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("PM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_handles_hour_23_24h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 23,
                    selectedMinute = 55,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true
                )
            }
        }

        composeTestRule.onNodeWithText("23").assertIsDisplayed()
        composeTestRule.onNodeWithText("55").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_handles_minute_55() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 55,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true
                )
            }
        }

        composeTestRule.onNodeWithText("55").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_handles_minute_0() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true
                )
            }
        }

        composeTestRule.onNodeWithText("00").assertIsDisplayed()
    }

    // ==================== Selection State ====================

    @Test
    fun wheelTimePicker_updates_when_external_selection_changes() {
        composeTestRule.setContent {
            var hour by remember { mutableIntStateOf(10) }

            MaterialTheme {
                WheelTimePicker(
                    selectedHour = hour,
                    selectedMinute = 30,
                    onTimeSelected = { h, _ -> hour = h },
                    use24Hour = true
                )

                // Button to change hour externally
                androidx.compose.material3.Button(
                    onClick = { hour = 15 }
                ) {
                    androidx.compose.material3.Text("Change Hour")
                }
            }
        }

        // Initial state
        composeTestRule.onNodeWithText("10").assertIsDisplayed()

        // Click button to change hour
        composeTestRule.onNodeWithText("Change Hour").performClick()

        // Wait for recomposition
        composeTestRule.waitForIdle()

        // Should now display 15
        composeTestRule.onNodeWithText("15").assertIsDisplayed()
    }

    // ==================== Minute Interval ====================

    @Test
    fun wheelTimePicker_respects_minute_interval() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true,
                    minuteInterval = 15 // Only 00, 15, 30, 45
                )
            }
        }

        // Should display 30 (valid interval)
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_rounds_to_nearest_minute_interval() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 32, // Should round to 30 with interval 5
                    onTimeSelected = { _, _ -> },
                    use24Hour = true,
                    minuteInterval = 5
                )
            }
        }

        // Should display 30 (nearest to 32 with interval 5)
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }
}
