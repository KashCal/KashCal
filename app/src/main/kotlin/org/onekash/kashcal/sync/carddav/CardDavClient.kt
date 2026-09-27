package org.onekash.kashcal.sync.carddav

import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.carddav.model.CardDavContactData
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactSyncReport
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.carddav.model.PhotoBytes
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Speaks CardDAV (RFC 6352) for contact sync: discovers a login's address books, detects
 * changes, fetches raw vCard bodies, and uploads and deletes contact resources.
 *
 * The read methods return [CalDavResult] (from the generic `sync.client.model`) with the raw
 * `text/vcard` bodies untouched; [CardDavContactReader] parses them into the contact model. The
 * write verbs [putContact] and [deleteContact], called by
 * [org.onekash.kashcal.sync.contacts.ContactPushStrategy], return sealed outcomes
 * ([ContactUploadResult], [ContactDeleteResult]) so the caller branches on a result such as a
 * non-fatal precondition failure without decoding status codes.
 */
interface CardDavClient {

    // ========== Discovery ==========

    /**
     * Discovers the CardDAV endpoint via the RFC 6764 well-known URL (`/.well-known/carddav`),
     * following redirects. Returns the final URL, or [serverUrl] when well-known is unsupported;
     * an error only when the transport guard refused a redirect.
     */
    suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String>

    /** Discovers the principal URL via PROPFIND `DAV:current-user-principal` (RFC 5397). */
    suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String>

    /**
     * Discovers the addressbook-home-set URLs via PROPFIND `CARDDAV:addressbook-home-set`
     * (RFC 6352 §7.1.1), which may hold more than one.
     */
    suspend fun discoverAddressBookHome(principalUrl: String): CalDavResult<List<String>>

    /**
     * Lists the address books under a home-set URL via PROPFIND Depth:1, choosing each one's
     * vCard version from its advertised `supported-address-data` (RFC 6352 §6.2.2).
     */
    suspend fun listAddressBooks(addressBookHomeUrl: String): CalDavResult<List<CardDavAddressBook>>

    // ========== Change Detection ==========

    /** Returns the `CS:getctag` collection tag, a cheap change check. */
    suspend fun getCtag(addressBookUrl: String): CalDavResult<String?>

    /** Returns the current `DAV:sync-token` for delta sync (RFC 6578). */
    suspend fun getSyncToken(addressBookUrl: String): CalDavResult<String?>

    // ========== Fetching ==========

    /**
     * Returns the hrefs changed and deleted since [syncToken] (null for initial sync) via a
     * sync-collection REPORT (RFC 6578). A 403/410 or a `valid-sync-token` precondition body is
     * a non-retryable error, which tells the caller to fall back to a full listing.
     */
    suspend fun syncCollection(
        addressBookUrl: String,
        syncToken: String?
    ): CalDavResult<ContactSyncReport>

    /**
     * Lists every contact href and etag in a collection via PROPFIND Depth:1 (RFC 4918): the
     * full listing used when there is no usable sync-token.
     */
    suspend fun listAllContactHrefs(addressBookUrl: String): CalDavResult<List<Pair<String, String?>>>

    /**
     * Fetches raw vCard bodies and etags for [hrefs] via addressbook-multiget REPORT
     * (RFC 6352 §8.7), requesting `address-data` at [vcardVersion] (§10.4). Empty [hrefs]
     * returns an empty list without a request.
     */
    suspend fun fetchContactsByHref(
        addressBookUrl: String,
        hrefs: List<String>,
        vcardVersion: String
    ): CalDavResult<List<CardDavContactData>>

    /**
     * Fetches a contact's remote `PHOTO` with an authenticated GET using this client's
     * credentials.
     *
     * A `PHOTO` URL is server-controlled and this client sends preemptive Basic auth plus
     * Digest, so a GET to a foreign host would leak the account credentials. Any [photoUrl]
     * outside the CardDAV endpoint's registrable domain is refused with an error and no
     * request; iCloud's `gateway.icloud.com` and `pNN-contacts.icloud.com` share `icloud.com`
     * and are allowed.
     *
     * Success needs a 2xx with a raster image `Content-Type` and a non-empty body under the size
     * cap. Every other result is an error; its `isRetryable` tells the caller whether to keep
     * the photo pending for a later sync (a 401, 5xx, 429, 408, empty body or network failure)
     * or give up on this URL (a foreign host, a non-image or oversized body, or another status).
     */
    suspend fun fetchPhoto(photoUrl: String): CalDavResult<PhotoBytes>

    // ========== Writing ==========

    /**
     * Uploads a contact's vCard to [resourceUrl] as `text/vcard` with a conditional PUT
     * (RFC 6352 §6.3.2, RFC 4918 §9.7).
     *
     * [ContactPrecondition.IfAbsent] sends `If-None-Match: *` (create only if nothing is there);
     * [ContactPrecondition.IfMatch] sends `If-Match: "<etag>"` (update only if the known version
     * still matches). [resourceUrl] is used verbatim: the caller derives a new resource's name
     * via [contactResourceName] and reuses the stored href for an update.
     *
     * Returns [ContactUploadResult.Success] on 200/201/204, with the new ETag (null when the
     * server omitted it) and the final URL when the PUT was redirected; the non-fatal
     * [ContactUploadResult.PreconditionFailed] on 412/409, or without a request when the etag
     * can't be written into a header; [ContactUploadResult.PermissionDenied] on 403;
     * [ContactUploadResult.Gone] on 404/410; and [ContactUploadResult.Failed] for any other
     * status, a transport failure or a request the transport guard refused. The write is never
     * retried: a blind retry of a conditional write can misreport a success whose response was
     * lost as a precondition failure.
     */
    suspend fun putContact(
        resourceUrl: String,
        vcardBody: String,
        precondition: ContactPrecondition,
    ): ContactUploadResult

    /**
     * Deletes the contact resource at [resourceUrl] with a conditional DELETE (RFC 4918 §9.6)
     * carrying `If-Match: "<etag>"`.
     *
     * Returns [ContactDeleteResult.Deleted] on 200/204; the non-fatal
     * [ContactDeleteResult.PreconditionFailed] on 412/409, or without a request when [etag]
     * can't be written into a header; [ContactDeleteResult.AlreadyGone] on 404/410 (the intent
     * is satisfied); and [ContactDeleteResult.Failed] for any other status, a transport failure
     * or a request the transport guard refused. Never retried, for the same reason as
     * [putContact].
     */
    suspend fun deleteContact(resourceUrl: String, etag: String): ContactDeleteResult
}
