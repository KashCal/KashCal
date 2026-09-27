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
 * Looks up provider-specific services (CalDAV and CardDAV quirks, credential providers) by
 * [AccountProvider].
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
     * Returns the CalDAV quirks for [provider], or null if it doesn't use CalDAV.
     *
     * Also null for CALDAV: [DefaultQuirks] needs the server URL from `Account.homeSetUrl`, so
     * use [getQuirksForAccount].
     */
    fun getQuirks(provider: AccountProvider): CalDavQuirks? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudQuirks
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.CALDAV -> null  // Use getQuirksForAccount() - needs server URL from Account
    }

    /**
     * Returns the CalDAV quirks for [account], or null if it doesn't use CalDAV.
     *
     * CALDAV builds [DefaultQuirks] from `Account.homeSetUrl`; iCloud uses the singleton
     * [ICloudQuirks], which has a fixed base URL.
     *
     * @throws IllegalStateException if a CALDAV account has no homeSetUrl
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
     * Returns the CardDAV quirks and discovery entry point for [account], the contact-sync
     * analogue of [getQuirksForAccount].
     *
     * iCloud starts from its fixed contacts host; generic CardDAV starts from the host of
     * `homeSetUrl`. Returns null for providers without CardDAV, and for a CALDAV account whose
     * home host can't be resolved, so the caller skips instead of discovering from an empty URL.
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
                // Zoho serves contacts from a pinned host (contacts.zoho.com) that differs
                // from its calendar home host, so deriving the contacts base from the
                // calendar host would target the wrong host. Selected by the server host,
                // never the login email, which can be on a custom or third-party domain.
                // Only the verified global .com service is pinned; regional data centers
                // stay on the generic path ([ZohoCardDavQuirks]).
                isZohoGlobalHost(homeSetUrl) -> ZohoCardDavQuirks()
                else -> DefaultCardDavQuirks(serverBaseUrl = base)
            }
        }
    }

    /**
     * Returns whether [serverUrl]'s host is Zoho's verified global `.com` service. Parses with
     * [java.net.URI] so the host is compared without a `:port`, which would fail the suffix
     * match and misroute to generic discovery.
     */
    private fun isZohoGlobalHost(serverUrl: String): Boolean {
        val host = runCatching { java.net.URI(serverUrl).host }.getOrNull()?.lowercase()
            ?: return false
        return host == "zoho.com" || host.endsWith(".zoho.com")
    }

    /** Returns the credential provider for [provider], or null if it needs no credentials. */
    fun getCredentialProvider(provider: AccountProvider): CredentialProvider? = when (provider) {
        AccountProvider.LOCAL -> null
        AccountProvider.ICLOUD -> icloudCredentials
        AccountProvider.ICS -> null
        AccountProvider.CONTACTS -> null
        AccountProvider.CALDAV -> caldavCredentials
    }
}
