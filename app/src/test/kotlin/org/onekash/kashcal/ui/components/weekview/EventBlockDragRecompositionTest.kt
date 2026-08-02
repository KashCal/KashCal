package org.onekash.kashcal.ui.components.weekview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Regression guard for EventBlock's recomposition contract, related to #319.
 * When the same EventBlock node is recomposed with a fresh callback that closes
 * over a different DisplayEvent (as happens when a day re-sorts and a positional
 * slot is rebound), a tap or long-press must invoke the *current* callback, not
 * one captured at first composition. This isolates that contract to EventBlock;
 * the end-to-end list-identity fix lives in DayColumn's keyed slots.
 *
 * Runs under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EventBlockDragRecompositionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tag = "eventBlock"

    private fun displayEvent(id: Long, title: String): DisplayEvent =
        roomDisplayEvent(id = id, title = title, date = LocalDate.of(2023, 11, 14), hour = 9)

    @Test
    fun long_press_after_recomposition_fires_current_events_onDragStart() {
        var slotEventId by mutableStateOf(1L)
        var dragStartedFor: Long? = null

        composeTestRule.setContent {
            MaterialTheme {
                // Mirror DayColumn: read the slot's current event as a plain
                // value, then build a fresh lambda that captures it. The captured
                // value differs each recomposition — a stale gesture coroutine
                // would keep firing the first one.
                val current = slotEventId
                EventBlock(
                    displayEvent = displayEvent(current, "Event $current"),
                    height = 80.dp,
                    onClick = {},
                    isDraggable = true,
                    onDragStart = { dragStartedFor = current },
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        // Reschedule re-sorts the day: this slot now holds a different event.
        slotEventId = 2L
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(tag).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(
            "long-press must start dragging the event currently in the slot, not the one from first composition (#319)",
            2L,
            dragStartedFor
        )
    }

    @Test
    fun tap_after_recomposition_fires_current_events_onClick() {
        var slotEventId by mutableStateOf(1L)
        var clickedFor: Long? = null

        composeTestRule.setContent {
            MaterialTheme {
                val current = slotEventId
                EventBlock(
                    displayEvent = displayEvent(current, "Event $current"),
                    height = 80.dp,
                    onClick = { clickedFor = current },
                    isDraggable = true,
                    onDragStart = {},
                    modifier = Modifier.size(120.dp, 80.dp).testTag(tag)
                )
            }
        }

        slotEventId = 2L
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "tap must open the event currently in the slot, not the one from first composition (#319)",
            2L,
            clickedFor
        )
    }
}
