package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [formatBadgeCount], which sets the badge text on both the top-bar
 * account button and the account hub's Invites row: non-positive counts
 * give null (no badge), 1..99 render verbatim, anything larger renders
 * "99+".
 */
class BadgeCountFormatterTest {

    @Test
    fun zero_returnsNull() {
        assertNull(formatBadgeCount(0))
    }

    @Test
    fun negative_returnsNull() {
        assertNull(formatBadgeCount(-3))
    }

    @Test
    fun one_returnsSingleDigit() {
        assertEquals("1", formatBadgeCount(1))
    }

    @Test
    fun ninetyNine_returnsTwoDigits() {
        assertEquals("99", formatBadgeCount(99))
    }

    @Test
    fun oneHundred_returnsCappedString() {
        assertEquals("99+", formatBadgeCount(100))
    }

    @Test
    fun largeCount_returnsCappedString() {
        assertEquals("99+", formatBadgeCount(9999))
    }
}
