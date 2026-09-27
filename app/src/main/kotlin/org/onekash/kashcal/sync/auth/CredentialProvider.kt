package org.onekash.kashcal.sync.auth

import org.onekash.kashcal.util.maskEmail

/**
 * Loads and stores one provider's account credentials for CalDAV and CardDAV sync.
 *
 * Production implementations read [org.onekash.kashcal.data.credential.CredentialManager]
 * (EncryptedSharedPreferences); tests substitute mocks or properties files.
 */
interface CredentialProvider {

    /** Returns the credentials of Room account [accountId], or null if none are stored. */
    suspend fun getCredentials(accountId: Long): Credentials?

    /**
     * Returns the credentials of the first enabled account of this provider, or null if there
     * is none.
     */
    suspend fun getPrimaryCredentials(): Credentials?

    /** Returns whether credentials are stored for Room account [accountId]. */
    suspend fun hasCredentials(accountId: Long): Boolean

    /** Returns true if at least one account of this provider has stored credentials. */
    suspend fun hasAnyCredentials(): Boolean

    /** Saves [credentials] for Room account [accountId]; returns true on success. */
    suspend fun saveCredentials(accountId: Long, credentials: Credentials): Boolean

    /** Deletes the credentials of Room account [accountId]; returns true also when none existed. */
    suspend fun deleteCredentials(accountId: Long): Boolean

    /**
     * Clears the stored credentials of every account, of all providers, for when the user signs
     * out of every account.
     */
    suspend fun clearAllCredentials()
}

/**
 * Holds the login for one CalDAV or CardDAV account.
 *
 * @property username for iCloud, usually the Apple ID email.
 * @property password for iCloud, an app-specific password.
 * @property serverUrl defaults to iCloud's CalDAV host.
 * @property trustInsecure whether to trust self-signed certificates, for self-hosted servers.
 */
data class Credentials(
    val username: String,
    val password: String,
    val serverUrl: String = DEFAULT_ICLOUD_SERVER,
    val trustInsecure: Boolean = false
) {
    companion object {
        const val DEFAULT_ICLOUD_SERVER = "https://caldav.icloud.com"
    }

    /** Returns a loggable form: never any password characters, and a [maskEmail] username. */
    fun toSafeString(): String {
        return "Credentials(username=${username.maskEmail()}, password=****, trustInsecure=$trustInsecure)"
    }
}
