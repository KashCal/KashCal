package org.onekash.kashcal.ui.components.category

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [colorFor], the resolver behind [LocalTagColors], on the plain JVM: a custom color in
 * the map wins, matched case-insensitively; a null entry, a missing key or an empty map falls
 * back to [colorForTag].
 */
class LocalTagColorsTest {

    @Test
    fun `custom color in the map wins`() {
        val custom = 0xFF123456.toInt()
        assertEquals(custom, colorFor(mapOf("Work" to custom), "Work"))
    }

    @Test
    fun `null entry falls back to the hash color`() {
        assertEquals(colorForTag("Work"), colorFor(mapOf("Work" to null), "Work"))
    }

    @Test
    fun `missing key falls back to the hash color`() {
        assertEquals(colorForTag("Personal"), colorFor(mapOf("Work" to 0xFF123456.toInt()), "Personal"))
    }

    @Test
    fun `empty map always falls back to the hash color`() {
        assertEquals(colorForTag("Anything"), colorFor(emptyMap(), "Anything"))
    }

    @Test
    fun `custom color resolves when the lookup casing differs from the stored key`() {
        // The row is stored as "Work", its first-seen casing, but a chip resolves against an
        // event carrying "work"; the custom color still applies.
        val custom = 0xFF123456.toInt()
        assertEquals(custom, colorFor(mapOf("Work" to custom), "work"))
        assertEquals(custom, colorFor(mapOf("work" to custom), "WORK"))
    }

    @Test
    fun `case-insensitive fallback still yields the hash color for a null entry`() {
        // A differently-cased key with no custom color must fall back, not miss.
        assertEquals(colorForTag("work"), colorFor(mapOf("Work" to null), "work"))
    }
}
