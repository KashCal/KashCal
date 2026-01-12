package org.onekash.kashcal.sync.provider.icloud

import org.junit.Assert.*
import org.onekash.kashcal.sync.provider.icloud.ICloudAccount
import org.junit.Test

/**
 * Unit tests for ICloudAccount data class.
 *
 * Tests:
 * - Default values
 * - Credential validation
 * - State checks
 * - Constants
 */
class ICloudAccountTest {

    // ==================== Construction Tests ====================

    @Test
    fun `minimal construction with required fields`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx"
        )

        assertEquals("user@icloud.com", account.appleId)
        assertEquals("xxxx-xxxx-xxxx-xxxx", account.appSpecificPassword)
        assertEquals(ICloudAccount.DEFAULT_SERVER_URL, account.serverUrl)
        assertNull(account.calendarHomeUrl)
        assertNull(account.principalUrl)
        assertTrue(account.isEnabled)
        assertEquals(0L, account.lastSyncTime)
        assertNull(account.syncToken)
    }

    @Test
    fun `full construction with all fields`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "xxxx-xxxx-xxxx-xxxx",
            serverUrl = "https://custom.caldav.com",
            calendarHomeUrl = "https://custom.caldav.com/calendars",
            principalUrl = "https://custom.caldav.com/principal",
            isEnabled = false,
            lastSyncTime = 1234567890L,
            syncToken = "sync-token-123"
        )

        assertEquals("https://custom.caldav.com", account.serverUrl)
        assertEquals("https://custom.caldav.com/calendars", account.calendarHomeUrl)
        assertEquals("https://custom.caldav.com/principal", account.principalUrl)
        assertFalse(account.isEnabled)
        assertEquals(1234567890L, account.lastSyncTime)
        assertEquals("sync-token-123", account.syncToken)
    }

    // ==================== hasCredentials Tests ====================

    @Test
    fun `hasCredentials returns true when both fields present`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password"
        )

        assertTrue(account.hasCredentials())
    }

    @Test
    fun `hasCredentials returns false with empty appleId`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = "password"
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `hasCredentials returns false with blank appleId`() {
        val account = ICloudAccount(
            appleId = "   ",
            appSpecificPassword = "password"
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `hasCredentials returns false with empty password`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = ""
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `hasCredentials returns false with blank password`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "   "
        )

        assertFalse(account.hasCredentials())
    }

    @Test
    fun `hasCredentials returns false with both empty`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = ""
        )

        assertFalse(account.hasCredentials())
    }

    // ==================== isReadyForSync Tests ====================

    @Test
    fun `isReadyForSync returns true when has credentials and enabled`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            isEnabled = true
        )

        assertTrue(account.isReadyForSync())
    }

    @Test
    fun `isReadyForSync returns false when disabled`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            isEnabled = false
        )

        assertFalse(account.isReadyForSync())
    }

    @Test
    fun `isReadyForSync returns false without credentials`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = "",
            isEnabled = true
        )

        assertFalse(account.isReadyForSync())
    }

    @Test
    fun `isReadyForSync returns false when disabled and no credentials`() {
        val account = ICloudAccount(
            appleId = "",
            appSpecificPassword = "",
            isEnabled = false
        )

        assertFalse(account.isReadyForSync())
    }

    // ==================== hasDiscoveredCalendarHome Tests ====================

    @Test
    fun `hasDiscoveredCalendarHome returns true when URL present`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            calendarHomeUrl = "https://caldav.icloud.com/calendars"
        )

        assertTrue(account.hasDiscoveredCalendarHome())
    }

    @Test
    fun `hasDiscoveredCalendarHome returns false when URL null`() {
        val account = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password",
            calendarHomeUrl = null
        )

        assertFalse(account.hasDiscoveredCalendarHome())
    }

    // ==================== Constants Tests ====================

    @Test
    fun `DEFAULT_SERVER_URL is correct`() {
        assertEquals("https://caldav.icloud.com", ICloudAccount.DEFAULT_SERVER_URL)
    }

    @Test
    fun `APP_SPECIFIC_PASSWORD_URL is correct`() {
        assertEquals(
            "https://appleid.apple.com/account/manage",
            ICloudAccount.APP_SPECIFIC_PASSWORD_URL
        )
    }

    // ==================== Data Class Behavior Tests ====================

    @Test
    fun `copy preserves all fields`() {
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
    fun `copy allows partial modification`() {
        val original = ICloudAccount(
            appleId = "user@icloud.com",
            appSpecificPassword = "password"
        )

        val modified = original.copy(
            lastSyncTime = 999L,
            syncToken = "new-token"
        )

        assertEquals(original.appleId, modified.appleId)
        assertEquals(original.appSpecificPassword, modified.appSpecificPassword)
        assertEquals(999L, modified.lastSyncTime)
        assertEquals("new-token", modified.syncToken)
    }

    @Test
    fun `equals and hashCode work correctly`() {
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
}
