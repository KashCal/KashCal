package org.onekash.kashcal.sync.provider.local

import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.provider.CalendarProvider
import org.onekash.kashcal.sync.provider.ProviderCapabilities
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local calendar provider for offline-only calendars.
 *
 * Local calendars:
 * - Don't sync to any server (offline-only)
 * - Don't require authentication
 * - Store events only in local SQLite database
 * - Support all event features (recurring, reminders, etc.)
 *
 * Use cases:
 * - Default calendar for users without iCloud
 * - Private calendars that shouldn't sync
 * - Offline-first usage
 */
@Singleton
class LocalProvider @Inject constructor() : CalendarProvider {

    override val providerId: String = PROVIDER_ID

    override val displayName: String = "Local"

    override val requiresNetwork: Boolean = false

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsCalDAV = false,
        supportsIncrementalSync = false,  // No server to sync with
        supportsReminders = true,          // Local reminders work fine
        supportsPush = false,
        maxSyncRangeMonths = 0
    )

    /**
     * Local provider doesn't use CalDAV.
     */
    override fun getQuirks(): CalDavQuirks? = null

    /**
     * Local provider doesn't require credentials.
     */
    override fun getCredentialProvider(): CredentialProvider? = null

    companion object {
        /**
         * Provider ID stored in Account.provider field.
         * Must match value used in LocalCalendarInitializer.
         */
        const val PROVIDER_ID = "local"

        /**
         * URL scheme for local calendar caldavUrl field.
         * Format: "local://calendar/{id}"
         */
        const val URL_SCHEME = "local://"
    }
}
