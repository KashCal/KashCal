package org.onekash.kashcal.data.credential

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UnifiedCredentialManager.
 *
 * Note: EncryptedSharedPreferences requires Android Keystore which is not
 * available in Robolectric. These tests verify UnifiedCredentialManager behavior
 * by testing the AccountCredentials data class directly and verifying graceful
 * degradation when encryption is unavailable.
 *
 * For full integration testing of encrypted storage, use instrumented tests
 * in androidTest folder running on actual device/emulator.
 */
class UnifiedCredentialManagerTest {

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

    // ==================== AccountCredentials Tests (Data Class) ====================

    @Test
    fun `AccountCredentials with minimal fields`() {
        val credentials = AccountCredentials(
            username = "user@example.com",
            password = "secure-password",
            serverUrl = "https://caldav.example.com"
        )

        assertEquals("user@example.com", credentials.username)
        assertEquals("secure-password", credentials.password)
        assertEquals("https://caldav.example.com", credentials.serverUrl)
        assertFalse(credentials.trustInsecure)
        assertNull(credentials.principalUrl)
        assertNull(credentials.calendarHomeSet)
    }

    @Test
    fun `AccountCredentials with all fields`() {
        val credentials = AccountCredentials(
            username = "user@example.com",
            password = "password",
            serverUrl = "https://caldav.example.com",
            trustInsecure = true,
            principalUrl = "https://caldav.example.com/principals/user",
            calendarHomeSet = "https://caldav.example.com/calendars/user"
        )

        assertTrue(credentials.trustInsecure)
        assertEquals("https://caldav.example.com/principals/user", credentials.principalUrl)
        assertEquals("https://caldav.example.com/calendars/user", credentials.calendarHomeSet)
    }

    @Test
    fun `AccountCredentials for iCloud uses default server URL`() {
        val credentials = AccountCredentials(
            username = "appleid@icloud.com",
            password = "app-specific-password",
            serverUrl = AccountCredentials.ICLOUD_DEFAULT_SERVER_URL
        )

        assertEquals("https://caldav.icloud.com", credentials.serverUrl)
    }

    @Test
    fun `ICLOUD_DEFAULT_SERVER_URL constant is correct`() {
        assertEquals("https://caldav.icloud.com", AccountCredentials.ICLOUD_DEFAULT_SERVER_URL)
    }

    @Test
    fun `AccountCredentials copy preserves all fields`() {
        val original = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com",
            trustInsecure = true,
            principalUrl = "https://server.com/principal",
            calendarHomeSet = "https://server.com/home"
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `AccountCredentials copy allows modification`() {
        val original = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
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
    fun `AccountCredentials equals and hashCode work correctly`() {
        val cred1 = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        val cred2 = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )
        val cred3 = AccountCredentials(
            username = "other",
            password = "pass",
            serverUrl = "https://server.com"
        )

        assertEquals(cred1, cred2)
        assertEquals(cred1.hashCode(), cred2.hashCode())
        assertNotEquals(cred1, cred3)
    }

    // ==================== UnifiedCredentialManager API Tests ====================

    @Test
    fun `UnifiedCredentialManager instantiation with context`() {
        val manager = UnifiedCredentialManager(context)

        assertNotNull(manager)
    }

    @Test
    fun `isEncryptionAvailable returns false in test environment`() {
        // In Robolectric, EncryptedSharedPreferences won't work
        assertFalse(credentialManager.isEncryptionAvailable())
    }

    @Test
    fun `saveCredentials returns false when encryption unavailable`() = runBlocking {
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )

        val result = credentialManager.saveCredentials(1L, credentials)

        assertFalse(result)
    }

    @Test
    fun `getCredentials returns null when encryption unavailable`() = runBlocking {
        val result = credentialManager.getCredentials(1L)

        assertNull(result)
    }

    @Test
    fun `hasCredentials returns false when encryption unavailable`() = runBlocking {
        val result = credentialManager.hasCredentials(1L)

        assertFalse(result)
    }

    @Test
    fun `getAllAccountIds returns empty when encryption unavailable`() = runBlocking {
        val result = credentialManager.getAllAccountIds()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deleteCredentials completes without error when encryption unavailable`() = runBlocking {
        // Should not throw
        credentialManager.deleteCredentials(1L)
    }

    @Test
    fun `clearAllCredentials completes without error when encryption unavailable`() = runBlocking {
        // Should not throw
        credentialManager.clearAllCredentials()
    }

    @Test
    fun `getEncryptionError returns message when encryption fails`() {
        val manager = UnifiedCredentialManager(context)
        // Force encryption initialization
        manager.isEncryptionAvailable()

        // In test env, there should be an error message
        val error = manager.getEncryptionError()
        assertNotNull(error)
    }

    // ==================== Account-Keyed Storage Pattern Tests ====================

    @Test
    fun `key format uses account prefix`() = runBlocking {
        // Testing internal key format through behavior
        // When encryption works, keys should be: account_1_username, account_1_password, etc.
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://server.com"
        )

        // Won't succeed in test env, but verifies no crash with valid account ID
        val result = credentialManager.saveCredentials(1L, credentials)
        assertFalse(result) // Expected in test environment
    }

    @Test
    fun `different account IDs are isolated`() = runBlocking {
        // In production, account 1 and account 2 would have separate keys:
        // - account_1_password
        // - account_2_password

        val cred1 = AccountCredentials("user1", "pass1", "https://server1.com")
        val cred2 = AccountCredentials("user2", "pass2", "https://server2.com")

        credentialManager.saveCredentials(1L, cred1)
        credentialManager.saveCredentials(2L, cred2)

        // In test env, both fail gracefully
        assertNull(credentialManager.getCredentials(1L))
        assertNull(credentialManager.getCredentials(2L))
    }

    // ==================== Provider-Specific Workflows ====================

    @Test
    fun `iCloud credentials workflow`() {
        // iCloud uses Apple ID as username
        val credentials = AccountCredentials(
            username = "user@icloud.com",  // Apple ID
            password = "xxxx-xxxx-xxxx-xxxx",  // App-specific password
            serverUrl = AccountCredentials.ICLOUD_DEFAULT_SERVER_URL,
            principalUrl = "https://caldav.icloud.com/12345678/principal/",
            calendarHomeSet = "https://caldav.icloud.com/12345678/calendars/"
        )

        assertEquals("user@icloud.com", credentials.username)
        assertEquals("https://caldav.icloud.com", credentials.serverUrl)
        assertFalse(credentials.trustInsecure) // iCloud uses valid certs
    }

    @Test
    fun `CalDAV credentials workflow - Nextcloud`() {
        val credentials = AccountCredentials(
            username = "admin@nextcloud.example.com",
            password = "app-password",
            serverUrl = "https://nextcloud.example.com/remote.php/dav",
            trustInsecure = false,
            principalUrl = "https://nextcloud.example.com/remote.php/dav/principals/users/admin/",
            calendarHomeSet = "https://nextcloud.example.com/remote.php/dav/calendars/admin/"
        )

        assertTrue(credentials.serverUrl.contains("remote.php/dav"))
        assertNotNull(credentials.principalUrl)
        assertNotNull(credentials.calendarHomeSet)
    }

    @Test
    fun `CalDAV credentials workflow - self-signed server`() {
        val credentials = AccountCredentials(
            username = "user",
            password = "pass",
            serverUrl = "https://home-server.local:5232",
            trustInsecure = true  // Self-signed certificate
        )

        assertTrue(credentials.trustInsecure)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles Long MAX_VALUE account ID`() = runBlocking {
        val credentials = AccountCredentials("user", "pass", "https://server.com")

        // Should not throw with large account ID
        credentialManager.saveCredentials(Long.MAX_VALUE, credentials)
        credentialManager.getCredentials(Long.MAX_VALUE)
        credentialManager.hasCredentials(Long.MAX_VALUE)
        credentialManager.deleteCredentials(Long.MAX_VALUE)
    }

    @Test
    fun `handles zero account ID`() = runBlocking {
        val credentials = AccountCredentials("user", "pass", "https://server.com")

        // Should not throw with zero account ID
        credentialManager.saveCredentials(0L, credentials)
        credentialManager.getCredentials(0L)
        credentialManager.hasCredentials(0L)
        credentialManager.deleteCredentials(0L)
    }

    @Test
    fun `credentials with special characters in password`() {
        val credentials = AccountCredentials(
            username = "user@domain.com",
            password = "p@ss!w0rd#\$%^&*()_+=[]{}|;':\",./<>?",
            serverUrl = "https://server.com"
        )

        // Should handle special characters
        assertTrue(credentials.password.contains("@"))
        assertTrue(credentials.password.contains("#"))
    }

    @Test
    fun `credentials with unicode in username`() {
        val credentials = AccountCredentials(
            username = "用户名@example.com",
            password = "password",
            serverUrl = "https://server.com"
        )

        assertTrue(credentials.username.contains("用户名"))
    }

    @Test
    fun `credentials with very long values`() {
        val longUrl = "https://very-long-subdomain.even-longer-domain.example.com/very/long/path/to/caldav/endpoint"
        val longUsername = "a".repeat(256) + "@example.com"
        val longPassword = "x".repeat(1000)

        val credentials = AccountCredentials(
            username = longUsername,
            password = longPassword,
            serverUrl = longUrl
        )

        assertTrue(credentials.serverUrl.length > 50)
        assertTrue(credentials.username.length > 250)
        assertEquals(1000, credentials.password.length)
    }

    // ==================== updateDiscoveryUrls Tests ====================

    @Test
    fun `updateDiscoveryUrls completes without error when encryption unavailable`() = runBlocking {
        // Should not throw
        credentialManager.updateDiscoveryUrls(
            accountId = 1L,
            principalUrl = "https://server.com/principal",
            calendarHomeSet = "https://server.com/calendars"
        )
    }
}
