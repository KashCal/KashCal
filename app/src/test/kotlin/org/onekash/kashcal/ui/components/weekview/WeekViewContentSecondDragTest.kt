package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Regression for GitHub #319, driven through the real WeekViewContent so the
 * drag-state lifecycle is exercised end-to-end: a full long-press drag that
 * commits a reschedule, then a plain tap on a *second* event.
 *
 * Reported symptoms (physical device): after the first event is dragged to a new
 * time and the move commits, even a plain tap on a second event grabs the first
 * event again and snaps it back, while the tapped event's quick-view never opens.
 *
 * Mechanism: the reschedule re-sorts the day's events by start time. Without a
 * stable key on each event slot, Compose reuses the EventBlock nodes by position,
 * so the node whose long-lived gesture pointerInput coroutine was just active for
 * the first (dragged) event is rebound to the slot the user now taps — and keeps
 * firing the first event's callbacks. A stable key() per event slot makes Compose
 * move each node with its event instead of reusing by position.
 *
 * Runs under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class WeekViewContentSecondDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val date = LocalDate.now()

    private fun displayEvent(id: Long, title: String, hour: Int): DisplayEvent =
        roomDisplayEvent(id = id, title = title, date = date, hour = hour)

    // The day (1), 3-day (3) and week (7) views all render through the same
    // WeekViewContent -> DayColumn path, so the fix must hold for every one.
    @Test
    fun day_view_tap_second_event_after_committed_drag_opens_that_event() =
        assertSecondEventTapIsNotHijacked(visibleDays = 1)

    @Test
    fun three_day_view_tap_second_event_after_committed_drag_opens_that_event() =
        assertSecondEventTapIsNotHijacked(visibleDays = 3)

    @Test
    fun week_view_tap_second_event_after_committed_drag_opens_that_event() =
        assertSecondEventTapIsNotHijacked(visibleDays = 7)

    private fun assertSecondEventTapIsNotHijacked(visibleDays: Int) {
        // Alpha 09:00, Bravo 14:00 on the same day (well separated so both are
        // hittable). In multi-day views both land in the same day column.
        val alpha = displayEvent(1L, "Alpha", 9)
        val bravo = displayEvent(2L, "Bravo", 14)

        var timed by mutableStateOf(persistentListOf(alpha, bravo).toImmutableList())
        var clickedTitle: String? = null
        var rescheduledTitle: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = timed,
                    allDayEvents = persistentListOf(),
                    isLoading = false,
                    error = null,
                    scrollPosition = 0,
                    hourHeight = 60f,
                    visibleDays = visibleDays,
                    onDatePickerRequest = {},
                    onScrollPositionChange = {},
                    onEventClick = { clickedTitle = it.title },
                    onReschedule = { ev, _, targetMinutes ->
                        // Mirror the Room Flow: reschedule mutates the list, which
                        // re-emits and recomposes the grid (Alpha moves later).
                        rescheduledTitle = ev.title
                        val movedHour = targetMinutes / 60
                        timed = persistentListOf(
                            displayEvent(1L, "Alpha", movedHour),
                            bravo
                        ).toImmutableList()
                    },
                    modifier = Modifier.fillMaxSize().testTag("week")
                )
            }
        }
        composeTestRule.waitForIdle()

        // Full committing drag of Alpha: press, hold past long-press, move down, release.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText("Alpha").performTouchInput {
            down(center)
            // Hold past the long-press timeout so the drag arms.
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 300f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 300f))
            up()
        }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // Now simply TAP Bravo. It must open Bravo's quick-view and must NOT
        // re-trigger a reschedule of Alpha.
        rescheduledTitle = null
        clickedTitle = null

        composeTestRule.onNodeWithText("Bravo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "tapping Bravo must open Bravo, not the dragged event (#319, visibleDays=$visibleDays)",
            "Bravo",
            clickedTitle
        )
        assertNull(
            "tapping Bravo must not reschedule any event (#319, visibleDays=$visibleDays)",
            rescheduledTitle
        )
    }
}
