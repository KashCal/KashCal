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
 * Compose UI tests for [WheelTimePicker] and [VerticalWheelPicker].
 *
 * Covered:
 * - The selected hour, minute and AM/PM render in 12h and 24h mode (midnight, noon, hour 23,
 *   minutes 0 and 55, a 15-minute interval), and an outside selection change scrolls the wheel
 * - A swipe or an animated scroll emits a new centered item, mid-scroll values included, no
 *   item is emitted more than twice over two passes, and a circular wheel recenters after many
 *   jumps
 * - The wheel's content description
 * - visibleItems of 3 and 5, and 2-item non-circular wheels, compose without crashing
 *
 * The callback-wiring, font-weight and minute-rounding tests assert only that the picker renders.
 *
 * A circular wheel is a virtual list of itemCount * CIRCULAR_MULTIPLIER entries (12,000 for 12
 * items), so an item's text can appear on more than one row.
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

        // 2 PM shows as "2".
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("14").assertIsDisplayed()
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

        // The circular AM/PM wheel can show "AM" on more than one row.
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

        // The circular AM/PM wheel can show "PM" on more than one row.
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

        // The callback fires only when the centered item changes, which takes a scroll. This
        // checks only that the picker renders with the callback wired; the swipe tests below
        // drive it.
        composeTestRule.onNodeWithText("10").assertIsDisplayed()
    }

    // ==================== Center Selection ====================

    @Test
    fun wheelTimePicker_scroll_selects_centered_item_not_top_item() {
        // The selection is the centered item, not the top visible one (hour 17 centered must
        // not report 15).
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

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()

        // With visibleItems=5 the wheel shows 10, 11, [12], 13, 14; a top-item selection would
        // report 10. The callback fires only on a change, so at 12 it doesn't fire, and only
        // the displayed "12" is checked.
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

        // Item 12 is displayed, centered by the initial index.
        composeTestRule.onNodeWithText("12").assertIsDisplayed()

        // The centered 12 renders bold and 11 and 13 normal; font weight isn't asserted.
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

        // A circular wheel also sets a stateDescription ("Circular scrolling enabled"), but
        // only the content description is asserted.
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
                    selectedHour = 0, // Midnight, 12 AM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Midnight shows as 12 AM; the circular AM/PM wheel can show "AM" on more than one row.
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("AM")[0].assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_handles_noon_12h_mode() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 12, // Noon, 12 PM
                    selectedMinute = 0,
                    onTimeSelected = { _, _ -> },
                    use24Hour = false
                )
            }
        }

        // Noon shows as 12 PM; the circular AM/PM wheel can show "PM" on more than one row.
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

                // Changes the hour from outside the picker.
                androidx.compose.material3.Button(
                    onClick = { hour = 15 }
                ) {
                    androidx.compose.material3.Text("Change Hour")
                }
            }
        }

        composeTestRule.onNodeWithText("10").assertIsDisplayed()

        composeTestRule.onNodeWithText("Change Hour").performClick()

        composeTestRule.waitForIdle()

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

        // 30 is on the 15-minute grid.
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_rounds_to_nearest_minute_interval() {
        var callbackMinute = -1

        composeTestRule.setContent {
            MaterialTheme {
                WheelTimePicker(
                    selectedHour = 10,
                    selectedMinute = 32, // Nearest 5-minute option is 30
                    onTimeSelected = { _, minute -> callbackMinute = minute },
                    use24Hour = true,
                    minuteInterval = 5
                )
            }
        }

        // Let the rounding LaunchedEffect run.
        composeTestRule.waitForIdle()

        // Checks only that the picker composes; neither this test nor a unit test asserts the
        // rounded minute, and callbackMinute stays unread (rounding doesn't call onTimeSelected).
        composeTestRule.waitForIdle()
    }

    // ==================== Initial Index Floor (no crash) ====================

    @Test
    fun verticalWheelPicker_nonCircular_2items_visibleItems3_noCrash() {
        // A 2-item non-circular wheel with visibleItems=3. The initial index
        // (selectedIndex - centeringOffset) is -1 here and must floor at 0, or it crashes.
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
        // A 2-item non-circular wheel with the default visibleItems=5: the unfloored initial
        // index is -2.
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
        // PM selected, visibleItems=3.
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
        // Full WheelTimePicker in 12h mode, a morning hour, visibleItems=3.
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
        // Full WheelTimePicker in 12h mode, an afternoon hour, visibleItems=3.
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
        // Midnight (hour 0, 12 AM), visibleItems=3.
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
        // 24h mode, visibleItems=3.
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

    // ==================== Swipe Selection ====================

    @Test
    fun verticalWheelPicker_swipe_selects_center_item() {
        // After a swipe the centered item is selected, not the top visible one (hour 17
        // centered must not report 15).

        var selectedValue = 12

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

        assertEquals("Initial selection should be 12", 12, selectedValue)

        // Swiping up scrolls toward higher numbers (13, 14, 15...).
        composeTestRule.onNodeWithContentDescription("Wheel picker with 24 options")
            .performTouchInput {
                swipeUp(startY = centerY, endY = centerY - 200f)
            }

        composeTestRule.waitForIdle()
        Thread.sleep(500)  // Time for the snap animation to finish
        composeTestRule.waitForIdle()

        // The exact value depends on swipe distance, so the offset from the centered item
        // isn't checked.
        println("After swipe up: selectedValue = $selectedValue")

        // Only that the selection moved off 12 is asserted, not its direction.
        assertNotEquals("Selection should have changed from 12", 12, selectedValue)
    }

    // ==================== Visible Items Configuration (Issue #88) ====================

    @Test
    fun wheelTimePicker_displays_with_5_visible_items() {
        // visibleItems=5, the default, for larger touch targets on small screens.
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

        // The centered hour and minute; adjacent items aren't asserted.
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun wheelTimePicker_12h_mode_with_5_visible_items_no_crash() {
        // The 2-item AM/PM wheel, circular in WheelTimePicker, with visibleItems=5.
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

    // ==================== Emission While Scrolling (#238) ====================

    @Test
    fun verticalWheelPicker_emits_intermediate_items_during_animated_scroll() {
        // The callback fires as the wheel scrolls, not only on settle, so a mid-fling Done
        // commits the shown value (#238). An outside selectedItem change drives an animated
        // scroll across many items; every onItemSelected call is captured.
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

        // The picker's LaunchedEffect(selectedItem) calls animateScrollToItem, which steps
        // centerIndex through intermediate values frame by frame.
        composeTestRule.runOnUiThread { externalSelected.value = 18 }

        // Poll for animation progress; the snap-fling animation takes about 300-500ms.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && captured.size < 3) {
            composeTestRule.mainClock.advanceTimeBy(16)
        }
        composeTestRule.waitForIdle()

        // 0 to 18 takes the shorter way round: 0, 23, 22, ... 18. Emission while scrolling
        // gives more than the final value.
        assertTrue(
            "Expected intermediate emissions during animated scroll, got: $captured",
            captured.size >= 2
        )
        // The final centered value is 18.
        assertEquals(18, captured.last())
    }

    @Test
    fun verticalWheelPicker_circular_recenters_to_middle_band_after_settle() {
        // Edge recentering happens after settle, separate from the snapshotFlow effect that
        // emits the selection (#238).
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

        // A long run of scrolls pushes the virtual index away from the middle.
        // CIRCULAR_MULTIPLIER = 1000 and 24 items give middleStart = 12000 and a recenter
        // threshold of 6000. The virtual index can't be queried, so the check is that the
        // picker stays centered on each chosen item.
        listOf(7, 14, 21, 4, 11, 18, 1, 8, 15, 22).forEach { target ->
            composeTestRule.runOnUiThread { externalSelected.value = target }
            composeTestRule.waitForIdle()
            // Drive the snap and animateScrollToItem to completion with the Compose clock,
            // which is deterministic on slow CI emulators where Thread.sleep races the
            // animation.
            composeTestRule.mainClock.advanceTimeBy(500)
            composeTestRule.waitForIdle()
        }

        // After many large jumps the final item still displays. Without recentering the
        // virtual list would run off its end and the item wouldn't be visible.
        composeTestRule.onNodeWithText("22").assertIsDisplayed()
    }

    @Test
    fun verticalWheelPicker_does_not_double_emit_for_same_centered_item() {
        // distinctUntilChanged and the item != selectedItem check keep the same item from being
        // reported twice in a row (#238).
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

        // Scroll to 10 and back to 5, the start. Compose's clock drives it, not Thread.sleep,
        // so it is deterministic on CI emulators with variable wall-clock latency.
        composeTestRule.runOnUiThread { externalSelected.value = 10 }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(800)
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { externalSelected.value = 5 }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(800)
        composeTestRule.waitForIdle()

        // Each item may be emitted once per pass, so at most twice over the two passes; this
        // bounds the total count, not consecutive emissions.
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

        assertEquals("Initial hour should be 10", 10, lastSelectedHour)

        // The first "10" is on the hour wheel, which comes first in 24h mode (the minute
        // wheel shows a "10" too). Swiping up increases the hour.
        composeTestRule.onAllNodesWithText("10")[0]
            .performTouchInput {
                swipeUp(startY = centerY, endY = centerY - 150f)
            }

        composeTestRule.waitForIdle()
        Thread.sleep(500)
        composeTestRule.waitForIdle()

        println("After swipe: lastSelectedHour = $lastSelectedHour")

        // The exact hour depends on swipe physics, so only that it changed is asserted.
        assertNotEquals("Hour should have changed after swipe", 10, lastSelectedHour)
    }
}
