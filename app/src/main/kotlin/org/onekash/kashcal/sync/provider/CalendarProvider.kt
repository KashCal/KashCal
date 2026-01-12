package org.onekash.kashcal.sync.provider

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.quirks.CalDavQuirks

/**
 * Abstraction for calendar sync providers (iCloud, Google, local, etc.).
 *
 * This interface enables multi-provider support by encapsulating:
 * - Provider identification and display info
 * - CalDAV quirks for XML parsing differences
 * - Credential management
 * - Provider capabilities
 *
 * Architecture:
 * ```
 *                     CalendarProvider (interface)
 *                            │
 *          ┌─────────────────┼─────────────────┐
 *          ▼                 ▼                 ▼
 *    ICloudProvider    LocalProvider    (future providers)
 *          │                 │
 *          ▼                 ▼
 *    ICloudQuirks       (no quirks)
 *    ICloudCreds        (no creds)
 * ```
 *
 * Following Android Data Layer best practices:
 * - Single responsibility: Each provider handles only its own sync/auth
 * - Immutable data: ProviderCapabilities is a data class
 * - Main-safe: All operations are synchronous (quirks/creds are pre-loaded)
 *
 * @see ProviderRegistry for runtime provider lookup
 */
interface CalendarProvider {

    /**
     * Unique provider identifier stored in Account.provider.
     * Examples: "icloud", "local"
     */
    val providerId: String

    /**
     * Human-readable display name for UI.
     * Examples: "iCloud", "Local"
     */
    val displayName: String

    /**
     * Whether this provider requires network connectivity for sync.
     * Local calendars return false, CalDAV providers return true.
     */
    val requiresNetwork: Boolean

    /**
     * Provider capabilities for feature detection.
     */
    val capabilities: ProviderCapabilities

    /**
     * Get CalDAV quirks for this provider's XML parsing.
     * Returns null for providers that don't use CalDAV (e.g., local).
     */
    fun getQuirks(): CalDavQuirks?

    /**
     * Get credential provider for this provider's authentication.
     * Returns null for providers that don't require credentials (e.g., local).
     */
    fun getCredentialProvider(): CredentialProvider?

    /**
     * Check if this provider handles the given account.
     * Default implementation matches on provider ID.
     *
     * @param account The account to check
     * @return true if this provider should handle this account
     */
    fun handlesAccount(account: Account): Boolean = account.provider == providerId
}

/**
 * Capabilities that a calendar provider supports.
 *
 * Used for feature detection to enable/disable UI and sync features
 * based on what the provider supports.
 *
 * @property supportsCalDAV Whether the provider uses CalDAV protocol
 * @property supportsIncrementalSync Whether the provider supports sync-token/ctag for incremental sync
 * @property supportsReminders Whether the provider supports VALARM reminders
 * @property supportsPush Whether the provider supports push notifications for changes
 * @property maxSyncRangeMonths Maximum months to sync (0 = unlimited)
 */
data class ProviderCapabilities(
    val supportsCalDAV: Boolean = false,
    val supportsIncrementalSync: Boolean = true,
    val supportsReminders: Boolean = true,
    val supportsPush: Boolean = false,
    val maxSyncRangeMonths: Int = 0
)
