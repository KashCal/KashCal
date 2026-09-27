package org.onekash.kashcal.sync.adapter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Exposes [KashCalSyncAdapter]'s binder to Android's sync framework.
 *
 * Must be exported="true" for the sync framework to bind to it. One adapter instance is shared
 * across service instances, created under a lock.
 */
class KashCalSyncAdapterService : Service() {

    companion object {
        private val LOCK = Any()
        private var syncAdapter: KashCalSyncAdapter? = null
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(LOCK) {
            if (syncAdapter == null) {
                syncAdapter = KashCalSyncAdapter(applicationContext, false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = syncAdapter?.syncAdapterBinder
}
