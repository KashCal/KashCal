package org.onekash.kashcal.ui.shared

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.abs

/**
 * Accumulates one pinch gesture's zoom from its start value, so each frame multiplies a value
 * this gesture owns rather than state that lags a frame behind. Every frame's change is applied,
 * however small, so a slow pinch adds up. [clamp] bounds the seed and every result.
 */
class PinchZoomAccumulator(seed: Float, private val clamp: (Float) -> Float) {
    private var value = clamp(seed)

    /** Applies one frame's zoom change and returns the clamped zoom. */
    fun apply(zoomChange: Float): Float {
        value = clamp(value * zoomChange)
        return value
    }
}

/**
 * Turns a two-finger pinch into zoom changes, leaving one-finger gestures to the element and its
 * ancestors (a pager's swipe, pull-to-refresh).
 *
 * Only two-finger events count, so a one-finger drag is never taken. The pinch is read in the
 * Initial pass, before a pager or scrollable sees it, and consumed only once it has moved past
 * half the touch slop, so two fingers resting still aren't taken either. Each gesture
 * starts from [currentZoom], so a second pinch carries on from where the first stopped;
 * [onZoomChange] gets each new [clamp]ed value. New lambdas from a recomposition replace the old
 * ones without restarting a gesture in progress.
 */
fun Modifier.pinchZoom(
    currentZoom: () -> Float,
    clamp: (Float) -> Float,
    onZoomChange: (Float) -> Unit,
): Modifier = this then PinchZoomElement(currentZoom, clamp, onZoomChange)

private class PinchZoomElement(
    val currentZoom: () -> Float,
    val clamp: (Float) -> Float,
    val onZoomChange: (Float) -> Unit,
) : ModifierNodeElement<PinchZoomNode>() {
    override fun create() = PinchZoomNode(currentZoom, clamp, onZoomChange)

    override fun update(node: PinchZoomNode) {
        node.currentZoom = currentZoom
        node.clamp = clamp
        node.onZoomChange = onZoomChange
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pinchZoom"
    }

    // Identity equality: every recomposition's element differs, so update() hands the node the
    // new lambdas; the node itself is kept, so a pinch in progress isn't cancelled.
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

private class PinchZoomNode(
    var currentZoom: () -> Float,
    var clamp: (Float) -> Float,
    var onZoomChange: (Float) -> Unit,
) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode(
                PointerInputEventHandler {
                    val touchSlop = viewConfiguration.touchSlop * 0.5f
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var accumulator: PinchZoomAccumulator? = null
                        var lastEmitted = currentZoom()
                        // Zoom gathered before the slop is passed, gathered across frames as
                        // detectTransformGestures does: a steady pinch moves only a few percent
                        // per frame, which a per-frame check would never let past the slop.
                        var slopZoom = 1f
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } < 2) continue
                            val zoomChange = event.calculateZoom()
                            val active = accumulator
                            val next = if (active == null) {
                                slopZoom *= zoomChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false).coerceAtLeast(48f)
                                if (abs(1 - slopZoom) * centroidSize <= touchSlop) continue
                                // The frame that passes the slop applies everything gathered so
                                // far.
                                PinchZoomAccumulator(currentZoom(), clamp).also { accumulator = it }.apply(slopZoom)
                            } else {
                                active.apply(zoomChange)
                            }
                            event.changes.forEach { it.consume() }
                            if (next != lastEmitted) {
                                lastEmitted = next
                                onZoomChange(next)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            ),
        )
    }
}

/** Provides [LocalDensity] scaled by [zoom], so dp and sp inside grow together. */
@Composable
internal fun Zoomed(zoom: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = remember(density, zoom) { Density(density.density * zoom, density.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
