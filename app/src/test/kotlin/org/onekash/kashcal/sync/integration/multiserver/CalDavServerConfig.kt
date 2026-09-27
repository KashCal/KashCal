package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Describes one CalDAV server for the parameterized integration tests.
 *
 * Each server has its local.properties keys ([CalDavTestServerLoader]), a quirks factory and an
 * optional endpoint suffix (Baikal appends /dav.php/).
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
    val supportsCtag: Boolean = true,
    /**
     * True when the server's scheduling pipeline strips ATTENDEE lines on PUT because the
     * ORGANIZER mailto doesn't match the authenticated account. Set for iCloud, Stalwart,
     * Radicale (and its proxied copies), Zoho and Mailbox. Tests that need a synthetic ORGANIZER
     * must skip these servers: once the attendees are gone there is nothing to assert.
     */
    val stripsAttendeesOnSyntheticOrganizer: Boolean = false
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
            supportsCtag = true,
            stripsAttendeesOnSyntheticOrganizer = true
        )

        val STALWART = CalDavServerConfig(
            name = "Stalwart",
            serverKey = "STALWART_SERVER",
            usernameKey = "STALWART_USERNAME",
            passwordKey = "STALWART_PASSWORD",
            defaultServerUrl = "http://localhost:8080",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = true,
            supportsCtag = true,
            stripsAttendeesOnSyntheticOrganizer = true
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

        val BAIKAL_DIGEST = CalDavServerConfig(
            name = "BaikalDigest",
            serverKey = "BAIKAL_DIGEST_SERVER",
            usernameKey = "BAIKAL_DIGEST_USERNAME",
            passwordKey = "BAIKAL_DIGEST_PASSWORD",
            defaultServerUrl = "http://localhost:8083",
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
            supportsCtag = true,
            stripsAttendeesOnSyntheticOrganizer = true
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
            defaultServerUrl = "https://calendar.zoho.com",
            // Zoho's CalDAV endpoint is /caldav with no trailing slash (/caldav/ returns 501).
            // The bare root returns 400 on OPTIONS, which the reachability probe rejects;
            // /caldav returns 401, which it accepts as "server up."
            davEndpointSuffix = "/caldav",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = false,
            stripsAttendeesOnSyntheticOrganizer = true
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

        // Open-Xchange / OX App Suite (hosted). A PROPFIND on /caldav/ resolves
        // current-user-principal to /principals/users/<n>, whose calendar-home-set points back at
        // /caldav/; the calendar collection is an opaque base64-like child (e.g. /caldav/<id>/).
        // OX runs an RFC 6638 scheduling pipeline (schedule-inbox and outbox are present), so it
        // can route synthetic-organizer attendees as iCloud does.
        val MAILBOX = CalDavServerConfig(
            name = "Mailbox",
            serverKey = "MAILBOX_SERVER",
            usernameKey = "MAILBOX_USERNAME",
            passwordKey = "MAILBOX_PASSWORD",
            defaultServerUrl = "https://dav.mailbox.org",
            davEndpointSuffix = "/caldav/",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true,
            stripsAttendeesOnSyntheticOrganizer = true
        )

        // Fastmail (Cyrus-based CalDAV, hosted). Cyrus runs an RFC 6638 scheduling pipeline, so
        // implicit-PUT delivery that stamps SCHEDULE-STATUS is expected, as on iCloud; the
        // probes measure it rather than assume it.
        val FASTMAIL = CalDavServerConfig(
            name = "Fastmail",
            serverKey = "FASTMAIL_SERVER",
            usernameKey = "FASTMAIL_USERNAME",
            passwordKey = "FASTMAIL_PASSWORD",
            defaultServerUrl = "https://caldav.fastmail.com",
            // Fastmail serves CalDAV under /dav/ (the root 404s). A PROPFIND there resolves the
            // principal (/dav/principals/user/<addr>/), so target /dav/ and skip well-known
            // discovery. It needs an app-specific password; the primary one is rejected for
            // CalDAV.
            davEndpointSuffix = "/dav/",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        // Cyrus (the CalDAV engine Fastmail runs), a local container from the Cyrus project's own
        // test-server image, so it tracks real Cyrus behavior. Runs an RFC 6638 scheduling
        // pipeline like Fastmail and iCloud. Accepts any password (fakesaslauthd) for the seeded
        // users user1..user5. Well-known discovery redirects (301) from /dav/ to the calendar
        // home; the principal is /dav/principals/user/<user>/ and the calendar home
        // /dav/calendars/user/<user>/. Emits strong etags.
        val CYRUS = CalDavServerConfig(
            name = "Cyrus",
            serverKey = "CYRUS_SERVER",
            usernameKey = "CYRUS_USERNAME",
            passwordKey = "CYRUS_PASSWORD",
            defaultServerUrl = "http://localhost:8090",
            davEndpointSuffix = "/dav/",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = true,
            supportsCtag = true
        )

        // Xandikos (a Python CalDAV/CardDAV server) runs locally with no auth, so any credentials
        // pass, as on Radicale. The principal resolves from the root; the calendar home is
        // /user/calendars/. It grants the RFC 3744 <all> aggregate privilege, not leaf
        // <write>/<write-content>, which `XandikosReadOnlyDiscoveryTest` covers for #281.
        val XANDIKOS = CalDavServerConfig(
            name = "Xandikos",
            serverKey = "XANDIKOS_SERVER",
            usernameKey = "XANDIKOS_USERNAME",
            passwordKey = "XANDIKOS_PASSWORD",
            defaultServerUrl = "http://localhost:8999",
            quirksFactory = { url -> DefaultQuirks(url) },
            usesWellKnownDiscovery = false,
            supportsCtag = true
        )

        // The local Radicale behind a local TLS proxy, included only when KASHCAL_TLS_PROXY=1.
        // Each entry is a server setup the redirect and cleartext rules must handle:
        // - RadicaleTLS, https://localhost:9443: a plain https proxy in front of Radicale.
        // - RadicaleHttpUpgrade, http://localhost:9480: answers every request with a 301 to
        //   the same path on https://localhost:9443, like a server that forces https.
        // - RadicaleAbsHttpHrefs, https://localhost:9444: an https proxy that rewrites every
        //   `<href>/...` in its replies to `<href>http://localhost:9480/...`, like a server
        //   behind TLS that doesn't know it is.
        // The proxy's certificate must be in the JVM truststore the tests run with.
        private fun proxied(name: String, url: String) = RADICALE.copy(name = name, serverKey = null, defaultServerUrl = url)
        private val PROXIES get() = if (System.getenv("KASHCAL_TLS_PROXY") == "1") listOf(
            proxied("RadicaleTLS", "https://localhost:9443"),
            proxied("RadicaleHttpUpgrade", "http://localhost:9480"),
            proxied("RadicaleAbsHttpHrefs", "https://localhost:9444"),
        ) else emptyList()

        fun allServers(): List<CalDavServerConfig> = listOf(
            ICLOUD, STALWART, BAIKAL, BAIKAL_DIGEST, RADICALE, NEXTCLOUD,
            ZOHO, SOGO, MAILBOX, FASTMAIL, CYRUS, XANDIKOS
        ) + PROXIES
    }
}
