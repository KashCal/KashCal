package org.onekash.kashcal.sync.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.sync.provider.local.LocalProvider

/**
 * Unit tests for LocalProvider.
 *
 * Tests verify:
 * - Provider identification
 * - No quirks or credentials (local-only)
 * - Capability flags
 * - Account matching
 */
class LocalProviderTest {

    private lateinit var provider: LocalProvider

    @Before
    fun setup() {
        provider = LocalProvider()
    }

    // ==================== Provider Identity Tests ====================

    @Test
    fun `providerId is local`() {
        assertEquals("local", provider.providerId)
    }

    @Test
    fun `displayName is Local`() {
        assertEquals("Local", provider.displayName)
    }

    @Test
    fun `requiresNetwork is false`() {
        assertFalse(provider.requiresNetwork)
    }

    // ==================== Quirks and Credentials Tests ====================

    @Test
    fun `getQuirks returns null`() {
        // Local provider doesn't use CalDAV, no quirks needed
        assertNull(provider.getQuirks())
    }

    @Test
    fun `getCredentialProvider returns null`() {
        // Local provider doesn't require authentication
        assertNull(provider.getCredentialProvider())
    }

    // ==================== Capabilities Tests ====================

    @Test
    fun `capabilities supportsCalDAV is false`() {
        assertFalse(provider.capabilities.supportsCalDAV)
    }

    @Test
    fun `capabilities supportsIncrementalSync is false`() {
        // No server to sync with
        assertFalse(provider.capabilities.supportsIncrementalSync)
    }

    @Test
    fun `capabilities supportsReminders is true`() {
        // Local reminders work fine
        assertTrue(provider.capabilities.supportsReminders)
    }

    @Test
    fun `capabilities supportsPush is false`() {
        assertFalse(provider.capabilities.supportsPush)
    }

    // ==================== Account Matching Tests ====================

    @Test
    fun `handlesAccount returns true for local account`() {
        val account = Account(
            id = 1L,
            provider = "local",
            email = "local@device"
        )

        assertTrue(provider.handlesAccount(account))
    }

    @Test
    fun `handlesAccount returns false for iCloud account`() {
        val account = Account(
            id = 2L,
            provider = "icloud",
            email = "test@icloud.com"
        )

        assertFalse(provider.handlesAccount(account))
    }

    @Test
    fun `handlesAccount returns false for google account`() {
        val account = Account(
            id = 3L,
            provider = "google",
            email = "test@gmail.com"
        )

        assertFalse(provider.handlesAccount(account))
    }

    // ==================== Constants Tests ====================

    @Test
    fun `PROVIDER_ID constant is local`() {
        assertEquals("local", LocalProvider.PROVIDER_ID)
    }

    @Test
    fun `URL_SCHEME constant is correct`() {
        assertEquals("local://", LocalProvider.URL_SCHEME)
    }
}
