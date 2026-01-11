package org.onekash.kashcal.sync.provider.icloud

import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.provider.CalendarProvider
import org.onekash.kashcal.sync.provider.ProviderCapabilities
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iCloud CalDAV calendar provider.
 *
 * Encapsulates all iCloud-specific functionality:
 * - XML parsing quirks (ICloudQuirks)
 * - Credential management (ICloudCredentialProvider)
 * - iCloud-specific capabilities
 *
 * iCloud CalDAV characteristics:
 * - Uses app-specific passwords (2FA requirement)
 * - Supports incremental sync via sync-token
 * - Supports VALARM with vendor-specific extensions
 * - Redirects to regional servers (p*-caldav.icloud.com)
 *
 * @see ICloudQuirks for XML parsing details
 * @see ICloudCredentialProvider for credential management
 */
@Singleton
class ICloudProvider @Inject constructor(
    private val quirks: ICloudQuirks,
    private val credentialProvider: ICloudCredentialProvider
) : CalendarProvider {

    override val providerId: String = PROVIDER_ID

    override val displayName: String = "iCloud"

    override val requiresNetwork: Boolean = true

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsCalDAV = true,
        supportsIncrementalSync = true,
        supportsReminders = true,
        supportsPush = false,  // iCloud doesn't support CalDAV push
        maxSyncRangeMonths = 0 // No limit
    )

    override fun getQuirks(): CalDavQuirks = quirks

    override fun getCredentialProvider(): CredentialProvider = credentialProvider

    companion object {
        /**
         * Provider ID stored in Account.provider field.
         * Must match value used in AccountDiscoveryService and database queries.
         */
        const val PROVIDER_ID = "icloud"

        /**
         * iCloud CalDAV server base URL.
         */
        const val BASE_URL = "https://caldav.icloud.com"
    }
}
