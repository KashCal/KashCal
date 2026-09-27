package org.onekash.kashcal.domain.model

/**
 * Identifies an account's provider and the capabilities that follow from it.
 *
 * @property displayName name shown when the account has none of its own.
 * @property requiresNetwork whether sync needs network connectivity.
 * @property supportsCalDAV whether the provider syncs calendars over CalDAV.
 * @property supportsCardDAV whether the provider can sync contacts over CardDAV.
 * @property supportsIncrementalSync whether the provider supports sync-token or ctag.
 * @property supportsReminders whether the provider supports VALARM reminders.
 * @property supportsPush whether the provider supports push notifications.
 * @property maxSyncRangeMonths maximum months to sync; 0 means unlimited.
 */
enum class AccountProvider(
    val displayName: String,
    val requiresNetwork: Boolean,
    val supportsCalDAV: Boolean,
    val supportsCardDAV: Boolean,
    val supportsIncrementalSync: Boolean,
    val supportsReminders: Boolean,
    val supportsPush: Boolean,
    val maxSyncRangeMonths: Int
) {
    LOCAL(
        displayName = "Local",
        requiresNetwork = false,
        supportsCalDAV = false,
        supportsCardDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    ICLOUD(
        displayName = "iCloud",
        requiresNetwork = true,
        supportsCalDAV = true,
        supportsCardDAV = true,
        supportsIncrementalSync = true,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    ICS(
        displayName = "ICS Subscription",
        requiresNetwork = true,
        supportsCalDAV = false,
        supportsCardDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    CONTACTS(
        displayName = "Contact Birthdays",
        requiresNetwork = false,
        supportsCalDAV = false,
        supportsCardDAV = false,
        supportsIncrementalSync = false,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    ),
    CALDAV(
        displayName = "CalDAV",
        requiresNetwork = true,
        supportsCalDAV = true,
        supportsCardDAV = true,
        supportsIncrementalSync = true,
        supportsReminders = true,
        supportsPush = false,
        maxSyncRangeMonths = 0
    );

    /** True for every provider except [LOCAL], including [CONTACTS], which has no server. */
    val requiresSync: Boolean get() = this != LOCAL

    /** Whether this provider only pulls and never pushes. */
    val pullOnly: Boolean get() = this == ICS

    companion object {
        /**
         * Returns the provider for a stored value such as "icloud" or "local", ignoring case.
         *
         * @throws IllegalArgumentException if [value] is unknown, to fail fast on corrupt data.
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
