package org.onekash.kashcal.sync.provider

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Unit tests for ICloudProvider.
 *
 * Tests verify:
 * - Provider identification
 * - Correct quirks and credential provider returned
 * - Capability flags
 * - Account matching
 */
class ICloudProviderTest {

    private lateinit var quirks: ICloudQuirks
    private lateinit var credentialProvider: ICloudCredentialProvider
    private lateinit var provider: ICloudProvider

    @Before
    fun setup() {
        quirks = mockk(relaxed = true)
        credentialProvider = mockk(relaxed = true)
        provider = ICloudProvider(quirks, credentialProvider)
    }

    // ==================== Provider Identity Tests ====================

    @Test
    fun `providerId is icloud`() {
        assertEquals("icloud", provider.providerId)
    }

    @Test
    fun `displayName is iCloud`() {
        assertEquals("iCloud", provider.displayName)
    }

    @Test
    fun `requiresNetwork is true`() {
        assertTrue(provider.requiresNetwork)
    }

    // ==================== Quirks and Credentials Tests ====================

    @Test
    fun `getQuirks returns ICloudQuirks`() {
        val result = provider.getQuirks()

        assertNotNull(result)
        assertEquals(quirks, result)
    }

    @Test
    fun `getCredentialProvider returns ICloudCredentialProvider`() {
        val result = provider.getCredentialProvider()

        assertNotNull(result)
        assertEquals(credentialProvider, result)
    }

    // ==================== Capabilities Tests ====================

    @Test
    fun `capabilities supportsCalDAV is true`() {
        assertTrue(provider.capabilities.supportsCalDAV)
    }

    @Test
    fun `capabilities supportsIncrementalSync is true`() {
        assertTrue(provider.capabilities.supportsIncrementalSync)
    }

    @Test
    fun `capabilities supportsReminders is true`() {
        assertTrue(provider.capabilities.supportsReminders)
    }

    @Test
    fun `capabilities supportsPush is false`() {
        // iCloud doesn't support CalDAV push notifications
        assertTrue(!provider.capabilities.supportsPush)
    }

    // ==================== Account Matching Tests ====================

    @Test
    fun `handlesAccount returns true for iCloud account`() {
        val account = Account(
            id = 1L,
            provider = "icloud",
            email = "test@icloud.com"
        )

        assertTrue(provider.handlesAccount(account))
    }

    @Test
    fun `handlesAccount returns false for local account`() {
        val account = Account(
            id = 2L,
            provider = "local",
            email = "local@device"
        )

        assertTrue(!provider.handlesAccount(account))
    }

    @Test
    fun `handlesAccount returns false for google account`() {
        val account = Account(
            id = 3L,
            provider = "google",
            email = "test@gmail.com"
        )

        assertTrue(!provider.handlesAccount(account))
    }

    // ==================== Constants Tests ====================

    @Test
    fun `PROVIDER_ID constant is icloud`() {
        assertEquals("icloud", ICloudProvider.PROVIDER_ID)
    }

    @Test
    fun `BASE_URL constant is correct`() {
        assertEquals("https://caldav.icloud.com", ICloudProvider.BASE_URL)
    }
}
