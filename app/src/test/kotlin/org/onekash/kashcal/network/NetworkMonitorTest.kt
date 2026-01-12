package org.onekash.kashcal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NetworkMonitor.
 *
 * Tests:
 * - Initial state detection
 * - Connectivity callbacks
 * - State flow updates
 * - Monitoring lifecycle
 */
class NetworkMonitorTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial state is online when network available`() = runTest {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true

        // When
        val monitor = NetworkMonitor(context)

        // Then
        assertTrue(monitor.isOnline.value)
        assertFalse(monitor.isMetered.value)
    }

    @Test
    fun `initial state is offline when no network`() = runTest {
        // Given
        every { connectivityManager.activeNetwork } returns null

        // When
        val monitor = NetworkMonitor(context)

        // Then
        assertFalse(monitor.isOnline.value)
    }

    @Test
    fun `initial state is offline when no capabilities`() = runTest {
        // Given
        val network = mockk<Network>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns null

        // When
        val monitor = NetworkMonitor(context)

        // Then
        assertFalse(monitor.isOnline.value)
    }

    @Test
    fun `initial state is metered on cellular`() = runTest {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns false

        // When
        val monitor = NetworkMonitor(context)

        // Then
        assertTrue(monitor.isOnline.value)
        assertTrue(monitor.isMetered.value)
    }

    // ==================== checkCurrentConnectivity Tests ====================

    @Test
    fun `checkCurrentConnectivity returns true when connected`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        val monitor = NetworkMonitor(context)

        // When
        val result = monitor.checkCurrentConnectivity()

        // Then
        assertTrue(result)
    }

    @Test
    fun `checkCurrentConnectivity returns false when no network`() {
        // Given
        every { connectivityManager.activeNetwork } returns null

        val monitor = NetworkMonitor(context)

        // When
        val result = monitor.checkCurrentConnectivity()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkCurrentConnectivity returns false when internet not validated`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        val monitor = NetworkMonitor(context)

        // When
        val result = monitor.checkCurrentConnectivity()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkCurrentConnectivity returns true if ConnectivityManager unavailable`() {
        // Given - ConnectivityManager is null (safety fallback)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

        val monitor = NetworkMonitor(context)

        // When
        val result = monitor.checkCurrentConnectivity()

        // Then - Assume online to allow sync attempts
        assertTrue(result)
    }

    // ==================== Monitoring Lifecycle Tests ====================

    @Test
    fun `startMonitoring registers callback`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true
        every { connectivityManager.registerDefaultNetworkCallback(any()) } just Runs

        val monitor = NetworkMonitor(context)

        // When
        monitor.startMonitoring()

        // Then
        assertTrue(monitor.isMonitoring())
        verify { connectivityManager.registerDefaultNetworkCallback(any()) }
    }

    @Test
    fun `startMonitoring ignores duplicate calls`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true
        every { connectivityManager.registerDefaultNetworkCallback(any()) } just Runs

        val monitor = NetworkMonitor(context)
        monitor.startMonitoring()

        // When - call again
        monitor.startMonitoring()

        // Then - should only register once
        verify(exactly = 1) { connectivityManager.registerDefaultNetworkCallback(any()) }
    }

    @Test
    fun `stopMonitoring unregisters callback`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true
        every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs

        val monitor = NetworkMonitor(context)
        monitor.startMonitoring()
        assertTrue(monitor.isMonitoring())

        // When
        monitor.stopMonitoring()

        // Then
        assertFalse(monitor.isMonitoring())
        verify { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `stopMonitoring is safe when not started`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true

        val monitor = NetworkMonitor(context)
        assertFalse(monitor.isMonitoring())

        // When - stop without starting
        monitor.stopMonitoring()

        // Then - should not crash
        assertFalse(monitor.isMonitoring())
        verify(exactly = 0) { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    // ==================== Network Restore Callback Tests ====================

    @Test
    fun `setOnNetworkRestored stores callback`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true

        val monitor = NetworkMonitor(context)
        var callbackInvoked = false

        // When
        monitor.setOnNetworkRestored { callbackInvoked = true }

        // Then - callback is stored (will be invoked when network restored)
        assertFalse(callbackInvoked) // Not invoked yet
    }

    // ==================== StateFlow Tests ====================

    @Test
    fun `isOnline StateFlow reflects current state`() = runTest {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true

        val monitor = NetworkMonitor(context)

        // When
        val currentState = monitor.isOnline.first()

        // Then
        assertTrue(currentState)
    }

    @Test
    fun `isMetered StateFlow reflects metered state`() = runTest {
        // Given - cellular network
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns false

        val monitor = NetworkMonitor(context)

        // When
        val isMetered = monitor.isMetered.first()

        // Then
        assertTrue(isMetered)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `handles getSystemService exception gracefully`() {
        // Given
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } throws RuntimeException("Service unavailable")

        // When
        val monitor = NetworkMonitor(context)

        // Then - should assume online (safety fallback)
        assertTrue(monitor.isOnline.value)
    }

    @Test
    fun `handles registerNetworkCallback exception gracefully`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } returns true
        every { connectivityManager.registerDefaultNetworkCallback(any()) } throws SecurityException("Permission denied")

        val monitor = NetworkMonitor(context)

        // When
        monitor.startMonitoring()

        // Then - should not crash, monitoring not active
        assertFalse(monitor.isMonitoring())
    }
}
