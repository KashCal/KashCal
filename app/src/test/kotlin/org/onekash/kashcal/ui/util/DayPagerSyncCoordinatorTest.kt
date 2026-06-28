package org.onekash.kashcal.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DayPagerSyncCoordinator], the pure decision logic that breaks
 * the day-pager <-> selectedDate feedback loop behind issue #267.
 *
 * The rule: a pager settle should only be echoed back to the ViewModel when it
 * concluded a genuine USER DRAG. A programmatic scroll (grid tap, Today,
 * cold-start) produces no drag, so its settle must never propagate — that is the
 * stale-echo path that caused the infinite oscillation.
 *
 * The pager settles AFTER the finger lifts (post-fling), so "is the finger down
 * right now" is the wrong signal. Instead a user drag latches intent that is
 * consumed by the settle that follows it.
 */
class DayPagerSyncCoordinatorTest {

    @Test
    fun `programmatic-only settle does not propagate`() {
        val coordinator = DayPagerSyncCoordinator()
        // No drag ever happened (pure programmatic scroll).
        assertFalse(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `settle concluding a user drag propagates`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        coordinator.onDragStopped()
        // The settle that fires right after the fling settles must propagate.
        assertTrue(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `user-drag intent is consumed by the settle it produced`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        coordinator.onDragStopped()
        assertTrue(coordinator.shouldPropagateSettle())
        // A subsequent programmatic settle (no new drag) must NOT propagate.
        assertFalse(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `drag start alone latches intent before stop arrives`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        // Even if the settle is evaluated before Stop is observed, it is a user
        // gesture and must propagate.
        assertTrue(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `stop without start is idempotent and does not propagate`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStopped()
        coordinator.onDragStopped()
        assertFalse(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `interleaved start stop start resolves to a propagating settle`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        coordinator.onDragStopped()
        coordinator.onDragStarted()
        assertTrue(coordinator.shouldPropagateSettle())
    }
}
