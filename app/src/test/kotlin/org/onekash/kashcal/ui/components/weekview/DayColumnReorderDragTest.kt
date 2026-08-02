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
import androidx.compose.ui.test.onNodeWithText
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
 * Regression for GitHub #319: after one event is rescheduled, the day/3-day/week
 * grid re-sorts the day's events (by start time) and the keyless forEach in
 * DayColumn rebinds each positional EventBlock slot to a *different* DisplayEvent.
 * Grabbing an event after that reorder must start dragging the event the user
 * actually touched — not whichever event first occupied that positional slot.
 *
 * Runs under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class DayColumnReorderDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val date = LocalDate.of(2026, 5, 3)

    /** Timed event on [date] at [hour]:00 lasting one hour. */
    private fun displayEvent(id: Long, title: String, hour: Int): DisplayEvent =
        roomDisplayEvent(id = id, title = title, date = date, hour = hour)

    @Test
    fun long_press_after_reschedule_reorder_drags_the_touched_event() {
        // Alpha at 09:00 (slot 0), Bravo at 11:00 (slot 1).
        val alpha = displayEvent(1L, "Alpha", 9)
        val bravo = displayEvent(2L, "Bravo", 11)

        // Reschedule Alpha to 12:00 so the sorted order flips to Bravo, Alpha —
        // slot 0 now holds Bravo, slot 1 now holds Alpha.
        val alphaMoved = displayEvent(1L, "Alpha", 12)

        var events by mutableStateOf(listOf(alpha, bravo))
        var draggedTitle: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                DayColumn(
                    date = date,
                    events = events,
                    onEventClick = {},
                    onOverflowClick = {},
                    onEventDragStart = { ev, _ -> draggedTitle = ev.title },
                    modifier = Modifier.size(200.dp, 1400.dp).testTag("dayColumn")
                )
            }
        }

        // First drag of Bravo works (baseline).
        composeTestRule.onNodeWithText("Bravo").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals("Bravo", draggedTitle)

        // Reschedule Alpha -> the day re-sorts and slots rebind.
        draggedTitle = null
        events = listOf(alphaMoved, bravo)
        composeTestRule.waitForIdle()

        // Now grab Bravo again. It must still be Bravo that starts dragging,
        // not Alpha (whose lambda first occupied Bravo's new positional slot).
        composeTestRule.onNodeWithText("Bravo").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(
            "after reschedule reorder, long-pressing Bravo must drag Bravo, not the event that first held its slot (#319)",
            "Bravo",
            draggedTitle
        )
    }
}
