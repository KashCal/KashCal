package org.onekash.kashcal.ui.util

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Remembers a [DayPagerSyncCoordinator] and keeps it fed with the day pager's
 * drag interactions, so [DayPagerSyncCoordinator.shouldPropagateSettle] reflects
 * whether the current settle followed a user swipe.
 *
 * Extracted from the day pager so the drag → coordinator mapping is unit-testable
 * with a plain [androidx.compose.foundation.interaction.MutableInteractionSource]
 * (no fling simulation needed).
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
 * Breaks the day-pager <-> selectedDate feedback loop behind issue #267.
 *
 * The day view has two one-way bindings that, together, can form a cycle:
 *  - settle -> selectedDate: when the pager settles on a page, the new date is
 *    pushed up to the ViewModel.
 *  - selectedDate -> scroll: when selectedDate changes, the pager is scrolled to
 *    the matching page.
 *
 * Pushing EVERY settle upward means a programmatic scroll's own settle can be
 * echoed back as if it were navigation. When two dates are tapped in quick
 * succession the competing programmatic scrolls cancel and re-settle on
 * interleaved pages, and a settle observed against a stale selectedDate gets
 * written back — flipping the selection forever.
 *
 * The fix gates the upward push on a single fact: did this settle conclude a
 * real USER DRAG? A programmatic scroll emits no drag interaction, so its settle
 * is never propagated and the loop cannot form. A genuine swipe latches intent
 * that the following settle consumes.
 *
 * Holder is single-threaded: callers drive it from Compose effects on the
 * composition dispatcher, so a plain flag needs no synchronization.
 */
class DayPagerSyncCoordinator {

    /**
     * True once a user drag has begun and until the settle it produces has been
     * evaluated. The pager settles after the finger lifts (post-fling), so a
     * drag that has already stopped must keep this latched for the settle that
     * follows.
     */
    private var userDragPending = false

    /** Call when the pager reports a [androidx.compose.foundation.interaction.DragInteraction.Start]. */
    fun onDragStarted() {
        userDragPending = true
    }

    /**
     * Call when the pager reports a
     * [androidx.compose.foundation.interaction.DragInteraction.Stop] or Cancel.
     *
     * Intentionally does NOT clear the latch: the settle this drag produces
     * arrives after the stop, and that settle is the one that must propagate.
     */
    fun onDragStopped() {
        // No-op by design — the latch is consumed by shouldPropagateSettle().
    }

    /**
     * Decide whether the settle currently being processed should be pushed up to
     * the ViewModel, and consume the user-drag intent.
     *
     * @return true if the settle concluded a user drag (propagate it); false for
     *   a purely programmatic settle (suppress the echo).
     */
    fun shouldPropagateSettle(): Boolean {
        val propagate = userDragPending
        userDragPending = false
        return propagate
    }
}
