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
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ICloudCardDavQuirks
import org.onekash.kashcal.sync.carddav.ZohoCardDavQuirks
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

    // ==================== getCardDavQuirksForAccount Tests ====================

    @Test
    fun `getCardDavQuirksForAccount returns ICloudCardDavQuirks for ICLOUD account`() {
        val account = createAccount(provider = AccountProvider.ICLOUD)

        val quirks = registry.getCardDavQuirksForAccount(account)

        assertTrue(quirks is ICloudCardDavQuirks)
    }

    @Test
    fun `getCardDavQuirksForAccount pins the contacts host for a Zoho CALDAV account`() {
        // A Zoho account is a generic CALDAV account whose homeSetUrl points at the
        // calendar host (calendar.zoho.com). Deriving the contacts host from that
        // would target the wrong host; Zoho serves contacts from contacts.zoho.com.
        // Detection keys off the SERVER host (homeSetUrl), never the login email —
        // a Zoho login can be a custom or Gmail-backed address.
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            email = "someone@gmail.com",
            homeSetUrl = "https://calendar.zoho.com/caldav/123/calendars/",
        )

        val quirks = registry.getCardDavQuirksForAccount(account)

        assertTrue("Zoho home host must pin the contacts host", quirks is ZohoCardDavQuirks)
        assertEquals("https://contacts.zoho.com", quirks?.baseUrl)
    }

    @Test
    fun `getCardDavQuirksForAccount pins a Zoho host that carries an explicit port`() {
        // The host match must ignore any :port; a port left on the host string would
        // fail the .zoho.com suffix and misroute to generic discovery (which then
        // seeds from the login email and falls back to the CALENDAR host).
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            homeSetUrl = "https://calendar.zoho.com:443/caldav/123/calendars/",
        )

        val quirks = registry.getCardDavQuirksForAccount(account)

        assertTrue("A ported Zoho host must still pin the contacts host", quirks is ZohoCardDavQuirks)
        assertEquals("https://contacts.zoho.com", quirks?.baseUrl)
    }

    @Test
    fun `getCardDavQuirksForAccount does not pin regional Zoho hosts (untested gap falls through to generic)`() {
        // Regional Zoho contacts hosts (contacts.zoho.eu / .in / .com.cn) are not
        // verified, so a regional home host falls through to generic discovery
        // rather than being misrouted to a pinned host we haven't confirmed exists.
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            homeSetUrl = "https://calendar.zoho.eu/caldav/123/calendars/",
        )

        val quirks = registry.getCardDavQuirksForAccount(account)

        assertTrue("Regional Zoho must stay generic, not pinned", quirks is DefaultCardDavQuirks)
        assertTrue(quirks !is ZohoCardDavQuirks)
    }

    @Test
    fun `getCardDavQuirksForAccount returns generic quirks for a non-Zoho CALDAV account`() {
        val account = createAccount(
            provider = AccountProvider.CALDAV,
            homeSetUrl = "https://nextcloud.example.com/remote.php/dav/",
        )

        val quirks = registry.getCardDavQuirksForAccount(account)

        assertTrue(quirks is DefaultCardDavQuirks)
        assertTrue(quirks !is ZohoCardDavQuirks)
        assertEquals("https://nextcloud.example.com", quirks?.baseUrl)
    }

    @Test
    fun `getCardDavQuirksForAccount returns null for a CALDAV account with no home host`() {
        val account = createAccount(provider = AccountProvider.CALDAV, homeSetUrl = null)

        assertNull(registry.getCardDavQuirksForAccount(account))
    }

    @Test
    fun `getCardDavQuirksForAccount returns null for LOCAL account`() {
        assertNull(registry.getCardDavQuirksForAccount(createAccount(provider = AccountProvider.LOCAL)))
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
        homeSetUrl: String? = null,
        email: String = "test@example.com"
    ): Account {
        return Account(
            id = 1L,
            provider = provider,
            email = email,
            displayName = "Test Account",
            principalUrl = null,
            homeSetUrl = homeSetUrl,
            isEnabled = true
        )
    }
}
