package org.onekash.kashcal.sync.adapter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service wrapper for [KashCalAuthenticator].
 *
 * Exposes the authenticator's IBinder to Android's AccountManager framework.
 * Only the system account framework binds to this service (exported="false").
 */
class KashCalAuthenticatorService : Service() {

    private lateinit var authenticator: KashCalAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KashCalAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
