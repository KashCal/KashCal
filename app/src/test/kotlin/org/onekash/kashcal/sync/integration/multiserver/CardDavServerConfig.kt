package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.carddav.CardDavQuirks
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ICloudCardDavQuirks

/**
 * Configuration for a CardDAV server used in parameterized read-path integration
 * tests.
 *
 * A deliberately SEPARATE type from [CalDavServerConfig] rather than an
 * extension of it: the two protocols share credential *keys* in local.properties
 * but not their endpoint shapes, discovery quirks, or quirks factories. Reusing
 * the same credential keys (BAIKAL_*, RADICALE_*, …) keeps a single set of
 * secrets; everything else is CardDAV-specific.
 */
data class CardDavServerConfig(
    val name: String,
    val serverKey: String?,
    val usernameKey: String,
    val passwordKey: String,
    val defaultServerUrl: String?,
    /** Suffix appended to the server root to reach the CardDAV entry point. */
    val davEndpointSuffix: String? = null,
    val quirksFactory: (String) -> CardDavQuirks,
    /** RFC 6764 `/.well-known/carddav` discovery vs. targeting the endpoint directly. */
    val usesWellKnownDiscovery: Boolean = false,
) {
    override fun toString(): String = name

    companion object {
        val ICLOUD = CardDavServerConfig(
            name = "iCloud",
            serverKey = null,
            usernameKey = "ICLOUD_USERNAME",
            passwordKey = "ICLOUD_APP_PASSWORD",
            defaultServerUrl = "https://contacts.icloud.com",
            quirksFactory = { ICloudCardDavQuirks() },
            usesWellKnownDiscovery = false,
        )

        // Radicale serves CardDAV from the same root as CalDAV (any credentials
        // accepted in the local container).
        val RADICALE = CardDavServerConfig(
            name = "Radicale",
            serverKey = "RADICALE_SERVER",
            usernameKey = "RADICALE_USERNAME",
            passwordKey = "RADICALE_PASSWORD",
            defaultServerUrl = "http://localhost:5232",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = false,
        )

        // Baikal (sabre/dav) exposes CardDAV under the same /dav.php/ entry point
        // as its CalDAV; current-user-principal discovery resolves the
        // addressbook-home-set from there.
        val BAIKAL = CardDavServerConfig(
            name = "Baikal",
            serverKey = "BAIKAL_SERVER",
            usernameKey = "BAIKAL_USERNAME",
            passwordKey = "BAIKAL_PASSWORD",
            defaultServerUrl = "http://localhost:8081",
            davEndpointSuffix = "/dav.php/",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = false,
        )

        // Nextcloud serves CardDAV under /remote.php/dav/; RFC 6764 well-known
        // redirects there.
        val NEXTCLOUD = CardDavServerConfig(
            name = "Nextcloud",
            serverKey = "NEXTCLOUD_SERVER",
            usernameKey = "NEXTCLOUD_USERNAME",
            passwordKey = "NEXTCLOUD_PASSWORD",
            defaultServerUrl = null,
            usesWellKnownDiscovery = true,
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
        )

        // SOGo exposes CardDAV under /SOGo/dav/, parallel to its CalDAV endpoint.
        val SOGO = CardDavServerConfig(
            name = "SOGo",
            serverKey = "SOGO_SERVER",
            usernameKey = "SOGO_USERNAME",
            passwordKey = "SOGO_PASSWORD",
            defaultServerUrl = "http://localhost:8084",
            davEndpointSuffix = "/SOGo/dav/",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = false,
        )

        // Cyrus (the engine Fastmail runs) serves CardDAV under /dav/ with RFC
        // 6764 well-known discovery; the addressbook home is
        // /dav/addressbooks/user/<user>/.
        val CYRUS = CardDavServerConfig(
            name = "Cyrus",
            serverKey = "CYRUS_SERVER",
            usernameKey = "CYRUS_USERNAME",
            passwordKey = "CYRUS_PASSWORD",
            defaultServerUrl = "http://localhost:8090",
            davEndpointSuffix = "/dav/",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = true,
        )

        fun allServers(): List<CardDavServerConfig> = listOf(
            ICLOUD, RADICALE, BAIKAL, NEXTCLOUD, SOGO, CYRUS
        )
    }
}
