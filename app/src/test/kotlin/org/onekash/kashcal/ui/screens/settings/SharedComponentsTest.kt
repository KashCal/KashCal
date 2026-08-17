package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SharedComponents.
 * Tests SectionHeader, SettingsCard, and related utility functions.
 *
 * Note: Composable UI tests require AndroidX Compose testing which runs as
 * instrumented tests. These unit tests verify the supporting logic and data.
 */
class SharedComponentsTest {

    // ==================== AccentColors Tests ====================

    @Test
    fun `AccentColors success light has correct value`() {
        // System green: #34C759
        assertEquals(0xFF34C759.toInt().toLong(), AccentColors.SuccessLight.value.toLong() shr 32)
    }

    @Test
    fun `AccentColors success dark has correct value`() {
        // Brighter green for dark surfaces: #30D158
        assertEquals(0xFF30D158.toInt().toLong(), AccentColors.SuccessDark.value.toLong() shr 32)
    }

    // ==================== SubscriptionColors Tests ====================

    @Test
    fun `SubscriptionColors all contains 5 colors`() {
        assertEquals(5, SubscriptionColors.all.size)
    }

    @Test
    fun `SubscriptionColors default is Blue`() {
        assertEquals(SubscriptionColors.Blue, SubscriptionColors.default)
    }

    @Test
    fun `SubscriptionColors all contains default color`() {
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.default))
    }

    @Test
    fun `SubscriptionColors all colors are unique`() {
        val uniqueColors = SubscriptionColors.all.toSet()
        assertEquals(SubscriptionColors.all.size, uniqueColors.size)
    }

    // ==================== SyncIntervalOption Tests ====================

    @Test
    fun `subscriptionSyncIntervalOptions has 5 options`() {
        assertEquals(5, subscriptionSyncIntervalOptions.size)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes hourly`() {
        val hourly = subscriptionSyncIntervalOptions.find { it.hours == 1 }
        assertNotNull(hourly)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes daily`() {
        val daily = subscriptionSyncIntervalOptions.find { it.hours == 24 }
        assertNotNull(daily)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes weekly`() {
        val weekly = subscriptionSyncIntervalOptions.find { it.hours == 168 }
        assertNotNull(weekly)
    }

    @Test
    fun `normalizeSubscriptionUrl converts webcal to https`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("webcal://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl converts webcals to https`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("webcals://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl preserves https URLs`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("https://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl trims whitespace`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("  https://example.com/calendar.ics  ")
        )
    }
}
