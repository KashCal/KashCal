package org.onekash.kashcal.ui.shared

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {

    // ========== relativeLuminance (WCAG 2.x) ==========

    @Test
    fun `relative luminance is 0 for black and 1 for white`() {
        assertEquals(0.0, relativeLuminance(Color.Black), 1e-6)
        assertEquals(1.0, relativeLuminance(Color.White), 1e-6)
    }

    @Test
    fun `relative luminance weights green above red above blue`() {
        val r = relativeLuminance(Color.Red)
        val g = relativeLuminance(Color.Green)
        val b = relativeLuminance(Color.Blue)
        assertTrue("green should be brightest", g > r)
        assertTrue("red should be brighter than blue", r > b)
    }

    // ========== contrastRatio (WCAG 2.x) ==========

    @Test
    fun `black on white is the maximum 21 to 1 ratio`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
    }

    @Test
    fun `identical colors have a 1 to 1 ratio`() {
        assertEquals(1.0, contrastRatio(Color.Gray, Color.Gray), 1e-6)
    }

    @Test
    fun `contrast ratio is symmetric`() {
        val fg = Color(0xFF1E90FF.toInt())
        val bg = Color(0xFFFFD700.toInt())
        assertEquals(contrastRatio(fg, bg), contrastRatio(bg, fg), 1e-9)
    }

    // ========== contrastForegroundOn picks the higher-contrast option ==========

    @Test
    fun `foreground is black on a light background`() {
        assertEquals(Color.Black, contrastForegroundOn(Color(0xFFFFD700.toInt()))) // gold
    }

    @Test
    fun `foreground is white on a dark background`() {
        assertEquals(Color.White, contrastForegroundOn(Color(0xFF4169E1.toInt()))) // royalblue
    }

    @Test
    fun `chosen foreground always beats the other choice`() {
        // For any background, the returned foreground must have >= contrast than
        // the alternative black/white option.
        for (entry in EventColorPalette.entries) {
            val bg = Color(entry.argb)
            val chosen = contrastForegroundOn(bg)
            val other = if (chosen == Color.Black) Color.White else Color.Black
            assertTrue(
                "chosen fg for ${entry.name} should beat the alternative",
                contrastRatio(chosen, bg) >= contrastRatio(other, bg),
            )
        }
    }

    @Test
    fun `every palette entry meets WCAG AA for normal text against its chosen foreground`() {
        // Picking the better of black/white against a solid color is provably
        // >= 4.58:1 in the worst case, so AA (4.5:1) must always hold.
        for (entry in EventColorPalette.entries) {
            val bg = Color(entry.argb)
            val ratio = contrastRatio(contrastForegroundOn(bg), bg)
            assertTrue(
                "${entry.name} contrast $ratio should be >= 4.5",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `every wheel color meets WCAG AA against its chosen foreground`() {
        // contrastForegroundOn serves the full 92-color wheel palette (and
        // arbitrary user hex), not just the 11-color grid — so exercise the
        // whole wheel set to back the "any solid color clears AA" claim.
        for (entry in EventColorPalette.allCss3Colors) {
            val bg = Color(entry.argb)
            val ratio = contrastRatio(contrastForegroundOn(bg), bg)
            assertTrue(
                "${entry.name} contrast $ratio should be >= 4.5",
                ratio >= 4.5,
            )
        }
    }
}
