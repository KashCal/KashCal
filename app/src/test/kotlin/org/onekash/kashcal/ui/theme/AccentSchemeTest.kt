package org.onekash.kashcal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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
}
