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
 * One property stays static:
 * - adjacentMonthText: needs to be nearly invisible (no outlineVariant in Glance)
 */
object WidgetTheme {

    /**
     * Header background — the muted accent container (Material You secondary container). This
     * carries the user's chosen accent — whether it comes from the wallpaper (dynamic Material You)
     * or the in-app accent-color picker — at a low-emphasis, low-chroma tone rather than the loud
     * primary-container band. It reads as the same accent family as the app while separating from
     * the [contentBackground] body as a gentle, low-chroma band (and the day-separator rows
     * likewise) — the accent shows without becoming a saturated stripe.
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
     * Both accent sources are built by `accentColorProviders` (SEED from the picked accent, the
     * automatic source from the wallpaper-derived system accent), which overrides this role to
     * `surfaceVariant`. Glance's Material 3 interop would otherwise derive `widgetBackground` from
     * `secondaryContainer` — the accent header's own role — which is NOT a guaranteed-contrast pair
     * for onSurface item text and collapses at the widget's elevated header contrast (and for the
     * white/black accents). `surfaceVariant` keeps item text well clear of AA (9–16:1 for every
     * seed) while carrying a visibly-tinted body — `surface` would pair with text just as safely but
     * is so near-neutral the body reads flat. (On the rare device where no system accent is
     * available, the widget falls back to the platform's own widgetBackground.)
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
     * Subtle row tint for footer rows (Upcoming's show-more/less rows).
     * Pairs with [rowTintText] to satisfy WCAG AA in both light and dark dynamic-color themes.
     *
     * Uses `secondaryContainer` — the header/accent-band role — rather than `surfaceVariant`:
     * [contentBackground] (the body) is now `surfaceVariant`, so a surfaceVariant footer would
     * dissolve into the body. secondaryContainer sits one clear tonal step up from the body
     * (measured ~4.2–5.7:1 band-vs-body across every selectable seed) and its guaranteed on-pair
     * [rowTintText] keeps footer text at 7–9:1, so the footer reads as a distinct band that still
     * belongs to the same accent family.
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

    /** Adjacent month text color (very faded, for InDate/OutDate cells) — static, no M3 token */
    val adjacentMonthText = ColorProvider(
        day = Color(0xFFD0D0D0),   // Very light gray
        night = Color(0xFF505050)  // Very dark gray
    )
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
