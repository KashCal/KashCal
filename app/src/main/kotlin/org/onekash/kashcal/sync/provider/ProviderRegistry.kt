package org.onekash.kashcal.sync.provider

import org.onekash.kashcal.data.db.entity.Account
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for calendar providers.
 *
 * Single source of truth for provider lookup at runtime.
 * Following Android Data Layer Repository pattern:
 * - Provides unified access to all registered providers
 * - Handles provider-account matching
 * - Thread-safe via immutable collections
 *
 * @see CalendarProvider for provider interface
 */
interface ProviderRegistry {

    /**
     * Get all registered calendar providers.
     *
     * @return List of all providers (immutable)
     */
    fun getAllProviders(): List<CalendarProvider>

    /**
     * Get a provider by its ID.
     *
     * @param providerId The provider identifier (e.g., "icloud", "local")
     * @return The provider, or null if not found
     */
    fun getProvider(providerId: String): CalendarProvider?

    /**
     * Get the provider that handles a specific account.
     *
     * @param account The account to find a provider for
     * @return The matching provider, or null if no provider handles this account
     */
    fun getProviderForAccount(account: Account): CalendarProvider?

    /**
     * Get providers that require network for sync.
     * Used to filter which accounts to sync when online.
     *
     * @return List of network-requiring providers
     */
    fun getNetworkProviders(): List<CalendarProvider>

    /**
     * Get providers that support CalDAV protocol.
     *
     * @return List of CalDAV providers
     */
    fun getCalDavProviders(): List<CalendarProvider>
}

/**
 * Default implementation of ProviderRegistry.
 *
 * Uses Hilt @IntoSet injection to collect all CalendarProvider implementations.
 * Providers are registered in ProviderModule.
 *
 * Thread safety:
 * - Provider set is immutable after construction
 * - providerMap is lazily initialized once
 * - All lookup methods are read-only
 *
 * @param providers Set of all registered CalendarProvider implementations (injected via @IntoSet)
 */
@Singleton
class ProviderRegistryImpl @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards CalendarProvider>
) : ProviderRegistry {

    /**
     * Lazy-initialized map for O(1) provider lookup by ID.
     * Thread-safe: lazy {} is synchronized by default.
     */
    private val providerMap: Map<String, CalendarProvider> by lazy {
        providers.associateBy { it.providerId }
    }

    override fun getAllProviders(): List<CalendarProvider> {
        return providers.toList()
    }

    override fun getProvider(providerId: String): CalendarProvider? {
        return providerMap[providerId]
    }

    override fun getProviderForAccount(account: Account): CalendarProvider? {
        // First try exact match via provider ID
        providerMap[account.provider]?.let { return it }

        // Fall back to handlesAccount() for custom matching
        return providers.find { it.handlesAccount(account) }
    }

    override fun getNetworkProviders(): List<CalendarProvider> {
        return providers.filter { it.requiresNetwork }
    }

    override fun getCalDavProviders(): List<CalendarProvider> {
        return providers.filter { it.capabilities.supportsCalDAV }
    }
}
