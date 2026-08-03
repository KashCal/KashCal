package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.OkHttpCardDavClientFactory

/**
 * Loads CardDAV test server credentials and builds read-path clients.
 *
 * Credential *values* are read through [CalDavTestServerLoader.property] so both
 * protocols share one local.properties parse (and one set of secrets). Only the
 * client construction — via [OkHttpCardDavClientFactory] and the CardDAV
 * quirks — is specific here.
 */
object CardDavTestServerLoader {

    /**
     * Resolve credentials for a CardDAV server config, or null when any required
     * key is absent.
     */
    fun loadCredentials(config: CardDavServerConfig): ServerCredentials? {
        val username = CalDavTestServerLoader.property(config.usernameKey) ?: return null
        val password = CalDavTestServerLoader.property(config.passwordKey) ?: return null

        val serverUrl = if (config.serverKey != null) {
            CalDavTestServerLoader.property(config.serverKey) ?: config.defaultServerUrl ?: return null
        } else {
            config.defaultServerUrl ?: return null
        }

        val davEndpoint = if (config.davEndpointSuffix != null) {
            serverUrl.trimEnd('/') + config.davEndpointSuffix
        } else {
            serverUrl
        }

        return ServerCredentials(
            username = username,
            password = password,
            serverUrl = serverUrl,
            davEndpoint = davEndpoint,
        )
    }

    /**
     * Build a [CardDavClient] for a server config, or null when credentials are
     * unavailable.
     */
    fun createClient(config: CardDavServerConfig): Pair<CardDavClient, ServerCredentials>? {
        val creds = loadCredentials(config) ?: return null
        val quirks = config.quirksFactory(creds.serverUrl)
        val factory = OkHttpCardDavClientFactory()
        val credentials = Credentials(
            username = creds.username,
            password = creds.password,
            serverUrl = creds.davEndpoint,
        )
        return factory.createClient(credentials, quirks) to creds
    }

    /** Reachability probe — the CardDAV endpoint is a plain URL, so the CalDAV
     *  loader's OPTIONS probe applies unchanged. */
    fun isServerReachable(url: String): Boolean =
        CalDavTestServerLoader.isServerReachable(url)
}
