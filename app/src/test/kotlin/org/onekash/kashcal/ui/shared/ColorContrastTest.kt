package org.onekash.kashcal.ui.shared

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

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

    // ========== contrastForegroundOn: white whenever it reaches 3:1 ==========

    /** The 12 grid colors, the 92 wheel colors, and every 8th RGB channel level plus 255. */
    private val allColors: List<Pair<String, Color>> by lazy {
        val named = EventColorPalette.entries.map { it.name to Color(it.argb) } +
            EventColorPalette.allCss3Colors.map { it.name to Color(it.argb) }
        val steps = (0..255 step 8) + 255
        val cube = steps.flatMap { r ->
            steps.flatMap { g -> steps.map { b -> "rgb($r,$g,$b)" to Color(r, g, b) } }
        }
        named + cube
    }

    @Test
    fun `foreground is white exactly when white reaches 3 to 1`() {
        for ((name, bg) in allColors) {
            val expected = if (contrastRatio(Color.White, bg) >= 3.0) Color.White else Color.Black
            assertEquals(name, expected, contrastForegroundOn(bg))
        }
    }

    @Test
    fun `mid-tone colors from the issue 419 screenshot get white`() {
        assertEquals(Color.White, contrastForegroundOn(Color(0xFFFF0026.toInt())))
        assertEquals(Color.White, contrastForegroundOn(Color(0xFF007EFF.toInt())))
    }

    @Test
    fun `named picks match the 3 to 1 rule`() {
        val white = mapOf(
            "dodgerblue" to 0xFF1E90FF, "red" to 0xFFFF0000, "mediumorchid" to 0xFFBA55D3,
            "steelblue" to 0xFF4682B4, "royalblue" to 0xFF4169E1,
        )
        val black = mapOf(
            "tomato" to 0xFFFF6347, "hotpink" to 0xFFFF69B4, "lightseagreen" to 0xFF20B2AA,
            "peru" to 0xFFCD853F, "cornflowerblue" to 0xFF6495ED, "orchid" to 0xFFDA70D6,
            "rosybrown" to 0xFFBC8F8F, "mediumseagreen" to 0xFF3CB371, "lightcoral" to 0xFFF08080,
            "gold" to 0xFFFFD700,
        )
        for ((name, argb) in white) {
            assertEquals(name, Color.White, contrastForegroundOn(Color(argb.toInt())))
        }
        for ((name, argb) in black) {
            assertEquals(name, Color.Black, contrastForegroundOn(Color(argb.toInt())))
        }
    }

    @Test
    fun `every grid and wheel color reaches 3 to 1 against its chosen foreground`() {
        // Black is picked only where white is under 3:1, and black is above 7:1 there, so this
        // can fail only if the white threshold drops below 3:1.
        val offered = EventColorPalette.entries.map { it.name to it.argb } +
            EventColorPalette.allCss3Colors.map { it.name to it.argb }
        for ((name, argb) in offered) {
            val bg = Color(argb)
            val ratio = contrastRatio(contrastForegroundOn(bg), bg)
            assertTrue("$name contrast $ratio should be >= 3.0", ratio >= 3.0)
        }
    }

    // ========== APCA cross-check (test-only reference, not shipped) ==========

    // APCA 0.0.98G-4g lightness contrast, the perceptual method in the WCAG 3 draft. It exists
    // here only to check that the 3:1 rule never trades black for a white that reads worse; the
    // app itself uses WCAG 2 ratios.
    private fun apcaY(c: Color): Double =
        0.2126729 * c.red.toDouble().pow(2.4) +
            0.7151522 * c.green.toDouble().pow(2.4) +
            0.0721750 * c.blue.toDouble().pow(2.4)

    private fun apcaClamp(y: Double) = if (y > 0.022) y else y + (0.022 - y).pow(1.414)

    /** Returns APCA Lc for [text] on [bg]: positive for dark text on light, negative otherwise. */
    private fun apca(text: Color, bg: Color): Double {
        val t = apcaClamp(apcaY(text))
        val b = apcaClamp(apcaY(bg))
        return if (b > t) {
            val s = (b.pow(0.56) - t.pow(0.57)) * 1.14
            if (s < 0.1) 0.0 else (s - 0.027) * 100
        } else {
            val s = (b.pow(0.65) - t.pow(0.62)) * 1.14
            if (s > -0.1) 0.0 else (s + 0.027) * 100
        }
    }

    @Test
    fun `apca helper matches the published reference values`() {
        assertEquals(63.1, apca(Color(0xFF888888), Color.White), 0.1)
        assertEquals(-68.5, apca(Color.White, Color(0xFF888888)), 0.1)
        assertEquals(58.1, apca(Color.Black, Color(0xFFAAAAAA)), 0.1)
        assertEquals(91.7, apca(Color(0xFF112233), Color(0xFFDDEEFF)), 0.1)
    }

    @Test
    fun `white is never picked where it reads worse than black under APCA`() {
        // White at 3:1 or more always has at least black's APCA contrast, so no color gets
        // white where black would read better.
        for ((name, bg) in allColors) {
            if (contrastForegroundOn(bg) != Color.White) continue
            val white = abs(apca(Color.White, bg))
            val black = abs(apca(Color.Black, bg))
            assertTrue("$name: white Lc $white should be >= black Lc $black", white >= black)
        }
    }
}
