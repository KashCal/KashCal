package org.onekash.kashcal.sync.carddav

/**
 * CardDAV (RFC 6352) XML namespaces and element/property local-names used to
 * build request bodies and match streamed response elements.
 *
 * Centralized so the client's wire bodies and the parser's element matching
 * share one source of truth instead of scattering literals. This mirrors the
 * intent of the CalDAV stack while staying entirely inside `sync/carddav/`: it
 * borrows no CalDAV symbol, and the WebDAV / CalendarServer namespaces here are
 * the same wire constants the CalDAV side happens to use — protocol facts, not
 * a shared dependency.
 *
 * The parser runs namespace-aware, so element matching keys on the local-name
 * (`XmlPullParser.name` with a prefix-agnostic parser); these are those
 * local-names. The client's request bodies carry the namespace URIs inline with
 * explicit prefixes (`xmlns:card="urn:ietf:params:xml:ns:carddav"` etc.).
 */
internal object CardDavXmlNamespaces {

    // ----- Element / property local-names (namespace-aware matching) -----

    /** `CARDDAV:addressbook-home-set` — principal property (RFC 6352 §7.1.1). */
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

    /** `CARDDAV:address-data` — the vCard payload element (RFC 6352 §10.4). */
    const val ADDRESS_DATA = "address-data"

    /** `CS:getctag` collection-tag property (CalendarServer extension). */
    const val GETCTAG = "getctag"

    // ----- vCard versions (RFC 2426 / RFC 6350) -----

    /** vCard 3.0 (RFC 2426). The RFC 6352 §6.2.2 fallback when no version is advertised. */
    const val VCARD_VERSION_3_0 = "3.0"

    /** vCard 4.0 (RFC 6350). */
    const val VCARD_VERSION_4_0 = "4.0"
}
