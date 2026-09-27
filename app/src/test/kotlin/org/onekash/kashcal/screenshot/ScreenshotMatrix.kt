package org.onekash.kashcal.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection as UiLayoutDirection
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode

/**
 * Renders a composable across a fixed variant matrix and records or verifies PNGs, for JVM
 * screenshot tests (Robolectric native graphics, no device). Record mode:
 * `-Proborazzi.test.record=true`; verify (the CI gate): `-Proborazzi.test.verify=true`.
 * Capture is a no-op unless a Roborazzi task mode is set, so these tests are inert in the
 * normal test sweep.
 *
 * The theme is pinned (LIGHT, SEED scheme, a fixed accent seed), never dynamic Material You,
 * which is device-derived and can't be reproduced off-device. A small changeThreshold absorbs
 * sub-pixel text anti-aliasing noise between the record host and the CI runner (both must be
 * Linux x86_64; see src/test/screenshots/README.md).
 *
 * A [Variant] varies only font scale and layout direction. Theme isn't a matrix dimension:
 * the surfaces captured so far are self-styled (a share card's colors come from its own style,
 * not MaterialTheme.colorScheme), so a light/dark split would give byte-identical duplicates.
 * A theme-responsive surface should add its own themed variant set rather than make every
 * caller carry a theme axis.
 */
object ScreenshotMatrix {

    /** Directory of the committed goldens; tracked, so never under build/. */
    private const val GOLDEN_DIR = "src/test/screenshots"

    /**
     * Fixed accent seed for every golden. A literal, not the app's runtime default, so a
     * change to that default can't silently invalidate goldens.
     */
    private const val ACCENT_SEED: Int = 0xFF6750A4.toInt()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    /** One cell of the render matrix. [label] becomes the golden filename suffix. */
    data class Variant(
        val fontScale: Float,
        val rtl: Boolean,
    ) {
        val label: String = "font${fontScale}_${if (rtl) "rtl" else "ltr"}"
    }

    /** font {1.0, 1.5} x direction {LTR, RTL} = 4 cells. */
    val FONT_AND_DIRECTION: List<Variant> = buildList {
        for (fontScale in listOf(1.0f, 1.5f)) {
            for (rtl in listOf(false, true)) {
                add(Variant(fontScale = fontScale, rtl = rtl))
            }
        }
    }

    /** Single representative cell for content states we don't need the full matrix on. */
    val CANONICAL = Variant(fontScale = 1.0f, rtl = false)

    /** Captures [content] once per variant in [variants], to `<name>__<label>.png`. */
    fun captureMatrix(
        name: String,
        variants: List<Variant> = FONT_AND_DIRECTION,
        content: @Composable () -> Unit,
    ) {
        variants.forEach { variant -> capture(name, variant, content) }
    }

    /** Captures [content] for a single [variant]. */
    fun capture(
        name: String,
        variant: Variant = CANONICAL,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage("$GOLDEN_DIR/${name}__${variant.label}.png", roborazziOptions = options) {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(variant.fontScale) then
                    DeviceConfigurationOverride.LayoutDirection(
                        if (variant.rtl) UiLayoutDirection.Rtl else UiLayoutDirection.Ltr,
                    ),
            ) {
                KashCalTheme(
                    themeMode = ThemeMode.LIGHT,
                    colorSource = ColorSource.SEED,
                    accentSeed = ACCENT_SEED,
                ) {
                    content()
                }
            }
        }
    }
}
