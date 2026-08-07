package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.quirks.CalDavQuirks

/**
 * Abstraction for CardDAV (RFC 6352) provider-specific behavior.
 *
 * The CardDAV analogue of `CalDavQuirks`, kept standalone inside
 * `sync/carddav/`. It reuses the generic [CalDavQuirks.SyncCollectionData]
 * value type (a protocol-agnostic WebDAV-Sync result shape) but borrows no
 * CalDAV *client* symbol. Extraction is delegated to a held [CardDavXmlParser];
 * this seam exists for the small behavioral differences (URL normalization,
 * skip rules, auth requirements) between servers.
 *
 * Read-path scope: there is no write-facing method here. Methods for building
 * request URLs, discovering collections, and reading contact data only.
 */
interface CardDavQuirks {

    /** Provider identifier (e.g. "carddav", "icloud"). */
    val providerId: String

    /** Human-readable provider name. */
    val displayName: String

    /** Base CardDAV URL for this provider. */
    val baseUrl: String

    /** Whether this provider requires app-specific passwords. */
    val requiresAppSpecificPassword: Boolean

    /**
     * Whether the contacts host should be discovered from the account's email
     * domain via RFC 6764 DNS SRV/TXT. True only for generic servers whose host is
     * unknown a priori; false for providers with a pinned bootstrap host
     * ([baseUrl]) unrelated to the login email domain (iCloud, Zoho). Running SRV
     * on the email domain for a pinned-host provider could only misdirect it — a
     * same-registrable-domain `_carddavs` record would silently redirect sync.
     */
    val discoverHostViaDns: Boolean

    /** `DAV:current-user-principal` href from a PROPFIND response (RFC 5397). */
    fun extractPrincipalUrl(responseBody: String): String?

    /** `CARDDAV:addressbook-home-set` hrefs from a principal PROPFIND (RFC 6352 §7.1.1). */
    fun extractAddressBookHomeUrls(responseBody: String): List<String>

    /** Address book collections from a home-set PROPFIND Depth:1 response. */
    fun extractAddressBooks(responseBody: String): List<ParsedAddressBook>

    /** vCard bodies + etags from an addressbook-multiget REPORT (RFC 6352 §8.7). */
    fun extractAddressData(responseBody: String): List<ParsedAddressData>

    /** `DAV:sync-token` from a sync-collection REPORT response (RFC 6578). */
    fun extractSyncToken(responseBody: String): String?

    /** `CS:getctag` collection tag for cheap change detection. */
    fun extractCtag(responseBody: String): String?

    /** Single-pass sync-collection parse: token + changed items + deleted hrefs. */
    fun extractSyncCollectionData(responseBody: String): CalDavQuirks.SyncCollectionData

    /** Build the absolute URL for an address book given its (possibly relative) href. */
    fun buildAddressBookUrl(href: String, baseHost: String): String

    /** Build the absolute URL for a contact resource given its href. */
    fun buildContactUrl(href: String, addressBookUrl: String): String

    /** Additional headers this provider requires (e.g. User-Agent). */
    fun getAdditionalHeaders(): Map<String, String>

    /** Whether a response indicates the sync-token is invalid/expired (RFC 6578 §3.6). */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean

    /** Whether an address book href/name should be skipped (inbox, notifications, etc.). */
    fun shouldSkipAddressBook(href: String, displayName: String?): Boolean
}
