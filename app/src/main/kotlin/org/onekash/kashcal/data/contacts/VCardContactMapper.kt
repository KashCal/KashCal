package org.onekash.kashcal.data.contacts

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.Email as VEmail
import org.onekash.vcard.model.Phone as VPhone
import org.onekash.vcard.model.PostalAddress

/**
 * Byte cap for a contact photo written to the Contacts Photo column. Deliberately
 * well under the ~1 MB Android Binder transaction limit.
 *
 * A photo is written as an inline blob inside an `applyBatch` transaction, which
 * crosses Binder — a body approaching 1 MB would trip `TransactionTooLargeException`
 * and fail the whole write batch (the provider downscales large photos, but only
 * AFTER receiving the bytes over Binder, so its downscale can't rescue an oversized
 * transaction). This one cap governs both photo paths: the deferred URL fetch caps
 * its download here, and the mapper drops an inline blob that exceeds it. A real
 * contact avatar is a small image, so this rejects only pathological bodies.
 */
const val MAX_PHOTO_SIZE_BYTES: Long = 950L * 1024

/**
 * Maps the neutral [Contact] model onto Android Contacts Provider Data rows.
 *
 * This is the app-side, Android-coupled half of contact-sync parsing: the pure
 * vCard→neutral-model parse lives in the `vcard-core` module (behind the ez-vcard
 * compile wall), and this mapper consumes the already-resolved neutral fields to
 * produce a [MappedContact] carrying one [ContentValues] Data row per property, in
 * the same "return the row set, not a bare list" shape the calendar mapper uses
 * ([org.onekash.kashcal.sync.parser.icaldav.MappedEntity]).
 *
 * Deliberately pure: it performs no ContentResolver write, no batch, and no network
 * I/O. The produced rows carry no `RAW_CONTACT_ID` — the write layer supplies that
 * back-reference (via `withValueBackReference`) when it inserts the parent RawContact.
 *
 * The load-bearing contract is the birthday/anniversary alignment. KashCal already
 * ships readers that query `Event.CONTENT_ITEM_TYPE` rows by `Event.TYPE` =
 * [Event.TYPE_BIRTHDAY] / [Event.TYPE_ANNIVERSARY] and read `Event.START_DATE`. A
 * synced date lands in the shipped birthday/anniversary calendars only if it is
 * emitted as an Event row with the matching type constant and a start-date string the
 * reader can parse back — both the ISO `yyyy-MM-dd` form and the year-less `--MM-DD`
 * reduced-accuracy form (RFC 6350 §4.3.1) are accepted there.
 */
object VCardContactMapper {

    /**
     * Convert a neutral [contact] into the Data rows for a single RawContact.
     *
     * A remote-URL photo cannot become a Photo blob row without a network fetch, so it
     * is returned on [MappedContact.photoUrl] for a later step to resolve rather than
     * dropped; an inline (bytes) photo is emitted directly as a Photo Data row.
     */
    fun toEntity(contact: Contact): MappedContact {
        val rows = ArrayList<ContentValues>()

        rows += structuredNameRow(contact)
        contact.nickname?.let { rows += row(Nickname.CONTENT_ITEM_TYPE) { put(Nickname.NAME, it) } }
        // A blank EMAIL/TEL/IMPP value (some servers store empty property lines) would
        // otherwise become a phantom tappable row in the system Contacts app; skip it.
        // At most one email and one phone may carry IS_PRIMARY — the provider expects a
        // single primary per mimetype, so honour only the first preferred value.
        var emailPrimaryTaken = false
        contact.emails.forEach { email ->
            if (email.address.isBlank()) return@forEach
            val primary = email.preferred && !emailPrimaryTaken
            if (primary) emailPrimaryTaken = true
            rows += emailRow(email, primary)
        }
        var phonePrimaryTaken = false
        contact.phones.forEach { phone ->
            if (phone.number.isBlank()) return@forEach
            val primary = phone.preferred && !phonePrimaryTaken
            if (primary) phonePrimaryTaken = true
            rows += phoneRow(phone, primary)
        }
        contact.addresses.forEach { rows += postalRow(it) }
        organizationRow(contact)?.let { rows += it }
        contact.imHandles.forEach { im ->
            if (im.handle.isBlank()) return@forEach
            rows += row(Im.CONTENT_ITEM_TYPE) {
                put(Im.DATA, im.handle)
                // vCard IM protocols are open-ended (xmpp, twitter, matrix, …), so keep
                // them losslessly on the custom-protocol channel rather than forcing a
                // lossy match onto the fixed PROTOCOL_* set.
                put(Im.PROTOCOL, Im.PROTOCOL_CUSTOM)
                im.protocol?.let { put(Im.CUSTOM_PROTOCOL, it) }
            }
        }
        contact.relations.forEach { rel ->
            rows += row(Relation.CONTENT_ITEM_TYPE) {
                put(Relation.NAME, rel.name)
                val type = relationType(rel.type)
                put(Relation.TYPE, type)
                if (type == Relation.TYPE_CUSTOM) rel.type?.let { put(Relation.LABEL, it) }
            }
        }
        contact.urls.forEach { web ->
            if (web.url.isBlank()) return@forEach
            rows += row(Website.CONTENT_ITEM_TYPE) {
                put(Website.URL, web.url)
                if (web.label != null) {
                    put(Website.TYPE, Website.TYPE_CUSTOM)
                    put(Website.LABEL, web.label)
                } else {
                    put(Website.TYPE, Website.TYPE_OTHER)
                }
            }
        }
        contact.notes.forEach { note -> rows += row(Note.CONTENT_ITEM_TYPE) { put(Note.NOTE, note) } }
        // CATEGORIES -> one GroupMembership row per label, keyed by GROUP_SOURCE_ID
        // (the category name). The write layer provisions a titled Group with that
        // SOURCE_ID before the batch, so the membership resolves to a named group the
        // user sees rather than the provider auto-creating an untitled one. A pure
        // mapper can't know the group's row id, so it emits the stable string key.
        contact.categories.forEach { category ->
            if (category.isBlank()) return@forEach
            rows += row(GroupMembership.CONTENT_ITEM_TYPE) { put(GroupMembership.GROUP_SOURCE_ID, category) }
        }

        eventRow(contact.birthday, Event.TYPE_BIRTHDAY)?.let { rows += it }
        eventRow(contact.anniversary, Event.TYPE_ANNIVERSARY)?.let { rows += it }

        // An inline blob over the Binder-driven cap can't be written (it would fail the
        // whole applyBatch), so treat only within-cap inline bytes as emittable.
        val inlinePhoto = contact.photo?.data
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_PHOTO_SIZE_BYTES }
        if (inlinePhoto != null) {
            rows += row(Photo.CONTENT_ITEM_TYPE) { put(Photo.PHOTO, inlinePhoto) }
        }
        // No blob was emitted (no photo, empty/absent, or over-cap inline bytes): if a URL
        // is present, carry it for the deferred fetch step rather than dropping the photo.
        val photoUrl = if (inlinePhoto == null) contact.photo?.url else null

        return MappedContact(contact = contact, dataRows = rows, photoUrl = photoUrl)
    }

    /**
     * StructuredName is always written, even when every component is empty, because the
     * provider treats a missing StructuredName as an unnamed contact. DISPLAY_NAME comes
     * from the neutral model, which already derives it from `N` when the body had no `FN`.
     */
    private fun structuredNameRow(contact: Contact): ContentValues =
        row(StructuredName.CONTENT_ITEM_TYPE) {
            put(StructuredName.DISPLAY_NAME, contact.displayName)
            val n = contact.structuredName
            n.given?.let { put(StructuredName.GIVEN_NAME, it) }
            n.family?.let { put(StructuredName.FAMILY_NAME, it) }
            n.middle?.let { put(StructuredName.MIDDLE_NAME, it) }
            n.prefix?.let { put(StructuredName.PREFIX, it) }
            n.suffix?.let { put(StructuredName.SUFFIX, it) }
            // Phonetic reading aids (X-PHONETIC-*): drive CJK name sort/search on device.
            n.phoneticGiven?.let { put(StructuredName.PHONETIC_GIVEN_NAME, it) }
            n.phoneticMiddle?.let { put(StructuredName.PHONETIC_MIDDLE_NAME, it) }
            n.phoneticFamily?.let { put(StructuredName.PHONETIC_FAMILY_NAME, it) }
        }

    private fun emailRow(email: VEmail, primary: Boolean): ContentValues =
        row(Email.CONTENT_ITEM_TYPE) {
            put(Email.ADDRESS, email.address)
            // A custom label wins over the fixed types: the provider shows LABEL verbatim
            // under TYPE_CUSTOM, so a "School" email is not flattened to a generic type.
            if (email.label != null) {
                put(Email.TYPE, Email.TYPE_CUSTOM)
                put(Email.LABEL, email.label)
            } else {
                put(
                    Email.TYPE,
                    when {
                        email.types.any { it == "home" } -> Email.TYPE_HOME
                        email.types.any { it == "work" } -> Email.TYPE_WORK
                        else -> Email.TYPE_OTHER
                    },
                )
            }
            if (primary) put(Email.IS_PRIMARY, 1)
        }

    private fun phoneRow(phone: VPhone, primary: Boolean): ContentValues =
        row(Phone.CONTENT_ITEM_TYPE) {
            put(Phone.NUMBER, phone.number)
            if (phone.label != null) {
                put(Phone.TYPE, Phone.TYPE_CUSTOM)
                put(Phone.LABEL, phone.label)
            } else {
                val tokens = phone.types
                put(
                    Phone.TYPE,
                    when {
                        tokens.any { "cell" in it || "mobile" in it } -> Phone.TYPE_MOBILE
                        tokens.any { "work" in it } -> Phone.TYPE_WORK
                        tokens.any { "home" in it } -> Phone.TYPE_HOME
                        tokens.any { "fax" in it } -> Phone.TYPE_FAX_WORK
                        else -> Phone.TYPE_OTHER
                    },
                )
            }
            if (primary) put(Phone.IS_PRIMARY, 1)
        }

    private fun postalRow(adr: PostalAddress): ContentValues =
        row(StructuredPostal.CONTENT_ITEM_TYPE) {
            adr.poBox?.let { put(StructuredPostal.POBOX, it) }
            adr.extendedAddress?.let { put(StructuredPostal.NEIGHBORHOOD, it) }
            adr.street?.let { put(StructuredPostal.STREET, it) }
            adr.locality?.let { put(StructuredPostal.CITY, it) }
            adr.region?.let { put(StructuredPostal.REGION, it) }
            adr.postalCode?.let { put(StructuredPostal.POSTCODE, it) }
            adr.country?.let { put(StructuredPostal.COUNTRY, it) }
            if (adr.label != null) {
                put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                put(StructuredPostal.LABEL, adr.label)
            } else {
                put(
                    StructuredPostal.TYPE,
                    when {
                        adr.types.any { it == "home" } -> StructuredPostal.TYPE_HOME
                        adr.types.any { it == "work" } -> StructuredPostal.TYPE_WORK
                        else -> StructuredPostal.TYPE_OTHER
                    },
                )
            }
        }

    /**
     * `ORG` components split company / department; `TITLE` and `ROLE` ride the same row
     * (the provider stores both on the Organization mimetype — TITLE is the job title,
     * JOB_DESCRIPTION the ROLE/function). Emits nothing when the contact carries none of
     * organization, title, or role.
     */
    private fun organizationRow(contact: Contact): ContentValues? {
        val company = contact.organization.getOrNull(0)?.takeIf { it.isNotBlank() }
        val department = contact.organization.drop(1).joinToString("; ").takeIf { it.isNotBlank() }
        val title = contact.title?.takeIf { it.isNotBlank() }
        val role = contact.role?.takeIf { it.isNotBlank() }
        if (company == null && department == null && title == null && role == null) return null
        return row(Organization.CONTENT_ITEM_TYPE) {
            put(Organization.TYPE, Organization.TYPE_WORK)
            company?.let { put(Organization.COMPANY, it) }
            department?.let { put(Organization.DEPARTMENT, it) }
            title?.let { put(Organization.TITLE, it) }
            role?.let { put(Organization.JOB_DESCRIPTION, it) }
        }
    }

    /**
     * A birthday/anniversary becomes an Event Data row with [type], the constant the
     * shipped [ContactEventType] readers query. START_DATE is the provider's stored form:
     * a full date serializes as ISO `yyyy-MM-dd`; a reduced-accuracy value that carried no
     * year keeps its `--MM-DD` text so it is not dropped. Returns null when the neutral
     * date is absent or carries neither a date nor text.
     */
    private fun eventRow(date: ContactDate?, type: Int): ContentValues? {
        date ?: return null
        // A full calendar date serializes to ISO yyyy-MM-dd; otherwise fall back to the
        // retained text, which is EITHER a reduced-accuracy --MM-DD date (RFC 6350 §4.3.1)
        // OR genuinely free-text (e.g. "circa 1990"). START_DATE is a format-constrained
        // column, so emit only a value the shipped reader can parse back — a free-text value
        // would produce an Event row that silently never reaches the birthday/anniversary
        // calendars. Verify against the reader itself rather than re-encoding its format here.
        val startDate = date.date?.toString()
            ?: date.text?.takeIf { ContactEventUtils.parseContactDate(it) != null }
            ?: return null
        return row(Event.CONTENT_ITEM_TYPE) {
            put(Event.START_DATE, startDate)
            put(Event.TYPE, type)
        }
    }

    /** Map a vCard relation label to the provider's fixed relation type, else custom. */
    private fun relationType(label: String?): Int = when (label?.lowercase()) {
        "spouse" -> Relation.TYPE_SPOUSE
        "child" -> Relation.TYPE_CHILD
        "parent" -> Relation.TYPE_PARENT
        "father" -> Relation.TYPE_FATHER
        "mother" -> Relation.TYPE_MOTHER
        "brother" -> Relation.TYPE_BROTHER
        "sister" -> Relation.TYPE_SISTER
        "friend" -> Relation.TYPE_FRIEND
        "partner" -> Relation.TYPE_PARTNER
        "assistant" -> Relation.TYPE_ASSISTANT
        "manager" -> Relation.TYPE_MANAGER
        "relative" -> Relation.TYPE_RELATIVE
        else -> Relation.TYPE_CUSTOM
    }

    private inline fun row(mimeType: String, build: ContentValues.() -> Unit): ContentValues =
        ContentValues().apply {
            put(Data.MIMETYPE, mimeType)
            build()
        }
}
