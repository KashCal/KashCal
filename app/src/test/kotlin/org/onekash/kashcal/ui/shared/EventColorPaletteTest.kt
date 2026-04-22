package org.onekash.kashcal.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.R

class EventColorPaletteTest {

    @Test
    fun `hexForName returns argb for each palette entry`() {
        assertEquals(0xFFFF6347.toInt(), EventColorPalette.hexForName("tomato"))
        assertEquals(0xFFFF4500.toInt(), EventColorPalette.hexForName("orangered"))
        assertEquals(0xFFFF8C00.toInt(), EventColorPalette.hexForName("darkorange"))
        assertEquals(0xFFFFD700.toInt(), EventColorPalette.hexForName("gold"))
        assertEquals(0xFF9ACD32.toInt(), EventColorPalette.hexForName("yellowgreen"))
        assertEquals(0xFF32CD32.toInt(), EventColorPalette.hexForName("limegreen"))
        assertEquals(0xFF3CB371.toInt(), EventColorPalette.hexForName("mediumseagreen"))
        assertEquals(0xFF2E8B57.toInt(), EventColorPalette.hexForName("seagreen"))
        assertEquals(0xFF20B2AA.toInt(), EventColorPalette.hexForName("lightseagreen"))
        assertEquals(0xFF1E90FF.toInt(), EventColorPalette.hexForName("dodgerblue"))
        assertEquals(0xFF4169E1.toInt(), EventColorPalette.hexForName("royalblue"))
        assertEquals(0xFF7B68EE.toInt(), EventColorPalette.hexForName("mediumslateblue"))
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
        assertNull(EventColorPalette.hexForName("chartreuse"))
        assertNull(EventColorPalette.hexForName(""))
        assertNull(EventColorPalette.hexForName("not a color"))
    }

    @Test
    fun `hexForName returns null for removed legacy palette names`() {
        // Colors dropped from the palette in v23.7 (replaced by Google-curated equivalents).
        // Events storing these hex values parse as "Custom" and emit as hex on push.
        assertNull(EventColorPalette.hexForName("teal"))
        assertNull(EventColorPalette.hexForName("steelblue"))
        assertNull(EventColorPalette.hexForName("slateblue"))
        assertNull(EventColorPalette.hexForName("slategray"))
    }

    @Test
    fun `nameForHex returns lowercase css3 name for each palette entry`() {
        assertEquals("tomato", EventColorPalette.nameForHex(0xFFFF6347.toInt()))
        assertEquals("orangered", EventColorPalette.nameForHex(0xFFFF4500.toInt()))
        assertEquals("darkorange", EventColorPalette.nameForHex(0xFFFF8C00.toInt()))
        assertEquals("gold", EventColorPalette.nameForHex(0xFFFFD700.toInt()))
        assertEquals("yellowgreen", EventColorPalette.nameForHex(0xFF9ACD32.toInt()))
        assertEquals("limegreen", EventColorPalette.nameForHex(0xFF32CD32.toInt()))
        assertEquals("mediumseagreen", EventColorPalette.nameForHex(0xFF3CB371.toInt()))
        assertEquals("seagreen", EventColorPalette.nameForHex(0xFF2E8B57.toInt()))
        assertEquals("lightseagreen", EventColorPalette.nameForHex(0xFF20B2AA.toInt()))
        assertEquals("dodgerblue", EventColorPalette.nameForHex(0xFF1E90FF.toInt()))
        assertEquals("royalblue", EventColorPalette.nameForHex(0xFF4169E1.toInt()))
        assertEquals("mediumslateblue", EventColorPalette.nameForHex(0xFF7B68EE.toInt()))
        assertEquals("mediumorchid", EventColorPalette.nameForHex(0xFFBA55D3.toInt()))
        assertEquals("hotpink", EventColorPalette.nameForHex(0xFFFF69B4.toInt()))
        assertEquals("dimgray", EventColorPalette.nameForHex(0xFF696969.toInt()))
    }

    @Test
    fun `nameForHex returns null for non-palette hex`() {
        assertNull(EventColorPalette.nameForHex(0xFF123456.toInt()))
        assertNull(EventColorPalette.nameForHex(0xFF000000.toInt()))
        assertNull(EventColorPalette.nameForHex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `nameForHex returns null for removed legacy palette hex`() {
        // Previously-palette hex values no longer resolve to CSS3 names.
        // CalDAV emit will fall back to #RRGGBB for these.
        assertNull(EventColorPalette.nameForHex(0xFF008080.toInt())) // teal
        assertNull(EventColorPalette.nameForHex(0xFF4682B4.toInt())) // steelblue
        assertNull(EventColorPalette.nameForHex(0xFF6A5ACD.toInt())) // slateblue
        assertNull(EventColorPalette.nameForHex(0xFF708090.toInt())) // slategray
    }

    @Test
    fun `round-trip invariant for every palette slot`() {
        EventColorPalette.entries.forEach { entry ->
            val name = EventColorPalette.nameForHex(entry.argb)
            assertNotNull("nameForHex returned null for palette entry ${entry.name}", name)
            assertEquals(entry.argb, EventColorPalette.hexForName(name!!))
        }
    }

    @Test
    fun `entries list has 15 ordered palette entries`() {
        assertEquals(15, EventColorPalette.entries.size)
        assertEquals("tomato", EventColorPalette.entries[0].name)
        assertEquals("dimgray", EventColorPalette.entries[14].name)
    }

    @Test
    fun `stringResIdForColor returns label_calendar_default for null`() {
        assertEquals(R.string.label_calendar_default, EventColorPalette.stringResIdForColor(null))
    }

    @Test
    fun `stringResIdForColor returns color_name for palette hex`() {
        assertEquals(R.string.color_tomato, EventColorPalette.stringResIdForColor(0xFFFF6347.toInt()))
        assertEquals(R.string.color_mediumorchid, EventColorPalette.stringResIdForColor(0xFFBA55D3.toInt()))
        assertEquals(R.string.color_dimgray, EventColorPalette.stringResIdForColor(0xFF696969.toInt()))
    }

    @Test
    fun `stringResIdForColor returns label_custom for non-palette hex`() {
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF123456.toInt()))
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF000000.toInt()))
        // Legacy palette colors (teal/steelblue/slateblue/slategray) now fall through to Custom
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF008080.toInt()))
    }
}
