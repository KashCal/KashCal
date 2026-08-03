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
     */
    fun removeAccount(email: String) {
        try {
            val accountManager = AccountManager.get(context)
            accountManager
                .getAccountsByType(KashCalContactsAuthenticator.ACCOUNT_TYPE)
                .filter { it.name == email }
                .forEach { accountManager.removeAccountExplicitly(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove contacts account", e)
        }
    }
}
