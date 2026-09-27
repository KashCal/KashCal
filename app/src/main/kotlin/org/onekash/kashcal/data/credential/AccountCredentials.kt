package org.onekash.kashcal.data.credential

/**
 * Holds one account's stored credentials, for iCloud and CalDAV alike.
 *
 * @property username the Apple ID for iCloud; the email or username for CalDAV
 * @property password app-specific password
 * @property serverUrl user-provided for CalDAV; [ICLOUD_DEFAULT_SERVER_URL] for iCloud
 * @property trustInsecure allows self-signed certificates (CalDAV only)
 * @property principalUrl discovered CalDAV principal URL
 * @property calendarHomeSet first discovered CalDAV calendar home set URL
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
        const val ICLOUD_DEFAULT_SERVER_URL = "https://caldav.icloud.com"
    }
}
