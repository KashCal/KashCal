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
        // Pure black/white are omitted: the widget renders those through the monochrome snap
        // (a flat 21:1), not this raw scheme, and the snap's header pair is covered by the
        // achromatic-snap tests below. Silver stays — it is a raw-scheme seed, not snapped.
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
        // The month widget marks today with a solid accent circle (primary) and
        // draws the day number in onPrimary. That pair must clear AA for every
        // selectable seed, or today's number is unreadable on its own highlight.
        // Pure black/white are omitted: the widget renders those through the monochrome snap
        // (onPrimary/primary become panel/ink, a flat 21:1) rather than this raw scheme; the snap's
        // marker pair is covered by the achromatic-snap tests below. Silver stays (not snapped).
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
        // WidgetRefreshButton draws its glyph directly on the secondaryContainer header with the
        // same onSecondaryContainer tint as the add button when idle, so it must clear AA against
        // secondaryContainer for every selectable seed. (The transient dimmed cue uses `outline`
        // and is deliberately exempt — it is a brief de-emphasis, not persistent readable content.)
        // Pure black/white are omitted: they render through the monochrome snap (a flat 21:1), not
        // this raw scheme; the snap's header pair is covered by the achromatic-snap tests below.
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

    /** Resolves a Glance [ColorProvider]'s concrete color for the light (false) or dark (true) face. */
    private fun ColorProvider.resolve(dark: Boolean): Color =
        (this as DayNightColorProvider).getColor(dark)

    @Test
    fun `event item text is legible on the widget body for every accent seed including achromatic`() {
        // Drives the REAL providers a SEED widget renders — accentColorProviders(seed) — not a
        // stand-in scheme, so it trips if the override is ever removed. contentBackground reads the
        // active providers' `widgetBackground` role, which accentColorProviders overrides to
        // `surfaceVariant` — the most-tinted body role that stays a guaranteed-contrast pair with
        // both text roles. Item title AND time read `onSurface`; secondary copy reads
        // `onSurfaceVariant`. Both clear AA on surfaceVariant for every seed (this is why the body
        // is surfaceVariant and not the more-chromatic secondaryContainer, whose non-guaranteed
        // pairing drops below AA for saturated seeds). Assert on the real providers so reverting the
        // override to a less-safe role trips this. (The companion chroma test guards the other axis —
        // that the body is still visibly tinted; the luminance test guards that the dark header
        // stays dark.)
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
        // must be meaningfully more colorful than surface, or the accent is invisible. surfaceVariant
        // carries a real accent tint (chroma ~0.04–0.15 for these seeds) while staying a
        // guaranteed-contrast pair with the text roles — the safe most-tinted body.
        //
        // Achromatic seeds (white/black/silver) are excluded on purpose: they have no hue to show, so
        // their container is legitimately near-neutral and a chroma floor would be meaningless.
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
            // Body must clear a chroma floor AND out-tint bare surface. The floor sits below every
            // measured surfaceVariant value (min ~0.04 for teal) but above near-neutral surface, so
            // reverting the body to the flat `surface`/`background` role (the regression the user hit)
            // trips it via one branch or the other.
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
        // The reported regression: at a high contrast axis (this was briefly 0.8) the HCT engine
        // INVERTS the dark-mode secondaryContainer to a bright pastel (L ~0.68) with dark text —
        // "so much light in dark theme". The header (and footer) ride secondaryContainer, so that
        // inversion floods the top of the dark widget with light. This is the axis-sensitive role —
        // the surfaceVariant body barely moves with the axis, so pinning the body would NOT catch a
        // re-inflation; the header is the true regression vector. Pin the dark header's luminance
        // low: at the current widget contrast level it measures L ~0.08 for every seed. This ceiling
        // sits far below the bright-pastel failure and above the true tone, so re-inflating
        // WIDGET_ACCENT_CONTRAST_LEVEL toward 0.8 trips it. Light face is exempt — its header is
        // legitimately a light tone.
        //
        // This guards the RAW widgetScheme secondaryContainer, which the achromatic monochrome snap
        // never touches (the snap replaces roles downstream in accentColorProviders). So the black and
        // white seeds belong here unchanged — at this contrast level their raw dark header measures
        // L~0.08 like every other seed, and their snapped panel is a separate concern covered by the
        // achromatic-snap tests below.
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) {
            // Built at the widget contrast level (widgetScheme), so this IS the header the widget
            // renders and it moves with WIDGET_ACCENT_CONTRAST_LEVEL.
            val header = widgetScheme(seed, dark = true).secondaryContainer
            val l = relativeLuminance(header)
            // Generous ceiling: measured floor of the failure was ~0.68; true dark tone is ~0.08.
            if (l > 0.20) {
                failures += "seed=%06X darkHeaderLuminance=%.2f".format(seed and 0xFFFFFF, l)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Dark widget header too bright (contrast axis re-inflated?):\n" + failures.joinToString("\n"))
        }
    }

    // ==================== Achromatic-extreme monochrome snap ====================
    // A pure-black or pure-white accent seed has no hue for the HCT engine to preserve, so the raw
    // scheme collapses every role onto a muddy neutral gray — "I picked black, the widget is gray".
    // For these two seeds ONLY, accentColorProviders forces a crisp monochrome panel: every
    // background role goes to the pure extreme, primary text and on-roles go to the pure inverse
    // (a clean 21:1), and it holds in BOTH faces (a black seed is a black widget even in light mode).
    // The dimming hierarchy survives via mid-gray secondary/past text rather than a flat pure inverse.

    private companion object {
        const val BLACK_SEED: Int = 0xFF000000.toInt()
        const val WHITE_SEED: Int = 0xFFFFFFFF.toInt()
    }

    @Test
    fun `black seed forces an all-black panel with white text in both faces`() {
        val p: ColorProviders = accentColorProviders(BLACK_SEED)
        for (dark in listOf(false, true)) {
            // Background roles the panel paints: header/footer (secondaryContainer) and body
            // (widgetBackground). Both pure black, regardless of face.
            assertEquals("header bg dark=$dark", Color.Black, p.secondaryContainer.resolve(dark))
            assertEquals("body bg dark=$dark", Color.Black, p.widgetBackground.resolve(dark))
            // Primary text (onSurface) and header text (onSecondaryContainer): pure white.
            assertEquals("primary text dark=$dark", Color.White, p.onSurface.resolve(dark))
            assertEquals("header text dark=$dark", Color.White, p.onSecondaryContainer.resolve(dark))
            // `primary` is BOTH the today-circle fill AND accent text drawn on the black body
            // (e.g. "+N more"), so it must be the readable inverse (white), not black-on-black.
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
        // A pinned face must not defeat the snap: a black-seed widget pinned to LIGHT is still a black
        // panel, and pinned to DARK likewise. accentColorProviders(seed, forceDark) publishes the same
        // pure extreme for whichever face is pinned.
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
        // "Everything pure white" would flatten the design: times, past events and titles would all
        // read at identical 21:1 weight. Instead the two dimmed tiers step to gray so hierarchy holds:
        //   primary text (onSurface)        = pure inverse (21:1)   -- titles
        //   secondary text (onSurfaceVariant) = mid-gray, still >= AA -- event times, subtitles
        //   dim/past text (outline)          = dimmer gray           -- past events, sync cue
        // The secondary tier is asserted >= AA by the existing legibility test (which includes both
        // achromatic seeds); here we assert the ORDERING that makes it a hierarchy and not a flat wall.
        for (seed in listOf(BLACK_SEED, WHITE_SEED)) {
            val p: ColorProviders = accentColorProviders(seed)
            for (dark in listOf(false, true)) {
                val body = p.widgetBackground.resolve(dark)
                val primary = p.onSurface.resolve(dark)          // titles
                val secondary = p.onSurfaceVariant.resolve(dark) // times
                val past = p.outline.resolve(dark)               // past events / sync glyph

                // Each tier is a neutral gray (R==G==B) so nothing carries a stray tint.
                for ((label, c) in listOf("secondary" to secondary, "past" to past)) {
                    assertEquals("$label neutral R==G seed=${seed.toHex()} dark=$dark", c.red, c.green)
                    assertEquals("$label neutral G==B seed=${seed.toHex()} dark=$dark", c.green, c.blue)
                }

                // Contrast against the panel must strictly decrease: titles > times > past.
                val cPrimary = contrastRatio(primary, body)
                val cSecondary = contrastRatio(secondary, body)
                val cPast = contrastRatio(past, body)
                assert(cPrimary > cSecondary) {
                    "titles must out-contrast times seed=${seed.toHex()} dark=$dark: $cPrimary vs $cSecondary"
                }
                assert(cSecondary > cPast) {
                    "times must out-contrast past seed=${seed.toHex()} dark=$dark: $cSecondary vs $cPast"
                }
                // Secondary is NOT the flat pure inverse (that would erase the tier).
                assert(secondary != primary) {
                    "secondary must differ from primary inverse seed=${seed.toHex()} dark=$dark"
                }
            }
        }
    }

    private fun Int.toHex(): String = "%06X".format(this and 0xFFFFFF)

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
