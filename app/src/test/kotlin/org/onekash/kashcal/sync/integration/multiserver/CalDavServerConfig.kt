package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Configuration for a CalDAV server used in parameterized integration tests.
 *
 * Each server has credential keys for local.properties, a quirks factory,
 * and optional URL transforms (e.g., Baikal appends /dav.php/).
 */
data class CalDavServerConfig(
    val name: String,
    val serverKey: String?,
    val usernameKey: String,
    val passwordKey: String,
    val defaultServerUrl: String?,
    val davEndpointSuffix: String? = null,
    val quirksFactory: (String) -> CalDavQuirks,
    val usesWellKnownDiscovery: Boolean = false,
    val supportsCtag: Boolean = true
) {
    override fun toString(): String = name

    companion object {
        val ICLOUD = CalDavServerConfig(
            name = "iCloud",
            serverKey = null,
            usernameKey = "caldav.username",
            passwordKey = "caldav.app_password",
            defaultServerUrl = "https://caldav.icloud.com",
            quirksFactory = { ICloudQuirks() },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        val STALWART = CalDavServerConfig(
            name = "Stalwart",
            serverKey = "STALWART_SERVER",
            usernameKey = "STALWART_USERNAME",
            passwordKey = "STALWART_PASSWORD",
            defaultServerUrl = "http://localhost:8080",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = true,
            supportsCtag = true
        )

        val BAIKAL = CalDavServerConfig(
            name = "Baikal",
            serverKey = "BAIKAL_SERVER",
            usernameKey = "BAIKAL_USERNAME",
            passwordKey = "BAIKAL_PASSWORD",
            defaultServerUrl = "http://localhost:8081",
            davEndpointSuffix = "/dav.php/",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        val RADICALE = CalDavServerConfig(
            name = "Radicale",
            serverKey = "RADICALE_SERVER",
            usernameKey = "RADICALE_USERNAME",
            passwordKey = "RADICALE_PASSWORD",
            defaultServerUrl = "http://localhost:5232",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        val NEXTCLOUD = CalDavServerConfig(
            name = "Nextcloud",
            serverKey = "NEXTCLOUD_SERVER",
            usernameKey = "NEXTCLOUD_USERNAME",
            passwordKey = "NEXTCLOUD_PASSWORD",
            defaultServerUrl = null,
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = true,
            supportsCtag = true
        )

        val ZOHO = CalDavServerConfig(
            name = "Zoho",
            serverKey = "ZOHO_SERVER",
            usernameKey = "ZOHO_USERNAME",
            passwordKey = "ZOHO_PASSWORD",
            defaultServerUrl = null,
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = false
        )

        val SOGO = CalDavServerConfig(
            name = "SOGo",
            serverKey = "SOGO_SERVER",
            usernameKey = "SOGO_USERNAME",
            passwordKey = "SOGO_PASSWORD",
            defaultServerUrl = "http://localhost:8084",
            davEndpointSuffix = "/SOGo/dav/",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        fun allServers(): List<CalDavServerConfig> = listOf(
            ICLOUD, STALWART, BAIKAL, RADICALE, NEXTCLOUD, ZOHO, SOGO
        )
    }
}
