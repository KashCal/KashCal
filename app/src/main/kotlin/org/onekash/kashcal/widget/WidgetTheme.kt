package org.onekash.kashcal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider

/**
 * Names the color roles every KashCal widget paints with.
 *
 * Each property is a @Composable getter over `GlanceTheme.colors`, which the widget sets to its
 * accent seed's providers ([accentColorProviders]) or the platform's Material You palette.
 * [adjacentMonthText] alone is static, since Glance has no outlineVariant for a nearly invisible
 * tone; it still follows the light/dark pin.
 */
object WidgetTheme {

    /**
     * Header background: `secondaryContainer`, the user's accent (wallpaper or picked seed) at a
     * muted tone, not the loud primary container. At the widget's low contrast level it sits at
     * nearly the body's tone ([contentBackground]), so the widget reads as one tinted panel and
     * the bold title sets the header apart.
     */
    val headerBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.secondaryContainer

    /**
     * Text and icon color on [headerBackground]: `onSecondaryContainer`, a guaranteed-contrast M3
     * pair with it. onSurface or primary here isn't, and fails for some accent seeds.
     */
    val onHeaderBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.onSecondaryContainer

    /**
     * Tints a header glyph while the refresh cue is on. Glance has no alpha modifier, so the cue
     * swaps to `outline`, which reads as a greyed glyph on the header. It is a brief
     * de-emphasis, so it deliberately isn't held to the AA contrast bar [onHeaderBackground]
     * clears.
     */
    val dimmedOnHeaderBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.outline

    /**
     * Body background: Glance's `widgetBackground` role. For a seed accent,
     * [accentColorProviders] sets it to `surfaceVariant`; its KDoc gives the contrast reasons.
     * The automatic Material You source uses the platform's own widgetBackground.
     */
    val contentBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.widgetBackground

    /** Primary text: `onSurface`. */
    val primaryText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSurface

    /** Secondary text, such as empty-day rows, overflow counts and week numbers. */
    val secondaryText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSurfaceVariant

    /** Dimmed text of past events: `outline`. */
    val pastEventText: ColorProvider
        @Composable get() = GlanceTheme.colors.outline

    /** Accent for interactive elements: `primary`. */
    val accentColor: ColorProvider
        @Composable get() = GlanceTheme.colors.primary

    /**
     * Background of Upcoming's more-days footer: `secondaryContainer`, the header's role, so the
     * footer echoes the header tone at the bottom of the panel. Its "Open calendar" label and tap
     * target set it apart, not a separate band. Pairs with [rowTintText] for WCAG AA in light and
     * dark dynamic-color themes.
     */
    val rowTintBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.secondaryContainer

    /** Text on [rowTintBackground]: `onSecondaryContainer`. */
    val rowTintText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSecondaryContainer

    /** Fill behind today's day number in the month grid: `primary`. */
    val todayMarkerBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.primary

    /** Day number on [todayMarkerBackground]: `onPrimary`. */
    val onTodayMarker: ColorProvider
        @Composable get() = GlanceTheme.colors.onPrimary

    /**
     * Returns the faded static gray for adjacent-month (InDate/OutDate) cells.
     *
     * [forcedDark] is the widget's light/dark pin ([WidgetColorConfig]). A pinned face uses its
     * gray for both day and night, so the pair can't flip against the pinned scheme; null follows
     * the system day/night setting.
     */
    fun adjacentMonthText(forcedDark: Boolean? = null) = when (forcedDark) {
        null -> ColorProvider(
            day = Color(0xFFD0D0D0),   // Very light gray
            night = Color(0xFF505050)  // Very dark gray
        )
        true -> ColorProvider(day = Color(0xFF505050), night = Color(0xFF505050))
        false -> ColorProvider(day = Color(0xFFD0D0D0), night = Color(0xFFD0D0D0))
    }
}

/**
 * Names widget color tokens, returned by pure selectors so which token a row uses is
 * unit-testable without a Compose render harness. [provider] is the only token to
 * ColorProvider mapping.
 */
internal enum class WidgetThemeColor {
    HeaderBackground,
    OnHeaderBackground
}

/** Pairs the background and text tokens of a day-header row. */
internal data class DayHeaderColors(
    val background: WidgetThemeColor,
    val text: WidgetThemeColor
)

/**
 * Selects the day-header row colors: the header tokens for every day, so the days read as one
 * banner scale. Callers mark today with bold text and a "today" label instead.
 */
internal fun dayHeaderColors(isToday: Boolean): DayHeaderColors =
    DayHeaderColors(WidgetThemeColor.HeaderBackground, WidgetThemeColor.OnHeaderBackground)

/** Maps a [WidgetThemeColor] token to its [WidgetTheme] provider. */
@Composable
internal fun WidgetThemeColor.provider(): ColorProvider = when (this) {
    WidgetThemeColor.HeaderBackground -> WidgetTheme.headerBackground
    WidgetThemeColor.OnHeaderBackground -> WidgetTheme.onHeaderBackground
}
