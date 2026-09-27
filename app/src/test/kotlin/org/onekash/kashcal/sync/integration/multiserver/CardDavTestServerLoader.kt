package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.OkHttpCardDavClientFactory

/**
 * Loads CardDAV test server credentials and builds CardDAV clients.
 *
 * Values are read through [CalDavTestServerLoader.property], so both protocols share one
 * local.properties parse. Only the client construction, through [OkHttpCardDavClientFactory]
 * and the CardDAV quirks, is specific here.
 */
object CardDavTestServerLoader {

    /**
     * Loads credentials for [config], or null when the username, the password or a server URL
     * is missing.
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

    /** Creates a [CardDavClient] for [config], or null when [loadCredentials] returns null. */
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

    /** Delegates to [CalDavTestServerLoader.isServerReachable]: the endpoint is a plain URL. */
    fun isServerReachable(url: String): Boolean =
        CalDavTestServerLoader.isServerReachable(url)
}
