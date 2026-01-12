package org.onekash.kashcal.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

/**
 * Theme colors for the agenda widget.
 *
 * Uses Glance ColorProvider for automatic light/dark mode support.
 * Colors follow Material Design 3 guidelines.
 */
object WidgetTheme {

    /** Header background - light blue in light mode, dark blue in dark mode */
    val headerBackground = ColorProvider(
        day = Color(0xFFE3F2FD),   // Blue 50
        night = Color(0xFF1A237E)  // Indigo 900
    )

    /** Content background */
    val contentBackground = ColorProvider(
        day = Color.White,
        night = Color(0xFF121212)  // Material dark surface
    )

    /** Primary text color */
    val primaryText = ColorProvider(
        day = Color(0xFF212121),   // Gray 900
        night = Color(0xFFE0E0E0)  // Gray 300
    )

    /** Secondary text color (times, labels) */
    val secondaryText = ColorProvider(
        day = Color(0xFF757575),   // Gray 600
        night = Color(0xFF9E9E9E)  // Gray 500
    )

    /** Past event text color (dimmed) */
    val pastEventText = ColorProvider(
        day = Color(0xFFBDBDBD),   // Gray 400
        night = Color(0xFF616161)  // Gray 700
    )

    /** Accent color for interactive elements */
    val accentColor = ColorProvider(
        day = Color(0xFF2196F3),   // Blue 500
        night = Color(0xFF64B5F6)  // Blue 300
    )

    /** Divider color */
    val dividerColor = ColorProvider(
        day = Color(0xFFE0E0E0),   // Gray 300
        night = Color(0xFF424242)  // Gray 800
    )
}
