package org.onekash.kashcal.ui.util

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Remembers a [DayPagerSyncCoordinator] fed with the day pager's drag interactions, so
 * [DayPagerSyncCoordinator.shouldPropagateSettle] tells whether the current settle followed a
 * user swipe.
 *
 * Lives outside the day pager so `DayPagerSyncTest` can drive the drag-to-coordinator mapping
 * with a plain [androidx.compose.foundation.interaction.MutableInteractionSource], without
 * simulating a fling.
 */
@Composable
fun rememberDayPagerSyncCoordinator(
    interactionSource: InteractionSource
): DayPagerSyncCoordinator {
    val coordinator = remember { DayPagerSyncCoordinator() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> coordinator.onDragStarted()
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    coordinator.onDragStopped()
            }
        }
    }
    return coordinator
}

/**
 * Breaks the feedback loop between the day pager and selectedDate (#267).
 *
 * The day view has two one-way bindings that together can form a cycle:
 *  - settle to selectedDate: when the pager settles on a page, the new date is pushed up to the
 *    ViewModel.
 *  - selectedDate to scroll: when selectedDate changes, the pager scrolls to the matching page.
 *
 * If every settle were pushed up, a programmatic scroll's own settle would echo back as
 * navigation. When two dates are tapped in quick succession the competing scrolls cancel and
 * re-settle on interleaved pages, and a settle observed against a stale selectedDate is written
 * back, flipping the selection forever.
 *
 * So only a settle that concluded a user drag is pushed up. A programmatic scroll emits no drag
 * interaction, so its settle is never propagated and the loop can't form. A swipe latches intent
 * that the following settle consumes.
 *
 * Single-threaded: callers drive it from Compose effects on the composition dispatcher, so a
 * plain flag needs no synchronization.
 */
class DayPagerSyncCoordinator {

    /**
     * True from the start of a user drag until the settle it produces is evaluated. The pager
     * settles after the finger lifts and the fling ends, so a stopped drag must stay latched for
     * the settle that follows.
     */
    private var userDragPending = false

    /** Latches user-drag intent; call on [DragInteraction.Start]. */
    fun onDragStarted() {
        userDragPending = true
    }

    /**
     * Ends a drag; call on [DragInteraction.Stop] or [DragInteraction.Cancel].
     *
     * Deliberately leaves the latch set: the settle this drag produces arrives after the stop,
     * and that settle is the one that must propagate.
     */
    fun onDragStopped() {
        // A no-op by design; shouldPropagateSettle() consumes the latch.
    }

    /**
     * Returns whether the settle being processed should be pushed up to the ViewModel, and
     * consumes the user-drag intent.
     *
     * @return true if the settle concluded a user drag; false for a programmatic settle, whose
     *   echo is suppressed.
     */
    fun shouldPropagateSettle(): Boolean {
        val propagate = userDragPending
        userDragPending = false
        return propagate
    }
}
