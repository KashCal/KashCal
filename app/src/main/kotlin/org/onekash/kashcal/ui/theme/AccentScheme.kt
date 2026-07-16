package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * Builds a full Material 3 [ColorScheme] from a single accent seed color.
 *
 * Every tonal role (primary, containers, the surface family, error, outline, …) is
 * derived from the seed's hue mapped onto fixed Material tones, so text and UI contrast
 * hold for ANY seed — including pale ones like gold — rather than depending on the seed's
 * own lightness. This is what lets the user pick any accent without breaking readability;
 * [org.onekash.kashcal.ui.theme.AccentSchemeTest] proves the WCAG AA guarantee.
 *
 * The HCT engine applies its own dynamic tonal-contrast adjustment, so every role pair
 * (including the surface-container tones that back cards and bottom sheets) already clears
 * AA across the whole selectable palette at the default contrast level. [ACCENT_CONTRAST_LEVEL]
 * is the knob to raise if a future role ever needs more separation.
 *
 * This is a pure function over the seed [Int] — no Android [android.content.Context] and no
 * Compose composition are required — so it runs identically in the app and inside a Glance
 * widget's separate process.
 *
 * @param seed packed ARGB accent color (e.g. `0xFF0E6E62.toInt()`).
 * @param dark whether to build the dark-face scheme.
 */
fun accentColorScheme(seed: Int, dark: Boolean): ColorScheme =
    dynamicColorScheme(
        seedColor = Color(seed),
        isDark = dark,
        // Content keeps the primary faithful to the picked seed (gray stays gray, black stays
        // black). TonalSpot preserves only the seed's HUE and imposes its own chroma/tone, so
        // low-chroma seeds (gray/silver) collapse onto an unrelated color (teal) and black/white
        // can't map to a dark/light primary — the "I picked X but got Y" bug. Contrast stays
        // AA-safe for every selectable seed (AccentSchemeTest).
        style = PaletteStyle.Content,
        contrastLevel = ACCENT_CONTRAST_LEVEL,
    ).withCrispAchromaticContainer(seed)

/**
 * Snaps the accent-container pair to a pure black/white inverse for the two achromatic-extreme
 * seeds, leaving every other seed (and every other role) untouched.
 *
 * HCT has no hue to preserve for pure white or pure black, so the tonal engine pairs the
 * container with a mid-gray "on" tone that only scrapes WCAG AA (~4.6:1) and reads as muddy on
 * the surface the user perceives as "the accent" — most visibly the widget header, which is
 * backed by primaryContainer/onPrimaryContainer. Forcing the crisp inverse (black-on-white /
 * white-on-black, a full 21:1) makes the picked color look like the color that was picked.
 *
 * Only the container pair is changed. In particular `primary` is left as the engine's readable
 * gray: forcing it to pure white would make the accent invisible against the light surface
 * (the primary/surface visibility guarantee, AccentSchemeTest).
 */
private fun ColorScheme.withCrispAchromaticContainer(seed: Int): ColorScheme = when (seed) {
    PURE_WHITE_SEED -> copy(primaryContainer = Color.White, onPrimaryContainer = Color.Black)
    PURE_BLACK_SEED -> copy(primaryContainer = Color.Black, onPrimaryContainer = Color.White)
    else -> this
}

/** Packed ARGB for the two achromatic-extreme seeds the picker offers. */
private const val PURE_WHITE_SEED: Int = 0xFFFFFFFF.toInt()
private const val PURE_BLACK_SEED: Int = 0xFF000000.toInt()

/**
 * HCT contrast axis, range -1.0..1.0 (0.0 = Material default). The default already keeps
 * every selectable seed at WCAG AA (see the accent-scheme test), so no bump is applied;
 * raise this only if a newly-relied-upon role needs more tonal separation.
 */
private const val ACCENT_CONTRAST_LEVEL: Double = 0.0
