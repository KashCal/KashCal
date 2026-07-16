package org.onekash.kashcal.ui.components.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the tag color helper. These assert *determinism* and
 * *diversity*, never a specific literal color — `String.hashCode()` is stable
 * within a JVM but not guaranteed identical across platforms, so pinning an
 * exact palette index would be a false-confidence test.
 */
class CategoryColorsTest {

    @Test
    fun `same name yields the same color`() {
        assertEquals(colorForTag("work"), colorForTag("work"))
    }

    @Test
    fun `color is case-insensitive`() {
        // Case-insensitive dedup means Work and work are one tag -> one color.
        assertEquals(colorForTag("Work"), colorForTag("work"))
        assertEquals(colorForTag("WORK"), colorForTag("work"))
    }

    @Test
    fun `color is drawn from the palette`() {
        assertTrue(colorForTag("anything") in CATEGORY_PALETTE)
    }

    @Test
    fun `different names generally differ`() {
        // Not a guarantee for any specific pair, but across a spread of names
        // we expect more than one distinct color to be produced.
        val distinct = listOf("work", "personal", "family", "travel", "health", "finance", "focus")
            .map { colorForTag(it) }
            .distinct()
        assertTrue("expected palette diversity across names", distinct.size > 1)
    }

    @Test
    fun `on-color for a dark background is light`() {
        // 0xFF202020 is near-black -> foreground should be white.
        assertEquals(0xFFFFFFFF.toInt(), onColorFor(0xFF202020.toInt()))
    }

    @Test
    fun `on-color for a light background is dark`() {
        // 0xFFF0F0F0 is near-white -> foreground should be black.
        assertEquals(0xFF000000.toInt(), onColorFor(0xFFF0F0F0.toInt()))
    }

    @Test
    fun `on-color differs between a palette color and its inverse extreme`() {
        // Sanity: the two on-color outcomes are not identical.
        assertNotEquals(onColorFor(0xFF000000.toInt()), onColorFor(0xFFFFFFFF.toInt()))
    }
}
