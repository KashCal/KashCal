package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/**
 * A fixed light + dark Material 3 scheme pair for a branded (non-dynamic) theme. Modes that
 * carry a palette bypass Material You so the brand colors show on every device; modes with a
 * null palette use dynamic/baseline colors instead.
 */
class ThemePalette(val light: ColorScheme, val dark: ColorScheme)

/**
 * KashCal Teal brand palette — full Material 3 tonal schemes anchored on the KashCal brand teal
 * (primary #0E6E62 light / #45C2AD dark, matching the website's brand tokens). Teal is reserved
 * for "now" and "action" chrome (today marker, now line, FAB, selected day, active tab); surfaces
 * carry a faint teal cast. Every text-on-fill pair meets WCAG AA (>= 4.5:1); ThemeModeTest asserts
 * this, including the surface-container roles that back ModalBottomSheet/Card/NavigationBar. User
 * event colors are never sourced from here.
 */
val TealPalette = ThemePalette(
    light = lightColorScheme(
        primary = Color(0xFF0E6E62),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFA8F0E3),
        onPrimaryContainer = Color(0xFF00201C),
        secondary = Color(0xFF4A6360),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E1),
        onSecondaryContainer = Color(0xFF051F1B),
        tertiary = Color(0xFF4B607C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD3E4FF),
        onTertiaryContainer = Color(0xFF041C35),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFBFCFB),
        onBackground = Color(0xFF14201E),
        surface = Color(0xFFFBFCFB),
        onSurface = Color(0xFF14201E),
        surfaceVariant = Color(0xFFDAE5E1),
        onSurfaceVariant = Color(0xFF3D4946),
        surfaceDim = Color(0xFFD9DFDD),
        surfaceBright = Color(0xFFFBFCFB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F5F3),
        surfaceContainer = Color(0xFFEBF1EF),
        surfaceContainerHigh = Color(0xFFE5EBE9),
        surfaceContainerHighest = Color(0xFFDFE5E3),
        outline = Color(0xFF6D7976),
        outlineVariant = Color(0xFFBEC9C5),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2A322F),
        inverseOnSurface = Color(0xFFEBF2EE),
        inversePrimary = Color(0xFF45C2AD),
        surfaceTint = Color(0xFF0E6E62),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF45C2AD),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF005141),
        onPrimaryContainer = Color(0xFFA8F0E3),
        secondary = Color(0xFFB0CCC6),
        onSecondary = Color(0xFF1B3531),
        secondaryContainer = Color(0xFF334B46),
        onSecondaryContainer = Color(0xFFCCE8E1),
        tertiary = Color(0xFFB3C8E8),
        onTertiary = Color(0xFF1C314B),
        tertiaryContainer = Color(0xFF334863),
        onTertiaryContainer = Color(0xFFD3E4FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0D1413),
        onBackground = Color(0xFFE6ECE9),
        surface = Color(0xFF0D1413),
        onSurface = Color(0xFFE6ECE9),
        surfaceVariant = Color(0xFF3D4946),
        onSurfaceVariant = Color(0xFFBEC9C4),
        surfaceDim = Color(0xFF0D1413),
        surfaceBright = Color(0xFF333B39),
        surfaceContainerLowest = Color(0xFF080F0E),
        surfaceContainerLow = Color(0xFF151D1B),
        surfaceContainer = Color(0xFF19211F),
        surfaceContainerHigh = Color(0xFF232B29),
        surfaceContainerHighest = Color(0xFF2E3634),
        outline = Color(0xFF87938F),
        outlineVariant = Color(0xFF3D4946),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE6ECE9),
        inverseOnSurface = Color(0xFF2A322F),
        inversePrimary = Color(0xFF0E6E62),
        surfaceTint = Color(0xFF45C2AD),
    ),
)
