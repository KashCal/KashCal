package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.carddav.CardDavQuirks
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.carddav.ZohoCardDavQuirks
import org.onekash.kashcal.sync.carddav.ICloudCardDavQuirks

/**
 * Describes one CardDAV server for the parameterized CardDAV integration tests.
 *
 * A separate type from [CalDavServerConfig], not an extension of it: the protocols can share
 * local.properties credential keys but not endpoint shapes, discovery quirks or quirks
 * factories. Most entries reuse the CalDAV keys (BAIKAL_*, RADICALE_* and so on), so there is
 * one set of secrets; iCloud uses its own ICLOUD_* keys.
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
    /** True for RFC 6764 `/.well-known/carddav` discovery; false targets the endpoint directly. */
    val usesWellKnownDiscovery: Boolean = false,
    /**
     * True when the server accepts an external-URL photo (`PHOTO;VALUE=URI`) on write but
     * silently strips it on read-back, while keeping inline base64 photos. This is server
     * policy: the same push path keeps URL photos on conformant servers. URI-photo assertions
     * are recorded, not failed, for such a server. Set for Open-Xchange (mailbox.org).
     */
    val dropsUriPhoto: Boolean = false,
    /**
     * True when the server accepts a vCard `KIND` on write but doesn't persist it (it reads
     * back null), while keeping every other field. This is server policy: `KIND` survives the
     * same push path on conformant servers, so the KIND assertion is skipped for such a server
     * instead of failing as a lost field. Set for Open-Xchange (mailbox.org).
     */
    val dropsKind: Boolean = false,
    /**
     * The host a real account of this provider stores from CalDAV setup, when it differs from
     * the CardDAV [defaultServerUrl]. Only split-host providers set it (Zoho: contacts on
     * `contacts.zoho.com`, calendars on `calendar.zoho.com`; Fastmail likewise).
     * `MultiServerCardDavWellKnownProbeTest` uses it to check whether contacts are reachable
     * from the CalDAV host alone, or only through a bootstrap constant or another lookup.
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

        // Radicale serves CardDAV from the same root as CalDAV; the local container accepts any
        // credentials.
        val RADICALE = CardDavServerConfig(
            name = "Radicale",
            serverKey = "RADICALE_SERVER",
            usernameKey = "RADICALE_USERNAME",
            passwordKey = "RADICALE_PASSWORD",
            defaultServerUrl = "http://localhost:5232",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = false,
        )

        // Xandikos serves CardDAV from the same root as CalDAV. The local container runs with
        // --current-user-principal /user/, so principal discovery resolves the
        // addressbook-home-set from the bare root without well-known. It grants the RFC 3744
        // aggregate <all> privilege, not the leaf <write>/<write-content>, so a parser that
        // fails to map <all> to a write grant shows its book as read-only here (#281,
        // `MultiServerCardDavWritableBookDiscoveryTest`).
        val XANDIKOS = CardDavServerConfig(
            name = "Xandikos",
            serverKey = "XANDIKOS_SERVER",
            usernameKey = "XANDIKOS_USERNAME",
            passwordKey = "XANDIKOS_PASSWORD",
            defaultServerUrl = "http://localhost:8999",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = false,
        )

        // Baikal (sabre/dav) serves CardDAV under the same /dav.php/ entry point as CalDAV;
        // current-user-principal discovery resolves the addressbook-home-set from there.
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

        // Nextcloud serves CardDAV under /remote.php/dav/; RFC 6764 well-known redirects there.
        val NEXTCLOUD = CardDavServerConfig(
            name = "Nextcloud",
            serverKey = "NEXTCLOUD_SERVER",
            usernameKey = "NEXTCLOUD_USERNAME",
            passwordKey = "NEXTCLOUD_PASSWORD",
            defaultServerUrl = null,
            usesWellKnownDiscovery = true,
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
        )

        // SOGo serves CardDAV under /SOGo/dav/, the same entry point as its CalDAV.
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

        // Cyrus (the engine Fastmail runs) serves CardDAV under /dav/ with RFC 6764 well-known
        // discovery; the addressbook home is /dav/addressbooks/user/<user>/.
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

        // Zoho serves CardDAV from a different host than CalDAV (contacts.zoho.com, not
        // calendar.zoho.com). It reuses the ZOHO_* credentials but pins the contacts host through
        // the production [ZohoCardDavQuirks], which ignores the passed URL, as iCloud's hosted
        // default does. serverKey = null keeps the calendar URL out of the CardDAV path. Only the
        // probes use this entry (`MultiServerCardDavZohoProbeTest` and the
        // [allDiscoveryProbeServers] probes), so it stays out of [allServers].
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

        // Fastmail serves CardDAV from carddav.fastmail.com, a different host from its
        // caldav.fastmail.com CalDAV host. It publishes a _carddavs._tcp SRV record, so an SRV
        // lookup ([org.onekash.kashcal.sync.carddav.CardDavHostResolver]) reaches it from the
        // bare domain; the well-known probe measures whether well-known alone also gets there.
        // It needs an app-specific password, as on its CalDAV side.
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

        // mailbox.org (Open-Xchange) serves both CalDAV and CardDAV from dav.mailbox.org: a
        // same-host provider, though it publishes SRV records. It covers a same-host well-known
        // path beside the split-host ones and reuses the MAILBOX_* CalDAV credentials.
        val MAILBOX = CardDavServerConfig(
            name = "Mailbox",
            serverKey = "MAILBOX_SERVER",
            usernameKey = "MAILBOX_USERNAME",
            passwordKey = "MAILBOX_PASSWORD",
            defaultServerUrl = "https://dav.mailbox.org",
            davEndpointSuffix = "/carddav/",
            quirksFactory = { url -> DefaultCardDavQuirks(url) },
            usesWellKnownDiscovery = true,
            dropsUriPhoto = true,
            dropsKind = true,
        )

        /** Returns the local Radicale behind a local TLS proxy, as in [CalDavServerConfig]. */
        private fun proxied(name: String, url: String) = RADICALE.copy(name = name, serverKey = null, defaultServerUrl = url)
        private val PROXIES get() = if (System.getenv("KASHCAL_TLS_PROXY") == "1") listOf(
            proxied("RadicaleTLS", "https://localhost:9443"),
            proxied("RadicaleHttpUpgrade", "http://localhost:9480"),
            proxied("RadicaleAbsHttpHrefs", "https://localhost:9444"),
        ) else emptyList()

        fun allServers(): List<CardDavServerConfig> = listOf(
            ICLOUD, RADICALE, XANDIKOS, BAIKAL, NEXTCLOUD, SOGO, CYRUS, MAILBOX
        ) + PROXIES

        /**
         * Returns [allServers] plus Zoho and Fastmail, the hosted providers kept out of it, for
         * the probes that walk them too (`MultiServerCardDavWellKnownProbeTest`,
         * `MultiServerCardDavPhotoPushProbeTest`). mailbox.org is already in [allServers].
         */
        fun allDiscoveryProbeServers(): List<CardDavServerConfig> =
            allServers() + listOf(ZOHO, FASTMAIL)
    }
}
