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
 * Reads the Contacts Provider Data rows of one RawContact back into the neutral [Contact]
 * model, the inverse of [VCardContactMapper].
 *
 * It is pure: it takes the rows as `List<ContentValues>` and touches no ContentResolver,
 * Cursor or Binder. The provider read that produces them lives under `sync/contacts/`.
 *
 * ## Inverse on the forward image
 *
 * [VCardContactMapper] loses some detail: it collapses each vCard `TYPE` list onto one provider
 * constant, drops the photo `contentType` and URL, keeps one preferred value per mimetype, and
 * keeps birthday and anniversary text only when it parses as a date. This mapper inverts that
 * image: `reverse(forward(x))` is a fixed point (`DeviceContactRowMapperTest`, every fixture), and
 * it equals [org.onekash.vcard.VCardParser]'s model on the facets that round-trip losslessly.
 *
 * It differs on the lossy facets, such as the email and phone `TYPE` tokens the provider
 * can't store (`INTERNET`, `VOICE`). [org.onekash.vcard.VCardWriter] compares emails and
 * phones by full equality, so the first write after a reverse map rewrites those lines in the
 * device's form; the server then holds a form that reverse-maps to itself, so they converge.
 * The photo doesn't churn: the writer compares its bytes and URL and ignores `contentType`,
 * so a device-sourced photo isn't rewritten on a no-edit round trip.
 *
 * ## Fields not on Data rows
 *
 * `uid`, `version`, `kind` and `rawVCard` aren't on Data rows, so the caller supplies them.
 * The sync layer passes only the UID (from SYNC1); the rest keep their defaults, and the push
 * writes the body at the address book's version.
 */
object DeviceContactRowMapper {

    /**
     * Rebuilds a [Contact] from the [dataRows] of one RawContact; [uid], [version], [kind] and
     * [rawVCard] come from the caller (see the class doc).
     *
     * [groupTitlesById] maps a local `Groups._ID` to its title, so a `GroupMembership` row with
     * only a `GROUP_ROW_ID` resolves to a category (the People app leaves `GROUP_SOURCE_ID`
     * blank for a user-created label). The sync layer reads the account's groups once per
     * scan; with the empty default only `GROUP_SOURCE_ID` categories resolve.
     */
    fun toContact(
        dataRows: List<ContentValues>,
        uid: String = "",
        version: String = "3.0",
        kind: String? = null,
        rawVCard: String = "",
        groupTitlesById: Map<Long, String> = emptyMap(),
    ): Contact {
        val byMime = dataRows.groupBy { it.getAsString(Data.MIMETYPE) }

        val nameRow = byMime[StructuredName.CONTENT_ITEM_TYPE]?.firstOrNull()
        val structuredName = structuredName(nameRow)
        // FN is stored as DISPLAY_NAME; without it, derive the name from N as the parser does.
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
                .mapNotNull { row ->
                    // Prefer a non-blank GROUP_SOURCE_ID (this app's groups use the title as
                    // SOURCE_ID). A People-app label has only GROUP_ROW_ID, looked up in
                    // the title map; a row resolved by neither is dropped.
                    row.getAsString(GroupMembership.GROUP_SOURCE_ID).blankToNull()
                        ?: row.getAsLong(GroupMembership.GROUP_ROW_ID)?.let { groupTitlesById[it] }
                }
                .distinct(),
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
        // A custom label wins, as in the forward mapper; each fixed constant maps back to one
        // token, and TYPE_CUSTOM or an unmapped constant to none.
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
     * Rebuilds `ORG` as COMPANY followed by DEPARTMENT split on "; ", the forward mapper's
     * join. A unit that itself contained "; " comes back split, and a blank leading component
     * (`ORG:;Unit`) is lost because the forward mapper drops a blank company; both are
     * forward losses that hold under the fixed point.
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
        // The Photo row has no MIME type and no URL, so both stay null; the forward mapper
        // drops contentType and never puts a URL photo in a row.
        return VPhoto(data = bytes)
    }

    /** Returns the first [type] Event row's date: an ISO date as [ContactDate.date], else text. */
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
     * Keeps [pref] on only the first preferred item, as the forward mapper sets one `IS_PRIMARY`
     * per mimetype. A real RawContact can have `IS_PRIMARY=1` on several rows of one mimetype
     * (unlike the unique `IS_SUPER_PRIMARY`); without the clamp the writer would rewrite the extra
     * PREF markers on every sync.
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
