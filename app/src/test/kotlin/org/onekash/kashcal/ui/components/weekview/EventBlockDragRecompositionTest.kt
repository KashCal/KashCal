package org.onekash.kashcal.ui.components.weekview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * slot is rebound), a tap must invoke the *current* callback, not one captured
 * at first composition. This isolates that contract to EventBlock; the
 * end-to-end list-identity fix lives in DayColumn's keyed slots.
 *
 * Drag-to-reschedule is no longer detected here — it moved to a grid-level
 * overlay (see detectEventDrag in EventDragGesture.kt) that hit-tests the
 * touched position directly rather than relying on a per-block closure, so
 * it isn't susceptible to this recomposition hazard in the first place.
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
    fun tap_after_recomposition_fires_current_events_onClick() {
        var slotEventId by mutableStateOf(1L)
        var clickedFor: Long? = null

        composeTestRule.setContent {
            MaterialTheme {
                val current = slotEventId
                EventBlock(
                    displayEvent = displayEvent(current, "Event $current"),
                    height = 80.dp,
                    width = 120.dp,
                    onClick = { clickedFor = current },
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
