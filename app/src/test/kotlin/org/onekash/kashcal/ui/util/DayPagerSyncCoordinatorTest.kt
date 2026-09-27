package org.onekash.kashcal.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [DayPagerSyncCoordinator], the decision logic that breaks the feedback loop between the
 * day pager and selectedDate (#267).
 *
 * A pager settle is pushed back to the ViewModel only when it concluded a user drag. A
 * programmatic scroll (grid tap, Today, cold start) produces no drag, so its settle must never
 * propagate; echoing it is what made the selection oscillate forever.
 *
 * The pager settles after the finger lifts and the fling ends, so "is the finger down" is the
 * wrong signal. A user drag latches intent that the following settle consumes.
 */
class DayPagerSyncCoordinatorTest {

    @Test
    fun `programmatic-only settle does not propagate`() {
        val coordinator = DayPagerSyncCoordinator()
        // No drag happened: a programmatic scroll.
        assertFalse(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `settle concluding a user drag propagates`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        coordinator.onDragStopped()
        // The settle that fires after the fling must propagate.
        assertTrue(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `user-drag intent is consumed by the settle it produced`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        coordinator.onDragStopped()
        assertTrue(coordinator.shouldPropagateSettle())
        // A later programmatic settle, with no new drag, must not propagate.
        assertFalse(coordinator.shouldPropagateSettle())
    }

    @Test
    fun `drag start alone latches intent before stop arrives`() {
        val coordinator = DayPagerSyncCoordinator()
        coordinator.onDragStarted()
        // A settle evaluated before Stop is observed is still a user gesture and must
        // propagate.
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
