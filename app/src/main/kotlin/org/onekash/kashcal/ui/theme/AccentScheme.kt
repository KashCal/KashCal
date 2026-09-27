package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * Builds a full Material 3 [ColorScheme] from one accent seed color.
 *
 * Every tonal role (primary, the containers, the surface family, error, outline) maps the seed's
 * hue onto fixed Material tones, so contrast doesn't depend on the seed's own lightness and a pale
 * seed such as gold stays readable. `AccentSchemeTest` checks WCAG AA for every selectable seed in
 * both faces. The HCT engine adjusts tonal contrast itself, so at the default level every checked
 * pair, the surface-container tones behind cards and bottom sheets included, already clears AA;
 * [ACCENT_CONTRAST_LEVEL] is the knob if a role ever needs more separation.
 *
 * Pure over the seed [Int]: it needs no [android.content.Context] and no composition, so widget
 * code ([org.onekash.kashcal.widget.accentColorProviders]) calls it too.
 *
 * @param seed packed ARGB accent color (e.g. `0xFF0E6E62.toInt()`).
 * @param dark whether to build the dark-face scheme.
 * @param contrastLevel HCT contrast axis (-1.0..1.0). Defaults to [ACCENT_CONTRAST_LEVEL] for the
 *   app; widgets pass [WIDGET_ACCENT_CONTRAST_LEVEL].
 * @param snapAchromaticContainers whether to apply [withCrispAchromaticContainer]. On by default
 *   for the app's tonal chips. Widgets pass `false`: their header rides `secondaryContainer`, and
 *   snapping it to pure white or black would blow the header out to the extreme instead of the
 *   muted accent tone. At the widget contrast level the raw engine already gives the achromatic
 *   header text about 5:1.
 */
fun accentColorScheme(
    seed: Int,
    dark: Boolean,
    contrastLevel: Double = ACCENT_CONTRAST_LEVEL,
    snapAchromaticContainers: Boolean = true,
): ColorScheme =
    dynamicColorScheme(
        seedColor = Color(seed),
        isDark = dark,
        // Content keeps the primary faithful to the picked seed: gray stays gray, black stays
        // black. TonalSpot keeps only the seed's hue and imposes its own chroma and tone, so a
        // low-chroma seed (gray, silver) turns into an unrelated color (teal) and black or white
        // can't give a dark or light primary.
        style = PaletteStyle.Content,
        contrastLevel = contrastLevel,
    ).let { if (snapAchromaticContainers) it.withCrispAchromaticContainer(seed) else it }

/**
 * Snaps the primary and secondary container pairs to pure black and white for the pure white and
 * pure black seeds; every other seed and every other role is left as the engine made it.
 *
 * HCT has no hue to keep for pure white or black, so the engine pairs each container with a
 * mid-gray "on" tone that only scrapes WCAG AA (~4.6:1) and looks muddy where the user sees the
 * accent, most visibly the tonal selection chips on primaryContainer/onPrimaryContainer. The
 * inverse pair (21:1) makes the picked color look like the one picked.
 *
 * `primary` stays the engine's readable gray: pure white would make the accent invisible on the
 * light surface (the primary/surface check in `AccentSchemeTest`). Widgets skip this snap; the
 * reason is on `snapAchromaticContainers` in [accentColorScheme].
 */
private fun ColorScheme.withCrispAchromaticContainer(seed: Int): ColorScheme = when (seed) {
    PURE_WHITE_SEED -> copy(
        primaryContainer = Color.White,
        onPrimaryContainer = Color.Black,
        secondaryContainer = Color.White,
        onSecondaryContainer = Color.Black,
    )
    PURE_BLACK_SEED -> copy(
        primaryContainer = Color.Black,
        onPrimaryContainer = Color.White,
        secondaryContainer = Color.Black,
        onSecondaryContainer = Color.White,
    )
    else -> this
}

/** Packed ARGB of the pure white and pure black seeds the picker offers. */
private const val PURE_WHITE_SEED: Int = 0xFFFFFFFF.toInt()
private const val PURE_BLACK_SEED: Int = 0xFF000000.toInt()

/**
 * HCT contrast axis for the app, -1.0..1.0 (0.0 is the Material default). The default already
 * keeps every selectable seed at WCAG AA (`AccentSchemeTest`); raise it only if a role the app
 * starts relying on needs more tonal separation.
 */
private const val ACCENT_CONTRAST_LEVEL: Double = 0.0

/**
 * HCT contrast axis for widgets only; the app uses [ACCENT_CONTRAST_LEVEL].
 *
 * Widgets read as one tinted panel: the body rides `surfaceVariant` and the header and footer rows
 * ride `secondaryContainer`, which at this low axis sit at nearly the same tone, so the header
 * stands out by its bold title more than by a tonal step. The header text (onSecondaryContainer)
 * is the AA-critical pair and gains as the axis rises; this small positive value gives it about
 * 5:1 for every selectable seed without a visible band between header and body.
 *
 * It must stay low. At 0.8 the dark-mode container inverts to a bright pastel with dark text
 * (dark header luminance ~0.68, against ~0.08 here); `WidgetThemeTest` caps the dark header's
 * luminance so it can't creep back up.
 */
const val WIDGET_ACCENT_CONTRAST_LEVEL: Double = 0.10
