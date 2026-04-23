package org.onekash.kashcal.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.R

class EventColorPaletteTest {

    // ========== Grid palette (11 hue-distinct entries) ==========

    @Test
    fun `entries list has 11 hue-distinct colors in documented order`() {
        assertEquals(11, EventColorPalette.entries.size)
        val expectedOrder = listOf(
            "tomato", "darkorange", "gold", "yellowgreen", "limegreen",
            "lightseagreen", "dodgerblue", "royalblue", "mediumorchid",
            "hotpink", "dimgray"
        )
        assertEquals(expectedOrder, EventColorPalette.entries.map { it.name })
    }

    @Test
    fun `hexForName returns argb for every grid palette entry`() {
        assertEquals(0xFFFF6347.toInt(), EventColorPalette.hexForName("tomato"))
        assertEquals(0xFFFF8C00.toInt(), EventColorPalette.hexForName("darkorange"))
        assertEquals(0xFFFFD700.toInt(), EventColorPalette.hexForName("gold"))
        assertEquals(0xFF9ACD32.toInt(), EventColorPalette.hexForName("yellowgreen"))
        assertEquals(0xFF32CD32.toInt(), EventColorPalette.hexForName("limegreen"))
        assertEquals(0xFF20B2AA.toInt(), EventColorPalette.hexForName("lightseagreen"))
        assertEquals(0xFF1E90FF.toInt(), EventColorPalette.hexForName("dodgerblue"))
        assertEquals(0xFF4169E1.toInt(), EventColorPalette.hexForName("royalblue"))
        assertEquals(0xFFBA55D3.toInt(), EventColorPalette.hexForName("mediumorchid"))
        assertEquals(0xFFFF69B4.toInt(), EventColorPalette.hexForName("hotpink"))
        assertEquals(0xFF696969.toInt(), EventColorPalette.hexForName("dimgray"))
    }

    @Test
    fun `hexForName is case-insensitive`() {
        assertEquals(0xFFBA55D3.toInt(), EventColorPalette.hexForName("MediumOrchid"))
        assertEquals(0xFFBA55D3.toInt(), EventColorPalette.hexForName("MEDIUMORCHID"))
        assertEquals(0xFFFF6347.toInt(), EventColorPalette.hexForName("TOMATO"))
    }

    @Test
    fun `hexForName returns null for unknown names`() {
        assertNull(EventColorPalette.hexForName(""))
        assertNull(EventColorPalette.hexForName("not a color"))
        assertNull(EventColorPalette.hexForName("rebeccapurple")) // CSS4, not in our wheel
    }

    @Test
    fun `nameForHex returns lowercase css3 name for grid palette entries`() {
        assertEquals("tomato", EventColorPalette.nameForHex(0xFFFF6347.toInt()))
        assertEquals("darkorange", EventColorPalette.nameForHex(0xFFFF8C00.toInt()))
        assertEquals("gold", EventColorPalette.nameForHex(0xFFFFD700.toInt()))
        assertEquals("mediumorchid", EventColorPalette.nameForHex(0xFFBA55D3.toInt()))
        assertEquals("dimgray", EventColorPalette.nameForHex(0xFF696969.toInt()))
    }

    @Test
    fun `nameForHex returns null for non-wheel hex values`() {
        assertNull(EventColorPalette.nameForHex(0xFF123456.toInt()))
    }

    @Test
    fun `grid palette round-trips cleanly`() {
        EventColorPalette.entries.forEach { entry ->
            val name = EventColorPalette.nameForHex(entry.argb)
            assertNotNull("nameForHex returned null for palette entry ${entry.name}", name)
            assertEquals(entry.argb, EventColorPalette.hexForName(name!!))
        }
    }

    @Test
    fun `every grid entry has a non-zero labelRes (typo guard)`() {
        EventColorPalette.entries.forEach { entry ->
            assertTrue(
                "labelRes for ${entry.name} is zero — did R.string.color_${entry.name} get deleted?",
                entry.labelRes != 0
            )
        }
    }

    @Test
    fun `every grid entry also exists in wheel palette (drift prevention)`() {
        val wheelHexes = EventColorPalette.allCss3Colors.map { it.argb }.toSet()
        EventColorPalette.entries.forEach { entry ->
            assertTrue(
                "Grid entry ${entry.name} (0x${entry.argb.toUInt().toString(16)}) " +
                    "missing from allCss3Colors",
                entry.argb in wheelHexes
            )
        }
    }

    // ========== Wheel palette (92 CSS3 colors grouped by family) ==========

    @Test
    fun `allCss3Colors contains 92 entries`() {
        assertEquals(92, EventColorPalette.allCss3Colors.size)
    }

    @Test
    fun `allCss3Colors names are unique and lowercase`() {
        val names = EventColorPalette.allCss3Colors.map { it.name }
        assertEquals("duplicate names in allCss3Colors", names.size, names.toSet().size)
        names.forEach { name ->
            assertEquals("name should be lowercase: $name", name.lowercase(), name)
        }
    }

    @Test
    fun `every HueFamily has at least one color`() {
        HueFamily.entries.forEach { family ->
            assertTrue(
                "HueFamily $family has no colors",
                EventColorPalette.colorsInFamily(family).isNotEmpty()
            )
        }
    }

    @Test
    fun `colorsInFamily returns only colors of that family`() {
        HueFamily.entries.forEach { family ->
            EventColorPalette.colorsInFamily(family).forEach { entry ->
                assertEquals(family, entry.family)
            }
        }
    }

    @Test
    fun `wheel nameForHex resolves extended css3 colors`() {
        // These are wheel-only (not in grid). Row label becomes "Custom" but
        // CalDAV emit still uses the CSS3 name.
        assertEquals("crimson", EventColorPalette.nameForHex(0xFFDC143C.toInt()))
        assertEquals("mediumpurple", EventColorPalette.nameForHex(0xFF9370DB.toInt()))
        assertEquals("teal", EventColorPalette.nameForHex(0xFF008080.toInt()))
        assertEquals("slateblue", EventColorPalette.nameForHex(0xFF6A5ACD.toInt()))
        assertEquals("sienna".let { null }, null) // sienna not in our 92-set
    }

    @Test
    fun `wheel hexForName resolves extended css3 names`() {
        assertEquals(0xFFDC143C.toInt(), EventColorPalette.hexForName("crimson"))
        assertEquals(0xFF008080.toInt(), EventColorPalette.hexForName("teal"))
        assertEquals(0xFF4682B4.toInt(), EventColorPalette.hexForName("steelblue"))
    }

    @Test
    fun `entryForArgb returns entry for wheel colors, null otherwise`() {
        val tomato = EventColorPalette.entryForArgb(0xFFFF6347.toInt())
        assertNotNull(tomato)
        assertEquals("tomato", tomato!!.name)
        assertEquals(HueFamily.RED, tomato.family)

        assertNull(EventColorPalette.entryForArgb(0xFF123456.toInt()))
    }

    // ========== resolveColorForFamily (wheel left-wheel-change logic) ==========

    @Test
    fun `resolveColorForFamily keeps current selection when family matches`() {
        // tomato is RED — if user is on tomato and RED is still selected, keep tomato
        val result = EventColorPalette.resolveColorForFamily(
            HueFamily.RED,
            currentArgb = 0xFFFF6347.toInt() // tomato
        )
        assertEquals("tomato", result.name)
    }

    @Test
    fun `resolveColorForFamily jumps to first color when family changes`() {
        // User is on tomato (RED), switches to GREEN — should return first GREEN entry
        val result = EventColorPalette.resolveColorForFamily(
            HueFamily.GREEN,
            currentArgb = 0xFFFF6347.toInt() // tomato (RED)
        )
        assertEquals(HueFamily.GREEN, result.family)
        assertEquals(EventColorPalette.colorsInFamily(HueFamily.GREEN).first(), result)
    }

    @Test
    fun `resolveColorForFamily handles null current selection`() {
        val result = EventColorPalette.resolveColorForFamily(
            HueFamily.BLUE,
            currentArgb = null
        )
        assertEquals(HueFamily.BLUE, result.family)
        assertEquals(EventColorPalette.colorsInFamily(HueFamily.BLUE).first(), result)
    }

    @Test
    fun `resolveColorForFamily handles non-wheel current argb`() {
        val result = EventColorPalette.resolveColorForFamily(
            HueFamily.PURPLE,
            currentArgb = 0xFF123456.toInt() // not in wheel
        )
        assertEquals(HueFamily.PURPLE, result.family)
        assertEquals(EventColorPalette.colorsInFamily(HueFamily.PURPLE).first(), result)
    }

    // ========== Row label resolution ==========

    @Test
    fun `stringResIdForColor returns label_calendar_default for null`() {
        assertEquals(R.string.label_calendar_default, EventColorPalette.stringResIdForColor(null))
    }

    @Test
    fun `stringResIdForColor returns color_name for grid palette hex`() {
        assertEquals(R.string.color_tomato, EventColorPalette.stringResIdForColor(0xFFFF6347.toInt()))
        assertEquals(R.string.color_mediumorchid, EventColorPalette.stringResIdForColor(0xFFBA55D3.toInt()))
        assertEquals(R.string.color_dimgray, EventColorPalette.stringResIdForColor(0xFF696969.toInt()))
    }

    @Test
    fun `stringResIdForColor returns label_custom for wheel-only colors`() {
        // crimson is in wheel but not in grid — row label falls back to Custom
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFFDC143C.toInt()))
        // teal was previously in grid (v23.6.0), now wheel-only
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF008080.toInt()))
    }

    @Test
    fun `stringResIdForColor returns label_custom for arbitrary hex`() {
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF123456.toInt()))
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF000000.toInt()))
    }
}
