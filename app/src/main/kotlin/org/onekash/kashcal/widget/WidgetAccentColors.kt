package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.material3.ColorProviders
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.theme.WIDGET_ACCENT_CONTRAST_LEVEL
import org.onekash.kashcal.ui.theme.accentColorScheme

/**
 * Builds Glance [ColorProviders] from an accent seed, so widgets recolor with the app's accent.
 *
 * Light and dark schemes come from the shared [accentColorScheme] (pure HCT, no Context or
 * composition) and go through Glance's Material 3 interop, which picks one against the system
 * day/night setting at render time. [forceDark] pins one face by publishing its scheme as both
 * the day and the night palette, so Glance's pick can't reach the other face.
 *
 * ## Header
 *
 * The header rides the muted `secondaryContainer` role at [WIDGET_ACCENT_CONTRAST_LEVEL]. Its
 * text (`onSecondaryContainer`) is a guaranteed-contrast M3 pair, so it clears AA for any seed by
 * construction, the wallpaper-derived automatic accent included. The low axis lifts it to ~5:1
 * across the tested palette while keeping the header at nearly the body's tone, so header and
 * body read as one tinted panel, not a band over a plainer body.
 *
 * Widgets opt out of the achromatic-container snap (`snapAchromaticContainers = false`): snapping
 * `secondaryContainer` to pure white or black for those seeds would blow the header out to the
 * extreme. At this contrast level the raw engine already gives the achromatic header 5:1+ text.
 * Pure black and pure white seeds get [monochromeSnap] instead.
 *
 * ## Body
 *
 * The body (`widgetBackground`) is pinned to `surfaceVariant`, the most-tinted body role that
 * keeps item text at full contrast for every seed. `secondaryContainer` carries more chroma, but
 * its pairing with `onSurface` and `onSurfaceVariant` isn't guaranteed and drops below WCAG AA for
 * saturated seeds (item text measured as low as ~2.5:1). `surfaceVariant` still shows the accent
 * (chroma up to ~0.15 for chromatic seeds) with item text at ~7:1+ (onSurface) and secondary text
 * at ~5.5:1+ (onSurfaceVariant).
 *
 * Only a seed accent comes through here (SEED, or FOLLOW_APP with the app on a seed); the DYNAMIC
 * source renders on the device's dynamic palette ([resolveWidgetAccentColors]).
 */
fun accentColorProviders(seed: Int, forceDark: Boolean? = null): ColorProviders {
    val light = accentColorScheme(
        seed, dark = false,
        contrastLevel = WIDGET_ACCENT_CONTRAST_LEVEL,
        snapAchromaticContainers = false,
    )
    val dark = accentColorScheme(
        seed, dark = true,
        contrastLevel = WIDGET_ACCENT_CONTRAST_LEVEL,
        snapAchromaticContainers = false,
    )
    // A pinned face publishes its scheme as both day and night; otherwise light rides day and
    // dark rides night, and Glance picks against the system setting at render time.
    val (day, night) = when (forceDark) {
        null -> light to dark
        true -> dark to dark
        false -> light to light
    }
    // `base` carries every role from the two schemes through Glance's Material 3 interop. It is
    // republished through the core DSL with only widgetBackground changed to surfaceVariant;
    // reading the other roles off `base` keeps them in step with the interop.
    val base = ColorProviders(light = day, dark = night)
    // A pure-black or pure-white seed has no hue for the HCT engine to preserve, so the raw
    // scheme collapses onto a muddy neutral gray ("I picked black, the widget is gray"). Those two
    // seeds snap to a monochrome panel instead; every other seed falls through untouched.
    monochromeSnap(seed, base)?.let { return it }
    return colorProviders(
        primary = base.primary,
        onPrimary = base.onPrimary,
        primaryContainer = base.primaryContainer,
        onPrimaryContainer = base.onPrimaryContainer,
        secondary = base.secondary,
        onSecondary = base.onSecondary,
        secondaryContainer = base.secondaryContainer,
        onSecondaryContainer = base.onSecondaryContainer,
        tertiary = base.tertiary,
        onTertiary = base.onTertiary,
        tertiaryContainer = base.tertiaryContainer,
        onTertiaryContainer = base.onTertiaryContainer,
        error = base.error,
        errorContainer = base.errorContainer,
        onError = base.onError,
        onErrorContainer = base.onErrorContainer,
        background = base.background,
        onBackground = base.onBackground,
        surface = base.surface,
        onSurface = base.onSurface,
        surfaceVariant = base.surfaceVariant,
        onSurfaceVariant = base.onSurfaceVariant,
        outline = base.outline,
        inverseOnSurface = base.inverseOnSurface,
        inverseSurface = base.inverseSurface,
        inversePrimary = base.inversePrimary,
        widgetBackground = base.surfaceVariant,
    )
}

/** Packed ARGB for the two achromatic-extreme seeds the picker offers. */
private const val PURE_WHITE_SEED: Int = 0xFFFFFFFF.toInt()
private const val PURE_BLACK_SEED: Int = 0xFF000000.toInt()

/** Returns the neutral gray [t] (0..1) of the way from [ink] toward [panel]. */
private fun grayBetween(ink: Color, panel: Color, t: Float): Color =
    Color(
        red = ink.red + (panel.red - ink.red) * t,
        green = ink.green + (panel.green - ink.green) * t,
        blue = ink.blue + (panel.blue - ink.blue) * t,
    )

/**
 * Republishes [base] as a monochrome panel for a pure-black or pure-white seed; returns null for
 * every other seed, which keeps the tinted path.
 *
 * `panel` is the pure extreme every painted background role takes (the `secondaryContainer`
 * header and footer, the `surfaceVariant` and `widgetBackground` body); `ink` is the inverse the
 * text roles take. The two dimmed tiers step from `ink` toward `panel` so the widget's text
 * hierarchy survives a flat 21:1 panel: secondary text (`onSurfaceVariant`: event times,
 * subtitles) at 30%, which stays above WCAG AA against the panel, and the de-emphasis tier
 * (`outline`: past events, the transient sync glyph) at 50%.
 *
 * `primary` is the ink, not the panel: it is both the today-marker fill and accent text on the
 * body ("+N more", the empty state's "add event"), so it must be readable there, a white
 * today-circle with a black number on the black panel. `onPrimary` is therefore the panel.
 *
 * Every role but the error roles is flat (one color for both faces), so a black seed is a black
 * widget in light mode too, whether or not a face is pinned.
 */
private fun monochromeSnap(seed: Int, base: ColorProviders): ColorProviders? {
    val (panel, ink) = when (seed) {
        PURE_BLACK_SEED -> Color.Black to Color.White
        PURE_WHITE_SEED -> Color.White to Color.Black
        else -> return null
    }
    val secondary = grayBetween(ink, panel, 0.30f) // event times, subtitles: dimmed but >= AA
    val dim = grayBetween(ink, panel, 0.50f)        // past events, transient sync glyph
    return colorProviders(
        primary = flat(ink),
        onPrimary = flat(panel),
        primaryContainer = flat(panel),
        onPrimaryContainer = flat(ink),
        secondary = flat(ink),
        onSecondary = flat(panel),
        secondaryContainer = flat(panel),
        onSecondaryContainer = flat(ink),
        tertiary = flat(ink),
        onTertiary = flat(panel),
        tertiaryContainer = flat(panel),
        onTertiaryContainer = flat(ink),
        // Error keeps the accent scheme's red; a monochrome panel shouldn't mute an error tone.
        // No widget paints it today.
        error = base.error,
        errorContainer = base.errorContainer,
        onError = base.onError,
        onErrorContainer = base.onErrorContainer,
        background = flat(panel),
        onBackground = flat(ink),
        surface = flat(panel),
        onSurface = flat(ink),
        surfaceVariant = flat(panel),
        onSurfaceVariant = flat(secondary),
        outline = flat(dim),
        inverseOnSurface = flat(panel),
        inverseSurface = flat(ink),
        inversePrimary = flat(panel),
        widgetBackground = flat(panel),
    )
}

/** Returns a Glance day/night provider that resolves to [color] for both faces. */
private fun flat(color: Color) = androidx.glance.color.ColorProvider(day = color, night = color)

/**
 * The colors a widget renders with.
 *
 * @param colors the resolved [ColorProviders], or null for the platform's Material You palette
 *   (the call site's `accentColors ?: GlanceTheme.colors` fallback). Null only for the DYNAMIC
 *   source following the system day/night setting; a pinned face always has providers, since the
 *   platform palette must then be republished with both faces set to the forced one.
 * @param forcedDark the widget's light/dark pin: null follows the system, true is dark, false is
 *   light. Static day/night colors drawn outside the Glance scheme (the month grid's
 *   adjacent-month text) use it to match the pinned face.
 */
data class WidgetColorConfig(
    val colors: ColorProviders?,
    val forcedDark: Boolean?,
)

/**
 * Resolves a widget's colors from the widget appearance settings ([WidgetColorSource] and
 * [WidgetThemeSource]), which are separate from the app's but can follow it.
 *
 * Color source:
 * - FOLLOW_APP (default): the app's own color-source resolution, including the retired-teal
 *   migration, so app and widgets match.
 * - SEED (the widget accent picker): tinted providers from the widget-only seed through
 *   [accentColorProviders], the same generator the app uses.
 * - DYNAMIC: the device's Material You palette.
 *
 * Light or dark face, for every source:
 * - Follow app: the app's face. When the app follows the device, `forcedDark` is null and the
 *   widget follows the device too: SEED providers carry both faces for Glance to pick at render
 *   time, and DYNAMIC shows the platform palette. Don't reseed a scheme from a single
 *   system-accent tone here; it replaces the dynamic palette (near-neutral system surfaces,
 *   accent where the system places it) with an app-derived tint, and the widget stops matching
 *   the launcher and system UI.
 * - Light or Dark: the forced scheme is published as both the day and night palette; for
 *   DYNAMIC that is the platform's own dynamic scheme for the forced face ([dynamicScheme]).
 *
 * @param dynamicScheme builds the platform dynamic scheme for a face; a parameter so unit tests
 *   don't need a Context-backed palette. Defaults to the real dynamic schemes, always available at
 *   minSdk 31.
 */
suspend fun resolveWidgetAccentColors(
    context: Context,
    dataStore: KashCalDataStore,
    dynamicScheme: (dark: Boolean) -> ColorScheme = { dark ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    },
): WidgetColorConfig {
    // The app's theme feeds both the follow-app face below and the follow-app color source's
    // retired-teal migration, so read it once here.
    val appTheme = dataStore.theme.first()
    val forcedDark: Boolean? = when (WidgetThemeSource.fromPrefValue(dataStore.widgetThemeSource.first())) {
        WidgetThemeSource.LIGHT -> false
        WidgetThemeSource.DARK -> true
        // When the app follows the device its face is null, so the widget follows the device
        // too without a separate "system" option.
        WidgetThemeSource.FOLLOW_APP -> ThemeMode.fromPrefValue(appTheme).forcedDark
    }
    return when (WidgetColorSource.fromPrefValue(dataStore.widgetColorSource.first())) {
        WidgetColorSource.SEED ->
            WidgetColorConfig(accentColorProviders(dataStore.widgetAccentSeed.first(), forcedDark), forcedDark)
        WidgetColorSource.DYNAMIC -> dynamicConfig(forcedDark, dynamicScheme)
        WidgetColorSource.FOLLOW_APP -> {
            // Mirror the app's color-source resolution (including the retired-teal migration).
            val appSource = ColorSource.fromPrefValue(
                explicit = dataStore.colorSource.first(),
                legacyTheme = appTheme,
            )
            when (appSource) {
                ColorSource.SEED ->
                    WidgetColorConfig(accentColorProviders(dataStore.accentSeed.first(), forcedDark), forcedDark)
                ColorSource.DYNAMIC -> dynamicConfig(forcedDark, dynamicScheme)
            }
        }
    }
}

/**
 * Returns the DYNAMIC-source config: null colors when following the system face (the platform
 * palette at the call site), or the platform's dynamic scheme pinned to the forced face.
 */
private fun dynamicConfig(
    forcedDark: Boolean?,
    dynamicScheme: (dark: Boolean) -> ColorScheme,
): WidgetColorConfig = when (forcedDark) {
    null -> WidgetColorConfig(colors = null, forcedDark = null)
    else -> {
        val pinned = dynamicScheme(forcedDark)
        WidgetColorConfig(ColorProviders(light = pinned, dark = pinned), forcedDark)
    }
}
