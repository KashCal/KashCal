package org.onekash.kashcal.domain.model

/**
 * Enum representing calendar account providers.
 *
 * Provides type-safe provider identification with associated capabilities.
 * Replaces string-based provider detection throughout the codebase.
 *
 * @property displayName Human-readable name for UI
 * @property requiresNetwork Whether sync requires network connectivity
 * @property supportsCalDAV Whether provider uses CalDAV protocol
 * @property supportsIncrementalSync Whether provider supports sync-token/ctag
 * @property supportsReminders Whether provider supports VALARM reminders
 * @property supportsPush Whether provider supports push notifications
 * @property maxSyncRangeMonths Maximum months to sync (0 = unlimited)
 */
enum class AccountProvider(
    val displayName: String,
    val requiresNetwork: Boolean,
    val supportsCalDAV: Boolean,
    val supportsIncrementalSync: Boolean,
    val supportsReminders: Boolean,
    val supportsPush: Boolean,
    val maxSyncRangeMonths: Int
) {
    LOCAL(
        displayName = "Local",
        requiresNetwork = false,
        supportsCalDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    ICLOUD(
        displayName = "iCloud",
        requiresNetwork = true,
        supportsCalDAV = true,
        supportsIncrementalSync = true,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    ICS(
        displayName = "ICS Subscription",
        requiresNetwork = true,
        supportsCalDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    CONTACTS(
        displayName = "Contact Birthdays",
        requiresNetwork = false,
        supportsCalDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    CALDAV(
        displayName = "CalDAV",
        requiresNetwork = true,
        supportsCalDAV = true,
        supportsIncrementalSync = true,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    );

    /** Whether this provider syncs to a remote server */
    val requiresSync: Boolean get() = this != LOCAL

    /** Whether this provider is pull-only (no push) */
    val pullOnly: Boolean get() = this == ICS

    companion object {
        /**
         * Convert database string to enum.
         *
         * @param value The stored string value (e.g., "icloud", "local")
         * @return The corresponding AccountProvider
         * @throws IllegalArgumentException if value is unknown (fail-fast on data corruption)
         */
        fun fromString(value: String): AccountProvider = when (value.lowercase()) {
            "local" -> LOCAL
            "icloud" -> ICLOUD
            "ics" -> ICS
            "contacts" -> CONTACTS
            "caldav" -> CALDAV
            else -> throw IllegalArgumentException("Unknown provider: $value")
        }
    }
}
