package org.onekash.kashcal.sync.provider.icloud

import android.content.Context
import org.onekash.kashcal.sync.provider.icloud.ICloudAccount
import org.onekash.kashcal.sync.provider.icloud.ICloudAuthManager
import android.content.SharedPreferences
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ICloudAuthManager.
 *
 * Note: EncryptedSharedPreferences requires Android Keystore which is not
 * available in Robolectric. These tests verify ICloudAuthManager behavior
 * by testing the ICloudAccount data class directly and mocking scenarios.
 *
 * For full integration testing of encrypted storage, use instrumented tests
 * in androidTest folder running on actual device/emulator.
 */
class ICloudAuthManagerTest {

    private lateinit var context: Context
    private lateinit var authManager: ICloudAuthManager
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
        every { mockEditor.clear() } returns mockEditor
        every { mockEditor.apply() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== ICloudAccount Tests (Data Class) ====================
    // These tests verify the data class behavior independent of storage

    @Test
    fun `ICloudAccount with valid credentials has credentials`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx"
        )

        assertTrue(account.hasCredentials())
    }

    @Test
    fun `ICloudAccount with empty appleId has no credentials`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = "password"
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `ICloudAccount with empty password has no credentials`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = ""
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `ICloudAccount with blank appleId has no credentials`() {
        val account = ICloudAccount(
            appleId = "   ",
            appSpecificPassword = "password"
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `ICloudAccount isReadyForSync when enabled and has credentials`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password",
            isEnabled = true
        )

        assertTrue(account.isReadyForSync())
    }

    @Test
    fun `ICloudAccount isReadyForSync false when disabled`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password",
            isEnabled = false
        )

        assertFalse(account.isReadyForSync())
    }

    @Test
    fun `ICloudAccount isReadyForSync false without credentials`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = "",
            isEnabled = true
        )

        assertFalse(account.isReadyForSync())
    }

    @Test
    fun `ICloudAccount hasDiscoveredCalendarHome when URL present`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password",
            calendarHomeUrl = "https://caldav.icloud.com/calendars"
        )

        assertTrue(account.hasDiscoveredCalendarHome())
    }

    @Test
    fun `ICloudAccount hasDiscoveredCalendarHome false when URL null`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password",
            calendarHomeUrl = null
        )

        assertFalse(account.hasDiscoveredCalendarHome())
    }

    @Test
    fun `ICloudAccount default values are correct`() {
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password"
        )

        assertEquals(ICloudAccount.DEFAULT_SERVER_URL, account.serverUrl)
        assertNull(account.calendarHomeUrl)
        assertNull(account.principalUrl)
        assertTrue(account.isEnabled)
        assertEquals(0L, account.lastSyncTime)
        assertNull(account.syncToken)
    }

    @Test
    fun `ICloudAccount DEFAULT_SERVER_URL is correct`() {
        assertEquals("https://caldav.icloud.com", ICloudAccount.DEFAULT_SERVER_URL)
    }

    @Test
    fun `ICloudAccount APP_SPECIFIC_PASSWORD_URL is correct`() {
        assertEquals(
            "https://appleid.apple.com/account/manage",
            ICloudAccount.APP_SPECIFIC_PASSWORD_URL
        )
    }

    @Test
    fun `ICloudAccount copy preserves all fields`() {
        val original = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            serverUrl = "https://custom.com",
            calendarHomeUrl = "https://custom.com/calendars",
            principalUrl = "https://custom.com/principal",
            isEnabled = true,
            lastSyncTime = 123L,
            syncToken = "token"
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `ICloudAccount copy allows modification`() {
        val original = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password"
        )

        val modified = original.copy(
            lastSyncTime = 999L,
            syncToken = "new-token"
        )

        assertEquals(original.appleId, modified.appleId)
        assertEquals(999L, modified.lastSyncTime)
        assertEquals("new-token", modified.syncToken)
    }

    @Test
    fun `ICloudAccount equals and hashCode work correctly`() {
        val account1 = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password"
        )
        val account2 = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password"
        )
        val account3 = ICloudAccount(
            appleId = "other@icloud.com",
            appSpecificPassword = "password"
        )

        assertEquals(account1, account2)
        assertEquals(account1.hashCode(), account2.hashCode())
        assertNotEquals(account1, account3)
    }

    // ==================== ICloudAuthManager API Tests ====================
    // These tests verify the manager instantiates correctly

    @Test
    fun `ICloudAuthManager instantiation with context`() {
        val manager = ICloudAuthManager(context)

        // Manager should instantiate without error
        assertNotNull(manager)
    }

    @Test
    fun `ICloudAuthManager isEncryptionAvailable returns false in test env`() {
        // In Robolectric, EncryptedSharedPreferences won't work
        val manager = ICloudAuthManager(context)

        // This is expected to be false in test environment
        // (no Android Keystore support)
        assertFalse(manager.isEncryptionAvailable())
    }

    @Test
    fun `ICloudAuthManager saveAccount returns false when encryption unavailable`() {
        val manager = ICloudAuthManager(context)
        val account = ICloudAccount(
            appleId = "test@icloud.com",
            appSpecificPassword = "password"
        )

        // Without encryption, saveAccount should return false gracefully
        val result = manager.saveAccount(account)

        assertFalse(result)
    }

    @Test
    fun `ICloudAuthManager loadAccount returns null when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.loadAccount()

        assertNull(result)
    }

    @Test
    fun `ICloudAuthManager isConfigured returns false when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.isConfigured()

        assertFalse(result)
    }

    @Test
    fun `ICloudAuthManager isEnabled returns false when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.isEnabled()

        assertFalse(result)
    }

    @Test
    fun `ICloudAuthManager getLastSyncTime returns 0 when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.getLastSyncTime()

        assertEquals(0L, result)
    }

    @Test
    fun `ICloudAuthManager hasDefaultCalendar returns false when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.hasDefaultCalendar()

        assertFalse(result)
    }

    @Test
    fun `ICloudAuthManager getDefaultCalendarUrl returns null when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.getDefaultCalendarUrl()

        assertNull(result)
    }

    @Test
    fun `ICloudAuthManager getDefaultCalendarName returns null when encryption unavailable`() {
        val manager = ICloudAuthManager(context)

        val result = manager.getDefaultCalendarName()

        assertNull(result)
    }

    // ==================== Complete Workflow Tests ====================
    // These verify business logic flows using the data class

    @Test
    fun `complete account setup workflow with data class`() {
        // Step 1: Create initial account
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx"
        )
        assertTrue(account.hasCredentials())

        // Step 2: Update with discovered URLs
        val withUrls = account.copy(
            calendarHomeUrl = "https://p180-caldav.icloud.com/123/calendars",
            principalUrl = "https://p180-caldav.icloud.com/123/principal"
        )
        assertTrue(withUrls.hasDiscoveredCalendarHome())

        // Step 3: Update after sync
        val afterSync = withUrls.copy(
            syncToken = "sync-token-abc",
            lastSyncTime = System.currentTimeMillis()
        )
        assertEquals("sync-token-abc", afterSync.syncToken)
        assertTrue(afterSync.lastSyncTime > 0)

        // Verify complete state
        assertTrue(afterSync.hasCredentials())
        assertTrue(afterSync.hasDiscoveredCalendarHome())
        assertTrue(afterSync.isReadyForSync())
    }

    @Test
    fun `account disable workflow with data class`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            isEnabled = true
        )
        assertTrue(account.isReadyForSync())

        val disabled = account.copy(isEnabled = false)

        assertFalse(disabled.isReadyForSync())
        assertTrue(disabled.hasCredentials()) // Still has credentials
    }

    @Test
    fun `account with all optional fields populated`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx",
            serverUrl = "https://caldav.icloud.com",
            calendarHomeUrl = "https://caldav.icloud.com/123/calendars",
            principalUrl = "https://caldav.icloud.com/123/principal",
            isEnabled = true,
            lastSyncTime = 1234567890L,
            syncToken = "sync-token-123"
        )

        assertTrue(account.hasCredentials())
        assertTrue(account.hasDiscoveredCalendarHome())
        assertTrue(account.isReadyForSync())
        assertEquals(1234567890L, account.lastSyncTime)
        assertEquals("sync-token-123", account.syncToken)
    }
}
