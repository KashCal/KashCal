package org.onekash.kashcal.sync.adapter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service wrapper for [KashCalContactsAuthenticator].
 *
 * Exposes the authenticator's IBinder to Android's AccountManager framework.
 * Only the system account framework binds to this service (exported="false").
 */
class KashCalContactsAuthenticatorService : Service() {

    private lateinit var authenticator: KashCalContactsAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KashCalContactsAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
