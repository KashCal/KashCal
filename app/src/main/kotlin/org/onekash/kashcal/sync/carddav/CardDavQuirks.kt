package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.quirks.CalDavQuirks

/**
 * Holds the per-server CardDAV (RFC 6352) differences: URL building, collection skip rules,
 * auth requirements and response extraction.
 *
 * The CardDAV counterpart of `CalDavQuirks`, kept inside `sync/carddav/`. It reuses the
 * protocol-agnostic [CalDavQuirks.SyncCollectionData] result type but borrows no CalDAV client
 * symbol (`CardDavCalDavIsolationTest`). Implementations delegate extraction to a
 * [CardDavXmlParser]. None of the methods is specific to the write verbs.
 */
interface CardDavQuirks {

    /** Provider identifier (e.g. "carddav", "icloud"). */
    val providerId: String

    /** Human-readable provider name. */
    val displayName: String

    /** The provider's CardDAV base URL; also the domain photo fetches must stay within. */
    val baseUrl: String

    /** Whether this provider requires app-specific passwords. */
    val requiresAppSpecificPassword: Boolean

    /**
     * Whether to discover the contacts host from the account's email domain via RFC 6764 DNS
     * SRV/TXT. True only for generic servers whose host isn't known up front; false for
     * providers with a pinned [baseUrl] unrelated to the login email domain (iCloud, Zoho). SRV
     * on the email domain could only misdirect a pinned-host provider: a `_carddavs` record in
     * the same registrable domain would silently redirect sync.
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

    /** `CS:getctag` collection tag, a cheap change check. */
    fun extractCtag(responseBody: String): String?

    /** Parses a sync-collection response in one pass: token, changed items, deleted hrefs. */
    fun extractSyncCollectionData(responseBody: String): CalDavQuirks.SyncCollectionData

    /** Builds the absolute URL of an address book from its href, which may be relative. */
    fun buildAddressBookUrl(href: String, baseHost: String): String

    /** Builds the absolute URL of a contact resource from its href. */
    fun buildContactUrl(href: String, addressBookUrl: String): String

    /** Additional headers this provider requires (e.g. User-Agent). */
    fun getAdditionalHeaders(): Map<String, String>

    /** Whether a response says the sync-token is invalid or expired (RFC 6578 §3.6). */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean

    /** Whether to skip a collection such as a scheduling inbox, outbox or notification one. */
    fun shouldSkipAddressBook(href: String, displayName: String?): Boolean
}
