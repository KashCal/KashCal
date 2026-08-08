package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.matchesReservedCollection

/**
 * CardDAV quirks for generic RFC 6352 servers (Radicale, Baikal, SOGo,
 * Nextcloud, Cyrus, and any standard CardDAV server).
 *
 * Mirrors the CalDAV `DefaultQuirks`: server base URL comes in via the
 * constructor (from the login's home-set URL), no app-specific password is
 * required, and all extraction delegates to a held [CardDavXmlParser].
 *
 * Provider metadata ([providerId], [displayName], [requiresAppSpecificPassword])
 * are constructor parameters so a provider that differs only in those values
 * (see [ICloudCardDavQuirks]) is a thin subclass rather than a copy — the
 * extraction and URL-resolution behavior is identical across every RFC 6352
 * server, so there is nothing else to fork.
 */
open class DefaultCardDavQuirks(
    private val serverBaseUrl: String,
    override val providerId: String = "carddav",
    override val displayName: String = "CardDAV",
    override val requiresAppSpecificPassword: Boolean = false,
    override val discoverHostViaDns: Boolean = true,
) : CardDavQuirks {

    private val xmlParser = CardDavXmlParser()

    override val baseUrl: String get() = serverBaseUrl

    override fun extractPrincipalUrl(responseBody: String): String? =
        xmlParser.extractPrincipalUrl(responseBody)

    override fun extractAddressBookHomeUrls(responseBody: String): List<String> =
        xmlParser.extractAddressBookHomeUrls(responseBody)

    override fun extractAddressBooks(responseBody: String): List<ParsedAddressBook> =
        xmlParser.extractAddressBooks(responseBody)
            .filter { !shouldSkipAddressBook(it.href, it.displayName) }

    override fun extractAddressData(responseBody: String): List<ParsedAddressData> =
        xmlParser.extractAddressData(responseBody)

    override fun extractSyncToken(responseBody: String): String? =
        xmlParser.extractSyncToken(responseBody)

    override fun extractCtag(responseBody: String): String? =
        xmlParser.extractCtag(responseBody)

    override fun extractSyncCollectionData(responseBody: String): CalDavQuirks.SyncCollectionData =
        xmlParser.extractSyncCollectionData(responseBody)

    override fun buildAddressBookUrl(href: String, baseHost: String): String =
        resolveUrl(href, baseHost)

    override fun buildContactUrl(href: String, addressBookUrl: String): String =
        resolveUrl(href, baseHostOf(addressBookUrl))

    override fun getAdditionalHeaders(): Map<String, String> =
        mapOf("User-Agent" to "KashCal/2.0 (Android)")

    override fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean =
        // 410 Gone or the DAV:valid-sync-token precondition body indicates an
        // expired token. A bare 403 is "permission denied", not expiry.
        responseCode == 410 || responseBody.contains("valid-sync-token", ignoreCase = true)

    override fun shouldSkipAddressBook(href: String, displayName: String?): Boolean {
        // Skip the scheduling (inbox/outbox) and notification collections a server
        // may expose alongside real address books. Match a reserved word only as a
        // whole PATH SEGMENT, never as a substring: a user's real book called
        // "notifications-contacts" or "my-inbox-friends" — or any account whose
        // username contains one of these words — must survive. Radicale (arbitrary
        // collection paths whose segment carries username + book name) is where a
        // substring match would silently hide real contacts. The display name is not a
        // discriminator: a book carries the <addressbook> resourcetype to reach this
        // filter, so one the user named "Inbox" must surface.
        return matchesReservedCollection(href = href)
    }

    /**
     * Resolve a possibly-relative href against a base host into an absolute URL.
     * Absolute hrefs (including iCloud's `pNN-contacts.icloud.com` partition
     * hosts) are preserved verbatim — no canonicalization.
     */
    private fun resolveUrl(href: String, baseHost: String): String =
        if (href.startsWith("http")) {
            href
        } else {
            val normalizedHost = baseHost.trimEnd('/')
            val normalizedHref = if (href.startsWith("/")) href else "/$href"
            "$normalizedHost$normalizedHref"
        }
}

/** Scheme + authority of a URL (drops the path), or the input if it has no scheme. */
internal fun baseHostOf(url: String): String =
    if (url.contains("://")) {
        val afterProtocol = url.substringAfter("://")
        val host = afterProtocol.substringBefore("/")
        url.substringBefore("://") + "://" + host
    } else {
        url.substringBefore("/")
    }
