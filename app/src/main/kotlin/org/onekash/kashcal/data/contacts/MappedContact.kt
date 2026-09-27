package org.onekash.kashcal.data.contacts

import android.content.ContentValues
import org.onekash.vcard.model.Contact

/**
 * Result of mapping a neutral [Contact] onto Android Contacts Provider Data rows, in the same
 * carrier shape as the calendar side's [org.onekash.kashcal.sync.parser.icaldav.MappedEntity].
 *
 * [dataRows] are the mimetype-tagged rows of one RawContact (StructuredName, Email, Phone and
 * so on) with no `RAW_CONTACT_ID`: the write layer sets it when it writes the RawContact,
 * because a pure mapper can't know the id.
 *
 * [photoUrl] carries a remote-URL `PHOTO`, which can't become a Photo row without network
 * I/O, to the later photo fetch. It is set only when the photo has a URL and no Photo row was
 * emitted ([VCardContactMapper.toEntity]).
 *
 * [contact] is the source model, kept so the write layer reads the UID and raw vCard for the
 * RawContact SYNC columns without re-parsing.
 */
data class MappedContact(
    val contact: Contact,
    val dataRows: List<ContentValues>,
    val photoUrl: String? = null,
)
