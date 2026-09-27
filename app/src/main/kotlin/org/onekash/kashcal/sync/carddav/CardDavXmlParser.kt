package org.onekash.kashcal.sync.carddav

import android.util.Log
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.util.EtagUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses CardDAV (RFC 6352) responses with XmlPullParser.
 *
 * A standalone sibling of [CalDavXmlParser] that borrows no CalDAV client symbol. The WebDAV
 * multistatus parts both protocols share (principal discovery, sync-token and ctag extraction,
 * the sync-collection changed/deleted split) are RFC 4918 / RFC 6578 shapes with no
 * protocol-specific elements, so they delegate to a held [CalDavXmlParser]. The extractors that
 * key on CardDAV elements (`addressbook-home-set`, the `addressbook` resourcetype,
 * `supported-address-data`, `address-data`) live here.
 *
 * The parser runs namespace-aware and matches on the local-name ([XmlPullParser.name]); the
 * CardDAV local-names are in [CardDavXmlNamespaces]. Request bodies write namespace URIs inline.
 *
 * The client maps the outputs [ParsedAddressBook] and [ParsedAddressData] onto the
 * resolved-URL models in `carddav.model`.
 */
class CardDavXmlParser {

    /**
     * Shared multistatus skeleton for protocol-agnostic extraction. Held, never subclassed:
     * `CardDavCalDavIsolationTest` allows [CalDavXmlParser] as shared generic infrastructure.
     */
    private val delegate = CalDavXmlParser()

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    // ---- Delegated protocol-agnostic extraction (RFC 4918 / RFC 6578) ----

    /** `DAV:current-user-principal` href from a PROPFIND response (RFC 5397). */
    fun extractPrincipalUrl(xml: String): String? = delegate.extractPrincipalUrl(xml)

    /** `DAV:sync-token` from a sync-collection REPORT response (RFC 6578 §3.7). */
    fun extractSyncToken(xml: String): String? = delegate.extractSyncToken(xml)

    /** `CS:getctag` collection tag (CalendarServer extension). */
    fun extractCtag(xml: String): String? = delegate.extractCtag(xml)

    /**
     * Splits a sync-collection or PROPFIND Depth:1 response into changed (href + etag) and
     * deleted hrefs plus the new sync-token (RFC 6578). The multistatus shape is the same for
     * CalDAV and CardDAV.
     */
    fun extractSyncCollectionData(xml: String): CalDavQuirks.SyncCollectionData =
        delegate.extractSyncCollectionData(xml)

    // ---- CardDAV-specific extraction (RFC 6352) ----

    /**
     * Extracts `CARDDAV:addressbook-home-set` hrefs from a principal PROPFIND response
     * (RFC 6352 §7.1.1), like the CalDAV `calendar-home-set` walk.
     */
    fun extractAddressBookHomeUrls(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            var inHomeSet = false
            val urls = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == CardDavXmlNamespaces.ADDRESSBOOK_HOME_SET) {
                            inHomeSet = true
                        } else if (inHomeSet && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val url = parser.text.trim()
                                if (url.isNotEmpty()) {
                                    urls.add(url)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == CardDavXmlNamespaces.ADDRESSBOOK_HOME_SET) {
                            inHomeSet = false
                        }
                    }
                }
                parser.next()
            }
            urls
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse addressbook home URLs: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * Extracts address book collections from a PROPFIND Depth:1 response.
     *
     * A response is an address book only when its `resourcetype` carries `CARDDAV:addressbook`
     * (RFC 6352 §5.2); plain collections and other resource types are skipped. Per book:
     * `DAV:displayname`, `CARDDAV:addressbook-description` (§6.2.1), `CS:getctag`, read-only
     * status from the current-user-privilege-set, and the vCard version from
     * `CARDDAV:supported-address-data` chosen by [negotiateVersion].
     *
     * Same structure as [CalDavXmlParser.extractCalendars].
     */
    fun extractAddressBooks(xml: String): List<ParsedAddressBook> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val books = mutableListOf<ParsedAddressBook>()

            var inResponse = false
            var inPropstat = false
            var inResourceType = false
            var inPrivilegeSet = false
            var inSupportedAddressData = false
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var currentDescription: String? = null
            var currentCtag: String? = null
            var isAddressBook = false
            var hasWritePrivilege = false
            var isReadOnly = false
            val advertisedVersions = mutableSetOf<String>()
            // Per-propstat tracking for RFC 4918 multi-propstat servers (Radicale, Stalwart): a
            // resourcetype returned inside a 404/403 propstat must not count as a readable
            // address book. Same check as extractCalendars.
            var currentPropstatHasResourceType = false
            var currentPropstatStatus: String? = null
            var resourceTypeStatusOk = true

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentDisplayName = null
                                currentDescription = null
                                currentCtag = null
                                isAddressBook = false
                                hasWritePrivilege = false
                                isReadOnly = false
                                advertisedVersions.clear()
                                resourceTypeStatusOk = true
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                            }
                            "propstat" -> {
                                inPropstat = true
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                            }
                            "resourcetype" -> {
                                inResourceType = true
                                currentPropstatHasResourceType = true
                            }
                            "current-user-privilege-set" -> inPrivilegeSet = true
                            CardDavXmlNamespaces.ADDRESSBOOK ->
                                if (inResourceType) isAddressBook = true
                            in WRITE_PRIVILEGE_ELEMENTS -> if (inPrivilegeSet) hasWritePrivilege = true
                            "read-only" -> isReadOnly = true
                            CardDavXmlNamespaces.SUPPORTED_ADDRESS_DATA ->
                                inSupportedAddressData = true
                            CardDavXmlNamespaces.ADDRESS_DATA_TYPE -> {
                                if (inSupportedAddressData) {
                                    parser.getAttributeValue(null, "version")
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { advertisedVersions.add(it) }
                                }
                            }
                            "href" -> if (inResponse && !inPropstat && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "displayname" -> {
                                currentDisplayName = readText(parser)?.takeIf { it.isNotBlank() }
                                    ?.let { CalDavXmlParser.decodeXmlEntities(it) }
                            }
                            CardDavXmlNamespaces.ADDRESSBOOK_DESCRIPTION -> {
                                currentDescription = readText(parser)?.takeIf { it.isNotBlank() }
                                    ?.let { CalDavXmlParser.decodeXmlEntities(it) }
                            }
                            CardDavXmlNamespaces.GETCTAG -> {
                                currentCtag = readText(parser)?.takeIf { it.isNotBlank() }
                            }
                            "status" -> if (inPropstat) {
                                currentPropstatStatus = readText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                // Surface the book only when the propstat that carried its
                                // resourcetype was 2xx (RFC 4918 default: an absent status
                                // is OK).
                                if (isAddressBook && currentHref != null && resourceTypeStatusOk) {
                                    books.add(
                                        ParsedAddressBook(
                                            href = currentHref,
                                            displayName = currentDisplayName ?: "Unnamed",
                                            description = currentDescription,
                                            ctag = currentCtag,
                                            isReadOnly = isReadOnly || !hasWritePrivilege,
                                            vcardVersion = negotiateVersion(advertisedVersions)
                                        )
                                    )
                                }
                                inResponse = false
                            }
                            "propstat" -> {
                                if (currentPropstatHasResourceType) {
                                    val status = currentPropstatStatus
                                    resourceTypeStatusOk = status == null ||
                                        status.contains("200") ||
                                        status.contains("201")
                                }
                                inPropstat = false
                            }
                            "resourcetype" -> inResourceType = false
                            "current-user-privilege-set" -> inPrivilegeSet = false
                            CardDavXmlNamespaces.SUPPORTED_ADDRESS_DATA ->
                                inSupportedAddressData = false
                        }
                    }
                }
                parser.next()
            }

            books
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse address books: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * Picks the vCard version to request for a collection from the versions it advertises in
     * `CARDDAV:supported-address-data` (RFC 6352 §6.2.2).
     *
     * Returns 4.0 (RFC 6350) when the server offers it, otherwise 3.0 (RFC 2426). An absent
     * property (empty set) means 3.0, the spec default.
     */
    fun negotiateVersion(advertisedVersions: Set<String>): String =
        if (advertisedVersions.contains(CardDavXmlNamespaces.VCARD_VERSION_4_0)) {
            CardDavXmlNamespaces.VCARD_VERSION_4_0
        } else {
            CardDavXmlNamespaces.VCARD_VERSION_3_0
        }

    /**
     * Extracts `CARDDAV:address-data` bodies from an addressbook-multiget REPORT response
     * (RFC 6352 §8.7 / §10.4): per member, its href, normalized etag and raw vCard body.
     *
     * A response with an etag but no `address-data` (e.g. the collection self-row) is
     * skipped, as is one whose body lacks `BEGIN:VCARD`. Same shape as
     * [CalDavXmlParser.extractICalData].
     */
    fun extractAddressData(xml: String): List<ParsedAddressData> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val entries = mutableListOf<ParsedAddressData>()

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var currentVCard: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                currentVCard = null
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "getetag" -> {
                                currentEtag = EtagUtils.normalizeEtag(readText(parser))
                            }
                            CardDavXmlNamespaces.ADDRESS_DATA -> {
                                currentVCard = readTextOrCdata(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            val href = currentHref
                            val vcard = currentVCard
                            if (href != null && vcard != null && vcard.contains("BEGIN:VCARD")) {
                                entries.add(
                                    ParsedAddressData(
                                        href = href,
                                        etag = currentEtag,
                                        vcardBody = vcard
                                    )
                                )
                            } else if (href != null && vcard == null && currentEtag != null) {
                                // Etag but no address-data: a member the server failed to
                                // materialize, or the collection self-row. Not fatal.
                                Log.w(TAG, "Response for $href has no address-data")
                            } else if (href != null && vcard != null) {
                                // address-data without BEGIN:VCARD (truncated or garbled) is
                                // dropped; log it so a real fetch failure is visible.
                                Log.w(TAG, "Response for $href has address-data without BEGIN:VCARD; skipping")
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            entries
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse address data: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun createParser(xml: String): XmlPullParser {
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    /** Reads the current element's text, advancing to the next token. */
    private fun readText(parser: XmlPullParser): String? {
        parser.next()
        return if (parser.eventType == XmlPullParser.TEXT) {
            parser.text.trim()
        } else {
            null
        }
    }

    /** Reads the current element's text or CDATA content (servers may CDATA-wrap bodies). */
    private fun readTextOrCdata(parser: XmlPullParser): String? {
        parser.next()
        return when (parser.eventType) {
            XmlPullParser.TEXT -> parser.text.trim()
            XmlPullParser.CDSECT -> parser.text.trim()
            else -> null
        }
    }

    companion object {
        private const val TAG = "CardDavXmlParser"

        /**
         * WebDAV privilege local-names that confer content-write rights (RFC 3744 §3.11/§3.12
         * aggregation): an address book granting any of these is writable, else read-only. Uses
         * the one definition on [CalDavXmlParser] so the CalDAV and CardDAV paths can't drift.
         */
        private val WRITE_PRIVILEGE_ELEMENTS = CalDavXmlParser.WRITE_PRIVILEGE_ELEMENTS
    }
}

/**
 * Holds the parsed properties of one address book collection (RFC 6352 §5.2). The href is
 * verbatim from the server; the client resolves it against the home host and maps this onto
 * [org.onekash.kashcal.sync.carddav.model.CardDavAddressBook].
 */
data class ParsedAddressBook(
    val href: String,
    val displayName: String,
    val description: String? = null,
    val ctag: String? = null,
    val isReadOnly: Boolean = false,
    val vcardVersion: String,
)

/**
 * Holds one addressbook-multiget member (RFC 6352 §8.7): its href, normalized etag (null when
 * the server omitted it) and raw vCard body.
 */
data class ParsedAddressData(
    val href: String,
    val etag: String?,
    val vcardBody: String,
)
