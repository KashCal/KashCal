package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

/**
 * Stub SyncAdapter for CalendarProvider registration.
 *
 * This adapter does NOT perform real sync — that's handled by
 * [org.onekash.kashcal.sync.worker.CalDavSyncWorker] via WorkManager.
 *
 * Its sole purpose is to register with contentAuthority="com.android.calendar"
 * so Android recognizes KashCal as a calendar app and routes
 * `content://com.android.calendar` intents to it.
 *
 * Auto-sync is disabled via [SystemAccountRegistrar] so this method
 * should rarely be called. If it is (e.g., user taps "Sync" in system
 * Settings), it's a safe no-op.
 */
class KashCalSyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    companion object {
        private const val TAG = "KashCalSyncAdapter"
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
