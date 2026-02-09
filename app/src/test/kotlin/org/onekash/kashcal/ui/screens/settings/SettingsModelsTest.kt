package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Settings models.
 * Tests ICloudConnectionState, IcsSubscriptionUiModel, and FetchCalendarState.
 */
class SettingsModelsTest {

    // ==================== ICloudConnectionState Tests ====================

    @Test
    fun `ICloudConnectionState NotConnected has default empty fields`() {
        val state = ICloudConnectionState.NotConnected()

        assertEquals("", state.appleId)
        assertEquals("", state.password)
        assertFalse(state.showHelp)
        assertNull(state.error)
    }

    @Test
    fun `ICloudConnectionState NotConnected stores credentials`() {
        val state = ICloudConnectionState.NotConnected(
            appleId = "user@icloud.com",
            password = "secret123"
        )

        assertEquals("user@icloud.com", state.appleId)
        assertEquals("secret123", state.password)
    }

    @Test
    fun `ICloudConnectionState NotConnected stores error`() {
        val state = ICloudConnectionState.NotConnected(
            error = "Invalid credentials"
        )

        assertEquals("Invalid credentials", state.error)
    }

    @Test
    fun `ICloudConnectionState Connecting is singleton`() {
        val state1 = ICloudConnectionState.Connecting
        val state2 = ICloudConnectionState.Connecting

        assertEquals(state1, state2)
    }

    @Test
    fun `ICloudConnectionState Connected stores account info`() {
        val state = ICloudConnectionState.Connected(
            accountId = 1L,
            appleId = "user@icloud.com",
            lastSyncTime = 1704067200000L, // Jan 1, 2024
            calendarCount = 5
        )

        assertEquals("user@icloud.com", state.appleId)
        assertEquals(1704067200000L, state.lastSyncTime)
        assertEquals(5, state.calendarCount)
    }

    @Test
    fun `ICloudConnectionState Connected default values`() {
        val state = ICloudConnectionState.Connected(accountId = 1L, appleId = "test@icloud.com")

        assertEquals("test@icloud.com", state.appleId)
        assertNull(state.lastSyncTime)
        assertEquals(0, state.calendarCount)
    }

    @Test
    fun `ICloudConnectionState states are distinct types`() {
        val notConnected = ICloudConnectionState.NotConnected()
        val connecting = ICloudConnectionState.Connecting
        val connected = ICloudConnectionState.Connected(accountId = 1L, appleId = "test@icloud.com")

        assertTrue(notConnected is ICloudConnectionState.NotConnected)
        assertTrue(connecting is ICloudConnectionState.Connecting)
        assertTrue(connected is ICloudConnectionState.Connected)

        assertFalse(notConnected is ICloudConnectionState.Connected)
        assertFalse(connecting is ICloudConnectionState.Connected)
        assertFalse(connected is ICloudConnectionState.NotConnected)
    }

    // ==================== IcsSubscriptionUiModel Tests ====================

    @Test
    fun `IcsSubscriptionUiModel stores all fields`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/calendar.ics",
            name = "Work Calendar",
            color = 0xFF0000FF.toInt(),
            enabled = true,
            lastSync = 1704067200000L,
            eventTypeId = 100L,
            lastError = null,
            syncIntervalHours = 12
        )

        assertEquals(1L, subscription.id)
        assertEquals("https://example.com/calendar.ics", subscription.url)
        assertEquals("Work Calendar", subscription.name)
        assertEquals(0xFF0000FF.toInt(), subscription.color)
        assertTrue(subscription.enabled)
        assertEquals(1704067200000L, subscription.lastSync)
        assertEquals(100L, subscription.eventTypeId)
        assertNull(subscription.lastError)
        assertEquals(12, subscription.syncIntervalHours)
    }

    @Test
    fun `IcsSubscriptionUiModel has correct default values`() {
        val subscription = IcsSubscriptionUiModel(
            id = null,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFFFF0000.toInt()
        )

        assertNull(subscription.id)
        assertTrue(subscription.enabled)
        assertEquals(0L, subscription.lastSync)
        assertNull(subscription.eventTypeId)
        assertNull(subscription.lastError)
        assertEquals(24, subscription.syncIntervalHours)
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns true for non-blank error`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFF0000FF.toInt(),
            lastError = "Connection timeout"
        )

        assertTrue(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns false for null error`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFF0000FF.toInt(),
            lastError = null
        )

        assertFalse(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns false for blank error`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFF0000FF.toInt(),
            lastError = "   "
        )

        assertFalse(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel hasError returns false for empty error`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFF0000FF.toInt(),
            lastError = ""
        )

        assertFalse(subscription.hasError())
    }

    @Test
    fun `IcsSubscriptionUiModel can be disabled`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = 0xFF0000FF.toInt(),
            enabled = false
        )

        assertFalse(subscription.enabled)
    }

    // ==================== FetchCalendarState Tests ====================

    @Test
    fun `FetchCalendarState Idle is singleton`() {
        val state1 = FetchCalendarState.Idle
        val state2 = FetchCalendarState.Idle

        assertEquals(state1, state2)
    }

    @Test
    fun `FetchCalendarState Loading is singleton`() {
        val state1 = FetchCalendarState.Loading
        val state2 = FetchCalendarState.Loading

        assertEquals(state1, state2)
    }

    @Test
    fun `FetchCalendarState Success stores name and count`() {
        val state = FetchCalendarState.Success(
            name = "Work Calendar",
            eventCount = 150
        )

        assertEquals("Work Calendar", state.name)
        assertEquals(150, state.eventCount)
    }

    @Test
    fun `FetchCalendarState Error stores message`() {
        val state = FetchCalendarState.Error("HTTP 404: Not Found")

        assertEquals("HTTP 404: Not Found", state.message)
    }

    @Test
    fun `FetchCalendarState states are distinct types`() {
        val idle = FetchCalendarState.Idle
        val loading = FetchCalendarState.Loading
        val success = FetchCalendarState.Success("Test", 10)
        val error = FetchCalendarState.Error("Error")

        assertTrue(idle is FetchCalendarState.Idle)
        assertTrue(loading is FetchCalendarState.Loading)
        assertTrue(success is FetchCalendarState.Success)
        assertTrue(error is FetchCalendarState.Error)
    }

    @Test
    fun `FetchCalendarState Success can have zero events`() {
        val state = FetchCalendarState.Success("Empty Calendar", 0)

        assertEquals("Empty Calendar", state.name)
        assertEquals(0, state.eventCount)
    }

    // ==================== Subscription List Operations ====================

    @Test
    fun `subscription list can be filtered by enabled`() {
        val subscriptions = listOf(
            createTestSubscription(1L, "Work", enabled = true),
            createTestSubscription(2L, "Personal", enabled = false),
            createTestSubscription(3L, "Family", enabled = true)
        )

        val enabled = subscriptions.filter { it.enabled }

        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `subscription list can be filtered by errors`() {
        val subscriptions = listOf(
            createTestSubscription(1L, "Work", error = null),
            createTestSubscription(2L, "Personal", error = "Timeout"),
            createTestSubscription(3L, "Family", error = null)
        )

        val withErrors = subscriptions.filter { it.hasError() }

        assertEquals(1, withErrors.size)
        assertEquals("Personal", withErrors[0].name)
    }

    @Test
    fun `subscription can be found by id`() {
        val subscriptions = listOf(
            createTestSubscription(1L, "Work"),
            createTestSubscription(2L, "Personal"),
            createTestSubscription(3L, "Family")
        )

        val found = subscriptions.find { it.id == 2L }

        assertNotNull(found)
        assertEquals("Personal", found?.name)
    }

    // ==================== Helper Functions ====================

    private fun createTestSubscription(
        id: Long,
        name: String,
        enabled: Boolean = true,
        error: String? = null
    ): IcsSubscriptionUiModel {
        return IcsSubscriptionUiModel(
            id = id,
            url = "https://example.com/$name.ics",
            name = name,
            color = 0xFF0000FF.toInt(),
            enabled = enabled,
            lastError = error
        )
    }
}
