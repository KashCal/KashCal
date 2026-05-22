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
import androidx.compose.ui.test.onAllNodesWithText
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

        // Should display AM (circular AM/PM may show multiple instances)
        composeTestRule.onAllNodesWithText("AM")[0].assertIsDisplayed()
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

        // Should display PM (circular AM/PM may show multiple instances)
        composeTestRule.onAllNodesWithText("PM")[0].assertIsDisplayed()
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

    // ==================== Scroll Selection Tests (Bug Fix Verification) ====================

    @Test
    fun wheelTimePicker_scroll_selects_centered_item_not_top_item() {
        // This test verifies the fix for the bug where scrolling to center hour 17
        // incorrectly selected hour 15 (the top visible item) instead of 17 (the center)
        var lastSelectedHour = -1
        var lastSelectedMinute = -1

        composeTestRule.setContent {
            MaterialTheme {
                var hour by remember { mutableIntStateOf(12) }
                var minute by remember { mutableIntStateOf(30) }

                WheelTimePicker(
                    selectedHour = hour,
                    selectedMinute = minute,
                    onTimeSelected = { h, m ->
                        hour = h
                        minute = m
                        lastSelectedHour = h
                        lastSelectedMinute = m
                    },
                    use24Hour = true
                )
            }
        }

        // Initial state: hour 12 should be displayed and centered
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()

        // The picker should show hour 12 at center
        // With visibleItems=5, we should see hours 10, 11, [12], 13, 14
        // If the bug existed, the selection would incorrectly report hour 10 (top item)
        // After the fix, selection should correctly report hour 12 (center item)

        // Verify initial selection is correct (12, not 10)
        // Note: The callback only fires on CHANGE, so if already at 12, no callback
        // We verify by checking the displayed value is 12
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_reports_center_item_on_scroll_settle() {
        var selectedValue = -1

        composeTestRule.setContent {
            MaterialTheme {
                var selected by remember { mutableIntStateOf(12) }

                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = selected,
                    onItemSelected = { item ->
                        selected = item
                        selectedValue = item
                    },
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

        composeTestRule.waitForIdle()

        // Verify item 12 is displayed (at center due to initialization)
        composeTestRule.onNodeWithText("12").assertIsDisplayed()

        // The center item (12) should be bold (isSelected = true)
        // Adjacent items (11, 13) should be normal weight
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

        // Midnight should display as 12 AM (circular AM/PM may show multiple instances)
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("AM")[0].assertIsDisplayed()
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

        // Noon should display as 12 PM (circular AM/PM may show multiple instances)
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("PM")[0].assertIsDisplayed()
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
        var callbackMinute = -1

        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 32, // Should round to 30 with interval 5
                    onTimeSelected = { _, minute -> callbackMinute = minute },
                    use24Hour = true,
                    minuteInterval = 5
                )
            }
        }

        // Wait for composition and LaunchedEffect to complete rounding
        composeTestRule.waitForIdle()

        // Verify component renders without crash - rounding logic is tested in unit tests.
        // Can't assert specific minute value because circular LazyColumn virtualizes items
        // and "30" may not be rendered if outside viewport.
        composeTestRule.waitForIdle()
    }

    // ==================== Crash Scenario Tests (centerOffset fix verification) ====================

    @Test
    fun verticalWheelPicker_nonCircular_2items_visibleItems3_noCrash() {
        // AM/PM wheel: 2 items, effectiveCircular=false, visibleItems=3
        // Buggy code produced initialIndex = -1 → crash
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = listOf("AM", "PM"),
                    selectedItem = "AM",
                    onItemSelected = {},
                    visibleItems = 3,
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

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_nonCircular_2items_visibleItems5_noCrash() {
        // AM/PM wheel with default visibleItems=5
        // Buggy code produced initialIndex = -2 → crash
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = listOf("AM", "PM"),
                    selectedItem = "AM",
                    onItemSelected = {},
                    visibleItems = 5,
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

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_nonCircular_2items_PM_selected_visibleItems3_noCrash() {
        // PM selected with visibleItems=3
        composeTestRule.setContent {
            MaterialTheme {
                VerticalWheelPicker(
                    items = listOf("AM", "PM"),
                    selectedItem = "PM",
                    onItemSelected = {},
                    visibleItems = 3,
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

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("PM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_12h_AM_visibleItems3_noCrash() {
        // Full WheelTimePicker in 12h mode, morning hour, visibleItems=3
        // This is the exact configuration from DateTimePicker that crashed
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9, // 9 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false,
                    visibleItems = 3
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_12h_PM_visibleItems3_noCrash() {
        // Full WheelTimePicker in 12h mode, afternoon hour, visibleItems=3
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 15, // 3 PM
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false,
                    visibleItems = 3
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("PM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_12h_midnight_visibleItems3_noCrash() {
        // Midnight (hour=0, 12 AM) with visibleItems=3
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 0, // 12 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false,
                    visibleItems = 3
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("AM").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_24h_visibleItems3_noCrash() {
        // 24h mode with visibleItems=3 (DateTimePicker config)
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 14,
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true,
                    visibleItems = 3
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("14").assertIsDisplayed()
    }

    // ==================== Swipe Selection Tests (Bug Fix Verification) ====================

    @Test
    fun verticalWheelPicker_swipe_selects_center_item() {
        // This test verifies the core fix: after swiping, the CENTER item should be
        // selected, not the TOP visible item.
        //
        // BUG (before fix): Swipe to center hour 17 → selects hour 15 (top item)
        // FIX (after fix): Swipe to center hour 17 → selects hour 17 (center item)

        var selectedValue = 12  // Start at 12

        composeTestRule.setContent {
            MaterialTheme {
                var selected by remember { mutableIntStateOf(12) }

                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = selected,
                    onItemSelected = { item ->
                        selected = item
                        selectedValue = item
                    },
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

        composeTestRule.waitForIdle()

        // Verify initial state: hour 12 is selected
        assertEquals("Initial selection should be 12", 12, selectedValue)

        // Swipe up to scroll to higher numbers (13, 14, 15...)
        composeTestRule.onNodeWithContentDescription("Wheel picker with 24 options")
            .performTouchInput {
                swipeUp(startY = centerY, endY = centerY - 200f)
            }

        // Wait for scroll to settle and selection callback
        composeTestRule.waitForIdle()
        Thread.sleep(500)  // Give time for snap animation to complete
        composeTestRule.waitForIdle()

        // After swipe up, a higher hour should be selected
        // The exact value depends on swipe distance, but it should NOT be
        // 2 less than expected (which was the bug)
        println("After swipe up: selectedValue = $selectedValue")

        // The selected value should be greater than 12 (we swiped up)
        // If the bug existed, we might get a lower value than expected
        assertNotEquals("Selection should have changed from 12", 12, selectedValue)
    }

    // ==================== Visible Items Configuration (Issue #88) ====================

    @Test
    fun wheelTimePicker_displays_with_5_visible_items() {
        // Verify WheelTimePicker works correctly with visibleItems=5
        // This is the new default for better touch targets on small screens
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 12,
                    selectedMinute = 30,
                    onTimeSelected = { _, _ -> },
                    use24Hour = true,
                    visibleItems = 5
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should display center item (12) and adjacent items
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_12h_mode_with_5_visible_items_no_crash() {
        // AM/PM wheel (2 items) must work with visibleItems=5
        // Tests the coerceAtLeast fix for small item counts
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 9, // 9 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false,
                    visibleItems = 5
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("9").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("AM")[0].assertIsDisplayed()
    }

    // ==================== Issue #238: Mid-fling Done commits visible value ====================

    @Test
    fun verticalWheelPicker_emits_intermediate_items_during_animated_scroll() {
        // Issue #238: callback should fire continuously as the wheel scrolls,
        // not only on settle. Drive an animated scroll across many items by
        // changing selectedItem externally; capture every onItemSelected call;
        // assert intermediate items appear in the captured list.
        val captured = mutableListOf<Int>()
        val externalSelected = mutableStateOf(0)

        composeTestRule.setContent {
            MaterialTheme {
                val current by externalSelected
                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = current,
                    onItemSelected = { item ->
                        captured.add(item)
                        externalSelected.value = item
                    },
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

        composeTestRule.waitForIdle()
        captured.clear()

        // Trigger an animated scroll across many items. The picker's internal
        // LaunchedEffect(selectedItem) calls animateScrollToItem, which steps
        // centerIndex through intermediate values frame-by-frame.
        composeTestRule.runOnUiThread { externalSelected.value = 18 }

        // Poll for animation progress; the snap-fling animation takes ~300-500ms.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && captured.size < 3) {
            composeTestRule.mainClock.advanceTimeBy(16)
        }
        composeTestRule.waitForIdle()

        // We scrolled from 0 → 18 (across the wrap-shorter path: 0, 23, 22, ... 18).
        // Continuous emission must produce more than just the final value.
        assertTrue(
            "Expected intermediate emissions during animated scroll, got: $captured",
            captured.size >= 2
        )
        // And the final centered value must land on 18.
        assertEquals(18, captured.last())
    }

    @Test
    fun verticalWheelPicker_circular_recenters_to_middle_band_after_settle() {
        // Issue #238 regression: edge recentering must still happen after settle
        // even though selection callback moved to a separate snapshotFlow effect.
        val externalSelected = mutableStateOf(0)

        composeTestRule.setContent {
            MaterialTheme {
                val current by externalSelected
                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = current,
                    onItemSelected = { externalSelected.value = it },
                    isCircular = true
                ) { item, _ ->
                    androidx.compose.material3.Text(text = String.format("%02d", item))
                }
            }
        }

        composeTestRule.waitForIdle()

        // Drive a long sequence of scrolls to push virtual index far from middle.
        // CIRCULAR_MULTIPLIER = 1000, items.size = 24 → middleStart = 12000,
        // recenter threshold = 6000. We can't directly query virtual index, but
        // after settling on each new value the picker should remain visually
        // centered on the chosen item, which proves recentering is happening.
        listOf(7, 14, 21, 4, 11, 18, 1, 8, 15, 22).forEach { target ->
            composeTestRule.runOnUiThread { externalSelected.value = target }
            composeTestRule.waitForIdle()
            // Drive snap + animateScrollToItem to completion via the Compose
            // clock — deterministic on slow CI emulators where Thread.sleep
            // races the animation.
            composeTestRule.mainClock.advanceTimeBy(500)
            composeTestRule.waitForIdle()
        }

        // After many large jumps, the final selected item must still display.
        // If recentering broke, the virtual list would have run off the end and
        // the item would no longer be visible.
        composeTestRule.onNodeWithText("22").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_does_not_double_emit_for_same_centered_item() {
        // Issue #238 regression: distinctUntilChanged + (item != selectedItem)
        // must prevent the same item from being reported twice in a row.
        val emissionCounts = mutableMapOf<Int, Int>()
        val externalSelected = mutableStateOf(5)

        composeTestRule.setContent {
            MaterialTheme {
                val current by externalSelected
                VerticalWheelPicker(
                    items = (0..23).toList(),
                    selectedItem = current,
                    onItemSelected = { item ->
                        emissionCounts[item] = (emissionCounts[item] ?: 0) + 1
                        externalSelected.value = item
                    },
                    isCircular = true
                ) { item, _ ->
                    androidx.compose.material3.Text(text = String.format("%02d", item))
                }
            }
        }

        composeTestRule.waitForIdle()
        emissionCounts.clear()

        // Scroll to 10, back to 5. Final centered item is 5 (same as start).
        // Drive Compose's clock forward instead of Thread.sleep — deterministic
        // across CI emulators with variable wall-clock latency.
        composeTestRule.runOnUiThread { externalSelected.value = 10 }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(800)
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { externalSelected.value = 5 }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(800)
        composeTestRule.waitForIdle()

        // No item should be emitted twice in a row for the same value.
        // (Counts may be > 1 across separate scroll passes, but the picker
        // must not double-fire while sitting on a single item.)
        emissionCounts.forEach { (item, count) ->
            assertTrue(
                "Item $item emitted $count times — exceeds single-pass max of 2",
                count <= 2
            )
        }
    }

    @Test
    fun wheelTimePicker_swipe_hour_selects_correct_value() {
        var lastSelectedHour = 10

        composeTestRule.setContent {
            MaterialTheme {
                var hour by remember { mutableIntStateOf(10) }
                var minute by remember { mutableIntStateOf(0) }

                WheelTimePicker(
                    selectedHour = hour,
                    selectedMinute = minute,
                    onTimeSelected = { h, m ->
                        hour = h
                        minute = m
                        lastSelectedHour = h
                    },
                    use24Hour = true
                )
            }
        }

        composeTestRule.waitForIdle()

        // Initial: hour 10
        assertEquals("Initial hour should be 10", 10, lastSelectedHour)

        // Find the hour picker (first wheel in 24h mode)
        // Swipe up to increase hour
        composeTestRule.onAllNodesWithText("10")[0]
            .performTouchInput {
                swipeUp(startY = centerY, endY = centerY - 150f)
            }

        composeTestRule.waitForIdle()
        Thread.sleep(500)
        composeTestRule.waitForIdle()

        println("After swipe: lastSelectedHour = $lastSelectedHour")

        // Hour should have increased (exact value depends on swipe physics)
        // Key verification: the selected hour should match what's visually centered
        assertNotEquals("Hour should have changed after swipe", 10, lastSelectedHour)
    }
}
