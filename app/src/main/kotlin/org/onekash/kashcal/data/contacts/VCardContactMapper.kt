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
 * Byte cap for a contact photo written to the Contacts Photo column, below the ~1 MB Binder
 * transaction limit.
 *
 * The photo is a blob inside an `applyBatch`, which crosses Binder; a body near 1 MB throws
 * `TransactionTooLargeException` and fails the whole batch. The provider downscales large
 * photos only after receiving the bytes, so that can't save an oversized transaction. The cap
 * governs both photo paths: the URL fetch stops its download here, and [VCardContactMapper]
 * drops a larger inline blob. A real contact avatar is small, so this rejects only
 * pathological bodies.
 */
const val MAX_PHOTO_SIZE_BYTES: Long = 950L * 1024

/**
 * Maps the neutral [Contact] model onto Android Contacts Provider Data rows, one per property,
 * returned as a [MappedContact].
 *
 * This is the Android half of contact parsing; the vCard to [Contact] parse lives in the
 * `vcard-core` module, which alone depends on ez-vcard. The mapper is pure: no ContentResolver
 * write, no batch, no network I/O.
 *
 * Birthdays and anniversaries must reach the contact event calendars, whose reader
 * ([BaseContactEventRepository]) queries Event rows by `Event.TYPE_BIRTHDAY` or
 * `Event.TYPE_ANNIVERSARY` and parses `Event.START_DATE`. So a synced date is emitted as an
 * Event row with the matching type and a START_DATE that reader parses, such as ISO
 * `yyyy-MM-dd` or the year-less `--MM-DD` (RFC 6350 §4.3.1).
 */
object VCardContactMapper {

    /**
     * Converts [contact] into the Data rows of one RawContact.
     *
     * Inline photo bytes within [MAX_PHOTO_SIZE_BYTES] become a Photo row. Otherwise the
     * photo's URL, if any, goes on [MappedContact.photoUrl] for the later fetch.
     */
    fun toEntity(contact: Contact): MappedContact {
        val rows = ArrayList<ContentValues>()

        rows += structuredNameRow(contact)
        contact.nickname?.let { rows += row(Nickname.CONTENT_ITEM_TYPE) { put(Nickname.NAME, it) } }
        // Skip a blank EMAIL/TEL/IMPP value (some servers store empty property lines), or it
        // becomes a phantom tappable row in the system Contacts app. The provider expects one
        // IS_PRIMARY per mimetype, so only the first preferred email and phone get it.
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
                // vCard IM protocols are open-ended (xmpp, matrix and others); the custom
                // protocol keeps them intact, where the fixed PROTOCOL_* set would lose some.
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
        // One GroupMembership row per CATEGORIES label, keyed by GROUP_SOURCE_ID (the
        // name), because a pure mapper can't know the group's row id. The write layer
        // creates a titled Group with that SOURCE_ID before the batch, or the provider would
        // auto-create an untitled one.
        contact.categories.forEach { category ->
            if (category.isBlank()) return@forEach
            rows += row(GroupMembership.CONTENT_ITEM_TYPE) { put(GroupMembership.GROUP_SOURCE_ID, category) }
        }

        eventRow(contact.birthday, Event.TYPE_BIRTHDAY)?.let { rows += it }
        eventRow(contact.anniversary, Event.TYPE_ANNIVERSARY)?.let { rows += it }

        val inlinePhoto = contact.photo?.data
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_PHOTO_SIZE_BYTES }
        if (inlinePhoto != null) {
            rows += row(Photo.CONTENT_ITEM_TYPE) { put(Photo.PHOTO, inlinePhoto) }
        }
        val photoUrl = if (inlinePhoto == null) contact.photo?.url else null

        return MappedContact(contact = contact, dataRows = rows, photoUrl = photoUrl)
    }

    /**
     * Builds the StructuredName row. It is written even when every component is empty, because
     * the provider treats a missing StructuredName as an unnamed contact. The parser already
     * derives [Contact.displayName] from `N` when the body has no `FN`.
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
            // X-PHONETIC-* reading aids drive CJK name sort and search on the device.
            n.phoneticGiven?.let { put(StructuredName.PHONETIC_GIVEN_NAME, it) }
            n.phoneticMiddle?.let { put(StructuredName.PHONETIC_MIDDLE_NAME, it) }
            n.phoneticFamily?.let { put(StructuredName.PHONETIC_FAMILY_NAME, it) }
        }

    private fun emailRow(email: VEmail, primary: Boolean): ContentValues =
        row(Email.CONTENT_ITEM_TYPE) {
            put(Email.ADDRESS, email.address)
            // A custom label wins over the fixed types: the provider shows LABEL verbatim
            // under TYPE_CUSTOM, so a "School" email keeps its label.
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
     * Builds the Organization row: the first `ORG` component is COMPANY, the rest are joined
     * into DEPARTMENT with "; ", `TITLE` is TITLE and `ROLE` is JOB_DESCRIPTION. Returns null
     * when all four are blank.
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
     * Builds an Event row of [type] ([ContactEventType.contactEventTypeId]) for a birthday or
     * anniversary. Returns null when there is no date, or when it has no full date and its text
     * isn't one the contact event reader parses.
     */
    private fun eventRow(date: ContactDate?, type: Int): ContentValues? {
        date ?: return null
        // A full date serializes as ISO yyyy-MM-dd. Otherwise the kept text is either a
        // reduced-accuracy --MM-DD date (RFC 6350 §4.3.1) or free text ("circa 1990"), which
        // would give an Event row that silently never reaches the calendars. The check calls
        // the reader's own parser so its formats aren't copied here.
        val startDate = date.date?.toString()
            ?: date.text?.takeIf { ContactEventUtils.parseContactDate(it) != null }
            ?: return null
        return row(Event.CONTENT_ITEM_TYPE) {
            put(Event.START_DATE, startDate)
            put(Event.TYPE, type)
        }
    }

    /** Maps a vCard relation label to the provider's fixed relation type, else TYPE_CUSTOM. */
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
