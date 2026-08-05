package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Long-press-drag detector for week-view event blocks.
 *
 * Mounted on a grid-level overlay rather than on the blocks themselves: a block
 * lives inside a HorizontalPager page, and paging away mid-drag disposes that
 * page and cancels its pointer coroutine without ever reaching onEnd/onCancel —
 * leaving the drag latched and the pager frozen. An overlay outside the pager
 * survives any number of page flips.
 *
 * Nothing is consumed until the long press succeeds, so taps, vertical scroll,
 * pinch-zoom and pager swipes behave exactly as they did before. From then on
 * every change is consumed on the Initial pass, so no ancestor can steal the
 * gesture.
 *
 * All offsets are local to the overlay, i.e. viewport coordinates of the
 * day-columns area.
 */
suspend fun <T : Any> PointerInputScope.detectEventDrag(
    hitTest: (Offset) -> T?,
    onStart: (T, Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val hit = hitTest(down.position) ?: return@awaitEachGesture
        if (!awaitLongPress(down)) return@awaitEachGesture

        onStart(hit, down.position)
        var released = false
        try {
            while (true) {
                val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                val change = pointerEvent.changes.firstOrNull { it.id == down.id } ?: break
                pointerEvent.changes.forEach { it.consume() }
                if (!change.pressed) {
                    released = true
                    break
                }
                onMove(change.position)
            }
        } finally {
            // Runs on coroutine cancellation too, so the drag can never latch.
            if (released) onEnd() else onCancel()
        }
    }
}

/**
 * Waits out the long-press timeout, returning false if the gesture turns into
 * something else first: the finger lifts, travels past touch slop (a scroll or
 * a pager swipe), or a second finger joins (a pinch-zoom).
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        var abandoned = false
        while (!abandoned) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            abandoned = event.changes.size > 1 ||
                change == null ||
                !change.pressed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        }
        false
    }
} catch (_: PointerEventTimeoutCancellationException) {
    // Timing out is the success case: the finger stayed put long enough.
    true
}
