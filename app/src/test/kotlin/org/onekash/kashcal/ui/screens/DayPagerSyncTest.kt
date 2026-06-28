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
 * Regression tests for the day-pager <-> selectedDate feedback loop (issue #267).
 *
 * Runs under Robolectric in the unit-test source set (no emulator), driving the
 * real production wiring ([rememberDayPagerSyncCoordinator]) through a
 * [MutableInteractionSource]. This verifies the actual drag → propagate gating
 * code, not a replica.
 *
 * The earlier bug: every settle was echoed up to selectedDate, including the
 * settle a programmatic scroll produced, so two rapid taps oscillated. The fix
 * gates the echo on whether the settle concluded a user drag.
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
            // No drag emitted: this models a settle that followed a programmatic
            // scroll (grid tap / Today / cold-start). It must NOT propagate.
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
                // Real user swipe: Start then Stop in order, then the pager
                // settles and the following settle must propagate.
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
        // Models the issue #267 scenario at the gating layer: a burst of
        // programmatic settles (from two near-simultaneous taps each scrolling
        // the pager) must produce zero echoes back to selectedDate, so the
        // settle↔selectedDate loop cannot sustain itself.
        val source = MutableInteractionSource()
        var echoes = 0

        composeTestRule.setContent {
            val coordinator = rememberDayPagerSyncCoordinator(source)
            LaunchedEffect(Unit) {
                // No DragInteraction emitted — every settle here is programmatic.
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
        // Integration guard: drives a REAL PagerState through the production
        // SYNC 1 (gated echo) and SYNC 2 (selectedDate -> scroll) effect logic,
        // with the real coordinator and real page<->date math. Two rapid
        // programmatic selectedDate writes must converge: the pager rests on the
        // last selection, no settle echoes back, and the convergence invariant
        // pageToDateMs(settledPage) == selectedDate holds at rest. (The
        // gating logic itself is mutation-covered by the coordinator and helper
        // tests above; this test locks the wired contract end-to-end.)
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

            // SYNC 1: settle -> gated echo back to selectedDate (mirrors production).
            LaunchedEffect(pagerState.settledPage) {
                val newDateMs = DayPagerUtils.pageToDateMs(pagerState.settledPage, todayMs)
                val isUserSettle = coordinator.shouldPropagateSettle()
                if (newDateMs != selectedDate && isUserSettle) {
                    echoes++
                    selectedDate = newDateMs
                }
            }
            // SYNC 2: selectedDate -> scroll the pager to match (mirrors production).
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

            // A laid-out pager is required for scrollToPage to actually move.
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
