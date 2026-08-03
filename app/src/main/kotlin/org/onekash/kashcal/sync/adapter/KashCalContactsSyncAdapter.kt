package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

/**
 * Stub SyncAdapter for ContactsProvider registration.
 *
 * This adapter does NOT perform real sync — CardDAV contact sync runs via
 * WorkManager, mirroring the calendar side.
 *
 * Its sole purpose is to register with contentAuthority="com.android.contacts"
 * under the dedicated `org.onekash.kashcal.contacts` account type, so Android
 * ties the per-login contacts account to the Contacts Provider and does not
 * purge its RawContacts.
 *
 * Auto-sync is disabled via [ContactSystemAccountRegistrar] so this method
 * should rarely be called. If it is (e.g., user taps "Sync" in system
 * Settings), it's a safe no-op.
 */
class KashCalContactsSyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    companion object {
        private const val TAG = "KashCalContactsSyncAdapter"
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        Log.d(TAG, "onPerformSync called (no-op, sync via WorkManager)")
    }
}
