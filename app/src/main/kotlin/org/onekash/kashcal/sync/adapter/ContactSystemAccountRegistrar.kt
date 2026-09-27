package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the per-login contacts account in Android AccountManager.
 *
 * Unlike the singleton calendar account ([SystemAccountRegistrar]), contacts get one account per
 * login, named after the login email, under the dedicated `org.onekash.kashcal.contacts` type
 * ([KashCalContactsAuthenticator]). Android shows the account name as the Contacts source label,
 * so the user sees the login email. A registered account type is also what stops Android from
 * purging the RawContacts written under it.
 *
 * [ensureAccount] runs when a login enables contact sync; [removeAccount] runs when contact sync
 * is disabled or the login is deleted. Both are idempotent and catch every exception, so a
 * registration failure never crashes the caller.
 */
@Singleton
class ContactSystemAccountRegistrar @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ContactSystemAccountRegistrar"
        private const val CONTACTS_AUTHORITY = "com.android.contacts"
    }

    /** Creates the contacts account named [email] if missing; a no-op when it exists. */
    fun ensureAccount(email: String) {
        try {
            val accountManager = AccountManager.get(context)
            val account = Account(email, KashCalContactsAuthenticator.ACCOUNT_TYPE)

            val exists = accountManager
                .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
                .any { it.name == email }
            if (exists) {
                Log.d(TAG, "Contacts account already registered for this login")
                return
            }

            val created = accountManager.addAccountExplicitly(account, null, null)
            if (created) {
                // Syncable so ContactsProvider recognizes it, but no auto-sync: contact sync
                // runs on WorkManager.
                ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(account, CONTACTS_AUTHORITY, false)
                Log.i(TAG, "Registered contacts account for ContactsProvider visibility")
            } else {
                Log.w(TAG, "Failed to create contacts account (may already exist)")
            }
        } catch (e: Exception) {
            // Registration is non-critical; never crash the caller.
            Log.w(TAG, "Failed to register contacts account", e)
        }
    }

    /**
     * Removes the contacts account named [email], a no-op when there is none. Removing the
     * account also purges the RawContacts Android holds under it.
     *
     * @return true if no matching account is left afterwards (removed, or none existed); false if
     *   AccountManager refused to remove one or threw, meaning the account and its synced
     *   RawContacts survived.
     */
    fun removeAccount(email: String): Boolean =
        removeAccount(email, AccountManager.get(context))

    /** Takes [accountManager] as a parameter so tests can reach the failure path. */
    internal fun removeAccount(email: String, accountManager: AccountManager): Boolean {
        return try {
            val matching = accountManager
                .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
                .filter { it.name == email }
            var allRemoved = true
            for (account in matching) {
                if (!accountManager.removeAccountExplicitly(account)) {
                    allRemoved = false
                    // The account and its RawContacts stay behind; report false so the login
                    // doesn't read as cleaned up.
                    Log.w(TAG, "AccountManager declined to remove a contacts account")
                }
            }
            allRemoved
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove contacts account", e)
            false
        }
    }
}
