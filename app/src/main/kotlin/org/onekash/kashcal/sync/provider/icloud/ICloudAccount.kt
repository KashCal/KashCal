package org.onekash.kashcal.sync.provider.icloud

/**
 * Data class representing an iCloud account for CalDAV sync.
 *
 * Contains credentials and discovered URLs for CalDAV operations.
 */
data class ICloudAccount(
    /** Apple ID (email address) */
    val appleId: String,

    /** App-specific password (NOT the main Apple ID password) */
    val appSpecificPassword: String,

    /** CalDAV server URL (usually discovered via PROPFIND) */
    val serverUrl: String = DEFAULT_SERVER_URL,

    /** Calendar home URL (discovered via principal) */
    val calendarHomeUrl: String? = null,

    /** Principal URL (for PROPFIND requests) */
    val principalUrl: String? = null,

    /** Whether sync is enabled for this account */
    val isEnabled: Boolean = true,

    /** Last successful sync timestamp (millis) */
    val lastSyncTime: Long = 0L,

    /** Server sync token for incremental sync */
    val syncToken: String? = null
) {
    /**
     * Check if credentials are present.
     */
    fun hasCredentials(): Boolean {
        return appleId.isNotBlank() && appSpecificPassword.isNotBlank()
    }

    /**
     * Check if account is ready for sync (has credentials and is enabled).
     */
    fun isReadyForSync(): Boolean {
        return hasCredentials() && isEnabled
    }

    /**
     * Check if calendar home has been discovered.
     */
    fun hasDiscoveredCalendarHome(): Boolean {
        return calendarHomeUrl != null
    }

    companion object {
        /** Default iCloud CalDAV server URL */
        const val DEFAULT_SERVER_URL = "https://caldav.icloud.com"

        /** iCloud requires app-specific passwords */
        const val APP_SPECIFIC_PASSWORD_URL = "https://appleid.apple.com/account/manage"
    }
}
