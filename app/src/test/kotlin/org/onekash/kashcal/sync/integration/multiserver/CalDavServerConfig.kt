package org.onekash.kashcal.sync.integration.multiserver

import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks

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
    val supportsCtag: Boolean = true,
    /**
     * When the server's iSchedule pipeline strips ATTENDEE rows on PUT
     * because the supplied ORGANIZER mailto doesn't match the authenticated
     * account. Documented behavior on iCloud / Stalwart / Radicale / Zoho
     * (see CALDAV_TEST_SERVERS.md scheduling quirk matrix). Tests that need
     * a synthetic ORGANIZER (different from auth account) must skip on
     * these servers — there's nothing to assert against once attendees are
     * gone.
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
            // Zoho's CalDAV endpoint is /caldav (no trailing slash — /caldav/
            // returns 501). Bare root returns HTTP 400 on OPTIONS, which the
            // reachability probe rejects; /caldav returns 401 (auth required)
            // which the probe accepts as "server up."
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

        fun allServers(): List<CalDavServerConfig> = listOf(
            ICLOUD, STALWART, BAIKAL, BAIKAL_DIGEST, RADICALE, NEXTCLOUD,
            ZOHO, SOGO
        )
    }
}
