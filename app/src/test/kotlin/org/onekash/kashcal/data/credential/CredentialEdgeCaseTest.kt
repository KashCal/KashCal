package org.onekash.kashcal.data.credential

import android.content.Context
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Edge case tests for credential storage and AccountCredentials data class.
 *
 * Tests empty/boundary field values, sequential operation consistency,
 * idempotent delete/clear, discovery URL update patterns, and data class
 * destructuring.
 *
 * Complements UnifiedCredentialManagerTest (25 tests) and
 * CredentialMigrationTest (12 tests).
 */
class CredentialEdgeCaseTest {

    private lateinit var context: Context
    private lateinit var credentialManager: UnifiedCredentialManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        credentialManager = UnifiedCredentialManager(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== AccountCredentials Empty/Boundary Values ==========

    @Test
    fun `AccountCredentials with empty username`() {
        val cred = AccountCredentials(
            username = "",
            password = "pass",
            serverUrl = "https://server.com"
        )
        assertEquals("", cred.username)
    }

    @Test
    fun `AccountCredentials with empty password`() {
        val cred = AccountCredentials(
            username = "user",
            password = "",
            serverUrl = "https://server.com"
        )
        assertEquals("", cred.password)
    }

    @Test
    fun `AccountCredentials with empty serverUrl`() {
        val cred = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = ""
        )
        assertEquals("", cred.serverUrl)
    }

    @Test
    fun `AccountCredentials with all empty strings`() {
        val cred = AccountCredentials(
            username = "",
            password = "",
            serverUrl = ""
        )
        assertEquals("", cred.username)
        assertEquals("", cred.password)
        assertEquals("", cred.serverUrl)
        assertFalse(cred.trustInsecure)
        assertNull(cred.principalUrl)
        assertNull(cred.calendarHomeSet)
    }

    @Test
    fun `AccountCredentials with whitespace-only values`() {
        val cred = AccountCredentials(
            username = "   ",
            password = "\t\n",
            serverUrl = " "
        )
        assertEquals("   ", cred.username)
        assertEquals("\t\n", cred.password)
        assertEquals(" ", cred.serverUrl)
    }

    // ========== AccountCredentials Destructuring ==========

    @Test
    fun `AccountCredentials destructuring extracts all components`() {
        val cred = AccountCredentials(
            username = "user@example.com",
            password = "secret",
            serverUrl = "https://caldav.example.com",
            trustInsecure = true,
            principalUrl = "https://caldav.example.com/principal/",
            calendarHomeSet = "https://caldav.example.com/calendars/"
        )

        val (username, password, serverUrl, trustInsecure, principalUrl, calendarHomeSet) = cred

        assertEquals("user@example.com", username)
        assertEquals("secret", password)
        assertEquals("https://caldav.example.com", serverUrl)
        assertTrue(trustInsecure)
        assertEquals("https://caldav.example.com/principal/", principalUrl)
        assertEquals("https://caldav.example.com/calendars/", calendarHomeSet)
    }

    // ========== AccountCredentials Discovery URL Patterns ==========

    @Test
    fun `AccountCredentials copy with discovery URLs simulates post-discovery update`() {
        // Initial credentials before discovery
        val initial = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://caldav.example.com"
        )
        assertNull(initial.principalUrl)
        assertNull(initial.calendarHomeSet)

        // After discovery, update with discovered URLs
        val discovered = initial.copy(
            principalUrl = "https://caldav.example.com/principals/user/",
            calendarHomeSet = "https://caldav.example.com/calendars/user/"
        )

        // Original unchanged
        assertNull(initial.principalUrl)
        // Discovered has URLs
        assertEquals("https://caldav.example.com/principals/user/", discovered.principalUrl)
        assertEquals("https://caldav.example.com/calendars/user/", discovered.calendarHomeSet)
        // Original fields preserved
        assertEquals(initial.username, discovered.username)
        assertEquals(initial.password, discovered.password)
        assertEquals(initial.serverUrl, discovered.serverUrl)
    }

    @Test
    fun `AccountCredentials copy with only principalUrl updated`() {
        val cred = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com",
            principalUrl = "https://server.com/old-principal/"
        )

        val updated = cred.copy(principalUrl = "https://server.com/new-principal/")

        assertEquals("https://server.com/new-principal/", updated.principalUrl)
        assertNull(updated.calendarHomeSet) // Still null
    }

    @Test
    fun `AccountCredentials URL with IP address and port`() {
        val cred = AccountCredentials(
            username = "admin",
            password = "pass",
            serverUrl = "https://192.168.1.100:5232/radicale/"
        )
        assertEquals("https://192.168.1.100:5232/radicale/", cred.serverUrl)
    }

    @Test
    fun `AccountCredentials URL without trailing slash`() {
        val cred = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://caldav.example.com"
        )
        assertFalse(cred.serverUrl.endsWith("/"))
    }

    // ========== CredentialManager Sequential Operations (Graceful Degradation) ==========

    @Test
    fun `getCredentials for non-existent account returns null`() = runBlocking {
        val result = credentialManager.getCredentials(999L)
        assertNull("Non-existent account should return null", result)
    }

    @Test
    fun `hasCredentials returns false for non-existent account`() = runBlocking {
        val result = credentialManager.hasCredentials(999L)
        assertFalse("Non-existent account should return false", result)
    }

    @Test
    fun `deleteCredentials for non-existent account does not throw`() = runBlocking {
        // Should complete without exception
        credentialManager.deleteCredentials(999L)
    }

    @Test
    fun `consecutive deleteCredentials same account is idempotent`() = runBlocking {
        // Should not throw on repeated delete
        credentialManager.deleteCredentials(1L)
        credentialManager.deleteCredentials(1L)
        credentialManager.deleteCredentials(1L)
    }

    @Test
    fun `consecutive clearAllCredentials is idempotent`() = runBlocking {
        credentialManager.clearAllCredentials()
        credentialManager.clearAllCredentials()
        // Should not throw
    }

    @Test
    fun `save then delete then get returns null`() = runBlocking {
        val cred = AccountCredentials("user", "pass", "https://server.com")

        credentialManager.saveCredentials(1L, cred)
        credentialManager.deleteCredentials(1L)
        val result = credentialManager.getCredentials(1L)

        // In test env, encryption unavailable so all return null/false anyway
        // But the operation sequence should not throw
        assertNull(result)
    }

    @Test
    fun `save then hasCredentials consistency`() = runBlocking {
        val cred = AccountCredentials("user", "pass", "https://server.com")
        credentialManager.saveCredentials(1L, cred)

        // In test env, encryption unavailable
        val has = credentialManager.hasCredentials(1L)
        assertFalse("Test env has no encryption, should return false", has)
    }

    // ========== updateDiscoveryUrls Edge Cases ==========

    @Test
    fun `updateDiscoveryUrls with null values`() = runBlocking {
        // Should not throw when both URLs are null
        credentialManager.updateDiscoveryUrls(
            accountId = 1L,
            principalUrl = null,
            calendarHomeSet = null
        )
    }

    @Test
    fun `updateDiscoveryUrls with only principalUrl`() = runBlocking {
        credentialManager.updateDiscoveryUrls(
            accountId = 1L,
            principalUrl = "https://server.com/principal/",
            calendarHomeSet = null
        )
        // Should not throw
    }

    @Test
    fun `updateDiscoveryUrls for non-existent account`() = runBlocking {
        // Should not throw even for account that was never saved
        credentialManager.updateDiscoveryUrls(
            accountId = Long.MAX_VALUE,
            principalUrl = "https://server.com/principal/",
            calendarHomeSet = "https://server.com/calendars/"
        )
    }

    // ========== AccountCredentials Equality Edge Cases ==========

    @Test
    fun `AccountCredentials with same fields but different trustInsecure are not equal`() {
        val cred1 = AccountCredentials("user", "pass", "https://server.com", trustInsecure = false)
        val cred2 = AccountCredentials("user", "pass", "https://server.com", trustInsecure = true)
        assertNotEquals(cred1, cred2)
    }

    @Test
    fun `AccountCredentials with same fields but different discovery URLs are not equal`() {
        val cred1 = AccountCredentials(
            "user", "pass", "https://server.com",
            principalUrl = "https://server.com/a/"
        )
        val cred2 = AccountCredentials(
            "user", "pass", "https://server.com",
            principalUrl = "https://server.com/b/"
        )
        assertNotEquals(cred1, cred2)
    }

    @Test
    fun `AccountCredentials null vs empty calendarHomeSet are not equal`() {
        val withNull = AccountCredentials("user", "pass", "https://server.com", calendarHomeSet = null)
        val withEmpty = AccountCredentials("user", "pass", "https://server.com", calendarHomeSet = "")
        assertNotEquals(withNull, withEmpty)
    }
}
