package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.carddav.CardDavQuirks
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ZohoCardDavQuirks
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
    /**
     * The host a real account of this provider has *stored* from CalDAV setup, when
     * it differs from the CardDAV [defaultServerUrl]. Only split-host providers set
     * it (Zoho: contacts on `contacts.zoho.com`, calendars on `calendar.zoho.com`).
     * The well-known discovery probe uses it to answer whether contacts are reachable
     * from the CalDAV host alone — i.e. whether the fix needs a bootstrap constant or
     * can derive the contacts host from what the account already knows.
     */
    val caldavHostUrl: String? = null,
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

        // Zoho serves CardDAV from a DIFFERENT host than its CalDAV endpoint
        // (contacts.zoho.com, not calendar.zoho.com), so it reuses the ZOHO_*
        // credentials but pins the contacts host via the production
        // ZohoCardDavQuirks (which ignores the passed URL and pins its own host),
        // mirroring iCloud's hosted default. serverKey = null keeps the calendar
        // URL out of the CardDAV path. Only the characterization probe consumes
        // this entry, so it is intentionally left OUT of allServers() (see
        // MultiServerCardDavZohoProbeTest).
        val ZOHO = CardDavServerConfig(
            name = "Zoho",
            serverKey = null,
            usernameKey = "ZOHO_USERNAME",
            passwordKey = "ZOHO_PASSWORD",
            defaultServerUrl = "https://contacts.zoho.com",
            caldavHostUrl = "https://calendar.zoho.com",
            quirksFactory = { ZohoCardDavQuirks() },
            usesWellKnownDiscovery = true,
        )

        // Fastmail serves CardDAV from carddav.fastmail.com, distinct from its
        // caldav.fastmail.com CalDAV host — a genuine split-host provider. It
        // publishes a _carddavs._tcp SRV record, so a proper SRV client would
        // reach it from the bare domain; the probe measures whether well-known
        // alone (which is all the client does today) also gets there. App-specific
        // password required, same as its CalDAV side.
        val FASTMAIL = CardDavServerConfig(
            name = "Fastmail",
            serverKey = null,
            usernameKey = "FASTMAIL_USERNAME",
            passwordKey = "FASTMAIL_PASSWORD",
            defaultServerUrl = "https://carddav.fastmail.com",
            caldavHostUrl = "https://caldav.fastmail.com",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = true,
        )

        // mailbox.org (Open-Xchange) serves BOTH CalDAV and CardDAV from
        // dav.mailbox.org — a same-host provider despite publishing SRV records.
        // Included to characterize a same-host well-known path alongside the
        // split-host ones. Reuses the MAILBOX_* CalDAV credentials.
        val MAILBOX = CardDavServerConfig(
            name = "Mailbox",
            serverKey = "MAILBOX_SERVER",
            usernameKey = "MAILBOX_USERNAME",
            passwordKey = "MAILBOX_PASSWORD",
            defaultServerUrl = "https://dav.mailbox.org",
            davEndpointSuffix = "/carddav/",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = true,
        )

        fun allServers(): List<CardDavServerConfig> = listOf(
            ICLOUD, RADICALE, BAIKAL, NEXTCLOUD, SOGO, CYRUS
        )

        /**
         * The full set the discovery-characterization probe walks, including the
         * hosted providers deliberately kept out of [allServers] (which gates the
         * assertion-bearing round-trip tests): Zoho, Fastmail, and mailbox.org.
         */
        fun allDiscoveryProbeServers(): List<CardDavServerConfig> =
            allServers() + listOf(ZOHO, FASTMAIL, MAILBOX)
    }
}
