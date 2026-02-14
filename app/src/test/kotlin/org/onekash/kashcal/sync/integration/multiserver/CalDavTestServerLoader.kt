package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads CalDAV test server credentials from local.properties.
 *
 * Uses the 3-path fallback pattern from existing integration tests:
 * 1. local.properties (project root)
 * 2. ../local.properties (parent directory)
 * 3. /onekash/KashCal/local.properties (absolute path)
 */
object CalDavTestServerLoader {

    private val possiblePaths = listOf(
        "local.properties",
        "../local.properties",
        "/onekash/KashCal/local.properties"
    )

    private val propertiesCache: Map<String, String> by lazy { loadAllProperties() }

    private fun loadAllProperties(): Map<String, String> {
        val props = mutableMapOf<String, String>()
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                file.readLines().forEach { line ->
                    if (line.startsWith("#") || !line.contains("=")) return@forEach
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2 && parts[0].isNotEmpty()) {
                        props[parts[0]] = parts[1]
                    }
                }
            }
        }
        return props
    }

    /**
     * Load credentials for the given server config.
     * Returns null if credentials are not available.
     */
    fun loadCredentials(config: CalDavServerConfig): ServerCredentials? {
        val username = propertiesCache[config.usernameKey] ?: return null
        val password = propertiesCache[config.passwordKey] ?: return null

        val serverUrl = if (config.serverKey != null) {
            propertiesCache[config.serverKey] ?: config.defaultServerUrl ?: return null
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
            davEndpoint = davEndpoint
        )
    }

    /**
     * Create a CalDavClient for the given server config.
     * Returns null if credentials are not available.
     */
    fun createClient(config: CalDavServerConfig): Pair<CalDavClient, ServerCredentials>? {
        val creds = loadCredentials(config) ?: return null
        val quirks = config.quirksFactory(creds.serverUrl)
        val factory = OkHttpCalDavClientFactory()
        val credentials = Credentials(
            username = creds.username,
            password = creds.password,
            serverUrl = creds.davEndpoint
        )
        val client = factory.createClient(credentials, quirks)
        return Pair(client, creds)
    }

    /**
     * Check if a server is reachable (2-second timeout).
     */
    fun isServerReachable(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            // Accept: 200-299 (OK), 301/302/307/308 (redirect), 401 (auth required)
            code in 200..399 || code == 401
        } catch (_: Exception) {
            false
        }
    }
}

data class ServerCredentials(
    val username: String,
    val password: String,
    val serverUrl: String,
    val davEndpoint: String
)
