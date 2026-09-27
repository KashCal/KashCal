package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.ui.util.rememberDayPagerSyncCoordinator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the day-pager and selectedDate feedback loop (#267): only a settle that concluded a user
 * drag is echoed up to selectedDate, so a programmatic scroll's settle can't make two rapid taps
 * oscillate.
 *
 * Runs under Robolectric without an emulator and drives the production
 * [rememberDayPagerSyncCoordinator] through a [MutableInteractionSource]. The last test also runs
 * a real pager through inline copies of HomeScreen's two pager effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DayPagerSyncTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun programmatic_settle_is_not_echoed_back_to_selected_date() {
        val source = MutableInteractionSource()
        var propagated: Boolean? = null

        composeTestRule.setContent {
            val coordinator = rememberDayPagerSyncCoordinator(source)
            // No drag emitted: a settle that followed a programmatic scroll (grid tap, Today,
            // cold start). It must not propagate.
            LaunchedEffect(Unit) {
                propagated = coordinator.shouldPropagateSettle()
            }
        }

        composeTestRule.waitForIdle()
        assertFalse(
            "A programmatic settle must not echo back into selectedDate (#267)",
            propagated!!
        )
    }

    @Test
    fun user_swipe_settle_is_propagated() {
        val source = MutableInteractionSource()
        var propagated: Boolean? = null

        composeTestRule.setContent {
            val coordinator = rememberDayPagerSyncCoordinator(source)
            LaunchedEffect(Unit) {
                val start = DragInteraction.Start()
                // A user swipe: Start then Stop, so the following settle must propagate.
                source.emit(start)
                source.emit(DragInteraction.Stop(start))
                composeTestRule.awaitIdle()
                propagated = coordinator.shouldPropagateSettle()
            }
        }

        composeTestRule.waitForIdle()
        assertTrue(
            "A settle that concluded a user swipe must update selectedDate",
            propagated!!
        )
    }

    @Test
    fun rapid_taps_converge_no_oscillation() {
        // The #267 scenario at the gating layer: a burst of programmatic settles (two
        // near-simultaneous taps each scrolling the pager) must echo nothing back to
        // selectedDate, so the loop can't sustain itself.
        val source = MutableInteractionSource()
        var echoes = 0

        composeTestRule.setContent {
            val coordinator = rememberDayPagerSyncCoordinator(source)
            LaunchedEffect(Unit) {
                // No DragInteraction emitted, so every settle here is programmatic.
                repeat(10) {
                    if (coordinator.shouldPropagateSettle()) echoes++
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(
            "Programmatic settles from rapid taps must not echo back (no oscillation)",
            0,
            echoes
        )
    }

    @Test
    fun wired_pager_converges_and_stays_in_sync_after_rapid_selections() {
        // Drives a real PagerState through inline copies of HomeScreen's two day-pager effects,
        // labeled SYNC 1 (gated echo) and SYNC 2 (selectedDate to scroll) below, with the real
        // coordinator and page-to-date math. Two rapid programmatic selectedDate writes must
        // converge: the pager rests on the last selection, no settle echoes back, and
        // pageToDateMs(settledPage) == selectedDate at rest. The gating logic itself is covered by
        // the tests above and `DayPagerSyncCoordinatorTest`.
        val todayMs = DayPagerUtils.dayCodeToMs(20260615)
        val dateA = DayPagerUtils.dayCodeToMs(20260620)
        val dateB = DayPagerUtils.dayCodeToMs(20260628)
        var echoes = 0
        var finalSelectedDate = 0L
        var finalSettledDateMs = 0L

        composeTestRule.setContent {
            val pagerState = rememberPagerState(
                initialPage = DayPagerUtils.dateToPage(todayMs, todayMs)
            ) { DayPagerUtils.TOTAL_PAGES }
            var selectedDate by remember { mutableLongStateOf(todayMs) }
            val coordinator = rememberDayPagerSyncCoordinator(pagerState.interactionSource)

            // SYNC 1: settle to a gated echo back to selectedDate (mirrors production).
            LaunchedEffect(pagerState.settledPage) {
                val newDateMs = DayPagerUtils.pageToDateMs(pagerState.settledPage, todayMs)
                val isUserSettle = coordinator.shouldPropagateSettle()
                if (newDateMs != selectedDate && isUserSettle) {
                    echoes++
                    selectedDate = newDateMs
                }
            }
            // SYNC 2: selectedDate scrolls the pager to match (mirrors production).
            LaunchedEffect(selectedDate) {
                val targetPage = DayPagerUtils.dateToPage(selectedDate, todayMs)
                if (targetPage != pagerState.currentPage) {
                    pagerState.scrollToPage(targetPage)
                }
            }

            // Two near-simultaneous taps: write A then B before anything settles.
            LaunchedEffect(Unit) {
                selectedDate = dateA
                selectedDate = dateB
            }

            finalSelectedDate = selectedDate
            finalSettledDateMs = DayPagerUtils.pageToDateMs(pagerState.settledPage, todayMs)

            // scrollToPage moves only a laid-out pager.
            HorizontalPager(state = pagerState, modifier = Modifier.size(320.dp)) {
                Box(Modifier.fillMaxSize())
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertEquals(
                "Programmatic settles must never echo back into selectedDate (#267)",
                0,
                echoes
            )
            assertEquals(
                "Selection must converge on the last tap (no oscillation back to A)",
                dateB,
                finalSelectedDate
            )
            assertEquals(
                "Convergence invariant: pager rests on the page matching selectedDate",
                finalSelectedDate,
                finalSettledDateMs
            )
        }
    }
}
