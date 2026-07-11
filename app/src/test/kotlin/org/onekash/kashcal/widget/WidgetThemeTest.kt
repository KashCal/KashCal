package org.onekash.kashcal.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.shared.contrastRatio
import org.onekash.kashcal.ui.theme.accentColorScheme

/**
 * Unit tests for the pure (non-@Composable) parts of [WidgetTheme].
 *
 * The selector returns enum-typed token names so the contrast contract can be
 * asserted at the unit-test layer; the composable hop from token -> ColorProvider
 * lives at WidgetTheme.kt as a small `when` block.
 */
class WidgetThemeTest {

    @Test
    fun `dayHeaderColors for today pairs header background with its matching on-color`() {
        val colors = dayHeaderColors(isToday = true)
        assertEquals(WidgetThemeColor.HeaderBackground, colors.background)
        // Must be the on-header token (onPrimaryContainer), NOT PrimaryText (onSurface):
        // onSurface is not a guaranteed-contrast pair against a primaryContainer header for an
        // arbitrary accent seed. This regressed once and made today headers unreadable.
        assertEquals(WidgetThemeColor.OnHeaderBackground, colors.text)
    }

    @Test
    fun `dayHeaderColors for non-today selects row tint background and row tint text`() {
        val colors = dayHeaderColors(isToday = false)
        assertEquals(WidgetThemeColor.RowTintBackground, colors.background)
        assertEquals(WidgetThemeColor.RowTintText, colors.text)
    }

    /**
     * The [WidgetThemeColor] token -> M3 role mapping the composable [provider] resolves to.
     * Kept in sync with WidgetTheme by intent; this is what lets the pairing be contrast-checked
     * without a Glance/Compose render harness.
     */
    private fun role(scheme: ColorScheme, token: WidgetThemeColor): Color = when (token) {
        WidgetThemeColor.HeaderBackground -> scheme.primaryContainer
        WidgetThemeColor.OnHeaderBackground -> scheme.onPrimaryContainer
        WidgetThemeColor.RowTintBackground -> scheme.surfaceVariant
        WidgetThemeColor.PrimaryText -> scheme.onSurface
        WidgetThemeColor.RowTintText -> scheme.onSurfaceVariant
    }

    @Test
    fun `add-button glyph on fill AND fill on header both clear WCAG AA for every accent seed`() {
        // WidgetAddButton sits on the primaryContainer header. It fills with onPrimaryContainer and
        // draws the glyph in the header tone (primaryContainer). Two pairs must hold: glyph-vs-fill
        // (legible icon) AND fill-vs-header (the chip stands off the header, not a `primary` blend).
        val seeds = listOf(
            0xFF0E6E62.toInt(), 0xFFC0C0C0.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFD700.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(),
        )
        val failures = mutableListOf<String>()
        for (seed in seeds) for (dark in listOf(false, true)) {
            val s = accentColorScheme(seed, dark)
            val glyphOnFill = contrastRatio(s.primaryContainer, s.onPrimaryContainer)
            val fillOnHeader = contrastRatio(s.onPrimaryContainer, s.primaryContainer)
            if (glyphOnFill < 4.5) failures += "glyph seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, glyphOnFill)
            if (fillOnHeader < 4.5) failures += "fill seed=%06X dark=%s ratio=%.2f".format(seed and 0xFFFFFF, dark, fillOnHeader)
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Add-button pairs below WCAG AA:\n" + failures.joinToString("\n"))
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
            val scheme = accentColorScheme(seed, dark)
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
