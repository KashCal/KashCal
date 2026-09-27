package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log

/**
 * Ensures the singleton KashCal calendar account exists in Android AccountManager.
 *
 * Android needs it to recognize KashCal as a calendar app and route CalendarProvider intents
 * (`content://com.android.calendar`) to it. The account syncs nothing; sync runs on WorkManager.
 *
 * Runs at every app start and is idempotent:
 * - First install, an account the user removed, or a restore to a new device: creates it.
 * - Account present: a no-op.
 * - Any exception, including OEM-ROM failures: logged, never crashes startup.
 */
class SystemAccountRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "SystemAccountRegistrar"
        private const val ACCOUNT_NAME = "KashCal"
        private const val CALENDAR_AUTHORITY = "com.android.calendar"
    }

    fun ensureAccount() {
        try {
            val accountManager = AccountManager.get(context)
            val existing = accountManager.getAccountsByType(KashCalAuthenticator.ACCOUNT_TYPE)

            if (existing.isNotEmpty()) {
                Log.d(TAG, "Account already registered")
                return
            }

            val account = Account(ACCOUNT_NAME, KashCalAuthenticator.ACCOUNT_TYPE)
            val created = accountManager.addAccountExplicitly(account, null, null)

            if (created) {
                // Syncable so CalendarProvider recognizes it, but no auto-sync: sync runs on
                // WorkManager.
                ContentResolver.setIsSyncable(account, CALENDAR_AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(account, CALENDAR_AUTHORITY, false)
                Log.i(TAG, "Registered KashCal account for CalendarProvider visibility")
            } else {
                Log.w(TAG, "Failed to create account (may already exist)")
            }
        } catch (e: Exception) {
            // Registration is non-critical; never crash app startup. Some OEM ROMs throw
            // SecurityException when the authenticator isn't registered yet (a race between
            // manifest parsing and onCreate).
            Log.w(TAG, "Failed to register CalendarProvider account", e)
        }
    }
}
