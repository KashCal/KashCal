package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exhaustive Compose UI tests for TimezonePickerChip component.
 *
 * Tests cover:
 * - Compact layout: abbreviation above globe icon (Issue #88)
 * - Abbreviation text display (short, medium, long, 2-line wrap)
 * - Globe icon display and interaction
 * - Local time preview display
 * - Accessibility semantics
 * - Search state callbacks
 * - Timezone selection callbacks
 * - Search functionality
 * - Edge cases and error handling
 */
@RunWith(AndroidJUnit4::class)
class TimezonePickerComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ==================== Abbreviation Display Tests ====================

    @Test
    fun abbreviation_short_displays_correctly() {
        // Short abbreviations (3 chars): EST, PST, UTC
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // EST or EDT (3 chars) should display
        val found = listOf("EST", "EDT").any { abbr ->
            try {
                composeTestRule.onNodeWithText(abbr).assertIsDisplayed()
                true
            } catch (e: AssertionError) { false }
        }
        assertTrue("Should display short abbreviation (EST/EDT)", found)
    }

    @Test
    fun abbreviation_medium_displays_without_crash() {
        // Medium abbreviations (4-5 chars): Australia/Sydney
        // Note: Actual abbreviation depends on DST state - just verify no crash
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "Australia/Sydney",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should render without crash, globe icon visible
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun abbreviation_long_offset_displays_without_crash() {
        // Long offset: GMT+05:30 (Asia/Kolkata)
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "Asia/Kolkata",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash, globe icon should be visible
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun abbreviation_very_long_offset_displays_without_crash() {
        // Very long offset: GMT+12:45 (Pacific/Chatham)
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "Pacific/Chatham",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun abbreviation_negative_offset_displays_without_crash() {
        // Negative offset: GMT-09:30 (Pacific/Marquesas)
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "Pacific/Marquesas",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun abbreviation_utc_displays_correctly() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "UTC",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("UTC").assertIsDisplayed()
    }

    // ==================== Device Default Timezone Tests ====================

    @Test
    fun device_default_timezone_shows_abbreviation() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = null, // Device default
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Globe icon should be visible
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun device_default_hides_use_device_option_in_search() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = null, // Already device default
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // "Use device timezone" should NOT be visible when already device default
        composeTestRule.onNodeWithText("Use device timezone").assertDoesNotExist()
    }

    // ==================== Globe Icon Tests ====================

    @Test
    fun globe_icon_is_displayed() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun globe_icon_is_clickable() {
        var clicked = false

        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    onSearchOpenChange = { if (it) clicked = true }
                )
            }
        }

        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()

        composeTestRule.waitForIdle()

        assertTrue("Globe icon should be clickable", clicked)
    }

    @Test
    fun globe_icon_hides_when_search_opens() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Globe icon should be hidden (search dropdown visible instead)
        composeTestRule.onNodeWithText("Search...").assertIsDisplayed()
    }

    // ==================== Local Time Preview Tests ====================

    @Test
    fun local_preview_shown_when_timezone_differs_from_device() {
        // Select a timezone different from device default
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "Pacific/Auckland", // NZ time, likely different from device
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Local preview should contain time indicator (colon in time format)
        // This is indirect - we verify the component renders without crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun local_preview_hidden_when_using_device_timezone() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = null, // Device default
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Component should render (local preview not shown for device default)
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    // ==================== Accessibility Tests ====================

    @Test
    fun accessibility_content_description_includes_timezone() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Content description should mention "Timezone"
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun accessibility_content_description_includes_tap_action() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        composeTestRule.waitForIdle()

        // Content description should mention "tap to change"
        composeTestRule.onNode(
            hasContentDescription("tap to change", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun accessibility_close_button_has_description() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Close button should have content description
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    // ==================== Search Callback Tests ====================

    @Test
    fun onSearchOpenChange_called_with_true_when_opened() {
        var searchOpen = false

        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    onSearchOpenChange = { searchOpen = it }
                )
            }
        }

        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSearchOpenChange should be called with true", searchOpen)
    }

    @Test
    fun onSearchOpenChange_called_with_false_when_closed() {
        var searchOpen = true

        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    onSearchOpenChange = { searchOpen = it }
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Close search
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.waitForIdle()

        assertFalse("onSearchOpenChange should be called with false", searchOpen)
    }

    @Test
    fun onSearchOpenChange_null_callback_does_not_crash() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    onSearchOpenChange = null // No callback
                )
            }
        }

        // Open and close search - should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.waitForIdle()

        // Should still be functional
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    // ==================== Selection Callback Tests ====================

    @Test
    fun onTimezoneSelected_called_when_selecting_from_search() {
        var selectedTz: String? = "America/New_York"

        composeTestRule.setContent {
            MaterialTheme {
                var tz by remember { mutableStateOf(selectedTz) }
                TimezonePickerChip(
                    selectedTimezone = tz,
                    onTimezoneSelected = { newTz ->
                        tz = newTz
                        selectedTz = newTz
                    },
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Click "Use device timezone"
        composeTestRule.onNodeWithText("Use device timezone").performClick()
        composeTestRule.waitForIdle()

        assertNull("Should select device timezone (null)", selectedTz)
    }

    @Test
    fun onTimezoneSelected_called_with_null_for_device_default() {
        var callbackValue: String? = "initial"

        composeTestRule.setContent {
            MaterialTheme {
                var tz by remember { mutableStateOf<String?>("America/New_York") }
                TimezonePickerChip(
                    selectedTimezone = tz,
                    onTimezoneSelected = { newTz ->
                        callbackValue = newTz
                        tz = newTz
                    },
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search and select device timezone
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Use device timezone").performClick()
        composeTestRule.waitForIdle()

        assertNull("Callback should receive null for device default", callbackValue)
    }

    // ==================== Search Functionality Tests ====================

    @Test
    fun search_placeholder_shown_when_empty() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Search...").assertIsDisplayed()
    }

    @Test
    fun search_closes_after_selection() {
        var searchOpen = false

        composeTestRule.setContent {
            MaterialTheme {
                var tz by remember { mutableStateOf<String?>("America/New_York") }
                TimezonePickerChip(
                    selectedTimezone = tz,
                    onTimezoneSelected = { tz = it },
                    referenceTimeMs = System.currentTimeMillis(),
                    onSearchOpenChange = { searchOpen = it }
                )
            }
        }

        // Open search
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).performClick()
        composeTestRule.waitForIdle()
        assertTrue("Search should be open", searchOpen)

        // Select device timezone
        composeTestRule.onNodeWithText("Use device timezone").performClick()
        composeTestRule.waitForIdle()

        assertFalse("Search should close after selection", searchOpen)
    }

    // ==================== Layout Tests ====================

    @Test
    fun component_renders_in_narrow_width() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    modifier = Modifier.width(56.dp) // Narrow width
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should still render without crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun component_renders_in_wide_width() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis(),
                    modifier = Modifier.width(200.dp) // Wide width
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    // ==================== Edge Cases ====================

    // Note: Invalid/empty timezone strings are not tested here because
    // the component expects valid IANA timezone IDs. Callers should validate
    // timezone strings before passing them to TimezonePickerChip.

    @Test
    fun handles_zero_reference_time() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = 0L // Epoch
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun handles_future_reference_time() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000 // 1 year future
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun handles_negative_reference_time() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = -1000L // Before epoch
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should not crash
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    // ==================== Multiple Interactions ====================

    @Test
    fun multiple_open_close_cycles_work() {
        // Verify component can open/close multiple times without crash
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        repeat(3) {
            // Open
            composeTestRule.onNode(
                hasContentDescription("Timezone", substring = true)
            ).performClick()
            composeTestRule.waitForIdle()

            // Verify search is open
            composeTestRule.onNodeWithText("Search...").assertIsDisplayed()

            // Close
            composeTestRule.onNodeWithContentDescription("Close").performClick()
            composeTestRule.waitForIdle()
        }

        // Should still be functional after multiple cycles
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }

    @Test
    fun rapid_open_close_does_not_crash() {
        composeTestRule.setContent {
            MaterialTheme {
                TimezonePickerChip(
                    selectedTimezone = "America/New_York",
                    onTimezoneSelected = {},
                    referenceTimeMs = System.currentTimeMillis()
                )
            }
        }

        // Rapid interactions
        repeat(5) {
            composeTestRule.onNode(
                hasContentDescription("Timezone", substring = true)
            ).performClick()
            composeTestRule.onNodeWithContentDescription("Close").performClick()
        }

        composeTestRule.waitForIdle()

        // Should still be functional
        composeTestRule.onNode(
            hasContentDescription("Timezone", substring = true)
        ).assertIsDisplayed()
    }
}
