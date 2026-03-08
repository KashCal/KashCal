package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log

/**
 * Ensures a KashCal account exists in Android AccountManager.
 *
 * This is required for Android to recognize KashCal as a calendar app
 * and route CalendarProvider intents (`content://com.android.calendar`)
 * to it. The account is a registration stub — real sync uses WorkManager.
 *
 * Safe to call multiple times (idempotent). Handles:
 * - First install: creates account
 * - Subsequent launches: no-op (account exists)
 * - User manually removed account: re-creates on next launch
 * - Backup/restore: re-creates if missing on new device
 * - OEM quirks: try-catch prevents startup crash
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
                // Syncable (recognized by CalendarProvider) but no auto-sync
                // (real sync is via WorkManager)
                ContentResolver.setIsSyncable(account, CALENDAR_AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(account, CALENDAR_AUTHORITY, false)
                Log.i(TAG, "Registered KashCal account for CalendarProvider visibility")
            } else {
                Log.w(TAG, "Failed to create account (may already exist)")
            }
        } catch (e: Exception) {
            // Don't crash app startup for a non-critical registration feature.
            // Known edge cases: SecurityException on some OEM ROMs when authenticator
            // isn't fully registered yet (race between manifest parsing and onCreate).
            Log.w(TAG, "Failed to register CalendarProvider account", e)
        }
    }
}
