package org.onekash.kashcal.sync.adapter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Exposes [KashCalAuthenticator]'s binder to AccountManager.
 *
 * Declared exported="false": only the system account framework binds to it.
 */
class KashCalAuthenticatorService : Service() {

    private lateinit var authenticator: KashCalAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KashCalAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
