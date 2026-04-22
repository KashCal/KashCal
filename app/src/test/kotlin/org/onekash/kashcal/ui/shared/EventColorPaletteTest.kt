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
        assertEquals(0xFFFF8C00.toInt(), EventColorPalette.hexForName("darkorange"))
        assertEquals(0xFFFFD700.toInt(), EventColorPalette.hexForName("gold"))
        assertEquals(0xFF9ACD32.toInt(), EventColorPalette.hexForName("yellowgreen"))
        assertEquals(0xFF3CB371.toInt(), EventColorPalette.hexForName("mediumseagreen"))
        assertEquals(0xFF008080.toInt(), EventColorPalette.hexForName("teal"))
        assertEquals(0xFF4682B4.toInt(), EventColorPalette.hexForName("steelblue"))
        assertEquals(0xFF6A5ACD.toInt(), EventColorPalette.hexForName("slateblue"))
        assertEquals(0xFFBA55D3.toInt(), EventColorPalette.hexForName("mediumorchid"))
        assertEquals(0xFFFF69B4.toInt(), EventColorPalette.hexForName("hotpink"))
        assertEquals(0xFF708090.toInt(), EventColorPalette.hexForName("slategray"))
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
    fun `nameForHex returns lowercase css3 name for each palette entry`() {
        assertEquals("tomato", EventColorPalette.nameForHex(0xFFFF6347.toInt()))
        assertEquals("darkorange", EventColorPalette.nameForHex(0xFFFF8C00.toInt()))
        assertEquals("gold", EventColorPalette.nameForHex(0xFFFFD700.toInt()))
        assertEquals("yellowgreen", EventColorPalette.nameForHex(0xFF9ACD32.toInt()))
        assertEquals("mediumseagreen", EventColorPalette.nameForHex(0xFF3CB371.toInt()))
        assertEquals("teal", EventColorPalette.nameForHex(0xFF008080.toInt()))
        assertEquals("steelblue", EventColorPalette.nameForHex(0xFF4682B4.toInt()))
        assertEquals("slateblue", EventColorPalette.nameForHex(0xFF6A5ACD.toInt()))
        assertEquals("mediumorchid", EventColorPalette.nameForHex(0xFFBA55D3.toInt()))
        assertEquals("hotpink", EventColorPalette.nameForHex(0xFFFF69B4.toInt()))
        assertEquals("slategray", EventColorPalette.nameForHex(0xFF708090.toInt()))
    }

    @Test
    fun `nameForHex returns null for non-palette hex`() {
        assertNull(EventColorPalette.nameForHex(0xFF123456.toInt()))
        assertNull(EventColorPalette.nameForHex(0xFF000000.toInt()))
        assertNull(EventColorPalette.nameForHex(0xFFFFFFFF.toInt()))
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
    fun `entries list has 11 ordered palette entries`() {
        assertEquals(11, EventColorPalette.entries.size)
        assertEquals("tomato", EventColorPalette.entries[0].name)
        assertEquals("slategray", EventColorPalette.entries[10].name)
    }

    @Test
    fun `stringResIdForColor returns label_calendar_default for null`() {
        assertEquals(R.string.label_calendar_default, EventColorPalette.stringResIdForColor(null))
    }

    @Test
    fun `stringResIdForColor returns color_name for palette hex`() {
        assertEquals(R.string.color_tomato, EventColorPalette.stringResIdForColor(0xFFFF6347.toInt()))
        assertEquals(R.string.color_mediumorchid, EventColorPalette.stringResIdForColor(0xFFBA55D3.toInt()))
        assertEquals(R.string.color_slategray, EventColorPalette.stringResIdForColor(0xFF708090.toInt()))
    }

    @Test
    fun `stringResIdForColor returns label_custom for non-palette hex`() {
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF123456.toInt()))
        assertEquals(R.string.label_custom, EventColorPalette.stringResIdForColor(0xFF000000.toInt()))
    }
}
