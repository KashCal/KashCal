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
 * Unlike the singleton calendar account ([SystemAccountRegistrar]), contacts
 * get ONE account per login, NAMED AFTER THE LOGIN EMAIL, under the dedicated
 * `org.onekash.kashcal.contacts` type ([KashCalContactsAuthenticator]). Android
 * surfaces the account *name* as the Contacts source label, so a real login
 * email is what the user sees. A registered account type is also what stops
 * Android from purging any RawContacts written under it.
 *
 * [ensureAccount] is created when a login enables contact sync; [removeAccount]
 * runs on disable, sign-out, or account deletion. Both are idempotent and
 * wrapped in try-catch so a registration hiccup never crashes the caller.
 */
@Singleton
class ContactSystemAccountRegistrar @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ContactSystemAccountRegistrar"
        private const val CONTACTS_AUTHORITY = "com.android.contacts"
    }

    /**
     * Ensure a contacts account named [email] exists. Safe to call repeatedly;
     * a second call for the same login is a no-op.
     */
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
                // Syncable (recognized by ContactsProvider) but no auto-sync
                // (real sync is via WorkManager).
                ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(account, CONTACTS_AUTHORITY, false)
                Log.i(TAG, "Registered contacts account for ContactsProvider visibility")
            } else {
                Log.w(TAG, "Failed to create contacts account (may already exist)")
            }
        } catch (e: Exception) {
            // Don't crash the caller for a non-critical registration step.
            Log.w(TAG, "Failed to register contacts account", e)
        }
    }

    /**
     * Remove the contacts account named [email], if present. No-op when the
     * login has no account. Removing the account also purges any RawContacts
     * Android holds under it.
     *
     * @return true if the login has no matching account left afterwards (either it
     *   was removed, or there was none to begin with); false if AccountManager
     *   refused to remove an existing account or threw — the caller then knows the
     *   account (and its synced RawContacts) survived.
     */
    fun removeAccount(email: String): Boolean =
        removeAccount(email, AccountManager.get(context))

    /** Testable seam: the [accountManager] is injected so the failure path is reachable. */
    internal fun removeAccount(email: String, accountManager: AccountManager): Boolean {
        return try {
            val matching = accountManager
                .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
                .filter { it.name == email }
            var allRemoved = true
            for (account in matching) {
                if (!accountManager.removeAccountExplicitly(account)) {
                    allRemoved = false
                    // A stuck removal leaves the account (and its RawContacts) behind;
                    // surface it rather than pretending the login was cleaned up.
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
