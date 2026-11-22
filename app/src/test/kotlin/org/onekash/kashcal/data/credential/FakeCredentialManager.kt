package org.onekash.kashcal.data.credential

/** In-memory [CredentialManager] for tests: every save succeeds and is kept in [stored]. */
class FakeCredentialManager : CredentialManager {

    val stored = mutableMapOf<Long, AccountCredentials>()

    override fun isEncryptionAvailable(): Boolean = true

    override suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean {
        stored[accountId] = credentials
        return true
    }

    override suspend fun getCredentials(accountId: Long): AccountCredentials? = stored[accountId]

    override suspend fun hasCredentials(accountId: Long): Boolean = accountId in stored

    override suspend fun deleteCredentials(accountId: Long) {
        stored.remove(accountId)
    }

    override suspend fun clearAllCredentials() {
        stored.clear()
    }
}
