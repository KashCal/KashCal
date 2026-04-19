package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeableSubscriptionItemTest {

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
