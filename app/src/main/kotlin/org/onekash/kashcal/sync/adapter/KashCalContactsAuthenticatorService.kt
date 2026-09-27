package org.onekash.kashcal.sync.adapter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Exposes [KashCalContactsAuthenticator]'s binder to AccountManager.
 *
 * Declared exported="false": only the system account framework binds to it.
 */
class KashCalContactsAuthenticatorService : Service() {

    private lateinit var authenticator: KashCalContactsAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KashCalContactsAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
