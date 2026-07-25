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
 * @param contrastLevel HCT contrast axis (-1.0..1.0). Defaults to [ACCENT_CONTRAST_LEVEL] (the app
 *   face); widgets pass a higher value so their muted secondary-container header clears the bare AA
 *   floor and separates visibly from the widget body — see [WIDGET_ACCENT_CONTRAST_LEVEL].
 * @param snapAchromaticContainers whether to force the achromatic-seed container snap (see
 *   [withCrispAchromaticContainer]). Defaults on for the app's tonal chips. Widgets pass `false`:
 *   the widget header rides `secondaryContainer` as a band over a neutral `surface` body, so snapping
 *   that role to pure white/black would collapse the band against the surface (a white band on the
 *   near-white light surface, invisible). At the widgets' elevated contrast level the raw engine
 *   already gives the achromatic header text 7:1+ and a clearly separated band, so the snap is both
 *   unnecessary there and actively harmful.
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
        // Content keeps the primary faithful to the picked seed (gray stays gray, black stays
        // black). TonalSpot preserves only the seed's HUE and imposes its own chroma/tone, so
        // low-chroma seeds (gray/silver) collapse onto an unrelated color (teal) and black/white
        // can't map to a dark/light primary — the "I picked X but got Y" bug. Contrast stays
        // AA-safe for every selectable seed (AccentSchemeTest).
        style = PaletteStyle.Content,
        contrastLevel = contrastLevel,
    ).let { if (snapAchromaticContainers) it.withCrispAchromaticContainer(seed) else it }

/**
 * Snaps the two accent-container pairs (primary AND secondary) to a pure black/white inverse for
 * the two achromatic-extreme seeds, leaving every other seed (and every other role) untouched.
 *
 * HCT has no hue to preserve for pure white or pure black, so the tonal engine pairs each
 * container with a mid-gray "on" tone that only scrapes WCAG AA (~4.6:1) and reads as muddy on
 * the surfaces the user perceives as "the accent" — most visibly the app's tonal selection chips,
 * backed by primaryContainer/onPrimaryContainer. Forcing the crisp inverse (black-on-white /
 * white-on-black, a full 21:1) makes the picked color look like the color that was picked.
 *
 * Only the two container pairs are changed. In particular `primary` is left as the engine's
 * readable gray: forcing it to pure white would make the accent invisible against the light
 * surface (the primary/surface visibility guarantee, AccentSchemeTest).
 *
 * Widgets opt OUT of this snap (see [accentColorScheme]'s `snapAchromaticContainers`): they paint
 * this container as a header band over a neutral surface body, so snapping it to pure white/black
 * would collapse the band against the surface instead of sharpening a chip.
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

/** Packed ARGB for the two achromatic-extreme seeds the picker offers. */
private const val PURE_WHITE_SEED: Int = 0xFFFFFFFF.toInt()
private const val PURE_BLACK_SEED: Int = 0xFF000000.toInt()

/**
 * HCT contrast axis, range -1.0..1.0 (0.0 = Material default). The default already keeps
 * every selectable seed at WCAG AA (see the accent-scheme test), so no bump is applied;
 * raise this only if a newly-relied-upon role needs more tonal separation.
 */
private const val ACCENT_CONTRAST_LEVEL: Double = 0.0

/**
 * HCT contrast axis for the WIDGET faces. Widgets paint their header on the muted
 * secondary-container role, which at the app's default level lands right on the bare AA floor
 * (~4.5:1 header text) and — worse — barely separates the header band from the widget body
 * (as low as ~1.2:1 for low-chroma seeds like olive). Bumping the axis lifts every rendered
 * widget pair at once (header text, accent band vs body, today marker, row tint) for every
 * selectable seed, so the muted header still reads as the picked accent but is clearly legible
 * and clearly a distinct band. At this level every selectable seed clears roughly the AAA bar
 * (~7:1): measured floors are header text >= ~7.3:1 (light) / ~9.4:1 (dark) and band vs body
 * >= ~6.9:1 (light) / ~8.4:1 (dark). Widget-scoped on purpose — the app face is unaffected.
 */
const val WIDGET_ACCENT_CONTRAST_LEVEL: Double = 0.8
