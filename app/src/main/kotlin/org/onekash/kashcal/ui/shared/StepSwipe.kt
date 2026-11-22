package org.onekash.kashcal.ui.shared

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How far a sideways drag must go to take one step. */
private val STEP_DISTANCE = 40.dp

/** How fast a sideways fling must be, per second, to take one step however short it is. */
private val STEP_FLING_VELOCITY = 500.dp

/**
 * Returns the step a finished sideways drag asks for: 1 forward, -1 back, 0 none. [distancePx]
 * and [velocityPx] are signed toward the reading direction's end, so a drag toward the start
 * (leftward in a left-to-right layout) is a step forward. A fling past [minVelocityPx] decides by
 * its own direction; otherwise the drag must have gone [minDistancePx].
 */
internal fun stepFromDrag(distancePx: Float, velocityPx: Float, minDistancePx: Float, minVelocityPx: Float): Int =
    when {
        velocityPx <= -minVelocityPx -> 1
        velocityPx >= minVelocityPx -> -1
        distancePx <= -minDistancePx -> 1
        distancePx >= minDistancePx -> -1
        else -> 0
    }

/**
 * Makes a sideways drag on this element take one step through [onStep]: 1 for the next day or
 * month, -1 for the previous one, by [stepFromDrag] at [STEP_DISTANCE] or [STEP_FLING_VELOCITY].
 * A drag toward the reading direction's start is the next one, mirrored right to left the way
 * the AutoMirrored arrows are. [canGoBack] and [canGoForward] turn each way off, read when the
 * drag ends; off while [enabled] is false, including a drag switched off before it ends (the
 * drag node reports that cancel as a stop with no speed). Used by the day sheet and the date
 * pickers' month grids, so all their swipes page alike (`StepSwipeTest`).
 */
@Composable
internal fun Modifier.stepSwipe(
    enabled: Boolean = true,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onStep: (Int) -> Unit,
): Modifier {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val minDistancePx = with(density) { STEP_DISTANCE.toPx() }
    val minVelocityPx = with(density) { STEP_FLING_VELOCITY.toPx() }
    val dragged = remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragged.floatValue += delta }
    // Built on every composition: the drag node takes the new callbacks, so a drag that ends
    // after a step reads the current flags and month or day.
    return this.draggable(
        state = dragState,
        orientation = Orientation.Horizontal,
        enabled = enabled,
        // Reversed right to left, so the distance and speed are signed toward the reading
        // direction's end either way.
        reverseDirection = isRtl,
        onDragStarted = { dragged.floatValue = 0f },
        onDragStopped = { velocity ->
            val step = stepFromDrag(dragged.floatValue, velocity, minDistancePx, minVelocityPx)
            val allowed = (step == 1 && canGoForward) || (step == -1 && canGoBack)
            if (enabled && allowed) onStep(step)
        }
    )
}
