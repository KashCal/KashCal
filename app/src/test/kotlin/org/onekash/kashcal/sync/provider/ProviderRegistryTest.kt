package org.onekash.kashcal.sync.provider

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account

/**
 * Unit tests for ProviderRegistry.
 *
 * Tests verify:
 * - Provider lookup by ID
 * - Provider lookup for account
 * - Network/CalDAV provider filtering
 */
class ProviderRegistryTest {

    private lateinit var icloudProvider: CalendarProvider
    private lateinit var localProvider: CalendarProvider
    private lateinit var registry: ProviderRegistry

    @Before
    fun setup() {
        icloudProvider = mockk {
            every { providerId } returns "icloud"
            every { displayName } returns "iCloud"
            every { requiresNetwork } returns true
            every { capabilities } returns ProviderCapabilities(supportsCalDAV = true)
            every { handlesAccount(any()) } answers {
                (firstArg() as Account).provider == "icloud"
            }
        }

        localProvider = mockk {
            every { providerId } returns "local"
            every { displayName } returns "Local"
            every { requiresNetwork } returns false
            every { capabilities } returns ProviderCapabilities(supportsCalDAV = false)
            every { handlesAccount(any()) } answers {
                (firstArg() as Account).provider == "local"
            }
        }

        registry = ProviderRegistryImpl(setOf(icloudProvider, localProvider))
    }

    // ==================== getAllProviders Tests ====================

    @Test
    fun `getAllProviders returns all registered providers`() {
        val providers = registry.getAllProviders()

        assertEquals(2, providers.size)
        assertTrue(providers.contains(icloudProvider))
        assertTrue(providers.contains(localProvider))
    }

    // ==================== getProvider Tests ====================

    @Test
    fun `getProvider returns correct provider for icloud`() {
        val provider = registry.getProvider("icloud")

        assertNotNull(provider)
        assertEquals("icloud", provider?.providerId)
    }

    @Test
    fun `getProvider returns correct provider for local`() {
        val provider = registry.getProvider("local")

        assertNotNull(provider)
        assertEquals("local", provider?.providerId)
    }

    @Test
    fun `getProvider returns null for unknown provider`() {
        val provider = registry.getProvider("google")

        assertNull(provider)
    }

    // ==================== getProviderForAccount Tests ====================

    @Test
    fun `getProviderForAccount returns iCloud provider for iCloud account`() {
        val account = Account(
            id = 1L,
            provider = "icloud",
            email = "test@icloud.com"
        )

        val provider = registry.getProviderForAccount(account)

        assertNotNull(provider)
        assertEquals("icloud", provider?.providerId)
    }

    @Test
    fun `getProviderForAccount returns local provider for local account`() {
        val account = Account(
            id = 2L,
            provider = "local",
            email = "local@device"
        )

        val provider = registry.getProviderForAccount(account)

        assertNotNull(provider)
        assertEquals("local", provider?.providerId)
    }

    @Test
    fun `getProviderForAccount returns null for unknown provider account`() {
        val account = Account(
            id = 3L,
            provider = "google",
            email = "test@gmail.com"
        )

        val provider = registry.getProviderForAccount(account)

        assertNull(provider)
    }

    // ==================== getNetworkProviders Tests ====================

    @Test
    fun `getNetworkProviders returns only providers requiring network`() {
        val networkProviders = registry.getNetworkProviders()

        assertEquals(1, networkProviders.size)
        assertEquals("icloud", networkProviders[0].providerId)
    }

    // ==================== getCalDavProviders Tests ====================

    @Test
    fun `getCalDavProviders returns only CalDAV providers`() {
        val caldavProviders = registry.getCalDavProviders()

        assertEquals(1, caldavProviders.size)
        assertEquals("icloud", caldavProviders[0].providerId)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `empty registry returns empty lists`() {
        val emptyRegistry = ProviderRegistryImpl(emptySet())

        assertTrue(emptyRegistry.getAllProviders().isEmpty())
        assertNull(emptyRegistry.getProvider("icloud"))
        assertTrue(emptyRegistry.getNetworkProviders().isEmpty())
        assertTrue(emptyRegistry.getCalDavProviders().isEmpty())
    }
}
