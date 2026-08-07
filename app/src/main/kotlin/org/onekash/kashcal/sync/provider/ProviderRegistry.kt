package org.onekash.kashcal.sync.provider

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.provider.caldav.CalDavCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.carddav.CardDavQuirks
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ICloudCardDavQuirks
import org.onekash.kashcal.sync.carddav.ZohoCardDavQuirks
import org.onekash.kashcal.sync.carddav.baseHostOf
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for provider-specific services (quirks, credentials).
 *
 * Single lookup point for CalDAV quirks and credential providers based on AccountProvider.
 * Replaces the old CalendarProvider interface with direct enum-based routing.
 *
 * Usage:
 * ```kotlin
 * // For providers with known server URLs (iCloud)
 * val quirks = providerRegistry.getQuirks(account.provider)
 *
 * // For providers needing account-specific server URL (generic CalDAV)
 * val quirks = providerRegistry.getQuirksForAccount(account)
 *
 * val credentials = providerRegistry.getCredentialProvider(account.provider)
 * ```
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val icloudQuirks: ICloudQuirks,
    private val icloudCredentials: ICloudCredentialProvider,
    private val caldavCredentials: CalDavCredentialProvider
) {
    /**
     * Get CalDAV quirks for a provider.
     *
     * Note: For CALDAV provider, this returns null because DefaultQuirks requires
     * the server URL from Account.homeSetUrl. Use [getQuirksForAccount] instead.
     *
     * @param provider The account provider
     * @return CalDavQuirks for the provider, or null if provider doesn't use CalDAV
     */
    fun getQuirks(provider: AccountProvider): CalDavQuirks? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudQuirks
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.CALDAV -> null  // Use getQuirksForAccount() - needs server URL from Account
    }

    /**
     * Get CalDAV quirks for a specific account.
     *
     * Required for CALDAV because DefaultQuirks needs the server URL from Account.homeSetUrl.
     * For iCloud, uses the singleton ICloudQuirks which has a fixed base URL.
     *
     * @param account The account entity
     * @return CalDavQuirks for the account, or null if account doesn't use CalDAV
     * @throws IllegalStateException if CALDAV account is missing homeSetUrl
     */
    fun getQuirksForAccount(account: Account): CalDavQuirks? = when (account.provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudQuirks
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.CALDAV -> {
            val serverUrl = checkNotNull(account.homeSetUrl) {
                "CALDAV account ${account.id} missing homeSetUrl"
            }
            DefaultQuirks(serverUrl)
        }
    }

    /**
     * Get CardDAV quirks + discovery entry point for a specific account, the
     * contact-sync analogue of [getQuirksForAccount]. iCloud starts from its fixed
     * contacts entry host; generic CardDAV starts from the account's own home host
     * (derived from `homeSetUrl`). Returns null for providers that don't do CardDAV
     * or (for CardDAV-capable accounts) whose home host can't be resolved, so the
     * caller can skip rather than start discovery from an empty URL.
     */
    fun getCardDavQuirksForAccount(account: Account): CardDavQuirks? = when (account.provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.ICLOUD -> ICloudCardDavQuirks()
        AccountProvider.CALDAV -> {
            val homeSetUrl = account.homeSetUrl.orEmpty()
            val base = baseHostOf(homeSetUrl)
            when {
                base.isBlank() -> null
                // Zoho serves contacts from a pinned host (contacts.zoho.com) that
                // differs from its calendar home host, so the generic "derive
                // contacts base from the calendar host" path would target the wrong
                // host. Selected by the SERVER host, never the login email (which can
                // be custom/Gmail-backed). Only the verified global .com service is
                // pinned; regional data centers stay on the generic path (see
                // ZohoCardDavQuirks).
                isZohoGlobalHost(homeSetUrl) -> ZohoCardDavQuirks()
                else -> DefaultCardDavQuirks(serverBaseUrl = base)
            }
        }
    }

    /**
     * Whether a server URL's host is Zoho's verified global `.com` service. Parses
     * with [java.net.URI] so a host is compared without any `:port` (a port left on
     * the string would fail the suffix match and misroute to generic discovery).
     */
    private fun isZohoGlobalHost(serverUrl: String): Boolean {
        val host = runCatching { java.net.URI(serverUrl).host }.getOrNull()?.lowercase()
            ?: return false
        return host == "zoho.com" || host.endsWith(".zoho.com")
    }

    /**
     * Get credential provider for a provider.
     *
     * @param provider The account provider
     * @return CredentialProvider for the provider, or null if provider doesn't need credentials
     */
    fun getCredentialProvider(provider: AccountProvider): CredentialProvider? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudCredentials
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.CALDAV -> caldavCredentials
    }
}
