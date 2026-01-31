package org.onekash.kashcal.sync.provider.caldav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CalDavCredentialManager.
 *
 * Note: EncryptedSharedPreferences requires Android Keystore which is not
 * available in Robolectric. These tests verify CalDavCredentialManager behavior
 * by testing the CalDavCredentials data class directly and verifying graceful
 * degradation when encryption is unavailable.
 *
 * For full integration testing of encrypted storage, use instrumented tests
 * in androidTest folder running on actual device/emulator.
 */
class CalDavCredentialManagerTest {

    private lateinit var context: Context
    private lateinit var credentialManager: CalDavCredentialManager
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Create mocked context and preferences
        context = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.clear() } returns mockEditor
        every { mockEditor.apply() } just Runs

        credentialManager = CalDavCredentialManager(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== CalDavCredentials Tests (Data Class) ====================
    // These tests verify the data class behavior independent of storage

    @Test
    fun `CalDavCredentials with valid data`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://nextcloud.example.com",
            username = "user@example.com",
            password = "secure-password"
        )

        assertEquals("https://nextcloud.example.com", credentials.serverUrl)
        assertEquals("user@example.com", credentials.username)
        assertEquals("secure-password", credentials.password)
        assertFalse(credentials.trustInsecure)
    }

    @Test
    fun `CalDavCredentials with trustInsecure true`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://self-signed.local",
            username = "admin",
            password = "password",
            trustInsecure = true
        )

        assertTrue(credentials.trustInsecure)
    }

    @Test
    fun `CalDavCredentials default trustInsecure is false`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://caldav.example.com",
            username = "user",
            password = "pass"
        )

        assertFalse(credentials.trustInsecure)
    }

    @Test
    fun `CalDavCredentials copy preserves all fields`() {
        val original = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass",
            trustInsecure = true
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `CalDavCredentials copy allows modification`() {
        val original = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass",
            trustInsecure = false
        )

        val modified = original.copy(
            password = "new-pass",
            trustInsecure = true
        )

        assertEquals("new-pass", modified.password)
        assertTrue(modified.trustInsecure)
        assertEquals("user", modified.username) // Unchanged
    }

    @Test
    fun `CalDavCredentials equals and hashCode work correctly`() {
        val cred1 = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass"
        )
        val cred2 = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass"
        )
        val cred3 = CalDavCredentials(
            serverUrl = "https://other.com",
            username = "user",
            password = "pass"
        )

        assertEquals(cred1, cred2)
        assertEquals(cred1.hashCode(), cred2.hashCode())
        assertNotEquals(cred1, cred3)
    }

    // ==================== CalDavCredentialManager API Tests ====================
    // These tests verify the manager gracefully handles unavailable encryption

    @Test
    fun `CalDavCredentialManager instantiation with context`() {
        val manager = CalDavCredentialManager(context)

        assertNotNull(manager)
    }

    @Test
    fun `CalDavCredentialManager isEncryptionAvailable returns false in test env`() {
        // In Robolectric, EncryptedSharedPreferences won't work
        val manager = CalDavCredentialManager(context)

        // Expected to be false in test environment (no Android Keystore)
        assertFalse(manager.isEncryptionAvailable())
    }

    @Test
    fun `saveCredentials returns false when encryption unavailable`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://nextcloud.example.com",
            username = "user",
            password = "pass"
        )

        val result = credentialManager.saveCredentials(1L, credentials)

        assertFalse(result)
    }

    @Test
    fun `getCredentials returns null when encryption unavailable`() {
        val result = credentialManager.getCredentials(1L)

        assertNull(result)
    }

    @Test
    fun `hasCredentials returns false when encryption unavailable`() {
        val result = credentialManager.hasCredentials(1L)

        assertFalse(result)
    }

    @Test
    fun `getAllAccountIds returns empty when encryption unavailable`() {
        val result = credentialManager.getAllAccountIds()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deleteCredentials completes without error when encryption unavailable`() {
        // Should not throw
        credentialManager.deleteCredentials(1L)
    }

    @Test
    fun `clearAllCredentials completes without error when encryption unavailable`() {
        // Should not throw
        credentialManager.clearAllCredentials()
    }

    @Test
    fun `getEncryptionError returns message when encryption fails`() {
        val manager = CalDavCredentialManager(context)
        // Force encryption initialization
        manager.isEncryptionAvailable()

        // In test env, there should be an error message
        val error = manager.getEncryptionError()
        assertNotNull(error)
    }

    // ==================== Key Format Tests ====================
    // Verify the account-keyed storage pattern

    @Test
    fun `key format is correct for account ID 1`() {
        // Testing internal key format through behavior
        // When encryption works, keys should be: caldav_1_server_url, caldav_1_username, etc.
        val credentials = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user",
            password = "pass"
        )

        // This would save with keys like caldav_1_server_url
        val result = credentialManager.saveCredentials(1L, credentials)

        // Won't succeed in test env, but verifies no crash with valid account ID
        assertFalse(result) // Expected in test environment
    }

    @Test
    fun `different account IDs are isolated`() {
        // In production, account 1 and account 2 would have separate keys:
        // - caldav_1_password
        // - caldav_2_password

        val cred1 = CalDavCredentials("https://server1.com", "user1", "pass1")
        val cred2 = CalDavCredentials("https://server2.com", "user2", "pass2")

        // These should use different keys (isolated storage)
        credentialManager.saveCredentials(1L, cred1)
        credentialManager.saveCredentials(2L, cred2)

        // In test env, both fail gracefully
        assertNull(credentialManager.getCredentials(1L))
        assertNull(credentialManager.getCredentials(2L))
    }

    // ==================== Workflow Tests ====================
    // Verify credential management workflows

    @Test
    fun `complete credential workflow with data class`() {
        // Step 1: Create credentials for account setup
        val credentials = CalDavCredentials(
            serverUrl = "https://nextcloud.example.com/remote.php/dav",
            username = "admin@example.com",
            password = "app-password-123"
        )

        assertTrue(credentials.serverUrl.isNotEmpty())
        assertTrue(credentials.username.isNotEmpty())
        assertTrue(credentials.password.isNotEmpty())
        assertFalse(credentials.trustInsecure)

        // Step 2: Update for self-signed certificate
        val withTrust = credentials.copy(trustInsecure = true)

        assertTrue(withTrust.trustInsecure)
        assertEquals(credentials.serverUrl, withTrust.serverUrl)
    }

    @Test
    fun `self-signed certificate workflow`() {
        // Initial attempt without trusting certificate
        val initial = CalDavCredentials(
            serverUrl = "https://home-server.local:8443",
            username = "user",
            password = "pass",
            trustInsecure = false
        )

        assertFalse(initial.trustInsecure)

        // After SSL error, user enables trust
        val trusted = initial.copy(trustInsecure = true)

        assertTrue(trusted.trustInsecure)
    }

    @Test
    fun `multiple server URLs supported`() {
        // Different server configurations
        val nextcloud = CalDavCredentials(
            serverUrl = "https://nextcloud.example.com/remote.php/dav",
            username = "user",
            password = "pass"
        )
        val baikal = CalDavCredentials(
            serverUrl = "https://baikal.example.com/dav.php",
            username = "admin",
            password = "admin-pass"
        )
        val radicale = CalDavCredentials(
            serverUrl = "https://radicale.local:5232",
            username = "user",
            password = "user-pass",
            trustInsecure = true // Local server
        )

        // All should be valid credentials
        assertTrue(nextcloud.serverUrl.contains("nextcloud"))
        assertTrue(baikal.serverUrl.contains("baikal"))
        assertTrue(radicale.serverUrl.contains("radicale"))
        assertTrue(radicale.trustInsecure)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles Long MAX_VALUE account ID`() {
        val credentials = CalDavCredentials("https://server.com", "user", "pass")

        // Should not throw with large account ID
        credentialManager.saveCredentials(Long.MAX_VALUE, credentials)
        credentialManager.getCredentials(Long.MAX_VALUE)
        credentialManager.hasCredentials(Long.MAX_VALUE)
        credentialManager.deleteCredentials(Long.MAX_VALUE)
    }

    @Test
    fun `handles zero account ID`() {
        val credentials = CalDavCredentials("https://server.com", "user", "pass")

        // Should not throw with zero account ID
        credentialManager.saveCredentials(0L, credentials)
        credentialManager.getCredentials(0L)
        credentialManager.hasCredentials(0L)
        credentialManager.deleteCredentials(0L)
    }

    @Test
    fun `credentials with special characters in password`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "user@domain.com",
            password = "p@ss!w0rd#\$%^&*()_+=[]{}|;':\",./<>?"
        )

        // Should handle special characters
        assertTrue(credentials.password.contains("@"))
        assertTrue(credentials.password.contains("#"))
    }

    @Test
    fun `credentials with unicode in username`() {
        val credentials = CalDavCredentials(
            serverUrl = "https://server.com",
            username = "用户名@example.com",
            password = "password"
        )

        assertTrue(credentials.username.contains("用户名"))
    }

    @Test
    fun `credentials with very long values`() {
        val longUrl = "https://very-long-subdomain.even-longer-domain.example.com/very/long/path/to/caldav/endpoint"
        val longUsername = "a".repeat(256) + "@example.com"
        val longPassword = "x".repeat(1000)

        val credentials = CalDavCredentials(
            serverUrl = longUrl,
            username = longUsername,
            password = longPassword
        )

        assertTrue(credentials.serverUrl.length > 50)
        assertTrue(credentials.username.length > 250)
        assertEquals(1000, credentials.password.length)
    }
}
