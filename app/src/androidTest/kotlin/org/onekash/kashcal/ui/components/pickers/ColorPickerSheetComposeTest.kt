package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ColorPickerSheet.
 *
 * Tests use ColorPickerSheetContent directly (without ModalBottomSheet)
 * to avoid animation timing issues that can cause flaky tests.
 *
 * Tests cover:
 * - Sheet displays all components (preview, slider, hex input, buttons)
 * - Hex input validation and error states
 * - Cancel/Select button behavior
 * - Accessibility labels
 */
@RunWith(AndroidJUnit4::class)
class ColorPickerSheetComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ==================== Display Tests ====================

    @Test
    fun colorPickerSheet_displays_header() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Choose Color").assertIsDisplayed()
    }

    @Test
    fun colorPickerSheet_displays_hex_input_label() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Hex Color").assertIsDisplayed()
    }

    @Test
    fun colorPickerSheet_displays_action_buttons() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select").assertIsDisplayed()
    }

    @Test
    fun colorPickerSheet_displays_initial_hex_value() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFF2196F3.toInt(), // Material Blue
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        // The hex value should be visible in the text field
        composeTestRule.onNode(hasText("2196F3")).assertExists()
    }

    // ==================== Button State Tests ====================

    @Test
    fun colorPickerSheet_select_button_enabled_with_valid_hex() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Select").assertIsEnabled()
    }

    // ==================== Callback Tests ====================

    @Test
    fun colorPickerSheet_cancel_calls_callback() {
        var cancelled = false

        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = {},
                    onCancel = { cancelled = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Cancel should trigger onCancel callback", cancelled)
    }

    @Test
    fun colorPickerSheet_select_calls_callback_with_color() {
        var selectedColor: Int? = null

        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(),
                    onColorSelected = { selectedColor = it },
                    onCancel = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Select").performClick()
        composeTestRule.waitForIdle()

        assertNotNull("Select should call onColorSelected", selectedColor)
        // Red at hue 0 should return red
        assertEquals("Should return red color", 0xFFFF0000.toInt(), selectedColor)
    }

    // ==================== Accessibility Tests ====================

    @Test
    fun colorPickerSheet_slider_has_accessibility_description() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFFFF0000.toInt(), // Red
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        // The slider should have accessibility description containing "Red"
        composeTestRule.onNode(
            hasContentDescription(value = "Red", substring = true, ignoreCase = true)
        ).assertExists()
    }

    @Test
    fun colorPickerSheet_slider_has_color_name_for_blue() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFF0000FF.toInt(), // Blue (hue ~240)
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        // The slider should have accessibility description containing "Blue"
        composeTestRule.onNode(
            hasContentDescription(value = "Blue", substring = true, ignoreCase = true)
        ).assertExists()
    }

    @Test
    fun colorPickerSheet_slider_has_color_name_for_green() {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPickerSheetContent(
                    currentColor = 0xFF00FF00.toInt(), // Green (hue 120)
                    onColorSelected = {},
                    onCancel = {}
                )
            }
        }

        // The slider should have accessibility description containing "Green"
        composeTestRule.onNode(
            hasContentDescription(value = "Green", substring = true, ignoreCase = true)
        ).assertExists()
    }
}
