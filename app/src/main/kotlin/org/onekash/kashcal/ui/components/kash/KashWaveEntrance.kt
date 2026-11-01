package org.onekash.kashcal.ui.components.kash

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import org.onekash.kashcal.ui.components.SheetEntranceEasing
import org.onekash.kashcal.ui.components.rememberEntranceProgress

/**
 * Where Kash is at one moment of its entrance: lifted by [offsetDp] (negative is up), scaled,
 * faded in by [alpha], and the waving arm turned by [armDegrees].
 */
internal data class KashWaveFrame(
    val offsetDp: Float,
    val scaleX: Float,
    val scaleY: Float,
    val alpha: Float,
    val armDegrees: Float,
) {
    companion object {
        /** Kash in place, arm down. */
        val REST = KashWaveFrame(offsetDp = 0f, scaleX = 1f, scaleY = 1f, alpha = 1f, armDegrees = 0f)
    }
}

// The settle: a drop into place that touches down with a squash, then rounds out.
private const val TOUCHDOWN_MS = 480
private const val SETTLED_MS = 704
// Two waves after the settle: each swing up and each swing back takes this long.
private const val SWING_MS = 256
private const val SWINGS = 4
private const val DROP_DP = 14f
private const val WAVE_DEGREES = -18f
private val SwingEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * How long the entrance waits once the sheet appears, so the sheet's slide-up doesn't hide the
 * drop.
 */
internal const val KASH_WAVE_START_DELAY_MS = 200L

/** How long the whole entrance runs: the settle, then both waves. */
internal const val KASH_WAVE_TOTAL_MS = SETTLED_MS + SWINGS * SWING_MS

/**
 * Returns Kash's frame [timeMs] into the entrance. It drops [DROP_DP] while fading in and
 * touches down squashed by [TOUCHDOWN_MS], rounds out by [SETTLED_MS] (both on the sheets'
 * entrance curve), then waves twice, the arm swinging up to [WAVE_DEGREES] and back. Times before
 * the start give the first frame; from [KASH_WAVE_TOTAL_MS] on it is [KashWaveFrame.REST].
 */
internal fun kashWaveFrame(timeMs: Long): KashWaveFrame {
    val t = timeMs.coerceIn(0L, KASH_WAVE_TOTAL_MS.toLong()).toInt()
    if (t < TOUCHDOWN_MS) {
        val p = SheetEntranceEasing.transform(t / TOUCHDOWN_MS.toFloat())
        return KashWaveFrame(
            offsetDp = lerp(-DROP_DP, 0f, p),
            scaleX = lerp(0.96f, 1.02f, p),
            scaleY = lerp(0.96f, 0.98f, p),
            alpha = p,
            armDegrees = 0f,
        )
    }
    if (t < SETTLED_MS) {
        val p = SheetEntranceEasing.transform((t - TOUCHDOWN_MS) / (SETTLED_MS - TOUCHDOWN_MS).toFloat())
        return KashWaveFrame(
            offsetDp = 0f,
            scaleX = lerp(1.02f, 1f, p),
            scaleY = lerp(0.98f, 1f, p),
            alpha = 1f,
            armDegrees = 0f,
        )
    }
    if (t >= KASH_WAVE_TOTAL_MS) return KashWaveFrame.REST
    val swing = (t - SETTLED_MS) / SWING_MS
    val p = SwingEasing.transform(((t - SETTLED_MS) % SWING_MS) / SWING_MS.toFloat())
    val angle = if (swing % 2 == 0) lerp(0f, WAVE_DEGREES, p) else lerp(WAVE_DEGREES, 0f, p)
    return KashWaveFrame.REST.copy(armDegrees = angle)
}

/**
 * Shows Kash settling in and waving hello, once per sheet: a rotation shows Kash at rest rather
 * than replaying, and with [motionEnabled] false it rests from the start. Decorative, like
 * [KashArt]. It starts [KASH_WAVE_START_DELAY_MS] after the sheet appears, while the sheet is still
 * sliding up. [onFrame] reports each frame composed, for tests.
 */
@Composable
internal fun KashWaveEntrance(
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    onFrame: (KashWaveFrame) -> Unit = {},
) {
    // Steady progress; kashWaveFrame eases each part of the entrance itself.
    val progress = rememberEntranceProgress(
        listOf(KASH_WAVE_START_DELAY_MS), KASH_WAVE_TOTAL_MS, motionEnabled, LinearEasing,
    ).single()
    val frame = kashWaveFrame((progress.value * KASH_WAVE_TOTAL_MS).toLong())
    SideEffect { onFrame(frame) }
    val layers = kashWaveLayers(kashUsesDarkFur())
    val offsetPx = with(LocalDensity.current) { frame.offsetDp.dp.toPx() }
    Box(modifier, contentAlignment = Alignment.Center) {
        // The layers' own canvas, so the shoulder pivot's fractions land on the drawing.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(KASH_ASPECT_RATIO)
                .graphicsLayer {
                    translationY = offsetPx
                    scaleX = frame.scaleX
                    scaleY = frame.scaleY
                    alpha = frame.alpha
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            // The arm is drawn first: in the drawing it sits behind the body.
            Image(
                painter = painterResource(layers.arm),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = frame.armDegrees
                        transformOrigin = KASH_WAVE_PIVOT
                    },
            )
            Image(painterResource(layers.body), contentDescription = null, modifier = Modifier.matchParentSize())
        }
    }
}
