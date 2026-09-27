package org.onekash.kashcal.sync.adapter

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

/**
 * Registers a no-op sync adapter for contentAuthority="com.android.contacts".
 *
 * CardDAV contact sync runs on WorkManager, like calendar sync. The registration under the
 * `org.onekash.kashcal.contacts` account type is what ties the per-login contacts account to
 * the Contacts Provider so Android doesn't purge its RawContacts.
 *
 * [ContactSystemAccountRegistrar] turns auto-sync off, so [onPerformSync] runs only on a manual
 * trigger such as "Sync" in system Settings, and does nothing.
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
