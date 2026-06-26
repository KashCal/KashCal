package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [avatarColorIndex] — the deterministic
 * hash(address) % palette mapping that keeps each person's avatar colour
 * stable across the picker list and the form chips.
 */
class AvatarColorTest {

    @Test
    fun `index is within palette range`() {
        val addresses = listOf("alice@example.com", "bob@x.test", "carol@y.test", "d@z.test")
        addresses.forEach {
            val i = avatarColorIndex(it, paletteSize = 6)
            assertTrue("index $i out of range", i in 0 until 6)
        }
    }

    @Test
    fun `same address yields the same index`() {
        assertEquals(
            avatarColorIndex("alice@example.com", paletteSize = 6),
            avatarColorIndex("alice@example.com", paletteSize = 6),
        )
    }

    @Test
    fun `index is canonical - mailto and case do not change it`() {
        val a = avatarColorIndex("mailto:Alice@Example.com", paletteSize = 6)
        val b = avatarColorIndex("alice@example.com", paletteSize = 6)
        assertEquals(a, b)
    }

    @Test
    fun `distinct addresses spread across more than one index`() {
        val indices = (0 until 30)
            .map { avatarColorIndex("user$it@example.com", paletteSize = 6) }
            .toSet()
        // A constant or degenerate hash would collapse to one bucket.
        assertTrue("expected spread across buckets, got $indices", indices.size >= 3)
    }

    @Test
    fun `index is non-negative even when hash is Int MIN-like`() {
        // Guards against the abs(Int.MIN_VALUE) overflow trap.
        (0 until 200).forEach {
            val i = avatarColorIndex("collision-probe-$it@example.com", paletteSize = 6)
            assertTrue(i >= 0)
        }
    }

    // ---- avatarInitials ----

    @Test
    fun `initials use first and last word for a full name`() {
        assertEquals("AC", avatarInitials("Alice Chen"))
        assertEquals("AB", avatarInitials("Alice Mary Brooks"))
    }

    @Test
    fun `initials use the single letter for a one-word name`() {
        assertEquals("A", avatarInitials("Alice"))
    }

    @Test
    fun `initials fall back to question mark for blank`() {
        assertEquals("?", avatarInitials("   "))
    }

    @Test
    fun `the You marker yields different initials than the person's name`() {
        // Pins why AttendeePickChip must compute initials from the real name
        // (initialsSource), not the visible "You" label: otherwise self's
        // avatar would read the initial of the word "You" instead of "AC".
        val personInitials = avatarInitials("Alice Chen")
        val youInitials = avatarInitials("You")
        assertEquals("AC", personInitials)
        assertEquals("Y", youInitials)
        assertTrue("initials must differ so the source matters", personInitials != youInitials)
    }
}
