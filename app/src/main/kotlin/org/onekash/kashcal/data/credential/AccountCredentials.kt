package org.onekash.kashcal.data.credential

/**
 * Complete credential model supporting both iCloud and CalDAV providers.
 *
 * Unified storage format:
 * - iCloud: appleId stored in username field, serverUrl uses ICLOUD_DEFAULT_SERVER_URL
 * - CalDAV: email/username stored in username field, serverUrl is user-provided
 *
 * @property username Apple ID for iCloud, email/username for CalDAV
 * @property password App-specific password
 * @property serverUrl CalDAV server URL (iCloud uses default)
 * @property trustInsecure Allow self-signed certificates (CalDAV only)
 * @property principalUrl Discovered CalDAV principal URL
 * @property calendarHomeSet Discovered CalDAV calendar home set URL
 */
data class AccountCredentials(
    val username: String,
    val password: String,
    val serverUrl: String,
    val trustInsecure: Boolean = false,
    val principalUrl: String? = null,
    val calendarHomeSet: String? = null
) {
    companion object {
        /** Default iCloud CalDAV server URL */
        const val ICLOUD_DEFAULT_SERVER_URL = "https://caldav.icloud.com"
    }
}
