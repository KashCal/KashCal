package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * CardDAV (RFC 6352) client interface — read path only.
 *
 * The transport surface for read-only contact sync: discover a login's address
 * books, detect changes, and fetch raw vCard bodies. There is deliberately no
 * write-facing method (no PUT/POST/DELETE) — MVP contact sync does not write
 * back to the server.
 *
 * All methods return [CalDavResult] (reused from the generic `sync.client.model`
 * infrastructure). The client returns raw `text/vcard` bytes untouched;
 * composing those into the neutral contact model is the reader's job
 * ([CardDavContactReader]).
 */
interface CardDavClient {

    // ========== Discovery ==========

    /**
     * Discover the CardDAV endpoint via the RFC 6764 well-known URL
     * (`/.well-known/carddav`), following redirects. Returns the final URL, or
     * the original when well-known is unsupported.
     */
    suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String>

    /**
     * Discover the user's principal URL from the server root via PROPFIND
     * `DAV:current-user-principal` (RFC 5397).
     */
    suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String>

    /**
     * Discover the addressbook-home-set URLs from the principal via PROPFIND
     * `CARDDAV:addressbook-home-set` (RFC 6352 §7.1.1). RFC allows more than one.
     */
    suspend fun discoverAddressBookHome(principalUrl: String): CalDavResult<List<String>>

    /**
     * List address book collections under a home-set URL via PROPFIND Depth:1,
     * negotiating each collection's vCard version from its advertised
     * `supported-address-data` (RFC 6352 §6.2.2).
     */
    suspend fun listAddressBooks(addressBookHomeUrl: String): CalDavResult<List<CardDavAddressBook>>

    // ========== Change Detection ==========

    /** Get the `CS:getctag` collection tag for cheap change detection. */
    suspend fun getCtag(addressBookUrl: String): CalDavResult<String?>

    /** Get the current `DAV:sync-token` for incremental sync (RFC 6578). */
    suspend fun getSyncToken(addressBookUrl: String): CalDavResult<String?>

    // ========== Fetching ==========

    /**
     * Incremental sync-collection REPORT (RFC 6578): changed + deleted hrefs
     * since [syncToken] (null for initial sync). A 403/410 or a
     * `valid-sync-token` precondition body is surfaced as a non-retryable error
     * so the caller knows to fall back to a full listing.
     */
    suspend fun syncCollection(
        addressBookUrl: String,
        syncToken: String?
    ): CalDavResult<ContactSyncReport>

    /**
     * List every contact resource href + etag in a collection via PROPFIND
     * Depth:1 (RFC 4918). The full-sync fallback when a sync-token is invalid.
     */
    suspend fun listAllContactHrefs(addressBookUrl: String): CalDavResult<List<Pair<String, String?>>>

    /**
     * Fetch specific contacts by href via addressbook-multiget REPORT
     * (RFC 6352 §8.7), requesting `address-data` at [vcardVersion] (§10.4).
     * Returns raw vCard bodies + etags. Empty [hrefs] short-circuits to an empty
     * list without a network round-trip.
     */
    suspend fun fetchContactsByHref(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String
    ): CalDavResult<List<CardDavContactData>>

    /**
     * Fetch a contact's remote `PHOTO` binary via an authenticated GET, reusing
     * the account credentials baked into this client.
     *
     * SECURITY: a `PHOTO` URL is server-controlled, and this client bakes in
     * preemptive Basic auth plus a Digest authenticator, so a GET to a foreign
     * host would leak the account credentials. The implementation therefore
     * REFUSES (returns an error, issues no request) any [photoUrl] whose host is
     * not the same registrable domain as the CardDAV endpoint — iCloud's
     * `gateway.icloud.com` vs `pNN-contacts.icloud.com` share `icloud.com` and
     * are permitted.
     *
     * Requires a 2xx response with an image `Content-Type`, and caps the
     * download size. A 401 (credential rotation), non-image body, oversized body,
     * network failure, or foreign-host refusal all surface as an error so the
     * caller leaves the photo pending for a later retry.
     */
    suspend fun fetchPhoto(photoUrl: String): CalDavResult<PhotoBytes>
}
