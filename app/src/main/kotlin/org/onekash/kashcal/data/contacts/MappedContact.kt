package org.onekash.kashcal.data.contacts

import android.content.ContentValues
import org.onekash.vcard.model.Contact

/**
 * Result of mapping a neutral [Contact] onto Android Contacts Provider Data rows.
 *
 * Mirrors the inbound-mapping shape used on the calendar side
 * ([org.onekash.kashcal.sync.parser.icaldav.MappedEntity]): the mapper returns a
 * value that *carries* the row set rather than a bare list, so a caller destructures
 * cleanly and the write layer has the extra context it needs at hand.
 *
 * [dataRows] are mimetype-tagged [ContentValues] for a single RawContact — one row per
 * StructuredName / Email / Phone / … — with **no** `RAW_CONTACT_ID` set. The write
 * layer (a later sprint) supplies that back-reference when it inserts the parent
 * RawContact; a pure mapper can't know the id, which is exactly why this stays a plain
 * row set and not a live provider write.
 *
 * [photoUrl] is the deferred-fetch handoff: a vCard whose `PHOTO` is a remote URL (not
 * inline bytes) can't become a Photo blob row here without network I/O, so the URL rides
 * along for the photo-fetch step to resolve later. It is null when the contact had no
 * photo or when the photo was inline (already emitted as a Photo Data row in [dataRows]).
 *
 * [contact] is the source neutral model, retained so the write layer can read identity
 * fields (UID, version, raw vCard) for the RawContact SYNC columns without re-parsing.
 */
data class MappedContact(
    val contact: Contact,
    val dataRows: List<ContentValues>,
    val photoUrl: String? = null,
)
