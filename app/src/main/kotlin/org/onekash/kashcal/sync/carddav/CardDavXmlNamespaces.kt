package org.onekash.kashcal.sync.carddav

/**
 * Names the CardDAV (RFC 6352) element and property local-names and vCard versions that
 * [CardDavXmlParser] matches. The client's request bodies spell their element names inline and
 * don't read these.
 *
 * It borrows no CalDAV symbol; the WebDAV and CalendarServer names here are wire constants the
 * CalDAV side also uses, protocol facts rather than a shared dependency.
 *
 * The parser runs namespace-aware and matches on the local-name (`XmlPullParser.name`), so
 * these are local-names only. The client's request bodies carry the namespace URIs inline with
 * explicit prefixes (e.g. `xmlns:card="urn:ietf:params:xml:ns:carddav"`).
 */
internal object CardDavXmlNamespaces {

    // ----- Element / property local-names (namespace-aware matching) -----

    /** `CARDDAV:addressbook-home-set` principal property (RFC 6352 §7.1.1). */
    const val ADDRESSBOOK_HOME_SET = "addressbook-home-set"

    /** `CARDDAV:addressbook` resourcetype marking an address book collection (RFC 6352 §5.2). */
    const val ADDRESSBOOK = "addressbook"

    /** `CARDDAV:addressbook-description` collection property (RFC 6352 §6.2.1). */
    const val ADDRESSBOOK_DESCRIPTION = "addressbook-description"

    /** `CARDDAV:supported-address-data` collection property (RFC 6352 §6.2.2). */
    const val SUPPORTED_ADDRESS_DATA = "supported-address-data"

    /**
     * `CARDDAV:address-data-type` child of supported-address-data, carrying the
     * `content-type` and `version` attributes a collection advertises (RFC 6352 §6.2.2).
     */
    const val ADDRESS_DATA_TYPE = "address-data-type"

    /** `CARDDAV:address-data`, the vCard payload element (RFC 6352 §10.4). */
    const val ADDRESS_DATA = "address-data"

    /** `CS:getctag` collection-tag property (CalendarServer extension). */
    const val GETCTAG = "getctag"

    // ----- vCard versions (RFC 2426 / RFC 6350) -----

    /** vCard 3.0 (RFC 2426). The RFC 6352 §6.2.2 fallback when no version is advertised. */
    const val VCARD_VERSION_3_0 = "3.0"

    /** vCard 4.0 (RFC 6350). */
    const val VCARD_VERSION_4_0 = "4.0"
}
