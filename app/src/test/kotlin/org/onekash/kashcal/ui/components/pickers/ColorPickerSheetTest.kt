package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ColorPickerSheet color conversion utilities.
 *
 * Uses Robolectric for android.graphics.Color access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ColorPickerSheetTest {

    // ==================== hueToArgb Tests ====================

    @Test
    fun `hueToArgb - red at 0 degrees`() {
        val argb = hueToArgb(0f)
        // Red at full saturation = 0xFFFF0000
        assertEquals(0xFFFF0000.toInt(), argb)
    }

    @Test
    fun `hueToArgb - green at 120 degrees`() {
        val argb = hueToArgb(120f)
        // Green at full saturation = 0xFF00FF00
        assertEquals(0xFF00FF00.toInt(), argb)
    }

    @Test
    fun `hueToArgb - blue at 240 degrees`() {
        val argb = hueToArgb(240f)
        // Blue at full saturation = 0xFF0000FF
        assertEquals(0xFF0000FF.toInt(), argb)
    }

    @Test
    fun `hueToArgb - yellow at 60 degrees`() {
        val argb = hueToArgb(60f)
        // Yellow at full saturation = 0xFFFFFF00
        assertEquals(0xFFFFFF00.toInt(), argb)
    }

    @Test
    fun `hueToArgb - cyan at 180 degrees`() {
        val argb = hueToArgb(180f)
        // Cyan at full saturation = 0xFF00FFFF
        assertEquals(0xFF00FFFF.toInt(), argb)
    }

    @Test
    fun `hueToArgb - magenta at 300 degrees`() {
        val argb = hueToArgb(300f)
        // Magenta at full saturation = 0xFFFF00FF
        assertEquals(0xFFFF00FF.toInt(), argb)
    }

    @Test
    fun `hueToArgb - coerces negative hue to 0`() {
        val argb = hueToArgb(-30f)
        assertEquals(hueToArgb(0f), argb)
    }

    @Test
    fun `hueToArgb - coerces hue above 360 to 360`() {
        val argb = hueToArgb(400f)
        assertEquals(hueToArgb(360f), argb)
    }

    // ==================== colorToHue Tests ====================

    @Test
    fun `colorToHue - extracts 0 from red`() {
        val hue = colorToHue(0xFFFF0000.toInt())
        assertEquals(0f, hue, 0.1f)
    }

    @Test
    fun `colorToHue - extracts 120 from green`() {
        val hue = colorToHue(0xFF00FF00.toInt())
        assertEquals(120f, hue, 0.1f)
    }

    @Test
    fun `colorToHue - extracts 240 from blue`() {
        val hue = colorToHue(0xFF0000FF.toInt())
        assertEquals(240f, hue, 0.1f)
    }

    @Test
    fun `colorToHue - extracts hue from desaturated color`() {
        // Pastel red (reduced saturation) still has hue ~ 0
        val pastelRed = 0xFFFF8080.toInt()
        val hue = colorToHue(pastelRed)
        assertEquals(0f, hue, 1f)
    }

    // ==================== argbToHex Tests ====================

    @Test
    fun `argbToHex - formats red correctly`() {
        val hex = argbToHex(0xFFFF0000.toInt())
        assertEquals("FF0000", hex)
    }

    @Test
    fun `argbToHex - formats green correctly`() {
        val hex = argbToHex(0xFF00FF00.toInt())
        assertEquals("00FF00", hex)
    }

    @Test
    fun `argbToHex - formats blue correctly`() {
        val hex = argbToHex(0xFF0000FF.toInt())
        assertEquals("0000FF", hex)
    }

    @Test
    fun `argbToHex - strips alpha channel`() {
        // Semi-transparent red
        val hex = argbToHex(0x80FF0000.toInt())
        assertEquals("FF0000", hex)
    }

    @Test
    fun `argbToHex - pads with zeros`() {
        // Dark blue
        val hex = argbToHex(0xFF00000A.toInt())
        assertEquals("00000A", hex)
    }

    // ==================== hexToArgb Tests ====================

    @Test
    fun `hexToArgb - parses red`() {
        val argb = hexToArgb("FF0000")
        assertEquals(0xFFFF0000.toInt(), argb)
    }

    @Test
    fun `hexToArgb - parses with hash prefix`() {
        val argb = hexToArgb("#00FF00")
        assertEquals(0xFF00FF00.toInt(), argb)
    }

    @Test
    fun `hexToArgb - case insensitive`() {
        val upper = hexToArgb("AABBCC")
        val lower = hexToArgb("aabbcc")
        val mixed = hexToArgb("AaBbCc")
        assertEquals(upper, lower)
        assertEquals(upper, mixed)
    }

    @Test
    fun `hexToArgb - returns null for invalid length`() {
        assertNull(hexToArgb("FFF"))
        assertNull(hexToArgb("FFFFFFF"))
    }

    @Test
    fun `hexToArgb - returns null for invalid characters`() {
        assertNull(hexToArgb("GGGGGG"))
        assertNull(hexToArgb("FF00GG"))
    }

    @Test
    fun `hexToArgb - always returns opaque color`() {
        val argb = hexToArgb("123456")
        assertNotNull(argb)
        // Alpha should be FF
        val alpha = (argb!! shr 24) and 0xFF
        assertEquals(255, alpha)
    }

    // ==================== isValidHex Tests ====================

    @Test
    fun `isValidHex - accepts valid 6 character hex`() {
        assertTrue(isValidHex("FF0000"))
        assertTrue(isValidHex("00ff00"))
        assertTrue(isValidHex("AbCdEf"))
        assertTrue(isValidHex("123456"))
    }

    @Test
    fun `isValidHex - accepts with hash prefix`() {
        assertTrue(isValidHex("#FF0000"))
    }

    @Test
    fun `isValidHex - rejects wrong length`() {
        assertFalse(isValidHex("FFF"))
        assertFalse(isValidHex("FFFF"))
        assertFalse(isValidHex("FFFFF"))
        assertFalse(isValidHex("FFFFFFF"))
    }

    @Test
    fun `isValidHex - rejects invalid characters`() {
        assertFalse(isValidHex("GGGGGG"))
        assertFalse(isValidHex("FF00G0"))
        assertFalse(isValidHex("FF 000"))
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `round trip - hue to argb to hex to argb`() {
        for (hue in listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f)) {
            val original = hueToArgb(hue)
            val hex = argbToHex(original)
            val restored = hexToArgb(hex)
            assertEquals("Round trip failed for hue $hue", original, restored)
        }
    }

    @Test
    fun `round trip - argb to hex to argb preserves color`() {
        val testColors = listOf(
            0xFFFF0000.toInt(), // Red
            0xFF00FF00.toInt(), // Green
            0xFF0000FF.toInt(), // Blue
            0xFFFFFF00.toInt(), // Yellow
            0xFF00FFFF.toInt(), // Cyan
            0xFFFF00FF.toInt(), // Magenta
            0xFF2196F3.toInt(), // Material Blue
            0xFF4CAF50.toInt()  // Material Green
        )
        for (color in testColors) {
            val hex = argbToHex(color)
            val restored = hexToArgb(hex)
            assertEquals("Round trip failed for 0x${Integer.toHexString(color)}", color, restored)
        }
    }

    // ==================== hueToColorName Tests ====================

    @Test
    fun `hueToColorName - returns correct names`() {
        assertEquals("Red", hueToColorName(0f))
        assertEquals("Orange", hueToColorName(30f))
        assertEquals("Yellow", hueToColorName(60f))
        assertEquals("Green", hueToColorName(120f))
        assertEquals("Cyan", hueToColorName(180f))
        assertEquals("Blue", hueToColorName(240f))
        assertEquals("Purple", hueToColorName(300f))
        assertEquals("Red", hueToColorName(350f))
    }

    @Test
    fun `hueToColorName - boundary values`() {
        // Test boundary between colors
        assertEquals("Red", hueToColorName(14f))
        assertEquals("Orange", hueToColorName(15f))
        assertEquals("Orange", hueToColorName(44f))
        assertEquals("Yellow", hueToColorName(45f))
    }
}
