package org.onekash.vcard

import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.Photo

/** A field of [Contact] that is written to and read from a vCard. */
enum class ContactField {
    UID,
    KIND,
    FORMATTED_NAME,
    STRUCTURED_NAME,
    NICKNAME,
    ORGANIZATION,
    TITLE,
    ROLE,
    EMAILS,
    PHONES,
    ADDRESSES,
    URLS,
    NOTES,
    CATEGORIES,
    PHOTO,
    BIRTHDAY,
    ANNIVERSARY,
    RELATIONS,
    IM_HANDLES,
}

/**
 * Returns the fields whose values differ between [a] and [b].
 *
 * A photo is compared by its URL and bytes, not its content type; a birthday or anniversary
 * without a year compares equal in any of its spellings ([normalizedYearless]). [Contact.version]
 * and [Contact.rawVCard] describe the body, not the contact, and are never compared. [VCardWriter]
 * rewrites exactly these fields when it patches a body, so a caller asking "is this the same
 * contact?" and the writer agree.
 */
fun changedFields(a: Contact, b: Contact): Set<ContactField> = buildSet {
    if (a.uid != b.uid) add(ContactField.UID)
    if (a.kind != b.kind) add(ContactField.KIND)
    if (a.displayName != b.displayName) add(ContactField.FORMATTED_NAME)
    if (a.structuredName != b.structuredName) add(ContactField.STRUCTURED_NAME)
    if (a.nickname != b.nickname) add(ContactField.NICKNAME)
    if (a.organization != b.organization) add(ContactField.ORGANIZATION)
    if (a.title != b.title) add(ContactField.TITLE)
    if (a.role != b.role) add(ContactField.ROLE)
    if (a.emails != b.emails) add(ContactField.EMAILS)
    if (a.phones != b.phones) add(ContactField.PHONES)
    if (a.addresses != b.addresses) add(ContactField.ADDRESSES)
    if (a.urls != b.urls) add(ContactField.URLS)
    if (a.notes != b.notes) add(ContactField.NOTES)
    if (a.categories != b.categories) add(ContactField.CATEGORIES)
    if (photoContentChanged(a.photo, b.photo)) add(ContactField.PHOTO)
    if (normalizedYearless(a.birthday) != normalizedYearless(b.birthday)) add(ContactField.BIRTHDAY)
    if (normalizedYearless(a.anniversary) != normalizedYearless(b.anniversary)) add(ContactField.ANNIVERSARY)
    if (a.relations != b.relations) add(ContactField.RELATIONS)
    if (a.imHandles != b.imHandles) add(ContactField.IM_HANDLES)
}

/**
 * Returns whether the photo's bytes or URL changed, ignoring [Photo.contentType]. A device
 * Contacts Photo row has no MIME column, so a photo read back from the device loses only its
 * contentType; counting that as a change would rewrite and relabel the PHOTO on every sync
 * without converging. A bytes or URL edit, or adding or removing the photo, still counts.
 */
private fun photoContentChanged(new: Photo?, old: Photo?): Boolean =
    new?.copy(contentType = null) != old?.copy(contentType = null)
