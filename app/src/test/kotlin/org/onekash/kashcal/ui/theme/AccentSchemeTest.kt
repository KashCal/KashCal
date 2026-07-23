package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.contrastRatio

/**
 * Proves that a ColorScheme generated from ANY user-selectable accent seed keeps
 * text/UI contrast at or above WCAG AA — the guarantee the accent picker rests on.
 *
 * The sweep covers every color the picker can produce (the full 92-color wheel, which
 * contains the grid by construction) plus the brand-teal default and the achromatic
 * extremes (white/black) that stress the tone mapping hardest. Contrast is measured
 * with the app's own [contrastRatio], so the assertion matches what ships.
 */
class AccentSchemeTest {

    /** WCAG AA: normal text needs >= 4.5:1; UI components / large text need >= 3:1. */
    private companion object {
        const val AA_TEXT = 4.5
        const val AA_UI = 3.0
    }

    private val seeds: List<Pair<String, Int>> =
        EventColorPalette.allCss3Colors.map { it.name to it.argb } +
            listOf(
                "brandTeal" to 0xFF0E6E62.toInt(),
                "white" to 0xFFFFFFFF.toInt(),
                "black" to 0xFF000000.toInt(),
            )

    @Test
    fun `every selectable seed yields an AA-compliant scheme in light and dark`() {
        val failures = mutableListOf<String>()
        for ((name, seed) in seeds) {
            for (dark in listOf(false, true)) {
                val s = accentColorScheme(seed, dark)
                checkScheme(name, dark, s, failures)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "WCAG AA violations (${failures.size}):\n" + failures.joinToString("\n"),
            )
        }
    }

    private fun checkScheme(
        name: String,
        dark: Boolean,
        s: ColorScheme,
        failures: MutableList<String>,
    ) {
        // Text on accent-colored fills: buttons, FAB, widget header, tonal chips.
        pair(name, dark, "onPrimary/primary", s.onPrimary, s.primary, AA_TEXT, failures)
        pair(name, dark, "onSecondary/secondary", s.onSecondary, s.secondary, AA_TEXT, failures)
        pair(name, dark, "onTertiary/tertiary", s.onTertiary, s.tertiary, AA_TEXT, failures)
        pair(name, dark, "onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, AA_TEXT, failures)
        pair(name, dark, "onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, AA_TEXT, failures)
        pair(name, dark, "onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, AA_TEXT, failures)
        pair(name, dark, "onError/error", s.onError, s.error, AA_TEXT, failures)

        // Body + secondary text on surfaces.
        pair(name, dark, "onSurface/surface", s.onSurface, s.surface, AA_TEXT, failures)
        pair(name, dark, "onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant, AA_TEXT, failures)
        pair(name, dark, "onBackground/background", s.onBackground, s.background, AA_TEXT, failures)

        // onSurface must stay legible on EVERY surface-container tone — these back
        // cards, bottom sheets, and navigation surfaces.
        pair(name, dark, "onSurface/surfaceContainerLowest", s.onSurface, s.surfaceContainerLowest, AA_TEXT, failures)
        pair(name, dark, "onSurface/surfaceContainerLow", s.onSurface, s.surfaceContainerLow, AA_TEXT, failures)
        pair(name, dark, "onSurface/surfaceContainer", s.onSurface, s.surfaceContainer, AA_TEXT, failures)
        pair(name, dark, "onSurface/surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh, AA_TEXT, failures)
        pair(name, dark, "onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest, AA_TEXT, failures)

        // Accent must be VISIBLE against the surface (non-text UI: FAB, selection marks).
        pair(name, dark, "primary/surface", s.primary, s.surface, AA_UI, failures)

        // Accent-colored TEXT on the surface must clear the stricter text threshold,
        // not just the UI one: the account hub paints `primary` as readable text on
        // the sheet surface in two places — the "Make it yours" section header and the
        // Accounts pill's outlined label (no fill, so the accent IS the text). If this
        // dips below AA text contrast for a seed, that copy becomes hard to read.
        pair(name, dark, "primary/surface (text)", s.primary, s.surface, AA_TEXT, failures)

        // The outline role must be VISIBLE against the surface: the account hub draws
        // hairline borders on the avatar circle and the Accounts pill with it, because
        // their tonal fills (primaryContainer / secondaryContainer) barely separate from
        // the surface for many seeds and collapse entirely for the white/black extremes.
        // If outline/surface drops below the UI threshold those shapes lose their edge.
        pair(name, dark, "outline/surface", s.outline, s.surface, AA_UI, failures)
    }

    private fun pair(
        name: String,
        dark: Boolean,
        label: String,
        fg: Color,
        bg: Color,
        min: Double,
        failures: MutableList<String>,
    ) {
        val ratio = contrastRatio(fg, bg)
        if (ratio < min) {
            val face = if (dark) "dark" else "light"
            failures += "  [$name/$face] $label = %.2f:1 (need >= %.1f)".format(ratio, min)
        }
    }

    /**
     * Guards against roles silently falling back to a Material default. If the generator
     * forgot to map a role, it would equal the baseline scheme's value — which for a
     * distinctive seed would leave that role off-brand. A generated scheme for a saturated
     * seed must differ from the plain baseline in its key accent roles.
     */
    @Test
    fun `generated scheme is not the untouched Material baseline`() {
        val seed = 0xFF0E6E62.toInt()
        val light = accentColorScheme(seed, dark = false)
        val dark = accentColorScheme(seed, dark = true)
        assert(light.primary != lightColorScheme().primary) {
            "light primary should be seed-derived, not the Material baseline"
        }
        assert(dark.primary != darkColorScheme().primary) {
            "dark primary should be seed-derived, not the Material baseline"
        }
    }

    /**
     * The two achromatic extremes are special: HCT has no hue to preserve, so the tonal
     * engine pairs the accent container with a mid-gray "on" tone that only scrapes AA
     * (~4.6:1) and reads as muddy on the widget header. For pure white and pure black we
     * snap the accent-container pair to the crisp inverse (black-on-white / white-on-black,
     * a full 21:1) in BOTH faces, so the header the user picked looks like the color they
     * picked. Every widget/app header surface uses primaryContainer/onPrimaryContainer.
     */
    @Test
    fun `pure white seed yields a pure white accent container with black text in both faces`() {
        for (dark in listOf(false, true)) {
            val s = accentColorScheme(0xFFFFFFFF.toInt(), dark)
            // Pure white bg + pure black fg is exactly 21:1 by definition, so pinning the two
            // colors pins the ratio; no separate contrast assertion needed.
            assertEquals("white container (dark=$dark)", Color.White, s.primaryContainer)
            assertEquals("white on-container (dark=$dark)", Color.Black, s.onPrimaryContainer)
        }
    }

    @Test
    fun `pure black seed yields a pure black accent container with white text in both faces`() {
        for (dark in listOf(false, true)) {
            val s = accentColorScheme(0xFF000000.toInt(), dark)
            assertEquals("black container (dark=$dark)", Color.Black, s.primaryContainer)
            assertEquals("black on-container (dark=$dark)", Color.White, s.onPrimaryContainer)
        }
    }

    /**
     * The achromatic snap must touch ONLY the accent-container pair. If a future change
     * over-broadened the post-processing (e.g. rewrote the surface family or primary), a
     * pure-white seed would silently wash out unrelated roles. Assert every role other than
     * primaryContainer/onPrimaryContainer is byte-for-byte the raw engine output. This also
     * pins that `primary` is untouched, so the primary/surface visibility guarantee holds.
     */
    @Test
    fun `achromatic snap leaves every other role identical to the raw engine`() {
        // Every role EXCEPT the intentionally-snapped container pair. ColorScheme has no value
        // equality, so compare role-by-role. If a future change over-broadened the snap, one of
        // these — surface, primary, the sibling containers — would diverge from the raw engine.
        val untouched: List<Pair<String, (ColorScheme) -> Color>> = listOf(
            "primary" to { it.primary },
            "onPrimary" to { it.onPrimary },
            "inversePrimary" to { it.inversePrimary },
            "secondary" to { it.secondary },
            "onSecondary" to { it.onSecondary },
            "secondaryContainer" to { it.secondaryContainer },
            "onSecondaryContainer" to { it.onSecondaryContainer },
            "tertiary" to { it.tertiary },
            "onTertiary" to { it.onTertiary },
            "tertiaryContainer" to { it.tertiaryContainer },
            "onTertiaryContainer" to { it.onTertiaryContainer },
            "surface" to { it.surface },
            "onSurface" to { it.onSurface },
            "surfaceVariant" to { it.surfaceVariant },
            "onSurfaceVariant" to { it.onSurfaceVariant },
            "surfaceTint" to { it.surfaceTint },
            "inverseSurface" to { it.inverseSurface },
            "inverseOnSurface" to { it.inverseOnSurface },
            "surfaceBright" to { it.surfaceBright },
            "surfaceDim" to { it.surfaceDim },
            "surfaceContainerLowest" to { it.surfaceContainerLowest },
            "surfaceContainerLow" to { it.surfaceContainerLow },
            "surfaceContainer" to { it.surfaceContainer },
            "surfaceContainerHigh" to { it.surfaceContainerHigh },
            "surfaceContainerHighest" to { it.surfaceContainerHighest },
            "background" to { it.background },
            "onBackground" to { it.onBackground },
            "error" to { it.error },
            "onError" to { it.onError },
            "errorContainer" to { it.errorContainer },
            "onErrorContainer" to { it.onErrorContainer },
            "outline" to { it.outline },
            "outlineVariant" to { it.outlineVariant },
            "scrim" to { it.scrim },
        )
        for (seed in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            for (dark in listOf(false, true)) {
                val snapped = accentColorScheme(seed, dark)
                val raw = rawContentScheme(seed, dark)
                for ((role, get) in untouched) {
                    assertEquals(
                        "seed=${Integer.toHexString(seed)} dark=$dark: $role must be untouched",
                        get(raw),
                        get(snapped),
                    )
                }
            }
        }
    }

    /** The unmodified MaterialKolor scheme the production function post-processes. */
    private fun rawContentScheme(seed: Int, dark: Boolean): ColorScheme =
        com.materialkolor.dynamicColorScheme(
            seedColor = Color(seed),
            isDark = dark,
            style = com.materialkolor.PaletteStyle.Content,
            contrastLevel = 0.0,
        )
}
