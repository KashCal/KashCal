package org.onekash.kashcal.ui.components.kash

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import org.onekash.kashcal.R

/** Width over height of every Kash drawing: the art is 220 by 210 in shape. */
internal const val KASH_ASPECT_RATIO = 220f / 210f

/**
 * Kash, the app's mascot, in one of its still poses. Each pose has two drawings, both
 * [KASH_ASPECT_RATIO] in shape: fur for light surfaces and a lighter fur for dark ones, so the
 * fuzzy edge stays visible.
 */
internal enum class KashPose(@DrawableRes val light: Int, @DrawableRes val dark: Int) {
    ASLEEP(R.drawable.kash_asleep, R.drawable.kash_asleep_dark),
    WINK(R.drawable.kash_wink, R.drawable.kash_wink_dark),
    GIFT(R.drawable.kash_gift, R.drawable.kash_gift_dark),
    CHEER(R.drawable.kash_cheer, R.drawable.kash_cheer_dark),
    MUG(R.drawable.kash_mug, R.drawable.kash_mug_dark),
    ARMFUL(R.drawable.kash_armful, R.drawable.kash_armful_dark),
}

/** The waving pose in two layers on one canvas: the arm, drawn first, and the body over it. */
internal data class KashWaveLayers(@DrawableRes val arm: Int, @DrawableRes val body: Int)

/** Returns [pose]'s drawing in dark-surface fur when [darkFur], else in light fur. */
@DrawableRes
internal fun kashDrawable(pose: KashPose, darkFur: Boolean): Int =
    if (darkFur) pose.dark else pose.light

/** Returns the waving pose's layers in dark-surface fur when [darkFur], else in light fur. */
internal fun kashWaveLayers(darkFur: Boolean): KashWaveLayers =
    if (darkFur) {
        KashWaveLayers(R.drawable.kash_wave_arm_dark, R.drawable.kash_wave_body_dark)
    } else {
        KashWaveLayers(R.drawable.kash_wave_arm, R.drawable.kash_wave_body)
    }

/**
 * The waving arm's shoulder, the point it turns about, as fractions of the wave layers' canvas:
 * 30% across and 95% down the arm's bounding box in the drawing. It belongs to the drawings, so
 * it changes only when they do.
 */
internal val KASH_WAVE_PIVOT = TransformOrigin(0.843f, 0.462f)

/**
 * Returns whether Kash should wear dark-surface fur: true when the theme in use has a dark
 * surface. It reads the theme, not the phone, because the app's own theme setting can differ
 * from the system's, and resource night qualifiers follow only the system.
 */
@Composable
internal fun kashUsesDarkFur(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Draws Kash in [pose]. Kash is decorative: the text beside it says what the screen means, so it
 * has no content description and TalkBack skips it. Callers set the size.
 */
@Composable
internal fun KashArt(pose: KashPose, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(kashDrawable(pose, kashUsesDarkFur())),
        contentDescription = null,
        modifier = modifier,
    )
}
