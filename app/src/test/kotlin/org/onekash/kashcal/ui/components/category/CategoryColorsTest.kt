package org.onekash.kashcal.ui.components.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [colorForTag] and [onColorFor]. The tag color tests assert determinism, case
 * insensitivity, palette membership and diversity, never a specific palette color.
 */
class CategoryColorsTest {

    @Test
    fun `same name yields the same color`() {
        assertEquals(colorForTag("work"), colorForTag("work"))
    }

    @Test
    fun `color is case-insensitive`() {
        // Case-insensitive dedup makes Work and work one tag, so one color.
        assertEquals(colorForTag("Work"), colorForTag("work"))
        assertEquals(colorForTag("WORK"), colorForTag("work"))
    }

    @Test
    fun `color is drawn from the palette`() {
        assertTrue(colorForTag("anything") in CATEGORY_PALETTE)
    }

    @Test
    fun `different names generally differ`() {
        // No specific pair must differ, but a spread of names gives more than one color.
        val distinct = listOf("work", "personal", "family", "travel", "health", "finance", "focus")
            .map { colorForTag(it) }
            .distinct()
        assertTrue("expected palette diversity across names", distinct.size > 1)
    }

    @Test
    fun `on-color for a dark background is light`() {
        // Near-black 0xFF202020 takes white.
        assertEquals(0xFFFFFFFF.toInt(), onColorFor(0xFF202020.toInt()))
    }

    @Test
    fun `on-color for a light background is dark`() {
        // Near-white 0xFFF0F0F0 takes black.
        assertEquals(0xFF000000.toInt(), onColorFor(0xFFF0F0F0.toInt()))
    }

    @Test
    fun `on-color differs between a palette color and its inverse extreme`() {
        // Black and white backgrounds get different on-colors.
        assertNotEquals(onColorFor(0xFF000000.toInt()), onColorFor(0xFFFFFFFF.toInt()))
    }
}
