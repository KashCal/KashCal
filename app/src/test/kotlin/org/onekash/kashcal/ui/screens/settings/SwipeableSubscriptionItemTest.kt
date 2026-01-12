package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SwipeableSubscriptionItem.
 *
 * Note: Composable UI interaction tests (swipe, click, toggle) require
 * AndroidX Compose testing which runs as instrumented tests.
 * These unit tests verify the supporting logic and data transformations.
 */
class SwipeableSubscriptionItemTest {

    // ==================== formatLastSyncTime Tests ====================

    @Test
    fun `formatLastSyncTime returns Not synced when lastSync is 0`() {
        assertEquals("Not synced", formatLastSyncTime(0))
    }

    @Test
    fun `formatLastSyncTime returns Not synced when lastSync is negative`() {
        assertEquals("Not synced", formatLastSyncTime(-1))
    }

    @Test
    fun `formatLastSyncTime returns minutes ago for recent sync`() {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        val result = formatLastSyncTime(fiveMinutesAgo)
        assertTrue("Expected '5m ago' but got '$result'", result.contains("m ago"))
    }

    @Test
    fun `formatLastSyncTime returns hours ago for older sync`() {
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
        val result = formatLastSyncTime(twoHoursAgo)
        assertTrue("Expected 'h ago' but got '$result'", result.contains("h ago"))
    }

    @Test
    fun `formatLastSyncTime returns 0m ago for just now`() {
        val justNow = System.currentTimeMillis()
        val result = formatLastSyncTime(justNow)
        assertEquals("0m ago", result)
    }

    @Test
    fun `formatLastSyncTime returns minutes for under 60 minutes`() {
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000)
        val result = formatLastSyncTime(thirtyMinutesAgo)
        assertTrue("Expected '30m ago' but got '$result'", result.endsWith("m ago"))
    }

    @Test
    fun `formatLastSyncTime returns 1h ago at exactly 60 minutes`() {
        val sixtyMinutesAgo = System.currentTimeMillis() - (60 * 60 * 1000)
        val result = formatLastSyncTime(sixtyMinutesAgo)
        assertEquals("1h ago", result)
    }

    // ==================== IcsSubscriptionUiModel Tests ====================

    @Test
    fun `IcsSubscriptionUiModel hasError returns true when lastError is set`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            enabled = true,
            lastSync = System.currentTimeMillis(),
            lastError = "Network error"
        )
        assertTrue(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns false when lastError is null`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            enabled = true,
            lastSync = System.currentTimeMillis(),
            lastError = null
        )
        assertFalse(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns false when lastError is empty`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Test Calendar",
            color = 0xFF2196F3.toInt(),
            enabled = true,
            lastSync = System.currentTimeMillis(),
            lastError = ""
        )
        // Empty string is considered no error (though unlikely in practice)
        assertFalse(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel default values`() {
        val subscription = IcsSubscriptionUiModel(
            id = null,
            url = "https://example.com/calendar.ics",
            name = "Test",
            color = SubscriptionColors.default
        )
        assertEquals(null, subscription.id)
        assertEquals(SubscriptionColors.default, subscription.color)
        assertTrue(subscription.enabled)
        assertEquals(0L, subscription.lastSync)
        assertEquals(null, subscription.lastError)
        assertEquals(null, subscription.eventTypeId)
        assertEquals(24, subscription.syncIntervalHours)
    }

    @Test
    fun `IcsSubscriptionUiModel stores all properties correctly`() {
        val subscription = IcsSubscriptionUiModel(
            id = 42L,
            url = "https://example.com/cal.ics",
            name = "My Calendar",
            color = 0xFF4CAF50.toInt(),
            enabled = false,
            lastSync = 1704067200000L,
            lastError = "Connection timeout",
            eventTypeId = 100L,
            syncIntervalHours = 6
        )

        assertEquals(42L, subscription.id)
        assertEquals("https://example.com/cal.ics", subscription.url)
        assertEquals("My Calendar", subscription.name)
        assertEquals(0xFF4CAF50.toInt(), subscription.color)
        assertFalse(subscription.enabled)
        assertEquals(1704067200000L, subscription.lastSync)
        assertEquals("Connection timeout", subscription.lastError)
        assertEquals(100L, subscription.eventTypeId)
        assertEquals(6, subscription.syncIntervalHours)
    }
}
