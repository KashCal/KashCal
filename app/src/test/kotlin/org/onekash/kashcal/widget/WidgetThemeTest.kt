package org.onekash.kashcal.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.color.DayNightColorProvider
import androidx.glance.unit.ColorProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.shared.contrastRatio
import org.onekash.kashcal.ui.theme.WIDGET_ACCENT_CONTRAST_LEVEL
import org.onekash.kashcal.ui.theme.accentColorScheme

/**
 * Unit tests for the pure (non-@Composable) parts of [WidgetTheme].
 *
 * The selector returns enum-typed token names so the contrast contract can be
 * asserted at the unit-test layer; the composable hop from token -> ColorProvider
 * lives at WidgetTheme.kt as a small `when` block.
 *
 * Every scheme here is built at [WIDGET_ACCENT_CONTRAST_LEVEL] — the level widgets actually
 * render at (via accentColorProviders), NOT the app default. Building at 0.0 would verify colors
 * the widget never shows and miss a regression that only bites at the widget level.
 */
class WidgetThemeTest {

    /**
     * The scheme a widget actually renders — built at the widget contrast level with the achromatic
     * container snap OFF, mirroring accentColorProviders. Building it any other way would verify
     * colors the widget never shows.
     */
    private fun widgetScheme(seed: Int, dark: Boolean): ColorScheme =
        accentColorScheme(
            seed, dark,
            contrastLevel = WIDGET_ACCENT_CONTRAST_LEVEL,
            snapAchromaticContainers = false,
        )

    @Test
    fun `every day header uses the header background with its matching on-color`() {
        // All day headers share the header background so the list reads as one
        // uniform banner; today is distinguished by bold text and a "today"
        // label, not a different background color.
        for (isToday in listOf(true, false)) {
            val colors = dayHeaderColors(isToday)
            assertEquals(WidgetThemeColor.HeaderBackground, colors.background)
            // Must be the on-header token (onSecondaryContainer), NOT onSurface:
            // onSurface is not a guaranteed-contrast pair against a secondaryContainer header for
            // an arbitrary accent seed. This regressed once and made today headers unreadable.
            assertEquals(WidgetThemeColor.OnHeaderBackground, colors.text)
        }
    }

    /**
     * The [WidgetThemeColor] token -> M3 role mapping the composable [provider] resolves to.
     * Kept in sync with WidgetTheme by intent; this is what lets the pairing be contrast-checked
     * without a Glance/Compose render harness.
     */
    private fun role(scheme: ColorScheme, token: WidgetThemeColor): Color = when (token) {
        WidgetThemeColor.HeaderBackground -> scheme.secondaryContainer
        WidgetThemeColor.OnHeaderBackground -> scheme.onSecondaryContainer
    }

    @Test
    fun `add-button glyph on the header clears WCAG AA for every accent seed`() {
        // WidgetAddButton draws a plain "+" glyph directly on the secondaryContainer header, with
        // no filled chip behind it. The glyph is tinted onSecondaryContainer — the header's own
        // on-role — so it must clear AA against secondaryContainer for every selectable seed.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val s = widgetScheme(seed, dark)
            val glyphOnHeader = contrastRatio(s.onSecondaryContainer, s.secondaryContainer)
            if (glyphOnHeader < 4.5) {
                failures += "glyph seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, glyphOnHeader)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Add-button glyph below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `month today-marker number on its accent circle clears WCAG AA for every accent seed`() {
        // The month widget marks today with a solid accent circle (primary) and
        // draws the day number in onPrimary. That pair must clear AA for every
        // selectable seed, or today's number is unreadable on its own highlight.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val s = widgetScheme(seed, dark)
            val numberOnMarker = contrastRatio(s.onPrimary, s.primary)
            if (numberOnMarker < 4.5) {
                failures += "today-marker seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, numberOnMarker)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Today-marker number below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `refresh-button glyph on the header clears WCAG AA for every accent seed`() {
        // WidgetRefreshButton draws its glyph directly on the secondaryContainer header with the
        // same onSecondaryContainer tint as the add button when idle, so it must clear AA against
        // secondaryContainer for every selectable seed. (The transient dimmed cue uses `outline`
        // and is deliberately exempt — it is a brief de-emphasis, not persistent readable content.)
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val s = widgetScheme(seed, dark)
            val glyphOnHeader = contrastRatio(s.onSecondaryContainer, s.secondaryContainer)
            if (glyphOnHeader < 4.5) {
                failures += "glyph seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, glyphOnHeader)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Refresh-button glyph below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }

    /** Resolves a Glance [ColorProvider]'s concrete color for the light (false) or dark (true) face. */
    private fun ColorProvider.resolve(dark: Boolean): Color =
        (this as DayNightColorProvider).getColor(dark)

    @Test
    fun `event item text is legible on the widget body for every accent seed including achromatic`() {
        // Drives the REAL providers a SEED widget renders — accentColorProviders(seed) — not a
        // stand-in scheme, so it trips if the override is ever removed. contentBackground reads the
        // active providers' `widgetBackground` role, which accentColorProviders overrides to
        // `surfaceVariant`; item title AND time read `onSurface`. Glance's Material 3 interop would
        // otherwise derive widgetBackground from secondaryContainer (the accent header's role), which
        // is not a guaranteed-contrast pair for onSurface and collapses at the elevated header
        // contrast — for the white/black accents the body would snap to the extreme while onSurface
        // follows day/night, so headers stayed crisp but items vanished. onSurface/surfaceVariant is
        // a guaranteed pair (9–16:1 here). Assert on the real providers: reverting the override drops
        // this below AA. (The companion chroma test guards the other axis — that the body is tinted.)
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val p: ColorProviders = accentColorProviders(seed)
            val body = p.widgetBackground.resolve(dark)          // WidgetTheme.contentBackground
            // Primary item text (title + time) on the body.
            val itemOnBody = contrastRatio(p.onSurface.resolve(dark), body)
            if (itemOnBody < 4.5) {
                failures += "item(onSurface) seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, itemOnBody)
            }
            // Secondary body text — empty-state copy, week day-of-week headers, upcoming subtitles,
            // month day-of-week labels all paint onSurfaceVariant (WidgetTheme.secondaryText) on the
            // same body and must also stay legible at the widget contrast level.
            val secondaryOnBody = contrastRatio(p.onSurfaceVariant.resolve(dark), body)
            if (secondaryOnBody < 4.5) {
                failures += "secondary(onSurfaceVariant) seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, secondaryOnBody)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Widget body text below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }

    /** Chroma proxy: RGB max-min channel spread (0 = perfectly neutral/gray, higher = more colorful). */
    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test
    fun `widget body is visibly accent-tinted for chromatic seeds, not flat neutral`() {
        // The legibility test above passes for a PURE-GRAY body too (onSurface-on-gray is ~19:1), so
        // it cannot catch the real bug the user hit twice: a body that reads flat with no accent.
        // This guards the OTHER axis — the body actually carries chroma. contentBackground -> the
        // providers' widgetBackground role (overridden to surfaceVariant); compare its chroma against
        // the bare `surface` role, which is near-neutral by M3 design. For a chromatic seed the body
        // must be meaningfully more colorful than surface, or the accent is invisible.
        //
        // Achromatic seeds (white/black/silver) are excluded on purpose: they have no hue to show, so
        // their surfaceVariant is legitimately near-neutral and a chroma floor would be meaningless.
        val chromaticSeeds = listOf(
            0xFF0E6E62.toInt(), // brand teal
            0xFFFFD700.toInt(), // gold
            0xFF1E90FF.toInt(), // dodgerblue
            0xFFFF69B4.toInt(), // hotpink
        )
        val failures = mutableListOf<String>()
        for (seed in chromaticSeeds) for (dark in listOf(false, true)) {
            val p: ColorProviders = accentColorProviders(seed)
            val bodyChroma = chroma(p.widgetBackground.resolve(dark))   // WidgetTheme.contentBackground
            val surfaceChroma = chroma(p.surface.resolve(dark))
            // Body must clear a small absolute chroma floor AND out-tint bare surface. The floor sits
            // below every measured value (min ~0.043 for teal) but above near-neutral surface, so
            // reverting the body to `surface` — the exact regression that shipped flat — trips this.
            if (bodyChroma < 0.04f || bodyChroma <= surfaceChroma) {
                failures += "seed=%06X dark=%s bodyChroma=%.3f surfaceChroma=%.3f".format(
                    seed and 0xFFFFFF, dark, bodyChroma, surfaceChroma,
                )
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Widget body not visibly accent-tinted:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `day-header text-on-background pairs clear WCAG AA for every accent seed`() {
        // Seeds spanning the selectable palette incl. the worst cases (low-chroma gray, black,
        // white, saturated). Header text used onSurface before the fix and failed here.
        val seeds = listOf(
            0xFF0E6E62.toInt(), // brand teal
            0xFFC0C0C0.toInt(), // silver (low chroma)
            0xFF000000.toInt(), // black
            0xFFFFFFFF.toInt(), // white
            0xFFFFD700.toInt(), // gold (pale)
            0xFF1E90FF.toInt(), // dodgerblue
            0xFFFF69B4.toInt(), // hotpink
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val scheme = widgetScheme(seed, dark)
            for (isToday in listOf(true, false)) {
                val c = dayHeaderColors(isToday)
                val ratio = contrastRatio(role(scheme, c.text), role(scheme, c.background))
                if (ratio < 4.5) {
                    failures += "seed=%06X dark=%s today=%s ratio=%.2f".format(
                        seed and 0xFFFFFF, dark, isToday, ratio,
                    )
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Day-header pairs below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }
}
