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
import org.onekash.vcard.model.ImHandle
import org.onekash.vcard.model.Photo as VPhoto
import org.onekash.vcard.model.PostalAddress
import org.onekash.vcard.model.StructuredName as VStructuredName
import org.onekash.vcard.model.WebAddress
import java.time.LocalDate
import org.onekash.vcard.model.Email as VEmail
import org.onekash.vcard.model.Phone as VPhone
import org.onekash.vcard.model.Relation as VRelation

/**
 * Reads the Contacts Provider Data rows of a single aggregated contact back into the
 * neutral [Contact] model — the exact inverse of [VCardContactMapper].
 *
 * Deliberately pure: it consumes an already-materialized `List<ContentValues>` (one
 * per Data row) and touches no ContentResolver, Cursor, or Binder. The provider read
 * that produces those rows lives in the write/sync layer under `sync/contacts/`; this
 * mapper is just the row→model transform, so it is unit-testable with plain
 * [ContentValues] and carries no provider-write marker.
 *
 * ## Faithful inverse on the forward image
 *
 * The forward mapper is lossy on a few sub-facets (it collapses each vCard `TYPE`
 * token list onto one provider constant, drops photo `contentType`/URL, keeps only the
 * first preferred value per mimetype, and retains anniversary text only as a date).
 * This mapper is the inverse ON THE FORWARD IMAGE: `reverse(forward(x))` is a fixed
 * point (proven for every fixture in the test), and it is facet-equal to
 * [org.onekash.vcard.VCardParser]'s neutral representation for the losslessly
 * round-tripping facets.
 *
 * It is NOT facet-equal on the forward-lossy sub-facets — most notably the secondary
 * `TYPE` tokens on email/phone (`INTERNET`, `VOICE`) the provider can't store. Because
 * [org.onekash.vcard.VCardWriter] diffs email/phone at full structural equality, the
 * first write after a reverse map regenerates those lines to their device-canonical
 * form; they then converge (the server holds the narrowed form, which reverse-maps to
 * itself). Photo is exempt from this churn: the writer diffs the photo on its bytes/URL
 * and ignores the unrecoverable `contentType`, so a device-sourced photo is not rewritten
 * or relabeled on a no-edit round trip.
 *
 * ## Identity fields are not on Data rows
 *
 * `uid`, `version`, `kind`, and `rawVCard` live on the RawContact SYNC columns, not the
 * Data rows, so they are supplied by the caller (the sync layer reads them off the
 * RawContact; tests pass the parsed originals). Everything else is reconstructed from
 * the rows.
 */
object DeviceContactRowMapper {

    /**
     * Reconstruct a [Contact] from the [dataRows] of one RawContact. [uid], [version],
     * [kind], and [rawVCard] are RawContact-level identity fields not stored on Data
     * rows; callers supply them (defaults suit row-only unit tests).
     */
    fun toContact(
        dataRows: List<ContentValues>,
        uid: String = "",
        version: String = "3.0",
        kind: String? = null,
        rawVCard: String = "",
    ): Contact {
        val byMime = dataRows.groupBy { it.getAsString(Data.MIMETYPE) }

        val nameRow = byMime[StructuredName.CONTENT_ITEM_TYPE]?.firstOrNull()
        val structuredName = structuredName(nameRow)
        // FN is stored on DISPLAY_NAME; fall back to the N-derived form (matching the
        // parser, which derives displayName from N when the body carried no FN).
        val displayName = nameRow?.getAsString(StructuredName.DISPLAY_NAME).blankToNull()
            ?: structuredName.toDisplayName()

        val org = byMime[Organization.CONTENT_ITEM_TYPE]?.firstOrNull()

        return Contact(
            version = version,
            uid = uid,
            kind = kind,
            structuredName = structuredName,
            displayName = displayName,
            nickname = byMime[Nickname.CONTENT_ITEM_TYPE]?.firstOrNull()
                ?.getAsString(Nickname.NAME).blankToNull(),
            emails = byMime[Email.CONTENT_ITEM_TYPE].orEmpty().map(::email)
                .clampSinglePreferred({ it.preferred }) { e, pref -> e.copy(preferred = pref) },
            phones = byMime[Phone.CONTENT_ITEM_TYPE].orEmpty().map(::phone)
                .clampSinglePreferred({ it.preferred }) { p, pref -> p.copy(preferred = pref) },
            addresses = byMime[StructuredPostal.CONTENT_ITEM_TYPE].orEmpty().map(::postal),
            organization = organization(org),
            title = org?.getAsString(Organization.TITLE).blankToNull(),
            role = org?.getAsString(Organization.JOB_DESCRIPTION).blankToNull(),
            urls = byMime[Website.CONTENT_ITEM_TYPE].orEmpty().map(::website),
            notes = byMime[Note.CONTENT_ITEM_TYPE].orEmpty().mapNotNull { it.getAsString(Note.NOTE).blankToNull() },
            imHandles = byMime[Im.CONTENT_ITEM_TYPE].orEmpty().map(::imHandle),
            relations = byMime[Relation.CONTENT_ITEM_TYPE].orEmpty().map(::relation),
            categories = byMime[GroupMembership.CONTENT_ITEM_TYPE].orEmpty()
                .mapNotNull { it.getAsString(GroupMembership.GROUP_SOURCE_ID).blankToNull() },
            photo = photo(byMime[Photo.CONTENT_ITEM_TYPE]?.firstOrNull()),
            birthday = eventDate(byMime[Event.CONTENT_ITEM_TYPE], Event.TYPE_BIRTHDAY),
            anniversary = eventDate(byMime[Event.CONTENT_ITEM_TYPE], Event.TYPE_ANNIVERSARY),
            rawVCard = rawVCard,
        )
    }

    private fun structuredName(row: ContentValues?): VStructuredName {
        row ?: return VStructuredName()
        return VStructuredName(
            family = row.getAsString(StructuredName.FAMILY_NAME).blankToNull(),
            given = row.getAsString(StructuredName.GIVEN_NAME).blankToNull(),
            middle = row.getAsString(StructuredName.MIDDLE_NAME).blankToNull(),
            prefix = row.getAsString(StructuredName.PREFIX).blankToNull(),
            suffix = row.getAsString(StructuredName.SUFFIX).blankToNull(),
            phoneticGiven = row.getAsString(StructuredName.PHONETIC_GIVEN_NAME).blankToNull(),
            phoneticMiddle = row.getAsString(StructuredName.PHONETIC_MIDDLE_NAME).blankToNull(),
            phoneticFamily = row.getAsString(StructuredName.PHONETIC_FAMILY_NAME).blankToNull(),
        )
    }

    private fun email(row: ContentValues): VEmail {
        val type = row.getAsInteger(Email.TYPE)
        val label = row.getAsString(Email.LABEL).blankToNull()
        // A custom label wins over the type token, exactly as the forward mapper prefers
        // it; the fixed provider constants otherwise map back to a single canonical token
        // (TYPE_CUSTOM and any unmapped constant fall through to no token).
        val types = when (type) {
            Email.TYPE_HOME -> listOf("home")
            Email.TYPE_WORK -> listOf("work")
            else -> emptyList()
        }
        return VEmail(
            address = row.getAsString(Email.ADDRESS).orEmpty(),
            types = types,
            preferred = row.isPrimary(Email.IS_PRIMARY),
            label = if (type == Email.TYPE_CUSTOM) label else null,
        )
    }

    private fun phone(row: ContentValues): VPhone {
        val type = row.getAsInteger(Phone.TYPE)
        val label = row.getAsString(Phone.LABEL).blankToNull()
        val types = when (type) {
            Phone.TYPE_MOBILE -> listOf("cell")
            Phone.TYPE_WORK -> listOf("work")
            Phone.TYPE_HOME -> listOf("home")
            Phone.TYPE_FAX_WORK -> listOf("fax")
            else -> emptyList()
        }
        return VPhone(
            number = row.getAsString(Phone.NUMBER).orEmpty(),
            types = types,
            preferred = row.isPrimary(Phone.IS_PRIMARY),
            label = if (type == Phone.TYPE_CUSTOM) label else null,
        )
    }

    private fun postal(row: ContentValues): PostalAddress {
        val type = row.getAsInteger(StructuredPostal.TYPE)
        val label = row.getAsString(StructuredPostal.LABEL).blankToNull()
        val types = when (type) {
            StructuredPostal.TYPE_HOME -> listOf("home")
            StructuredPostal.TYPE_WORK -> listOf("work")
            else -> emptyList()
        }
        return PostalAddress(
            poBox = row.getAsString(StructuredPostal.POBOX).blankToNull(),
            extendedAddress = row.getAsString(StructuredPostal.NEIGHBORHOOD).blankToNull(),
            street = row.getAsString(StructuredPostal.STREET).blankToNull(),
            locality = row.getAsString(StructuredPostal.CITY).blankToNull(),
            region = row.getAsString(StructuredPostal.REGION).blankToNull(),
            postalCode = row.getAsString(StructuredPostal.POSTCODE).blankToNull(),
            country = row.getAsString(StructuredPostal.COUNTRY).blankToNull(),
            types = types,
            label = if (type == StructuredPostal.TYPE_CUSTOM) label else null,
        )
    }

    /**
     * `ORG` = company (COMPANY) followed by the organizational units the forward mapper
     * joined into DEPARTMENT with "; ". Splitting on that separator is the inverse of
     * that join and is exact unless a unit itself contained "; " (a documented narrowing
     * that self-heals under the fixed point). A leading blank ORG component (`ORG:;Unit`)
     * is likewise not recoverable: the forward mapper drops a blank company before writing,
     * so there is no COMPANY cell to restore — same forward-loss class, fixed-point-safe.
     */
    private fun organization(row: ContentValues?): List<String> {
        row ?: return emptyList()
        val company = row.getAsString(Organization.COMPANY).blankToNull()
        val departments = row.getAsString(Organization.DEPARTMENT).blankToNull()
            ?.split("; ")?.filter { it.isNotBlank() }.orEmpty()
        return listOfNotNull(company) + departments
    }

    private fun website(row: ContentValues): WebAddress {
        val label = if (row.getAsInteger(Website.TYPE) == Website.TYPE_CUSTOM) {
            row.getAsString(Website.LABEL).blankToNull()
        } else {
            null
        }
        return WebAddress(url = row.getAsString(Website.URL).orEmpty(), label = label)
    }

    private fun imHandle(row: ContentValues): ImHandle =
        ImHandle(
            protocol = row.getAsString(Im.CUSTOM_PROTOCOL).blankToNull(),
            handle = row.getAsString(Im.DATA).orEmpty(),
        )

    private fun relation(row: ContentValues): VRelation {
        val type = row.getAsInteger(Relation.TYPE)
        val label = if (type == Relation.TYPE_CUSTOM) {
            row.getAsString(Relation.LABEL).blankToNull()
        } else {
            relationTypeToken(type)
        }
        return VRelation(name = row.getAsString(Relation.NAME).orEmpty(), type = label)
    }

    private fun photo(row: ContentValues?): VPhoto? {
        val bytes = row?.getAsByteArray(Photo.PHOTO)?.takeIf { it.isNotEmpty() } ?: return null
        // The Photo row carries no MIME subtype and never a URL, so those stay null —
        // matching the forward mapper, which drops contentType and routes URL photos out
        // of the row set entirely.
        return VPhoto(data = bytes)
    }

    /** First Event row of [type] → its [ContactDate]; ISO date parses to [ContactDate.date], else text. */
    private fun eventDate(rows: List<ContentValues>?, type: Int): ContactDate? {
        val start = rows.orEmpty()
            .firstOrNull { it.getAsInteger(Event.TYPE) == type }
            ?.getAsString(Event.START_DATE).blankToNull()
            ?: return null
        val date = runCatching { LocalDate.parse(start) }.getOrNull()
        return ContactDate(date = date, text = if (date == null) start else null)
    }

    /** Inverse of the forward relation-type table; each provider constant maps to one token. */
    private fun relationTypeToken(type: Int?): String? = when (type) {
        Relation.TYPE_SPOUSE -> "spouse"
        Relation.TYPE_CHILD -> "child"
        Relation.TYPE_PARENT -> "parent"
        Relation.TYPE_FATHER -> "father"
        Relation.TYPE_MOTHER -> "mother"
        Relation.TYPE_BROTHER -> "brother"
        Relation.TYPE_SISTER -> "sister"
        Relation.TYPE_FRIEND -> "friend"
        Relation.TYPE_PARTNER -> "partner"
        Relation.TYPE_ASSISTANT -> "assistant"
        Relation.TYPE_MANAGER -> "manager"
        Relation.TYPE_RELATIVE -> "relative"
        else -> null
    }

    private fun ContentValues.isPrimary(key: String): Boolean = getAsInteger(key) == 1

    /**
     * Keep [pref] on only the first item per list, mirroring the forward mapper, which
     * honours a single `IS_PRIMARY` per mimetype. A real RawContact can carry `IS_PRIMARY=1`
     * on several rows of one mimetype (unlike the globally-unique `IS_SUPER_PRIMARY`), so
     * clamping here keeps `reverse` a fixed point of the forward image — without it the
     * writer would rewrite the surplus PREF markers on every sync.
     */
    private inline fun <T> List<T>.clampSinglePreferred(pref: (T) -> Boolean, withPref: (T, Boolean) -> T): List<T> {
        var taken = false
        return map { item ->
            if (!pref(item)) return@map item
            if (taken) withPref(item, false) else { taken = true; item }
        }
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
}
