package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider

/**
 * Theme colors for all KashCal widgets.
 *
 * Uses Material You dynamic colors via GlanceTheme on Android 12+ (minSdk 31).
 * Properties that delegate to GlanceTheme.colors are @Composable getters — all call sites
 * are already in @Composable functions, so this is transparent.
 *
 * Two properties stay static:
 * - todayHighlightBackground: uses 20% alpha tint (no opaque M3 equivalent)
 * - adjacentMonthText: needs to be nearly invisible (no outlineVariant in Glance)
 */
object WidgetTheme {

    /** Header background — Material You primary container */
    val headerBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.primaryContainer

    /** Content/widget background — Glance-specific widget background */
    val contentBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.widgetBackground

    /** Primary text color — Material You on-surface */
    val primaryText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSurface

    /** Secondary text color (times, labels) — Material You on-surface-variant */
    val secondaryText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSurfaceVariant

    /** Past event text color (dimmed) — Material You outline */
    val pastEventText: ColorProvider
        @Composable get() = GlanceTheme.colors.outline

    /** Accent color for interactive elements — Material You primary */
    val accentColor: ColorProvider
        @Composable get() = GlanceTheme.colors.primary

    /** Today highlight background (accent with alpha) — static, no opaque M3 equivalent */
    val todayHighlightBackground = ColorProvider(
        day = Color(0x332196F3),   // Blue 500, 20% alpha
        night = Color(0x3364B5F6)  // Blue 300, 20% alpha
    )

    /** Adjacent month text color (very faded, for InDate/OutDate cells) — static, no M3 token */
    val adjacentMonthText = ColorProvider(
        day = Color(0xFFD0D0D0),   // Very light gray
        night = Color(0xFF505050)  // Very dark gray
    )

    /** Divider color — Material You outline */
    val dividerColor: ColorProvider
        @Composable get() = GlanceTheme.colors.outline
}
