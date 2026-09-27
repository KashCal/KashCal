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
 * Reads every one of these files that exists, in order, and a key in a later file overrides the
 * same key in an earlier one:
 * 1. local.properties (working directory)
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

    /**
     * Looks up any local.properties key, e.g. a cross-account attendee address. Null when absent.
     */
    fun property(key: String): String? = propertiesCache[key]

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
     * Loads credentials for [config], or null when the username, the password or a server URL
     * is missing. [ServerCredentials.davEndpoint] is the server URL plus the config's suffix.
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

    /** Creates a [CalDavClient] for [config], or null when [loadCredentials] returns null. */
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
     * Returns whether [url] answers OPTIONS with an accepted code. Redirects aren't followed;
     * connect and read each time out after 2 seconds.
     */
    fun isServerReachable(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            // Besides 2xx and 3xx, accept the codes live servers return at the probed path:
            // 401 (auth required), 403 (e.g. iCloud root), 404 (e.g. Fastmail root). Any other
            // code, such as Zoho's 400, is unreachable. Anything that throws (no socket, DNS
            // failure, timeout) gives false below.
            code in 200..399 || code == 401 || code == 403 || code == 404
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
