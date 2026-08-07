package org.onekash.kashcal.sync.carddav.model

/**
 * Data shapes for the CardDAV read path.
 *
 * These are the CardDAV-specific analogues of the CalDAV client models. The
 * generic result envelope is deliberately NOT re-declared here: the CardDAV
 * client reuses [org.onekash.kashcal.sync.client.model.CalDavResult] and its
 * `CalDavException` directly. Those live in `sync.client.model`, which is
 * generic infrastructure the isolation firewall permits (it forbids only the
 * CalDAV *client / strategy / engine* symbols, not the shared result type), so
 * a parallel sealed hierarchy would be pure duplication for no isolation gain.
 */

/**
 * An address book collection discovered under a login's addressbook-home-set.
 *
 * @property href The collection href exactly as the server returned it (may be
 *   server-relative or absolute).
 * @property url The resolved absolute collection URL, built against the home
 *   URL's host so cross-host partition homes (e.g. iCloud's `pNN-contacts`)
 *   resolve correctly.
 * @property displayName Human-readable name (`DAV:displayname`), or a fallback.
 * @property description `CARDDAV:addressbook-description` (RFC 6352 §6.2.1), or null.
 * @property ctag `CS:getctag` collection tag for cheap change detection, or null.
 * @property isReadOnly Derived from the current-user-privilege-set; contact sync
 *   is read-only regardless, but this records what the server grants.
 * @property vcardVersion The negotiated vCard version for this collection
 *   ("4.0" when the server advertises it, else "3.0" per RFC 6352 §6.2.2).
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
 * A single contact resource fetched via addressbook-multiget (RFC 6352 §8.7).
 *
 * The client returns the raw vCard body untouched; parsing into the neutral
 * contact model happens one layer up in the reader.
 *
 * @property href The resource href as returned by the server.
 * @property url The resolved absolute resource URL.
 * @property etag The normalized entity tag, or null when the server omitted it.
 * @property vcardBody The raw `text/vcard` body (verbatim).
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
 * @property syncToken The new sync-token to persist for the next incremental run.
 * @property changed Resources added or modified since the prior token.
 * @property deleted Hrefs of resources the server reports as removed.
 * @property truncated True on a 507 partial response (RFC 6578 §3.6): results
 *   are partial and the caller must continue with [syncToken].
 */
data class ContactSyncReport(
    val syncToken: String?,
    val changed: List<ContactSyncItem>,
    val deleted: List<String>,
    val truncated: Boolean = false,
)

/**
 * A single changed resource in a [ContactSyncReport]: an href plus its etag
 * (null when the server did not report one).
 */
data class ContactSyncItem(
    val href: String,
    val etag: String?,
)

/**
 * A photo binary fetched from a contact's remote `PHOTO` URL.
 *
 * @property bytes the raw image bytes, read verbatim (never charset-decoded).
 * @property contentType the response `Content-Type` (always an image type —
 *   the client rejects a non-image response before constructing this).
 */
data class PhotoBytes(
    val bytes: ByteArray,
    val contentType: String,
) {
    // ByteArray uses identity equals/hashCode by default; override so two
    // PhotoBytes with the same content compare equal (expected of a data holder).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoBytes) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}
