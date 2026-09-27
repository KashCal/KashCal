package org.onekash.kashcal.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.color.DayNightColorProvider
import androidx.glance.unit.ColorProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.shared.contrastRatio
import org.onekash.kashcal.ui.shared.relativeLuminance
import org.onekash.kashcal.ui.theme.WIDGET_ACCENT_CONTRAST_LEVEL
import org.onekash.kashcal.ui.theme.accentColorScheme

/**
 * Tests the widget color contract: the day-header token selector, WCAG AA contrast of the header,
 * today-marker and body text pairs, body tint, dark header luminance, and the monochrome snap for
 * pure black and white seeds.
 *
 * [dayHeaderColors] returns [WidgetThemeColor] tokens so contrast is asserted without a render
 * harness; the token to ColorProvider step is the `when` in [provider].
 *
 * Every scheme here is built at [WIDGET_ACCENT_CONTRAST_LEVEL], the level widgets render at
 * ([accentColorProviders]), not the app default. Building at 0.0 would verify colors the widget
 * never shows and miss a regression that only appears at the widget level.
 */
class WidgetThemeTest {

    /**
     * Builds the raw scheme a widget renders: the widget contrast level with the achromatic
     * container snap off, as [accentColorProviders] builds it. Any other build would verify colors
     * the widget never shows.
     */
    private fun widgetScheme(seed: Int, dark: Boolean): ColorScheme =
        accentColorScheme(
            seed, dark,
            contrastLevel = WIDGET_ACCENT_CONTRAST_LEVEL,
            snapAchromaticContainers = false,
        )

    @Test
    fun `every day header uses the header background with its matching on-color`() {
        // All day headers share the header background so the list reads as one banner; today
        // is set apart by bold text and a "today" label, not a different background.
        for (isToday in listOf(true, false)) {
            val colors = dayHeaderColors(isToday)
            assertEquals(WidgetThemeColor.HeaderBackground, colors.background)
            // Must be the on-header token (onSecondaryContainer), not onSurface: onSurface isn't a
            // guaranteed-contrast pair with a secondaryContainer header, and for some accent seeds
            // makes today headers unreadable.
            assertEquals(WidgetThemeColor.OnHeaderBackground, colors.text)
        }
    }

    /**
     * Maps a [WidgetThemeColor] token to the M3 role [provider] resolves it to, so the pairing is
     * contrast-checked without a Glance or Compose harness. A copy of [provider]'s mapping through
     * [WidgetTheme], so it can drift; keep the two in step.
     */
    private fun role(scheme: ColorScheme, token: WidgetThemeColor): Color = when (token) {
        WidgetThemeColor.HeaderBackground -> scheme.secondaryContainer
        WidgetThemeColor.OnHeaderBackground -> scheme.onSecondaryContainer
    }

    @Test
    fun `add-button glyph on the header clears WCAG AA for every accent seed`() {
        // WidgetAddButton draws a plain "+" on the secondaryContainer header with no chip behind
        // it, tinted onSecondaryContainer, the header's own on-role, so the pair must clear AA
        // for every selectable seed. Pure black and white are omitted: the widget renders them
        // through the monochrome snap (a flat 21:1), not this raw scheme, and the snap's header
        // pair is covered by the achromatic-snap tests below. Silver stays; it isn't snapped.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(),
            0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
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
        // The Month widget marks today with a solid primary circle and draws the day number in
        // onPrimary. That pair must clear AA for every selectable seed, or today's number is
        // unreadable on its own highlight. Pure black and white are omitted: the monochrome snap
        // renders them (onPrimary and primary become panel and ink, a flat 21:1), and the snap's
        // marker pair is covered by the achromatic-snap tests below. Silver isn't snapped.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(),
            0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
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
        // WidgetRefreshButton, when idle, draws its glyph on the secondaryContainer header with the
        // add button's onSecondaryContainer tint, so it must clear AA for every selectable seed.
        // The dimmed cue uses `outline` and is deliberately exempt: a brief de-emphasis, not
        // persistent content. Pure black and white are omitted: they render through the
        // monochrome snap (a flat 21:1), covered by the achromatic-snap tests below.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(),
            0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
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

    /**
     * Resolves a Glance [ColorProvider]'s concrete color for the light (false) or dark (true) face.
     */
    private fun ColorProvider.resolve(dark: Boolean): Color =
        (this as DayNightColorProvider).getColor(dark)

    @Test
    fun `event item text is legible on the widget body for every accent seed including achromatic`() {
        // Drives the providers a seed widget renders, accentColorProviders(seed), not a stand-in
        // scheme, so reverting the body override to a less safe role trips it. contentBackground
        // reads `widgetBackground`, which accentColorProviders sets to `surfaceVariant`; its KDoc
        // says why not secondaryContainer. Item title and time read `onSurface`, secondary copy
        // `onSurfaceVariant`; both must clear AA on the body for every seed. The chroma test below
        // guards that the body is still tinted, the luminance test that the dark header stays dark.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val p: ColorProviders = accentColorProviders(seed)
            val body = p.widgetBackground.resolve(dark)          // WidgetTheme.contentBackground
            // Item title and time on the body.
            val itemOnBody = contrastRatio(p.onSurface.resolve(dark), body)
            if (itemOnBody < 4.5) {
                failures += "item(onSurface) seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, itemOnBody)
            }
            // Secondary text (WidgetTheme.secondaryText, onSurfaceVariant) on the same body: the
            // empty, loading and error copy, and the Month widget's day-of-week labels, week
            // numbers and overflow counts.
            val secondaryOnBody = contrastRatio(p.onSurfaceVariant.resolve(dark), body)
            if (secondaryOnBody < 4.5) {
                failures += "secondary(onSurfaceVariant) seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, secondaryOnBody)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Widget body text below WCAG AA:\n" + failures.joinToString("\n"))
        }
    }

    /** Returns a chroma proxy: the RGB max-min channel spread, 0 for a neutral gray. */
    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test
    fun `widget body is visibly accent-tinted for chromatic seeds, not flat neutral`() {
        // The legibility test above passes for a pure-gray body too (onSurface on gray is ~19:1),
        // so it can't catch a body that reads flat with no accent. This guards that the body
        // carries chroma: the widgetBackground role (surfaceVariant) is compared against bare
        // `surface`, near-neutral by M3 design. For a chromatic seed the body must be more colorful
        // than surface, or the accent is invisible; surfaceVariant measures chroma ~0.04 to 0.15
        // for these seeds.
        //
        // Achromatic seeds (white, black, silver) are excluded: they have no hue to show, so their
        // container is near-neutral and a chroma floor would be meaningless.
        val chromaticSeeds = listOf(
            0xFF0E6E62.toInt(), // brand teal
            0xFFFFD700.toInt(), // gold
            0xFF1E90FF.toInt(), // dodgerblue
            0xFFFF69B4.toInt(), // hotpink
        )
        val failures = mutableListOf<String>()
        for (seed in chromaticSeeds) for (dark in listOf(false, true)) {
            val p: ColorProviders = accentColorProviders(seed)
            // The body: WidgetTheme.contentBackground.
            val bodyChroma = chroma(p.widgetBackground.resolve(dark))
            val surfaceChroma = chroma(p.surface.resolve(dark))
            // The body must clear a chroma floor and out-tint bare surface. The floor sits below
            // every measured surfaceVariant value (min ~0.04 for teal) but above near-neutral
            // surface, so reverting the body to the flat `surface` or `background` role trips one
            // branch or the other.
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
    fun `dark widget header stays a dark tinted tone, not a bright pastel`() {
        // At a high contrast level such as 0.8 the HCT engine inverts the dark secondaryContainer
        // to a bright pastel (L ~0.68) with dark text, flooding the top of the dark widget with
        // light, since the header and footer ride that role. The surfaceVariant body barely moves
        // with the contrast level, so pinning the body wouldn't catch it; the header does. At the
        // current widget contrast level the dark header measures L ~0.08 for every seed, so the
        // ceiling below trips if WIDGET_ACCENT_CONTRAST_LEVEL rises toward 0.8. The light face is
        // exempt; its header is a light tone.
        //
        // This checks the raw widgetScheme secondaryContainer, which the monochrome snap never
        // touches (it replaces roles later, in accentColorProviders). So black and white seeds stay
        // here: their raw dark header measures L ~0.08 like the rest, and their snapped panel is
        // covered by the achromatic-snap tests below.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) {
            // Built at the widget contrast level (widgetScheme), so this is the header the widget
            // renders, and it moves with WIDGET_ACCENT_CONTRAST_LEVEL.
            val header = widgetScheme(seed, dark = true).secondaryContainer
            val l = relativeLuminance(header)
            // The inverted pastel measures ~0.68, the dark tone ~0.08.
            if (l > 0.20) {
                failures += "seed=%06X darkHeaderLuminance=%.2f".format(seed and 0xFFFFFF, l)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Dark widget header too bright (contrast axis re-inflated?):\n" + failures.joinToString("\n"))
        }
    }

    // ==================== Achromatic-extreme monochrome snap ====================
    // A pure-black or pure-white seed has no hue for the HCT engine to preserve, so the raw scheme
    // collapses onto a muddy neutral gray ("I picked black, the widget is gray"). For these two
    // seeds only, accentColorProviders forces a monochrome panel: background roles take the pure
    // extreme, primary text and on-roles the pure inverse (21:1), in both faces (a black seed is a
    // black widget in light mode too). The dimming hierarchy survives through mid-gray secondary
    // and past text, not a flat inverse.

    private companion object {
        const val BLACK_SEED: Int = 0xFF000000.toInt()
        const val WHITE_SEED: Int = 0xFFFFFFFF.toInt()
    }

    @Test
    fun `black seed forces an all-black panel with white text in both faces`() {
        val p: ColorProviders = accentColorProviders(BLACK_SEED)
        for (dark in listOf(false, true)) {
            // The painted background roles, header and footer (secondaryContainer) and body
            // (widgetBackground), are pure black in either face.
            assertEquals("header bg dark=$dark", Color.Black, p.secondaryContainer.resolve(dark))
            assertEquals("body bg dark=$dark", Color.Black, p.widgetBackground.resolve(dark))
            // Primary text (onSurface) and header text (onSecondaryContainer): pure white.
            assertEquals("primary text dark=$dark", Color.White, p.onSurface.resolve(dark))
            assertEquals("header text dark=$dark", Color.White, p.onSecondaryContainer.resolve(dark))
            // `primary` is both the today-circle fill and accent text on the black body ("+N
            // more"), so it must be the readable inverse (white), not black on black.
            assertEquals("accent/primary dark=$dark", Color.White, p.primary.resolve(dark))
            // The day number drawn on that white today-circle is onPrimary -> black.
            assertEquals("onPrimary dark=$dark", Color.Black, p.onPrimary.resolve(dark))
        }
    }

    @Test
    fun `white seed forces an all-white panel with black text in both faces`() {
        val p: ColorProviders = accentColorProviders(WHITE_SEED)
        for (dark in listOf(false, true)) {
            assertEquals("header bg dark=$dark", Color.White, p.secondaryContainer.resolve(dark))
            assertEquals("body bg dark=$dark", Color.White, p.widgetBackground.resolve(dark))
            assertEquals("primary text dark=$dark", Color.Black, p.onSurface.resolve(dark))
            assertEquals("header text dark=$dark", Color.Black, p.onSecondaryContainer.resolve(dark))
            assertEquals("accent/primary dark=$dark", Color.Black, p.primary.resolve(dark))
            assertEquals("onPrimary dark=$dark", Color.White, p.onPrimary.resolve(dark))
        }
    }

    @Test
    fun `achromatic snap holds under a forced light or dark pin`() {
        // A pinned face must not defeat the snap: a black-seed widget pinned to light or dark is
        // still a black panel, and a white-seed widget a white one.
        for (forceDark in listOf(false, true)) {
            val black = accentColorProviders(BLACK_SEED, forceDark)
            assertEquals("black body forceDark=$forceDark", Color.Black, black.widgetBackground.resolve(forceDark))
            assertEquals("black text forceDark=$forceDark", Color.White, black.onSurface.resolve(forceDark))
            val white = accentColorProviders(WHITE_SEED, forceDark)
            assertEquals("white body forceDark=$forceDark", Color.White, white.widgetBackground.resolve(forceDark))
            assertEquals("white text forceDark=$forceDark", Color.Black, white.onSurface.resolve(forceDark))
        }
    }

    @Test
    fun `achromatic snap preserves the dimming hierarchy with mid-gray secondary and past text`() {
        // All text at the pure inverse would read at one 21:1 weight. The two dimmed tiers step to
        // gray so the hierarchy holds:
        //   onSurface, pure inverse (21:1): event titles and times
        //   onSurfaceVariant, mid-gray, at least AA: empty-state copy, Month labels and counts
        //   outline, dimmer gray: past events, the sync cue
        // The body legibility test above asserts the secondary tier clears AA for both achromatic
        // seeds; this asserts the ordering that makes it a hierarchy.
        for (seed in listOf(BLACK_SEED, WHITE_SEED)) {
            val p: ColorProviders = accentColorProviders(seed)
            for (dark in listOf(false, true)) {
                val body = p.widgetBackground.resolve(dark)
                val primary = p.onSurface.resolve(dark)          // titles and times
                val secondary = p.onSurfaceVariant.resolve(dark) // secondary copy
                val past = p.outline.resolve(dark)               // past events, sync glyph

                // Each dimmed tier is a neutral gray (R == G == B), with no stray tint.
                for ((label, c) in listOf("secondary" to secondary, "past" to past)) {
                    assertEquals("$label neutral R==G seed=${seed.toHex()} dark=$dark", c.red, c.green)
                    assertEquals("$label neutral G==B seed=${seed.toHex()} dark=$dark", c.green, c.blue)
                }

                // Contrast against the panel must strictly decrease: primary, secondary, past.
                // The messages below call the secondary tier "times"; event times use onSurface.
                val cPrimary = contrastRatio(primary, body)
                val cSecondary = contrastRatio(secondary, body)
                val cPast = contrastRatio(past, body)
                assert(cPrimary > cSecondary) {
                    "titles must out-contrast times seed=${seed.toHex()} dark=$dark: $cPrimary vs $cSecondary"
                }
                assert(cSecondary > cPast) {
                    "times must out-contrast past seed=${seed.toHex()} dark=$dark: $cSecondary vs $cPast"
                }
                // Secondary isn't the pure inverse, which would erase the tier.
                assert(secondary != primary) {
                    "secondary must differ from primary inverse seed=${seed.toHex()} dark=$dark"
                }
            }
        }
    }

    private fun Int.toHex(): String = "%06X".format(this and 0xFFFFFF)

    @Test
    fun `day-header text-on-background pairs clear WCAG AA for every accent seed`() {
        // Seeds spanning the selectable palette, worst cases included (low-chroma gray, black,
        // white, saturated). onSurface as header text fails here.
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
