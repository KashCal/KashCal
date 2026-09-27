package org.onekash.kashcal.sync.carddav.model

/**
 * Data shapes for the CardDAV read path, the CardDAV analogues of the CalDAV client models.
 *
 * The result envelope isn't re-declared: the CardDAV client reuses
 * [org.onekash.kashcal.sync.client.model.CalDavResult] and `CalDavException` from
 * `sync.client.model`. `CardDavCalDavIsolationTest` forbids only the CalDAV client, strategy
 * and engine symbols, so a parallel sealed hierarchy would be duplication with no isolation gain.
 */

/**
 * An address book collection discovered under a login's addressbook-home-set.
 *
 * @property href the collection href as the server returned it, server-relative or absolute.
 * @property url the absolute collection URL, resolved against the home URL's host so
 *   cross-host partition homes (iCloud's `pNN-contacts`) resolve.
 * @property displayName `DAV:displayname`, or a fallback.
 * @property description `CARDDAV:addressbook-description` (RFC 6352 §6.2.1), or null.
 * @property ctag `CS:getctag` collection tag for change detection, or null.
 * @property isReadOnly true when the current-user-privilege-set grants no write privilege
 *   or the response carries a `read-only` element. The push never uploads to a read-only book,
 *   and the pull writes its contacts read-only on the device.
 * @property vcardVersion the negotiated vCard version: "4.0" when the server advertises it,
 *   else "3.0" (RFC 6352 §6.2.2).
 */
data class CardDavAddressBook(
    val href: String,
    val url: String,
    val displayName: String,
    val description: String? = null,
    val ctag: String? = null,
    val isReadOnly: Boolean = true,
    val vcardVersion: String,
)

/**
 * A contact resource fetched via addressbook-multiget (RFC 6352 §8.7).
 *
 * The client returns the vCard body verbatim; the reader parses it into the contact model.
 *
 * @property href the resource href as the server returned it.
 * @property url the absolute resource URL.
 * @property etag the normalized entity tag, or null when the server omitted it.
 * @property vcardBody the `text/vcard` body, verbatim.
 */
data class CardDavContactData(
    val href: String,
    val url: String,
    val etag: String?,
    val vcardBody: String,
)

/**
 * Result of a sync-collection REPORT (RFC 6578) against an address book.
 *
 * @property syncToken the sync-token to persist for the next delta.
 * @property changed resources added or modified since the prior token.
 * @property deleted hrefs the server reports as removed.
 * @property truncated true on a 507 partial response (RFC 6578 §3.6): the caller must continue
 *   with [syncToken].
 */
data class ContactSyncReport(
    val syncToken: String?,
    val changed: List<ContactSyncItem>,
    val deleted: List<String>,
    val truncated: Boolean = false,
)

/** A changed resource in a [ContactSyncReport]; [etag] is null when the server reported none. */
data class ContactSyncItem(
    val href: String,
    val etag: String?,
)

/**
 * A photo fetched from a contact's remote `PHOTO` URL.
 *
 * @property bytes the image bytes, read verbatim (never charset-decoded).
 * @property contentType the response `Content-Type`, always a raster `image/` type: the client
 *   rejects any other type, SVG included, before constructing this.
 */
data class PhotoBytes(
    val bytes: ByteArray,
    val contentType: String,
) {
    // ByteArray equals/hashCode are by identity; compare content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoBytes) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}
