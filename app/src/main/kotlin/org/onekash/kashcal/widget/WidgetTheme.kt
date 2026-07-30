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
 * One stays static (no M3 token, but pin-aware):
 * - adjacentMonthText: needs to be nearly invisible (no outlineVariant in Glance)
 */
object WidgetTheme {

    /**
     * Header background — the muted accent container (Material You secondary container). This
     * carries the user's chosen accent — whether it comes from the wallpaper (dynamic Material You)
     * or the in-app accent-color picker — at a low-emphasis, low-chroma tone rather than the loud
     * primary-container band. At the widget's low contrast level this role sits at nearly the same
     * tone as the body ([contentBackground], `surfaceVariant`), so the widget reads as one
     * near-uniform tinted panel; the header is set apart by its bold title far more than by any
     * tonal step.
     */
    val headerBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.secondaryContainer

    /**
     * Text/icon color for content ON [headerBackground] — the M3 on-role for a secondaryContainer
     * surface. onSecondaryContainer/secondaryContainer is a guaranteed-contrast M3 pair; using
     * onSurface or primary here is not, and fails for some accent seeds.
     */
    val onHeaderBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.onSecondaryContainer

    /**
     * Lower-emphasis tint for a header glyph while a transient action is in flight (the refresh
     * "syncing" cue). Glance has no alpha modifier, so the cue is a token swap rather than a fade:
     * `outline` reads as a dimmed/greyed glyph against the header. This is a brief de-emphasis, not
     * persistent content, so it is intentionally NOT held to the AA contrast bar that
     * [onHeaderBackground] must clear.
     */
    val dimmedOnHeaderBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.outline

    /**
     * Content/widget background — Glance's widget background role.
     *
     * For the in-app SEED accent, `accentColorProviders` overrides this role to `surfaceVariant` —
     * the most-tinted body role that keeps item text at full contrast for every seed. It carries a
     * visible accent tint yet sits at nearly the header's tone at the widget's low contrast level, so
     * the widget reads as one near-uniform tinted panel. `secondaryContainer` carries more chroma but
     * its non-guaranteed pairing with the item/secondary text roles drops below AA for saturated
     * seeds, so it is not a safe body. Item text on `surfaceVariant` (onSurface) clears AA with margin
     * for every seed.
     *
     * The automatic (Material You) source does not build these providers; it renders on the device's
     * genuine dynamic palette, so this role is then the platform's own widgetBackground.
     */
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

    /**
     * Background for footer rows (Upcoming's show-more/less rows). Rides `secondaryContainer`, the
     * same role as the header, so the footer echoes the header tone at the top and bottom of the
     * near-uniform tinted panel; it reads as a distinct row through its own text label ("Open
     * calendar") and tap target rather than a separate background band. Pairs with [rowTintText]
     * (onSecondaryContainer) to satisfy WCAG AA in both light and dark dynamic-color themes.
     */
    val rowTintBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.secondaryContainer

    /** Text color paired with [rowTintBackground] — Material You onSecondaryContainer. */
    val rowTintText: ColorProvider
        @Composable get() = GlanceTheme.colors.onSecondaryContainer

    /** Fill behind today's day number in the month grid — Material You primary. */
    val todayMarkerBackground: ColorProvider
        @Composable get() = GlanceTheme.colors.primary

    /** Day-number color on top of [todayMarkerBackground] — Material You onPrimary. */
    val onTodayMarker: ColorProvider
        @Composable get() = GlanceTheme.colors.onPrimary

    /**
     * Adjacent month text color (very faded, for InDate/OutDate cells) — static, no M3 token.
     *
     * [forcedDark] is the widget's light/dark pin (see [WidgetColorConfig]): when the face is
     * pinned, day and night collapse onto the forced face's gray so the static pair can't flip
     * against the pinned scheme; null follows the system day/night setting as before.
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
 * Token-name enum for widget colors.
 *
 * Returned by pure selectors so the contrast contract (which token a row uses)
 * can be unit-tested without a Compose render harness. The composable
 * [provider] extension below is the only place enum -> ColorProvider mapping
 * lives, and is mechanically inspectable.
 */
internal enum class WidgetThemeColor {
    HeaderBackground,
    OnHeaderBackground
}

/** Background + text token pair for a day-header row. */
internal data class DayHeaderColors(
    val background: WidgetThemeColor,
    val text: WidgetThemeColor
)

/**
 * Pure selector for day-header row colors.
 *
 * Every day header uses the shared header background so the list of days reads
 * as one uniform banner scale; today is distinguished by bold text and a "today"
 * label rather than a different background color.
 */
internal fun dayHeaderColors(isToday: Boolean): DayHeaderColors =
    DayHeaderColors(WidgetThemeColor.HeaderBackground, WidgetThemeColor.OnHeaderBackground)

/** Composable mapping from a [WidgetThemeColor] token name to its concrete provider. */
@Composable
internal fun WidgetThemeColor.provider(): ColorProvider = when (this) {
    WidgetThemeColor.HeaderBackground -> WidgetTheme.headerBackground
    WidgetThemeColor.OnHeaderBackground -> WidgetTheme.onHeaderBackground
}
