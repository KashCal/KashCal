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
 * Checks that the all-day strip sits above the timed grid and reserves its own height, never
 * overlaying the grid's earliest hours. A strip floated over a grid starting at y=0 would hide
 * more of the early morning the more all-day events a day had, and that part could never be
 * scrolled into view.
 *
 * Through the real [WeekViewContent] at scroll-top, the first time label (midnight) must sit at
 * or below the strip's bottom edge (0.5 px tolerance), and the strip must stay under 200 dp tall.
 *
 * Runs headless under Robolectric in the unit source set (no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class AllDayStripOcclusionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // The 3-day pager opens on today, so the events must land on today's page to fill the
    // visible strip.
    private val day: LocalDate = LocalDate.now()

    private fun render(allDay: List<DisplayEvent>) {
        composeTestRule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = persistentListOf(),
                    allDayEvents = allDay.toImmutableList(),
                    isLoading = false,
                    error = null,
                    // Scroll-top: the grid is at midnight, where the strip would hide it.
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
        // A pile of all-day events makes the strip its tallest, the case where an overlaid strip
        // would hide the most morning hours.
        val events = (1..6).map { allDayDisplayEvent(id = it.toLong(), title = "AllDay $it", date = day) }
        render(events)
        assertMidnightBelowStrip()
    }

    @Test
    fun all_day_strip_stays_bounded_and_does_not_starve_the_timed_grid() {
        // The occlusion checks above only assert midnight isn't behind the strip. A fill-height
        // modifier on the strip's day-column box would let the non-weighted strip take the whole
        // screen height and starve the weighted, scrollable timed grid to zero px (blank screen,
        // no scroll); midnight and the strip bottom would both sit at the screen bottom, so the
        // occlusion check would still pass. An overflow case (a pile of all-day events, a "+N"
        // badge) engages the bottom-anchoring min-height, and the strip height is checked
        // directly: under 200 dp, not ballooning toward the 720 dp viewport.
        val events = (1..6).map { allDayDisplayEvent(id = it.toLong(), title = "AllDay $it", date = day) }
        render(events)
        val strip = composeTestRule.onNodeWithTag(TEST_TAG_ALL_DAY_STRIP).getUnclippedBoundsInRoot()
        val stripHeight = strip.bottom - strip.top
        assertTrue(
            "All-day strip height ($stripHeight) must stay bounded to its content, " +
                "not fill the screen and starve the timed grid",
            stripHeight.value < 200f
        )
    }
}
