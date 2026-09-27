package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

/**
 * Registers a no-op sync adapter for contentAuthority="com.android.calendar".
 *
 * Sync runs in [org.onekash.kashcal.sync.worker.CalDavSyncWorker] on WorkManager. The
 * registration is what makes Android recognize KashCal as a calendar app and route
 * `content://com.android.calendar` intents to it.
 *
 * [SystemAccountRegistrar] turns auto-sync off, so [onPerformSync] runs only on a manual
 * trigger such as "Sync" in system Settings, and does nothing.
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
