package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Regression guard: the all-day strip must sit above the timed grid and reserve
 * its own height, never overlaying and hiding the grid's earliest hours
 * (midnight onward). A previous overlay layout floated the strip on top of the
 * grid, which started at y=0 under the opaque strip, so the more all-day events
 * a day had, the more of the early morning was covered and could never be
 * scrolled into view.
 *
 * The contract, verified through the real composable at scroll-top: the first
 * time label (midnight / hour 0) must sit at or below the strip's bottom edge,
 * never behind it.
 *
 * Runs headless under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class AllDayStripOcclusionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // The day/3-day pager opens on today, so the events must land on today's
    // page to populate the visible strip.
    private val day: LocalDate = LocalDate.now()

    private fun render(allDay: List<DisplayEvent>) {
        composeTestRule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = persistentListOf(),
                    allDayEvents = allDay.toImmutableList(),
                    isLoading = false,
                    error = null,
                    // scroll-top: the grid is at midnight, where occlusion bites.
                    scrollPosition = 0,
                    savedScrollMinutes = 0,
                    visibleDays = 3,
                    onDatePickerRequest = {},
                    onEventClick = {},
                    onScrollPositionChange = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertMidnightBelowStrip() {
        val stripBottom = composeTestRule.onNodeWithTag(TEST_TAG_ALL_DAY_STRIP)
            .getUnclippedBoundsInRoot().bottom
        val midnightTop = composeTestRule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL)
            .getUnclippedBoundsInRoot().top

        assertTrue(
            "Midnight time label (top=$midnightTop) must not be hidden behind the " +
                "all-day strip (bottom=$stripBottom)",
            midnightTop.value >= stripBottom.value - 0.5f
        )
    }

    @Test
    fun midnight_is_visible_below_strip_with_one_all_day_event() {
        render(listOf(allDayDisplayEvent(id = 1, title = "Holiday", date = day)))
        assertMidnightBelowStrip()
    }

    @Test
    fun midnight_is_visible_below_strip_with_many_all_day_events() {
        // A pile of all-day events makes the strip its tallest; this is the case
        // that hid the most morning hours before the fix.
        val events = (1..6).map { allDayDisplayEvent(id = it.toLong(), title = "AllDay $it", date = day) }
        render(events)
        assertMidnightBelowStrip()
    }
}
