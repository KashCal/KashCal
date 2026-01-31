package org.onekash.kashcal.sync.provider

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.provider.caldav.CalDavCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Unit tests for ProviderRegistry.
 *
 * Tests verify:
 * - getQuirks returns correct quirks for each provider
 * - getQuirksForAccount returns correct quirks including DefaultQuirks for CALDAV
 * - getCredentialProvider returns correct credentials for each provider
 */
class ProviderRegistryTest {

    private lateinit var icloudQuirks: ICloudQuirks
    private lateinit var icloudCredentials: ICloudCredentialProvider
    private lateinit var caldavCredentials: CalDavCredentialProvider
    private lateinit var registry: ProviderRegistry

    @Before
    fun setup() {
        icloudQuirks = mockk(relaxed = true)
        icloudCredentials = mockk(relaxed = true)
        caldavCredentials = mockk(relaxed = true)
        registry = ProviderRegistry(icloudQuirks, icloudCredentials, caldavCredentials)
    }

    // ==================== getQuirks Tests ====================

    @Test
    fun `getQuirks returns ICloudQuirks for ICLOUD provider`() {
        val quirks = registry.getQuirks(AccountProvider.ICLOUD)

        assertNotNull(quirks)
        assertEquals(icloudQuirks, quirks)
    }

    @Test
    fun `getQuirks returns null for LOCAL provider`() {
        val quirks = registry.getQuirks(AccountProvider.LOCAL)

        assertNull(quirks)
    }

    @Test
    fun `getQuirks returns null for ICS provider`() {
        val quirks = registry.getQuirks(AccountProvider.ICS)

        assertNull(quirks)
    }

    @Test
    fun `getQuirks returns null for CONTACTS provider`() {
        val quirks = registry.getQuirks(AccountProvider.CONTACTS)

        assertNull(quirks)
    }

    @Test
    fun `getQuirks returns null for CALDAV provider - use getQuirksForAccount instead`() {
        // getQuirks returns null for CALDAV because DefaultQuirks needs server URL from Account
        // Use getQuirksForAccount() instead
        val quirks = registry.getQuirks(AccountProvider.CALDAV)

        assertNull(quirks)
    }

    // ==================== getQuirksForAccount Tests ====================

    @Test
    fun `getQuirksForAccount returns ICloudQuirks for ICLOUD account`() {
        val account = createAccount(provider = AccountProvider.ICLOUD)

        val quirks = registry.getQuirksForAccount(account)

        assertNotNull(quirks)
        assertEquals(icloudQuirks, quirks)
    }

    @Test
    fun `getQuirksForAccount returns DefaultQuirks for CALDAV account`() {
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            homeSetUrl = "https://nextcloud.example.com/remote.php/dav/calendars/user/"
        )

        val quirks = registry.getQuirksForAccount(account)

        assertNotNull(quirks)
        assertTrue(quirks is DefaultQuirks)
        assertEquals("https://nextcloud.example.com/remote.php/dav/calendars/user/", quirks?.baseUrl)
    }

    @Test(expected = IllegalStateException::class)
    fun `getQuirksForAccount throws for CALDAV account without homeSetUrl`() {
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            homeSetUrl = null
        )

        registry.getQuirksForAccount(account)  // Should throw
    }

    @Test
    fun `getQuirksForAccount returns null for LOCAL account`() {
        val account = createAccount(provider = AccountProvider.LOCAL)

        val quirks = registry.getQuirksForAccount(account)

        assertNull(quirks)
    }

    @Test
    fun `getQuirksForAccount returns null for ICS account`() {
        val account = createAccount(provider = AccountProvider.ICS)

        val quirks = registry.getQuirksForAccount(account)

        assertNull(quirks)
    }

    // ==================== getCredentialProvider Tests ====================

    @Test
    fun `getCredentialProvider returns ICloudCredentialProvider for ICLOUD provider`() {
        val credentials = registry.getCredentialProvider(AccountProvider.ICLOUD)

        assertNotNull(credentials)
        assertEquals(icloudCredentials, credentials)
    }

    @Test
    fun `getCredentialProvider returns null for LOCAL provider`() {
        val credentials = registry.getCredentialProvider(AccountProvider.LOCAL)

        assertNull(credentials)
    }

    @Test
    fun `getCredentialProvider returns null for ICS provider`() {
        val credentials = registry.getCredentialProvider(AccountProvider.ICS)

        assertNull(credentials)
    }

    @Test
    fun `getCredentialProvider returns null for CONTACTS provider`() {
        val credentials = registry.getCredentialProvider(AccountProvider.CONTACTS)

        assertNull(credentials)
    }

    @Test
    fun `getCredentialProvider returns CalDavCredentialProvider for CALDAV provider`() {
        val credentials = registry.getCredentialProvider(AccountProvider.CALDAV)

        assertNotNull(credentials)
        assertEquals(caldavCredentials, credentials)
    }

    // ==================== Helper Methods ====================

    private fun createAccount(
        provider: AccountProvider,
        homeSetUrl: String? = null
    ): Account {
        return Account(
            id = 1L,
            provider = provider,
            email = "test@example.com",
            displayName = "Test Account",
            principalUrl = null,
            homeSetUrl = homeSetUrl,
            isEnabled = true
        )
    }
}
